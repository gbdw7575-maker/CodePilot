package com.dsh.sandbox;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A3 有界采集：超限时保留首部**与尾部**（构建/测试失败的真正原因通常在输出末尾），
 * 字节账目如实（原始总字节 / 已采集 / 已丢弃），且不把多字节字符切成替换符。
 */
class BoundedStreamCollectorTest {

    private static BoundedStreamCollector collect(String content, int limit) {
        InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        BoundedStreamCollector collector = new BoundedStreamCollector(in, limit);
        collector.run();
        return collector;
    }

    @Test
    void underLimitReturnsVerbatim() {
        String content = "hello world";
        BoundedStreamCollector c = collect(content, 300);

        assertFalse(c.truncated());
        assertEquals(content, c.text());
        assertEquals(11, c.totalBytes());
        assertEquals(0, c.droppedBytes());
        assertEquals(11, c.collectedBytes());
    }

    /**
     * 回归：limit=300 时首部缓冲上限为 200，250 字节落在「超过 2/3 但未超 limit」的区间。
     * 修复前只返回首部，会**无声**丢掉尾部 50 字节且不带任何截断标记。
     */
    @Test
    void contentBetweenTwoThirdsAndLimitIsNotSilentlyCut() {
        String content = "a".repeat(250);
        BoundedStreamCollector c = collect(content, 300);

        assertFalse(c.truncated());
        assertFalse(c.text().contains("已截断"), "未超限不应出现截断标记");
        assertEquals(250, c.text().length(), "未超限必须逐字返回全部内容");
        assertEquals(content, c.text());
    }

    @Test
    void exactlyLimitReturnsVerbatim() {
        String content = "b".repeat(300);
        BoundedStreamCollector c = collect(content, 300);

        assertFalse(c.truncated());
        assertEquals(content, c.text());
        assertEquals(300, c.totalBytes());
        assertEquals(0, c.droppedBytes());
        assertEquals(300, c.collectedBytes());
    }

    @Test
    void overLimitKeepsHeadAndTailWithHonestAccounting() {
        // 共 5 + 1000 + 5 = 1010 字节；limit=300 ⇒ 首 200、末 100
        String content = "HEAD-" + "x".repeat(1000) + "-TAIL";
        BoundedStreamCollector c = collect(content, 300);

        assertTrue(c.truncated());
        assertEquals(1010, c.totalBytes());
        assertEquals(300, c.collectedBytes());
        assertEquals(710, c.droppedBytes());

        String text = c.text();
        assertTrue(text.startsWith("HEAD-"), "应保留首部");
        assertTrue(text.endsWith("-TAIL"), "应保留尾部——失败原因通常只在末尾");
        assertTrue(text.contains("中间 710 字节已截断"), "应如实带出被丢弃字节数");
        assertTrue(text.contains("原始输出共 1010 字节"), "应如实带出原始总字节数");
        assertTrue(text.contains("保留首 200 与末 100 字节"), "应说明本视图的实际保留量");
    }

    /**
     * 未超限时首尾缓冲可能重叠（尾部是「最近 tailLimit 字节」，会与首部覆盖同一段），
     * 拼接必须跳过重叠区，否则输出会重复一段内容。
     */
    @Test
    void overlappingHeadAndTailConcatenateWithoutDuplication() {
        // limit=10 ⇒ 首 6 字节 + 末 4 字节
        // 8 字节 ⇒ 6+4=10 > 8，重叠 2 字节：这是必须跳过重叠的情形
        String content = "中A\uD83D\uDE00"; // 3 + 1 + 4 = 8 字节
        assertEquals(8, content.getBytes(StandardCharsets.UTF_8).length);
        BoundedStreamCollector c = collect(content, 10);

        assertFalse(c.truncated());
        assertEquals(content, c.text(), "重叠区未被跳过会导致内容重复");

        // 7 字节 ⇒ 6+4=10 > 7，重叠 3 字节
        String seven = "中\uD83D\uDE00"; // 3 + 4 = 7 字节
        BoundedStreamCollector c2 = collect(seven, 10);
        assertFalse(c2.truncated());
        assertEquals(seven, c2.text());

        // 恰好等于 limit ⇒ 6+4=10，无重叠
        String ten = "中\uD83D\uDE00文"; // 3 + 4 + 3 = 10 字节
        BoundedStreamCollector c3 = collect(ten, 10);
        assertFalse(c3.truncated());
        assertEquals(ten, c3.text());
    }

    @Test
    void utf8BoundaryNeverProducesReplacementChar() {
        String content = "中文\uD83D\uDE00ABC中文\uD83D\uDE00".repeat(6);
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;

        for (int limit = 1; limit <= bytes; limit++) {
            BoundedStreamCollector c = collect(content, limit);
            String text = c.text();
            assertFalse(text.contains("\uFFFD"),
                    "limit=" + limit + " 时首尾切点落在多字节字符中间，产生了替换符");
            // 未超限时必须逐字返回
            if (limit >= bytes) {
                assertFalse(c.truncated());
                assertEquals(content, text);
            } else {
                assertTrue(c.truncated());
                assertEquals(bytes, c.totalBytes());
                assertEquals(bytes, c.collectedBytes() + c.droppedBytes());
            }
        }
    }

    @Test
    void zeroLimitDegradesGracefully() {
        BoundedStreamCollector c = collect("abcdef", 0);

        assertTrue(c.truncated());
        assertEquals(6, c.totalBytes());
        assertEquals(6, c.droppedBytes());
        assertEquals(0, c.collectedBytes());
        assertNotNull(c.text());
    }
}
