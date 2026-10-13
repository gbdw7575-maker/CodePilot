package com.dsh.application;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 真正的 Git 消费者验证，不用项目内部补丁解析器代替 git apply。 */
@EnabledIfSystemProperty(named = "dsh.test.gitapply", matches = "true")
class UnifiedDiffGitApplyTest {
    record Example(String name, String before, String after) { }

    static Stream<Example> examples() {
        return Stream.of(
                new Example("new.txt", null, "新增\nsecond\n"),
                new Example("deleted.txt", "before\n", null),
                new Example("new-empty.txt", null, ""),
                new Example("deleted-empty.txt", "", null),
                new Example("fill-empty.txt", "", "content\n"),
                new Example("clear.txt", "content\n", ""),
                new Example("add-eol.txt", "same", "same\n"),
                new Example("remove-eol.txt", "same\n", "same"),
                new Example("new-no-eol.txt", null, "tail"),
                new Example("delete-no-eol.txt", "tail", null),
                new Example("crlf.txt", "before\r\nlast\r\n", "after\r\nlast\r\n"),
                new Example("mixed.txt", "one\nlast", "ONE\r\nlast\n"),
                new Example("nested/space name.txt", null, "nested\n"),
                new Example("中文/文件.txt", null, "中文\n"),
                new Example("quoted\"\tname.txt", null, "quoted\n"),
                new Example("nearby.txt", "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk\nl\n",
                        "A\nb\nc\nd\ne\nf\nG\nh\ni\nj\nk\nl\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void gitApplyRestoresExactBytes(Example example, @TempDir Path temp) throws Exception {
        // 真正独立的仓库，避免位于父仓库子目录时 Git 解析路径产生误导。
        Path dir = Files.createTempDirectory(temp, "repo-");
        git(dir, "init", "-q");
        Path file = dir.resolve(example.name());
        Files.createDirectories(file.getParent());
        if (example.before() != null) Files.writeString(file, example.before());
        UnifiedDiff.TextLines oldText = UnifiedDiff.verbatim(example.before());
        UnifiedDiff.TextLines newText = UnifiedDiff.verbatim(example.after());
        String patch = UnifiedDiff.fileDiff(example.name(),
                example.before() == null ? null : oldText.lines(), oldText.endsWithNewline(),
                example.after() == null ? null : newText.lines(), newText.endsWithNewline());
        Files.writeString(dir.resolve("change.patch"), patch);
        git(dir, "apply", "--check", "change.patch");
        git(dir, "apply", "change.patch");
        if (example.after() == null) assertFalse(Files.exists(file), patch);
        else assertArrayEquals(example.after().getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file), patch);
    }

    private static void git(Path dir, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-c", "core.autocrlf=false", "-C", dir.toString()));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), output);
    }
}
