package com.dsh.application;

import java.util.ArrayList;
import java.util.List;

/**
 * 行级最小 unified diff（通用能力，非任何基准/语言特化）。
 *
 * <p>解决的问题：旧实现对任何修改文件都输出整文件
 * {@code @@ -1,N +1,M @@} 大 hunk，CRLF 差异会把整个文件标记为重写，
 * 补丁不可评审、git apply 易失败。本类用 LCS 计算真实变更行并按
 * 3 行上下文合并 hunk，输出标准 {@code git diff} 格式（含 diff --git 头）。
 *
 * <p>超大文件（行数乘积超限）回退为整文件 hunk，保证不 OOM。
 */
public final class UnifiedDiff {

    /** 上下文行数（git 默认 3 行）。 */
    private static final int CONTEXT = 3;
    /** LCS 动态规划表单元数上限（约 16MB int），超出则整文件输出。 */
    private static final int MAX_LCS_CELLS = 4_000_000;

    /** 一个编辑操作：kind=' ' 相同、'-' 仅旧、'+' 仅新。 */
    private record Op(char kind, String text, boolean oldEol, boolean newEol) {
    }

    private UnifiedDiff() {
    }

    /**
     * 生成单文件 unified diff。
     *
     * @param relPath 相对 POSIX 路径
     * @param oldLines 旧文件行（仅 null 表示文件不存在；空列表为存在的空文件）
     * @param newLines 新文件行（仅 null 表示文件不存在）
     * @return git 可直接 apply 的补丁段
     */
    public static String fileDiff(String relPath, List<String> oldLines, boolean oldEndsWithNewline,
                                  List<String> newLines, boolean newEndsWithNewline) {
        boolean added = oldLines == null;
        boolean deleted = newLines == null;
        if (added && deleted) {
            return "";
        }
        // 内容完全一致（含 CRLF 归一化后）不产生补丁
        if (!added && !deleted && oldLines.equals(newLines)
                && (oldLines.isEmpty() || oldEndsWithNewline == newEndsWithNewline)) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        out.append("diff --git ").append(gitPath("a/" + relPath)).append(' ')
                .append(gitPath("b/" + relPath)).append('\n');
        if (added) out.append("new file mode 100644\n");
        if (deleted) out.append("deleted file mode 100644\n");

        int n = added ? 0 : oldLines.size();
        int m = deleted ? 0 : newLines.size();
        // 空文件的创建/删除仅有 mode 头，不能伪造一个没有内容的 hunk。
        if (n == 0 && m == 0) return out.toString();
        out.append(added ? "--- /dev/null\n" : "--- " + gitPath("a/" + relPath) + "\n");
        out.append(deleted ? "+++ /dev/null\n" : "+++ " + gitPath("b/" + relPath) + "\n");
        if ((long) (n + 1) * (m + 1) > MAX_LCS_CELLS) {
            appendWholeFileHunk(out, relPath, oldLines, oldEndsWithNewline,
                    newLines, newEndsWithNewline, n, m);
            return out.toString();
        }

        List<Op> ops = lcsOps(oldLines, newLines, n, m, oldEndsWithNewline, newEndsWithNewline);
        appendHunks(out, ops);
        return out.toString();
    }

    /** LCS 动态规划 + 回溯，输出与旧实现相同顺序的编辑操作序列。 */
    private static List<Op> lcsOps(List<String> oldLines, List<String> newLines,
                                   int n, int m, boolean oldEol, boolean newEol) {
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (sameLine(oldLines, newLines, i, j, n, m, oldEol, newEol)) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }
        List<Op> ops = new ArrayList<>(n + m);
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (sameLine(oldLines, newLines, i, j, n, m, oldEol, newEol)) {
                ops.add(new Op(' ', oldLines.get(i),
                        lineHasEol(i, n, oldEol), lineHasEol(j, m, newEol)));
                i++;
                j++;
            } else if (dp[i][j + 1] >= dp[i + 1][j]) {
                ops.add(new Op('+', newLines.get(j), false, lineHasEol(j, m, newEol)));
                j++;
            } else {
                ops.add(new Op('-', oldLines.get(i), lineHasEol(i, n, oldEol), false));
                i++;
            }
        }
        while (i < n) {
            ops.add(new Op('-', oldLines.get(i), lineHasEol(i, n, oldEol), false));
            i++;
        }
        while (j < m) {
            ops.add(new Op('+', newLines.get(j), false, lineHasEol(j, m, newEol)));
            j++;
        }
        return ops;
    }

    private static boolean sameLine(List<String> oldLines, List<String> newLines,
                                    int i, int j, int n, int m, boolean oldEol, boolean newEol) {
        return oldLines.get(i).equals(newLines.get(j))
                && lineHasEol(i, n, oldEol) == lineHasEol(j, m, newEol);
    }

    /** Git 的 C 风格路径引用；非 ASCII 用 UTF-8 字节八进制，避免路径分隔歧义。 */
    private static String gitPath(String path) {
        if (path.chars().allMatch(c -> c > 32 && c < 127 && c != '"' && c != '\\')) return path;
        StringBuilder quoted = new StringBuilder("\"");
        for (byte value : path.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = Byte.toUnsignedInt(value);
            if (c == '"' || c == '\\') quoted.append('\\').append((char) c);
            else if (c < 32 || c >= 127) {
                quoted.append('\\').append((char) ('0' + (c >> 6)))
                        .append((char) ('0' + ((c >> 3) & 7))).append((char) ('0' + (c & 7)));
            } else quoted.append((char) c);
        }
        return quoted.append('"').toString();
    }

    /** 最后一行在文件缺少结尾换行时无 EOL；其余行都有换行。 */
    private static boolean lineHasEol(int index, int total, boolean fileEndsWithNewline) {
        return index < total - 1 || fileEndsWithNewline;
    }

    /** 把编辑操作按 CONTEXT 行上下文切分为若干 hunk 输出。 */
    private static void appendHunks(StringBuilder out, List<Op> ops) {
        int size = ops.size();
        // 1) 以变更块为中心开窗口（前 3 行/后 3 行上下文），间隔不超过 2*CONTEXT 的窗口合并
        List<int[]> windows = new ArrayList<>();
        for (int idx = 0; idx < size; idx++) {
            if (ops.get(idx).kind() == ' ') continue;
            int start = Math.max(0, idx - CONTEXT);
            int end = Math.min(size, idx + 1 + CONTEXT);
            if (!windows.isEmpty() && start <= windows.getLast()[1]) {
                windows.getLast()[1] = end;
            } else {
                windows.add(new int[]{start, end});
            }
        }

        // 2) 逐窗口计算行号并输出
        int oldPos = 0; // 已跨过窗口边界的旧文件行数
        int newPos = 0;
        int cursor = 0;
        for (int[] w : windows) {
            while (cursor < w[0]) {
                Op op = ops.get(cursor);
                if (op.kind() != '+') oldPos++;
                if (op.kind() != '-') newPos++;
                cursor++;
            }
            int oldBefore = oldPos, newBefore = newPos;
            int oldLen = 0, newLen = 0;
            StringBuilder body = new StringBuilder();
            for (int p = w[0]; p < w[1]; p++) {
                Op op = ops.get(p);
                body.append(op.kind()).append(op.text()).append('\n');
                boolean atOldEnd = op.kind() != '+' && !op.oldEol();
                boolean atNewEnd = op.kind() != '-' && !op.newEol();
                if (atOldEnd) {
                    body.append("\\ No newline at end of file\n");
                }
                if (atNewEnd && op.kind() != ' ') {
                    body.append("\\ No newline at end of file\n");
                }
                if (op.kind() != '+') {
                    oldLen++;
                    oldPos++;
                }
                if (op.kind() != '-') {
                    newLen++;
                    newPos++;
                }
                cursor++;
            }
            int oldStart = oldLen == 0 ? oldBefore : oldBefore + 1;
            int newStart = newLen == 0 ? newBefore : newBefore + 1;
            out.append("@@ -").append(oldStart).append(',').append(oldLen)
                    .append(" +").append(newStart).append(',').append(newLen)
                    .append(" @@\n");
            out.append(body);
        }
    }

    /** LCS 超限时回退：整文件单 hunk（保持旧行为，补丁仍可应用）。 */
    private static void appendWholeFileHunk(StringBuilder out, String relPath,
                                            List<String> oldLines, boolean oldEol,
                                            List<String> newLines, boolean newEol,
                                            int n, int m) {
        out.append("@@ -").append(n == 0 ? 0 : 1).append(',').append(n)
                .append(" +").append(m == 0 ? 0 : 1).append(',').append(m)
                .append(" @@\n");
        if (oldLines != null) {
            for (int i = 0; i < n; i++) {
                out.append('-').append(oldLines.get(i)).append('\n');
                if (i == n - 1 && !oldEol) {
                    out.append("\\ No newline at end of file\n");
                }
            }
        }
        if (newLines != null) {
            for (int j = 0; j < m; j++) {
                out.append('+').append(newLines.get(j)).append('\n');
                if (j == m - 1 && !newEol) {
                    out.append("\\ No newline at end of file\n");
                }
            }
        }
    }

    /**
     * 读取文本文件并归一化 CRLF：返回的行不含行尾，{@code endsWithNewline} 表示原文是否以换行结尾。
     * 这样 CRLF↔LF 差异不会让整个文件显示为修改。
     */
    public static TextLines normalize(String content) {
        return parse(content, true);
    }

    /** 导出时保留行中的 CR，使补丁应用后能还原真实编辑的文件字节。 */
    public static TextLines verbatim(String content) {
        return parse(content, false);
    }

    private static TextLines parse(String content, boolean normalizeCrlf) {
        if (content == null || content.isEmpty()) {
            return new TextLines(List.of(), true);
        }
        boolean endsWithNewline = content.endsWith("\n");
        String[] split = content.split("\n", -1);
        int count = endsWithNewline ? split.length - 1 : split.length;
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String line = split[i];
            if (normalizeCrlf && line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            lines.add(line);
        }
        return new TextLines(lines, endsWithNewline);
    }

    /** 归一化后的文件行。 */
    public record TextLines(List<String> lines, boolean endsWithNewline) {
    }
}
