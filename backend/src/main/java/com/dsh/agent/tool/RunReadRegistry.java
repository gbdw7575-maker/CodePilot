package com.dsh.agent.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D-18：每 Run 的已读文件指纹册（通用能力，非任何基准/语言特化）。
 *
 * <p>解决的问题：长任务在多轮上下文压缩后，模型会「遗忘」已读内容并全量重读同一个
 * 字节未变的文件，造成大量重复输入 token（真实负载观测到单个文件被重读十余次）。
 *
 * <p>按 (runId, 任务副本相对路径) 记录最近一次文件内容的 sha256 与已读行区间并集。
 * read_file 据此判断本次请求是否只是重复读取：字节未变且行区间已被覆盖时回引用视图，
 * 不重复正文；forceFull、读取未覆盖的新行、或文件已变更时总是返回正文。
 *
 * <p>D-20 探索收敛引导：另记本 Run 是否已产出写操作（write_file/apply_patch）。
 * 当 read_file 累计读取的不同文件数达到阈值且本 Run 无任何写操作时，向模型追加
 * 「已探索 N 文件未产出修改，请聚焦到最可能修改点」提示，避免发散探索。
 *
 * <p>仅内存态、按 Run 隔离：进程重启或 Run 释放后重读返回全文（只是不再省 token，不会出错）；
 * 容量有界以防异常累积。区间为 1-based 闭区间，内部按有序、相邻即合并的方式维护。
 */
@Component
public class RunReadRegistry {

    /** 已读行区间（1-based 闭区间）。 */
    public record CoveredRange(int startLine, int endLine) {
    }

    /** 一次读取的决策结果。 */
    public record Decision(boolean deduplicated, int readCount, String sha256,
                           List<CoveredRange> coveredRanges) {
    }

    /** 内存中保留的 Run 数上限；超出时淘汰一个最早登记的 Run（本地工具 Run 数有限）。 */
    static final int MAX_RUNS = 256;

    private static final class FileState {
        private String sha256;
        private int readCount;
        /** 有序、互不重叠、相邻已合并的区间。 */
        private final List<int[]> ranges = new ArrayList<>();
    }

    /** 每 Run 的已读文件状态。 */
    private static final class RunState {
        private final Map<String, FileState> files = new HashMap<>();
    }

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    /**
     * 登记并判定一次读取。
     *
     * @param runId     Run 标识（隔离边界）
     * @param relPath   任务副本内相对路径
     * @param sha256    当前文件字节的 sha256
     * @param startLine 请求起始行（1-based，含）
     * @param endLine   请求结束行（1-based，含），调用方保证 endLine &gt;= startLine
     * @param forceFull 模型显式要求重新返回正文
     * @return 去重决策；{@code deduplicated=true} 表示字节未变且请求区间已读过，应回引用视图
     */
    public synchronized Decision resolve(String runId, String relPath, String sha256,
                                          int startLine, int endLine, boolean forceFull) {
        String key = runId == null ? "" : runId;
        RunState state = runs.get(key);
        if (state == null) {
            if (runs.size() >= MAX_RUNS) {
                evictOne();
            }
            state = new RunState();
            runs.put(key, state);
        }

        FileState file = state.files.get(relPath);
        boolean unchanged = file != null && sha256.equals(file.sha256);
        if (file == null) {
            file = new FileState();
            file.sha256 = sha256;
            state.files.put(relPath, file);
        } else if (!unchanged) {
            // 文件已变更：旧区间对新内容无意义，整体作废后按新内容重新计读。
            file.sha256 = sha256;
            file.ranges.clear();
            file.readCount = 0;
        }

        if (!forceFull && unchanged && isFullyCovered(file.ranges, startLine, endLine)) {
            return new Decision(true, file.readCount, sha256, snapshot(file.ranges));
        }
        mergeRange(file.ranges, startLine, endLine);
        file.readCount++;
        return new Decision(false, file.readCount, sha256, snapshot(file.ranges));
    }

    /** Run 终态后清理（幂等）。 */
    public void release(String runId) {
        if (runId != null) {
            runs.remove(runId);
        }
    }

    /** 请求 [s,e] 是否被某个已有区间完整覆盖（区间有序，找到首个右端不小于 s 的即可判定）。 */
    private static boolean isFullyCovered(List<int[]> ranges, int s, int e) {
        for (int[] r : ranges) {
            if (r[1] < s) {
                continue;
            }
            return r[0] <= s && r[1] >= e;
        }
        return false;
    }

    /** 将 [s,e] 并入有序区间列表，相邻（end+1 与 start 相接）即合并。 */
    private static void mergeRange(List<int[]> ranges, int s, int e) {
        List<int[]> out = new ArrayList<>(ranges.size() + 1);
        int i = 0;
        int n = ranges.size();
        // 1. 完全位于 [s,e] 之前且不相邻的区间原样保留
        while (i < n && ranges.get(i)[1] + 1 < s) {
            out.add(ranges.get(i));
            i++;
        }
        // 2. 与 [s,e] 相交或相邻的区间并入（ranges 有序，遇首个不满足即可停止）
        while (i < n && ranges.get(i)[0] <= e + 1) {
            s = Math.min(s, ranges.get(i)[0]);
            e = Math.max(e, ranges.get(i)[1]);
            i++;
        }
        out.add(new int[]{s, e});
        // 3. 剩余区间原样保留
        while (i < n) {
            out.add(ranges.get(i));
            i++;
        }
        ranges.clear();
        ranges.addAll(out);
    }

    private static List<CoveredRange> snapshot(List<int[]> ranges) {
        List<CoveredRange> out = new ArrayList<>(ranges.size());
        for (int[] r : ranges) {
            out.add(new CoveredRange(r[0], r[1]));
        }
        return List.copyOf(out);
    }

    private void evictOne() {
        var it = runs.keySet().iterator();
        if (it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
