package com.dsh.agent.tool;

import com.dsh.application.DshProperties;
import com.dsh.agent.TokenEstimator;
import com.dsh.search.LiteralCodeScanner;
import com.dsh.search.RipgrepRunner;
import com.dsh.storage.ProjectEntity;
import com.dsh.workspace.SafePaths;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import com.dsh.sandbox.LocalProcessSandboxRunner;
import com.dsh.sandbox.DockerSandboxRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-05 search_code 验收：ripgrep 主路径（定位/ignore/中文/正则/零命中/截断/二进制/越界/大仓延迟），
 * rg 缺失时受限字面回退；并与旧字面扫描器做同仓结果对照。
 */
class SearchCodeToolTest {

    private static final String PROJECT_ID = "proj-search";
    private static final String RUN_ID = "run-search";

    @TempDir
    Path sourceRoot;
    @TempDir
    Path workRoot;

    private final ObjectMapper mapper = new ObjectMapper();
    private DshProperties properties;
    private WorkspaceManager workspaceManager;
    private LocalProcessSandboxRunner localSandbox;
    private WorkspaceLease lease;
    private SearchCodeTool tool;
    private final RipgrepRunner rg = new RipgrepRunner();

    /** rg 主路径用例只在本机存在 rg 时运行；回退用例独立验证降级，不受此门控影响。 */
    static boolean rgAvailable() {
        return new RipgrepRunner().isAvailable();
    }

    @BeforeEach
    void setUp() {
        properties = new DshProperties(new DshProperties.Security(List.of()),
                new DshProperties.Budget(24, 80, 200000, 20, 128000),
                new DshProperties.Loop(true), new DshProperties.Workspace(workRoot.toString()),
                new DshProperties.Sandbox("local", "dsh-sandbox:latest", "512m", "2.0", 60, 600, 4096),
                new DshProperties.Policy(10, List.of("WORKSPACE_WRITE", "EXECUTE")),
                new DshProperties.Provider(10, 120, 3, 500),
                null, null, null, null, null, null, null, null);
        workspaceManager = new WorkspaceManager(properties);
        localSandbox = new LocalProcessSandboxRunner(properties);
        ProjectEntity project = new ProjectEntity(PROJECT_ID, "搜索项目", sourceRoot.toString(),
                true, Instant.now(), Instant.now());
        lease = workspaceManager.prepare(RUN_ID, project);
        tool = new SearchCodeTool(rg);
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(RUN_ID, "thr1", "turn1", "call1", PROJECT_ID, lease,
                Instant.now().plusSeconds(600), localSandbox, properties.sandbox(), mapper);
    }

    private ObjectNode args(String query, String path, String mode, Integer limit) {
        ObjectNode node = mapper.createObjectNode();
        node.put("query", query);
        node.put("path", path);
        if (mode != null) {
            node.put("mode", mode);
        }
        if (limit != null) {
            node.put("limit", limit);
        }
        return node;
    }

    private Path write(String relative, String content) throws Exception {
        Path file = lease.dir().resolve(relative.replace("/", java.io.File.separator));
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private List<com.fasterxml.jackson.databind.JsonNode> rows(ToolOutcome o) {
        List<com.fasterxml.jackson.databind.JsonNode> rows = new java.util.ArrayList<>();
        o.structuredContent().path("matches").forEach(rows::add);
        return rows;
    }

    private Set<String> matchKeys(ToolOutcome o) {
        return rows(o).stream().map(r -> r.path("path").asText()).collect(Collectors.toSet());
    }

    // ---------- rg 主路径 ----------

    @Test
    void dockerSearchRunsWithoutHostRipgrepAndKeepsQueryLiteral() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("dsh.docker.test"),
                "显式启用 Docker 验收时运行");
        write("src/Source.java", "needle; touch escaped.txt\nneedle' ; touch escaped.txt; echo '\n注册失败\n");
        SearchCodeTool dockerTool = new SearchCodeTool(
                new RipgrepRunner("rg-definitely-absent-xyz-12345", mapper));
        DockerSandboxRunner docker = new DockerSandboxRunner(properties);
        ToolExecutionContext dockerCtx = new ToolExecutionContext(RUN_ID, "thr1", "turn1",
                "call1", PROJECT_ID, lease, Instant.now().plusSeconds(600), docker,
                properties.sandbox(), mapper);
        ToolOutcome result = dockerTool.execute(args("needle; touch escaped.txt", ".", "literal", 10), dockerCtx);
        assertEquals("SUCCEEDED", result.status());
        assertEquals("rg", result.structuredContent().path("engine").asText());
        assertEquals(1, rows(result).size());
        ToolOutcome quoted = dockerTool.execute(args("needle' ; touch escaped.txt; echo '",
                ".", "literal", 10), dockerCtx);
        assertEquals(1, rows(quoted).size());
        assertFalse(Files.exists(lease.dir().resolve("escaped.txt")));
    }

    @Test
    @EnabledIf("rgAvailable")
    void explicitGlobsFilterFilesAndCanIncludeGeneratedTypes() throws Exception {
        write("src/A.java", "NEEDLE\n");
        write("src/B.java", "NEEDLE\n");
        write("src/types.d.ts", "NEEDLE\n");
        ObjectNode filtered = args("NEEDLE", ".", "literal", 20);
        filtered.putArray("includeGlobs").add("*.java");
        filtered.putArray("excludeGlobs").add("B.java");
        assertEquals(Set.of("src/A.java"), matchKeys(tool.execute(filtered, ctx())));
        ObjectNode types = args("NEEDLE", ".", "literal", 20);
        types.putArray("includeGlobs").add("*.d.ts");
        assertEquals(Set.of("src/types.d.ts"), matchKeys(tool.execute(types, ctx())));
    }

    @Test
    @EnabledIf("rgAvailable")
    void locatesExactPathsAndLines() throws Exception {
        write("src/main/java/Foo.java", "class Foo {\n// TODO marker here\nint x = 1;\n}\n");
        write("src/main/java/Bar.java", "class Bar { /* TODO marker */ }\n");
        write("notes.txt", "nothing here\n");

        ToolOutcome o = tool.execute(args("TODO marker", ".", null, null), ctx());
        assertEquals("SUCCEEDED", o.status(), o.text());
        assertEquals("rg", o.structuredContent().path("engine").asText());
        assertEquals(2, o.structuredContent().path("matches").size());
        assertEquals(Set.of("src/main/java/Foo.java", "src/main/java/Bar.java"), matchKeys(o));

        var foo = rows(o).stream()
                .filter(r -> r.path("path").asText().endsWith("Foo.java"))
                .findFirst().orElseThrow();
        assertEquals(2, foo.path("line").asInt());
        assertEquals("// TODO marker here", foo.path("text").asText());
    }

    @Test
    @EnabledIf("rgAvailable")
    void respectsGitignoreSkipsHiddenAndGitDir() throws Exception {
        write(".gitignore", "*.log\nsecrets/\n");
        write("app.log", "TODO marker log\n");
        write("secrets/key.txt", "TODO marker secret\n");
        write(".hidden.txt", "TODO marker hidden\n");
        write(".git/objects/ab", "TODO marker git\n");
        write("src/Main.java", "// TODO marker kept\n");

        ToolOutcome o = tool.execute(args("TODO marker", ".", null, null), ctx());
        assertEquals(Set.of("src/Main.java"), matchKeys(o),
                "只有非忽略、非隐藏、非 .git 的文件应命中: " + o.text());
    }

    @Test
    @EnabledIf("rgAvailable")
    void chineseLiteralQueryMatches() throws Exception {
        write("src/错误处理.java", "// 用户注册失败时回滚事务\nvoid rollback() {}\n");
        ToolOutcome o = tool.execute(args("注册失败", ".", null, null), ctx());
        assertEquals(1, o.structuredContent().path("matches").size());
        assertEquals("src/错误处理.java",
                o.structuredContent().path("matches").get(0).path("path").asText());
        assertEquals(1, o.structuredContent().path("matches").get(0).path("line").asInt());
    }

    @Test
    @EnabledIf("rgAvailable")
    void regexModeSupportsPatternsAndLiteralDoesNot() throws Exception {
        write("a.txt", "fo\nfoo\nfooo\nbar\n");

        ToolOutcome regex = tool.execute(args("fo{2,3}", ".", "regex", null), ctx());
        assertEquals(2, regex.structuredContent().path("matches").size(),
                "正则应命中 foo/fooo 两行: " + regex.text());

        ToolOutcome literal = tool.execute(args("fo{2,3}", ".", "literal", null), ctx());
        assertEquals(0, literal.structuredContent().path("matches").size(),
                "literal 模式不解释正则元字符");
    }

    @Test
    @EnabledIf("rgAvailable")
    void invalidRegexFailsWithClearCode() throws Exception {
        write("a.txt", "foo\n");
        ToolException ex = assertThrows(ToolException.class,
                () -> tool.execute(args("[unclosed", ".", "regex", null), ctx()));
        assertEquals("INVALID_REGEX", ex.errorCode());
    }

    @Test
    @EnabledIf("rgAvailable")
    void zeroMatchesIsSuccessWithEmptyList() throws Exception {
        write("a.txt", "foo\n");
        ToolOutcome o = tool.execute(args("definitely-absent-xyz", ".", null, null), ctx());
        assertEquals("SUCCEEDED", o.status());
        assertEquals(0, o.structuredContent().path("matches").size());
        assertFalse(o.structuredContent().path("truncated").asBoolean());
        assertTrue(o.summary().contains("零命中"));
    }

    @Test
    @EnabledIf("rgAvailable")
    void limitTruncatesResultCount() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("line ").append(i).append(" NEEDLE\n");
        }
        write("many.txt", sb.toString());

        ToolOutcome o = tool.execute(args("NEEDLE", ".", null, 3), ctx());
        assertEquals(3, o.structuredContent().path("matches").size());
        assertTrue(o.structuredContent().path("truncated").asBoolean());
        assertTrue(o.truncated());
    }

    @Test
    @EnabledIf("rgAvailable")
    void longLineTruncatedAtFiveHundredChars() throws Exception {
        String longLine = "x".repeat(600) + "NEEDLE" + "y".repeat(50);
        write("long.txt", longLine + "\n");
        ToolOutcome o = tool.execute(args("NEEDLE", ".", null, null), ctx());
        var match = o.structuredContent().path("matches").get(0);
        assertEquals(500, match.path("text").asText().length());
        assertTrue(match.path("lineTruncated").asBoolean());
    }

    @Test
    @EnabledIf("rgAvailable")
    void binaryFilesSkipped() throws Exception {
        Path bin = write("blob.bin", "");
        byte[] data = new byte[]{0x00, 0x01, 'T', 'O', 'D', 'O', ' ', 'm', 0x00};
        Files.write(bin, data);
        write("a.txt", "TODO marker\n");
        ToolOutcome o = tool.execute(args("TODO marker", ".", null, null), ctx());
        assertEquals(Set.of("a.txt"), matchKeys(o));
    }

    @Test
    @EnabledIf("rgAvailable")
    void pathArgumentScopesSearch() throws Exception {
        write("sub/A.java", "NEEDLE sub\n");
        write("other/B.java", "NEEDLE other\n");
        ToolOutcome o = tool.execute(args("NEEDLE", "sub", null, null), ctx());
        assertEquals(Set.of("sub/A.java"), matchKeys(o), o.text());
    }

    @Test
    @EnabledIf("rgAvailable")
    void rejectsPathEscapeOutsideWorkspace() {
        assertThrows(SafePaths.PathEscapeException.class,
                () -> tool.execute(args("x", "../../../etc/passwd", null, null), ctx()));
        assertThrows(SafePaths.PathEscapeException.class,
                () -> tool.execute(args("x", "/etc/passwd", null, null), ctx()));
    }

    @Test
    @EnabledIf("rgAvailable")
    void largeRepoCompletesWithinLatencyBudget() throws Exception {
        for (int f = 0; f < 200; f++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2000; i++) {
                sb.append("ordinary content line ").append(i).append(" file ").append(f).append('\n');
            }
            write("bulk/f" + f + ".txt", sb.toString());
        }
        write("bulk/needle.txt", "the-unique-needle-42\n");

        long start = System.nanoTime();
        ToolOutcome o = tool.execute(args("the-unique-needle-42", ".", null, 50), ctx());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, o.structuredContent().path("matches").size());
        assertEquals("bulk/needle.txt",
                o.structuredContent().path("matches").get(0).path("path").asText());
        assertTrue(elapsedMs < 10_000, "大仓搜索应在 10s 内完成，实际 " + elapsedMs + "ms");
        // 模型视图有界：单匹配，文本仅一行
        assertTrue(o.text().length() < 2000);
    }

    @Test
    @EnabledIf("rgAvailable")
    void matchesOldLiteralScannerOnPlainTree() throws Exception {
        write("src/A.java", "NEEDLE one\n// no\nNEEDLE two\n");
        write("src/nested/B.txt", "needle lowercase\nNEEDLE three\n");
        write("doc.md", "# NEEDLE title\n");

        ToolOutcome rgOutcome = tool.execute(args("NEEDLE", ".", "literal", 200), ctx());
        var fallback = LiteralCodeScanner.scan(lease.dir(), lease.dir(), "NEEDLE", 200);

        Set<String> rgLocs = rows(rgOutcome).stream()
                .map(r -> r.path("path").asText() + "#" + r.path("line").asInt())
                .collect(Collectors.toSet());
        Set<String> fbLocs = fallback.matches().stream()
                .map(m -> m.path() + "#" + m.line()).collect(Collectors.toSet());
        assertEquals(fbLocs, rgLocs, "rg literal 与旧扫描器在普通树（无 ignore）上定位应一致");
        String fallbackText = fallback.matches().stream()
                .map(m -> m.path() + ":" + m.line() + ": " + m.text())
                .collect(Collectors.joining("\n"));
        assertEquals(TokenEstimator.estimateText(fallbackText),
                TokenEstimator.estimateText(rgOutcome.text()), "同一命中集的模型视图 token 粗估应一致");
    }

    // ---------- 回退路径（rg 缺失） ----------

    @Test
    void literalFallbackWorksWhenRgMissing() throws Exception {
        RipgrepRunner missing = new RipgrepRunner("rg-definitely-absent-xyz-12345", mapper);
        assertFalse(missing.isAvailable());
        SearchCodeTool fallbackTool = new SearchCodeTool(missing);

        write("src/A.java", "NEEDLE here\nnothing\nNEEDLE again\n");
        ToolOutcome o = fallbackTool.execute(args("NEEDLE", ".", null, null), ctx());
        assertEquals("SUCCEEDED", o.status());
        assertEquals("literal-fallback", o.structuredContent().path("engine").asText());
        assertEquals(2, o.structuredContent().path("matches").size());
        assertEquals("src/A.java",
                o.structuredContent().path("matches").get(0).path("path").asText());
    }

    @Test
    void regexRejectedWhenRgMissing() {
        RipgrepRunner missing = new RipgrepRunner("rg-definitely-absent-xyz-12345", mapper);
        SearchCodeTool fallbackTool = new SearchCodeTool(missing);
        ToolException ex = assertThrows(ToolException.class,
                () -> fallbackTool.execute(args("a+", ".", "regex", null), ctx()));
        assertEquals("RG_UNAVAILABLE", ex.errorCode());
    }
}
