package com.dsh.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 并发排空进程输出流（开发文档 §14：大日志不阻塞退出或超时处理）。
 *
 * <p>保留策略：总输出不超过 {@code limit} 字节时全量保留；超过时**保留首部与尾部**，
 * 中间以截断标记替代，并如实带出总字节数与被丢弃字节数。
 *
 * <p>为什么不再只留首部（A3）：构建/测试失败的真正原因通常在输出的**末尾**
 * （失败用例、异常栈、最终统计），只保留首部恰好把最关键的诊断证据丢掉，
 * 而后续 ArtifactStore 只能存到这份已被砍过的文本，模型即使回读产物也拿不到尾部。
 * 首尾配比与 LoopSteps 的工具视图同口径（首 2/3、尾 1/3）。
 *
 * <p>截断时对首部末尾与尾部开头做 UTF-8 边界校正，避免把多字节字符切成替换符。
 */
final class BoundedStreamCollector implements Runnable {

    /** 首部占 limit 的比例分子（首 2/3、尾 1/3）。 */
    private static final int HEAD_NUMERATOR = 2;
    private static final int RATIO_DENOMINATOR = 3;

    private final InputStream input;
    private final int limit;
    private final int headLimit;
    private final int tailLimit;

    private final ByteArrayOutputStream head = new ByteArrayOutputStream();
    /** 尾部环形缓冲，始终保存最近的 tailLimit 字节。 */
    private byte[] ring;
    private int ringPos;
    private boolean ringWrapped;

    private long totalBytes;
    private volatile boolean truncated;

    BoundedStreamCollector(InputStream input, int limit) {
        this.input = input;
        int effective = Math.max(0, limit);
        this.limit = effective;
        this.headLimit = effective * HEAD_NUMERATOR / RATIO_DENOMINATOR;
        this.tailLimit = effective - headLimit;
        this.ring = new byte[tailLimit];
    }

    @Override
    public void run() {
        byte[] buffer = new byte[4096];
        try (InputStream in = input) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                totalBytes += read;
                int headRemaining = headLimit - head.size();
                if (headRemaining > 0) {
                    head.write(buffer, 0, Math.min(read, headRemaining));
                }
                if (tailLimit > 0) {
                    appendTail(buffer, read);
                }
            }
        } catch (IOException e) {
            // 进程被强杀时读管道可能抛异常，按已收集内容处理
            truncated = true;
        }
        if (totalBytes > limit) {
            truncated = true;
        }
    }

    /** 把本次读到的字节追加进尾部环形缓冲，只保留最近 tailLimit 字节。 */
    private void appendTail(byte[] src, int len) {
        int offset = 0;
        while (offset < len) {
            int chunk = Math.min(len - offset, tailLimit - ringPos);
            System.arraycopy(src, offset, ring, ringPos, chunk);
            ringPos += chunk;
            offset += chunk;
            if (ringPos == tailLimit) {
                ringPos = 0;
                ringWrapped = true;
            }
        }
    }

    String text() {
        byte[] headBytes = head.toByteArray();
        byte[] orderedTail = tailOrdered();
        long dropped = droppedBytes();
        if (dropped <= 0) {
            // 未超限（或采集异常但未丢字节）：把首部与尾部按序接回**完整**内容。
            // 这一步不能省：首部缓冲只装到 headLimit，若直接只返回首部，
            // 「输出大于 2/3·limit 但未超 limit」会被无声截断且不带任何标记。
            return new String(concatInOrder(headBytes, orderedTail), StandardCharsets.UTF_8);
        }
        // 尾部开头的续字节属于被切掉的那个字符，跳过以免产生替换符
        int tailSkip = leadingContinuationBytes(orderedTail);
        String headText = new String(trimToUtf8Boundary(headBytes), StandardCharsets.UTF_8);
        String tailText = new String(orderedTail, tailSkip, orderedTail.length - tailSkip,
                StandardCharsets.UTF_8);
        return headText
                + "\n[……中间 " + dropped + " 字节已截断（原始输出共 " + totalBytes + " 字节，"
                + "本视图保留首 " + headBytes.length + " 与末 " + orderedTail.length + " 字节）……]\n"
                + tailText;
    }

    /**
     * 按序拼接首部与尾部缓冲，跳过重叠区。
     *
     * <p>尾部环形缓冲保存的是「最近 tailLimit 字节」，未超限时它与首部缓冲覆盖的范围重叠，
     * 重叠字节数＝{@code head.length + tail.length - totalBytes}，必须在拼接时跳过，
     * 否则输出会重复一段内容。
     */
    private byte[] concatInOrder(byte[] headBytes, byte[] orderedTail) {
        int overlap = (int) Math.max(0, headBytes.length + orderedTail.length - totalBytes);
        int tailPart = orderedTail.length - overlap;
        byte[] out = new byte[headBytes.length + Math.max(0, tailPart)];
        System.arraycopy(headBytes, 0, out, 0, headBytes.length);
        if (tailPart > 0) {
            System.arraycopy(orderedTail, overlap, out, headBytes.length, tailPart);
        }
        return out;
    }

    boolean truncated() {
        return truncated;
    }

    /** 原始输出总字节数（含被丢弃部分），供上层如实告知模型丢失规模。 */
    long totalBytes() {
        return totalBytes;
    }

    /** 被丢弃的字节数；未截断时为 0。 */
    long droppedBytes() {
        if (!truncated) {
            return 0;
        }
        return Math.max(0, totalBytes - head.size() - tailUsed());
    }

    /**
     * 本视图实际可提供的原始字节数＝原始总字节 − 已丢弃字节。
     *
     * <p>不用「首部 + 尾部缓冲占用」来定义：未超限时尾部缓冲与首部缓冲覆盖同一段内容，
     * 相加会把字节数重复计入（11 字节的短输出会算成 22）。
     */
    long collectedBytes() {
        return totalBytes - droppedBytes();
    }

    private long tailUsed() {
        return ringWrapped ? tailLimit : ringPos;
    }

    /** 按写入顺序返回尾部字节。 */
    private byte[] tailOrdered() {
        if (!ringWrapped) {
            return Arrays.copyOf(ring, ringPos);
        }
        byte[] out = new byte[tailLimit];
        System.arraycopy(ring, ringPos, out, 0, tailLimit - ringPos);
        System.arraycopy(ring, 0, out, tailLimit - ringPos, ringPos);
        return out;
    }

    /** 开头连续续字节的个数（最多 3），用于从合法字符边界开始解码。 */
    static int leadingContinuationBytes(byte[] bytes) {
        int i = 0;
        while (i < bytes.length && i < 3 && (bytes[i] & 0xC0) == 0x80) {
            i++;
        }
        return i;
    }

    /** 把字节前缀裁到合法 UTF-8 边界，避免尾部多字节字符被截成替换符。 */
    static byte[] trimToUtf8Boundary(byte[] bytes) {
        int end = bytes.length;
        int i = end;
        while (i > 0 && end - i < 3 && (bytes[i - 1] & 0xC0) == 0x80) {
            i--;
        }
        if (i == 0) {
            return bytes;
        }
        int lead = bytes[i - 1] & 0xFF;
        int expected = lead < 0x80 ? 1
                : (lead & 0xE0) == 0xC0 ? 2
                : (lead & 0xF0) == 0xE0 ? 3
                : (lead & 0xF8) == 0xF0 ? 4 : 1;
        int available = end - (i - 1);
        return Arrays.copyOf(bytes, available >= expected ? end : i - 1);
    }
}
