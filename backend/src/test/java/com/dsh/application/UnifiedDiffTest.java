package com.dsh.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最小 unified diff 单测：验证真实负载暴露的两个问题——
 * CRLF 致整文件伪重写、补丁不是真正的 minimal hunk。
 * git apply 往返用例默认跳过（git 非 JDK 组件），显式 -Ddsh.test.gitapply=true 开启。
 */
class UnifiedDiffTest {

    private static List<String> lines(String... lines) {
        return List.of(lines);
    }

    @Test
    void singleLineChangeProducesMinimalHunkWithThreeLinesContext() {
        List<String> oldLines = new ArrayList<>();
        List<String> newLines = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            oldLines.add("line" + i);
            newLines.add(i == 5 ? "CHANGED" : "line" + i);
        }
        String patch = UnifiedDiff.fileDiff("f.txt", oldLines, true, newLines, true);

        assertTrue(patch.contains("diff --git a/f.txt b/f.txt"));
        assertTrue(patch.contains("--- a/f.txt"));
        assertTrue(patch.contains("+++ b/f.txt"));
        // 单个 hunk，从旧/新第 2 行开始（变更在第 5 行、3 行上下文），各 7 行
        assertTrue(patch.contains("@@ -2,7 +2,7 @@"), patch);
        assertTrue(patch.contains("-line5\n"));
        assertTrue(patch.contains("+CHANGED\n"));
        // 远离变更点的内容不应出现在补丁里
        assertFalse(patch.contains(" line1\n"));
        assertFalse(patch.contains(" line10"));
        assertEquals(1, countOccurrences(patch, "@@ -"), "应只有一个 hunk");
    }

    @Test
    void distantChangesProduceTwoHunks() {
        List<String> oldLines = new ArrayList<>();
        List<String> newLines = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            oldLines.add("l" + i);
            newLines.add((i == 1 || i == 20) ? "x" + i : "l" + i);
        }
        String patch = UnifiedDiff.fileDiff("f.txt", oldLines, true, newLines, true);
        assertEquals(2, countOccurrences(patch, "@@ -"), patch);
        assertTrue(patch.contains("@@ -1,4 +1,4 @@"));
        assertTrue(patch.contains("@@ -17,4 +17,4 @@"));
    }

    @Test
    void crlfOnlyDifferenceProducesNoPatch() {
        UnifiedDiff.TextLines crlf = UnifiedDiff.normalize("a\r\nb\r\nc\r\n");
        UnifiedDiff.TextLines lf = UnifiedDiff.normalize("a\nb\nc\n");
        assertEquals(lf.lines(), crlf.lines());
        String patch = UnifiedDiff.fileDiff("f.txt", crlf.lines(), crlf.endsWithNewline(),
                lf.lines(), lf.endsWithNewline());
        assertEquals("", patch);
    }

    @Test
    void crlfChangeWithRealEditProducesSmallHunk() {
        // 旧文件 621 行 CRLF，仅第 190 行改动；新文件 LF。不应整文件重写
        List<String> oldLines = new ArrayList<>();
        List<String> newLines = new ArrayList<>();
        for (int i = 1; i <= 621; i++) {
            oldLines.add("row " + i);
            newLines.add(i == 190 ? "row fixed" : "row " + i);
        }
        // 旧文件按 CRLF、新文件按 LF 归一化
        UnifiedDiff.TextLines oldText = UnifiedDiff.normalize(String.join("\r\n", oldLines) + "\r\n");
        UnifiedDiff.TextLines newText = UnifiedDiff.normalize(String.join("\n", newLines) + "\n");
        oldLines.clear();
        oldLines.addAll(oldText.lines());
        newLines.clear();
        newLines.addAll(newText.lines());
        String patch = UnifiedDiff.fileDiff("blueprints.py", oldLines, true, newLines, true);
        assertEquals(1, countOccurrences(patch, "@@ -"), patch);
        assertTrue(patch.contains("@@ -187,7 +187,7 @@"), patch);
        assertTrue(patch.contains("-row 190\n"));
        assertTrue(patch.contains("+row fixed\n"));
        assertFalse(patch.contains("-row 1\n"), "首行不应出现在 hunk 中");
    }

    @Test
    void addedAndDeletedFileHeaders() {
        String added = UnifiedDiff.fileDiff("n.txt", null, true, lines("a", "b"), true);
        assertTrue(added.contains("--- /dev/null"));
        assertTrue(added.contains("new file mode 100644"));
        assertTrue(added.contains("+++ b/n.txt"));
        assertTrue(added.contains("@@ -0,0 +1,2 @@"));

        String deleted = UnifiedDiff.fileDiff("o.txt", lines("a"), true, null, true);
        assertTrue(deleted.contains("--- a/o.txt"));
        assertTrue(deleted.contains("deleted file mode 100644"));
        assertTrue(deleted.contains("+++ /dev/null"));
        assertTrue(deleted.contains("@@ -1,1 +0,0 @@"));
    }

    @Test
    void emptyFileExistenceIsNotContentEmptiness() {
        String added = UnifiedDiff.fileDiff("empty", null, true, List.of(), true);
        String deleted = UnifiedDiff.fileDiff("empty", List.of(), true, null, true);
        assertTrue(added.contains("new file mode 100644"));
        assertTrue(deleted.contains("deleted file mode 100644"));
        assertFalse(added.contains("@@"));
        assertFalse(deleted.contains("@@"));
        assertEquals("", UnifiedDiff.fileDiff("empty", List.of(), true, List.of(), true));
        String cleared = UnifiedDiff.fileDiff("cleared", lines("before"), true, List.of(), true);
        assertFalse(cleared.contains("deleted file mode"));
        assertTrue(cleared.contains("+++ b/cleared"));
    }

    @Test
    void newlineOnlyChangeProducesPatch() {
        String patch = UnifiedDiff.fileDiff("eol", lines("same"), false, lines("same"), true);
        assertTrue(patch.contains("-same\n\\ No newline at end of file\n"));
        assertTrue(patch.contains("+same\n"));
    }

    @Test
    void noNewlineAtEndOfFileIsMarked() {
        String patch = UnifiedDiff.fileDiff("f.txt", lines("a", "b"), false, lines("a", "B"), false);
        assertTrue(patch.contains("-b\n\\ No newline at end of file\n"));
        assertTrue(patch.contains("+B\n\\ No newline at end of file\n"));
    }

    @Test
    @EnabledIfSystemProperty(named = "dsh.test.gitapply", matches = "true")
    void patchesRoundTripThroughGitApply(@TempDir Path dir) throws Exception {
        // 首尾两处远距离变更 → 两个 hunk，验证行号与上下文正确可应用
        roundTrip(dir, "multi.txt",
                numberedLines(30, java.util.Set.of()),
                numberedLines(30, java.util.Set.of(0, 29)));
    }

    private void roundTrip(Path dir, String name, List<String> oldLines, List<String> newLines)
            throws Exception {
        Path applyDir = Files.createTempDirectory(dir, "apply-");
        Path file = applyDir.resolve(name);
        Files.writeString(file, String.join("\n", oldLines) + "\n");
        String patch = UnifiedDiff.fileDiff(name, oldLines, true, newLines, true);
        Files.writeString(applyDir.resolve("change.patch"), patch);
        Process process = new ProcessBuilder("git", "-c", "core.autocrlf=false",
                "-C", applyDir.toString(), "apply", "change.patch")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        assertEquals(String.join("\n", newLines) + "\n", Files.readString(file));
    }

    private static List<String> numberedLines(int n, java.util.Set<Integer> changedIndices) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            lines.add(changedIndices.contains(i) ? "CHANGED_LINE" : "line" + i);
        }
        return lines;
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }
}
