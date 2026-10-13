package com.dsh.sandbox;

import com.dsh.agent.tool.ApplyPatchTool;
import com.dsh.agent.tool.GitDiffTool;
import com.dsh.agent.tool.GitStatusTool;
import com.dsh.agent.tool.ListFilesTool;
import com.dsh.agent.tool.ReadFileTool;
import com.dsh.agent.tool.RunCommandTool;
import com.dsh.agent.tool.SearchCodeTool;
import com.dsh.agent.tool.Tool;
import com.dsh.agent.tool.ToolExecutionContext;
import com.dsh.agent.tool.ToolOutcome;
import com.dsh.agent.tool.UpdatePlanTool;
import com.dsh.application.DshProperties;
import com.dsh.common.Digest;
import com.dsh.storage.ProjectEntity;
import com.dsh.workspace.SafePaths;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-004 验收（开发准备与验收 §2）：文件哈希冲突、路径逃逸、真实命令、超时/清理证据。
 * 不依赖 Spring：直接组装隔离任务副本 + 本地真实沙箱 + 8 个 MVP 工具，全部真实文件 IO/进程。
 */
class SandboxIntegrationTest {

    private static final String RUN_ID = "run_test1";
    private static final String PROJECT_ID = "proj_test1";

    @TempDir
    Path sourceRoot;
    @TempDir
    Path workRoot;

    ObjectMapper mapper = new ObjectMapper();
    DshProperties properties;
    WorkspaceManager workspaceManager;
    LocalProcessSandboxRunner localSandbox;
    WorkspaceLease lease;

    private final ListFilesTool listFiles = new ListFilesTool();
    private final ReadFileTool readFile =
            new ReadFileTool(new com.dsh.agent.tool.RunReadRegistry());
    private final SearchCodeTool searchCode =
            new SearchCodeTool(new com.dsh.search.RipgrepRunner());
    private final ApplyPatchTool applyPatch = new ApplyPatchTool();
    private final RunCommandTool runCommand =
            new RunCommandTool(new com.dsh.agent.tool.RunCommandRegistry());
    private final GitStatusTool gitStatus = new GitStatusTool();
    private final GitDiffTool gitDiff = new GitDiffTool();
    // 该用例只验证 legacy steps 的工作区写入；v2 落库依赖 PlanService，由 MA-02 集成测试覆盖
    private final UpdatePlanTool updatePlan = new UpdatePlanTool(null);

    @BeforeEach
    void setUp() throws Exception {
        // 4 KiB 的日志上限，专门验证截断标记
        properties = new DshProperties(new DshProperties.Security(List.of()),
                new DshProperties.Budget(24, 80, 200000, 20, 128000),
                new DshProperties.Loop(true), new DshProperties.Workspace(workRoot.toString()),
                new DshProperties.Sandbox("local", "dsh-sandbox:latest", "512m", "2.0", 60, 600, 4096),
                new DshProperties.Policy(10, List.of("WORKSPACE_WRITE", "EXECUTE")),
                new DshProperties.Provider(10, 120, 3, 500),
                null, null, null, null, null, null, null, null);
        workspaceManager = new WorkspaceManager(properties);
        localSandbox = new LocalProcessSandboxRunner(properties);
    }

    private WorkspaceLease prepareLease(boolean trusted) {
        ProjectEntity project = new ProjectEntity(PROJECT_ID, "演示项目", sourceRoot.toString(),
                trusted, Instant.now(), Instant.now());
        lease = workspaceManager.prepare(RUN_ID, project);
        return lease;
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(RUN_ID, "thr1", "turn1", "call1", PROJECT_ID, lease,
                Instant.now().plusSeconds(600), localSandbox, properties.sandbox(), mapper);
    }

    private ObjectNode args() {
        return mapper.createObjectNode();
    }

    /** 直调工具时，受控错误以 ToolException 抛出（Dispatcher 层会映射为 ToolOutcome）。 */
    private String errorCodeOf(Tool tool, ObjectNode args) throws Exception {
        try {
            ToolOutcome outcome = tool.execute(args, ctx());
            return outcome.errorCode();
        } catch (com.dsh.agent.tool.ToolException e) {
            return e.errorCode();
        }
    }

    private String hash(Path file) throws Exception {
        return Digest.sha256Hex(Files.readAllBytes(file));
    }

    private void writeSource(String relative, String content) throws Exception {
        Path file = sourceRoot.resolve(relative.replace("/", java.io.File.separator));
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // ---------- 工作区隔离与基线 ----------

    @Test
    void workspaceCopyIsIsolatedAndDigestTracksChanges() throws Exception {
        writeSource("a.txt", "基线内容\n");
        prepareLease(true);
        assertEquals("基线内容\n", Files.readString(lease.dir().resolve("a.txt")));

        // 副本修改不回写用户项目
        Files.writeString(lease.dir().resolve("a.txt"), "副本修改\n");
        assertEquals("基线内容\n", Files.readString(sourceRoot.resolve("a.txt")));
        // 元数据目录在副本之外
        assertTrue(Files.isDirectory(lease.metaDir()));
        assertFalse(lease.metaDir().startsWith(lease.dir()));
    }

    // ---------- list/read/search ----------

    @Test
    void listReadAndSearchRespectCopyBoundary() throws Exception {
        writeSource("src/main/Foo.java", "class Foo {\n// TODO marker\n}\n");
        writeSource("src/main/Bar.java", "class Bar {}\n");
        writeSource("notes.txt", "release notes\n");
        prepareLease(true);

        ObjectNode listArgs = args();
        listArgs.put("path", ".");
        listArgs.put("glob", "*.java");
        ToolOutcome listing = listFiles.execute(listArgs, ctx());
        assertEquals("SUCCEEDED", listing.status());
        assertTrue(listing.text().contains("src/main/Foo.java"));
        assertTrue(listing.text().contains("src/main/Bar.java"));
        assertFalse(listing.text().contains("notes.txt"));

        ObjectNode readArgs = args();
        readArgs.put("path", "src/main/Foo.java");
        ToolOutcome read = readFile.execute(readArgs, ctx());
        assertEquals("SUCCEEDED", read.status());
        assertTrue(read.text().contains("TODO marker"));
        assertEquals(hash(sourceRoot.resolve("src/main/Foo.java")),
                read.structuredContent().path("sha256").asText());
        assertEquals(3, read.structuredContent().path("totalLines").asInt());

        ObjectNode searchArgs = args();
        searchArgs.put("query", "TODO marker");
        searchArgs.put("path", "src");
        ToolOutcome search = searchCode.execute(searchArgs, ctx());
        assertEquals("SUCCEEDED", search.status());
        assertEquals(1, search.structuredContent().path("matches").size());
        assertEquals("src/main/Foo.java",
                search.structuredContent().path("matches").get(0).path("path").asText());
    }

    @Test
    void readFileRejectsLineRangeErrors() throws Exception {
        writeSource("one.txt", "a\nb\nc\n");
        prepareLease(true);
        ObjectNode bad = args();
        bad.put("path", "one.txt");
        bad.put("startLine", 3);
        bad.put("endLine", 1);
        assertEquals("INVALID_ARGUMENTS", errorCodeOf(readFile, bad));
    }

    // ---------- apply_patch：哈希冲突 / 新建 / 穿越 ----------

    @Test
    void applyPatchWritesWhenHashesMatchIncludingNewFile() throws Exception {
        writeSource("doc.txt", "line1\nline2\nline3\n");
        prepareLease(true);
        Path target = lease.dir().resolve("doc.txt");
        String currentHash = hash(target);

        String patch = """
                --- a/doc.txt
                +++ b/doc.txt
                @@ -1,3 +1,3 @@
                 line1
                -line2
                +line2-changed
                 line3
                --- /dev/null
                +++ b/new.txt
                @@ -0,0 +1,1 @@
                +brand new
                """;
        ObjectNode a = args();
        a.put("patch", patch);
        ObjectNode hashes = a.putObject("expectedFileHashes");
        hashes.put("doc.txt", currentHash);
        hashes.putNull("new.txt");
        ToolOutcome outcome = applyPatch.execute(a, ctx());

        assertEquals("SUCCEEDED", outcome.status(), outcome.summary());
        assertEquals("line1\nline2-changed\nline3\n", Files.readString(target));
        assertEquals("brand new\n", Files.readString(lease.dir().resolve("new.txt")));
        assertNotNull(outcome.structuredContent().path("workspaceDigest").asText());
        // 用户原文件未动
        assertEquals("line1\nline2\nline3\n", Files.readString(sourceRoot.resolve("doc.txt")));
    }

    @Test
    void applyPatchAcceptsBareContextHunkWithoutLineNumbers() throws Exception {
        writeSource("bare.txt", "first\nold\nlast\n");
        prepareLease(true);
        Path target = lease.dir().resolve("bare.txt");
        ObjectNode a = args();
        a.put("patch", "--- a/bare.txt\n+++ b/bare.txt\n@@\n first\n-old\n+new\n last\n");
        a.putObject("expectedFileHashes").put("bare.txt", hash(target));
        ToolOutcome outcome = applyPatch.execute(a, ctx());
        assertEquals("SUCCEEDED", outcome.status());
        assertEquals("first\nnew\nlast\n", Files.readString(target));
    }

    @Test
    void malformedHunkReturnsControlledErrorAndDoesNotWrite() throws Exception {
        writeSource("bad.txt", "old\n");
        prepareLease(true);
        Path target = lease.dir().resolve("bad.txt");
        ObjectNode a = args();
        a.put("patch", "--- a/bad.txt\n+++ b/bad.txt\n@@ -bad +1 @@\n-old\n+new\n");
        a.putObject("expectedFileHashes").put("bad.txt", hash(target));
        assertEquals("INVALID_PATCH", errorCodeOf(applyPatch, a));
        assertEquals("old\n", Files.readString(target));
    }

    @Test
    void hashConflictRejectsEntirePatchAndKeepsFiles() throws Exception {
        writeSource("a.txt", "alpha\n");
        writeSource("b.txt", "beta\n");
        prepareLease(true);

        String patch = """
                --- a/a.txt
                +++ b/a.txt
                @@ -1 +1 @@
                -alpha
                +ALPHA
                --- a/b.txt
                +++ b/b.txt
                @@ -1 +1 @@
                -beta
                +BETA
                """;
        ObjectNode a = args();
        a.put("patch", patch);
        ObjectNode hashes = a.putObject("expectedFileHashes");
        hashes.put("a.txt", Digest.sha256Hex("wrong-content\n"));
        hashes.put("b.txt", hash(lease.dir().resolve("b.txt")));
        assertEquals("HASH_CONFLICT", errorCodeOf(applyPatch, a));
        assertEquals("alpha\n", Files.readString(lease.dir().resolve("a.txt")));
        assertEquals("beta\n", Files.readString(lease.dir().resolve("b.txt")));
    }

    @Test
    void expectedNullForExistingFileIsConflictAndNothingWritten() throws Exception {
        writeSource("exists.txt", "x\n");
        prepareLease(true);
        String patch = """
                --- /dev/null
                +++ b/exists.txt
                @@ -0,0 +1 @@
                +y
                """;
        ObjectNode a = args();
        a.put("patch", patch);
        a.putObject("expectedFileHashes").putNull("exists.txt");
        assertEquals("HASH_CONFLICT", errorCodeOf(applyPatch, a));
        assertEquals("x\n", Files.readString(lease.dir().resolve("exists.txt")));
    }

    @Test
    void missingOrExtraHashEntriesAreInvalidArguments() throws Exception {
        writeSource("a.txt", "alpha\n");
        prepareLease(true);
        String patch = """
                --- a/a.txt
                +++ b/a.txt
                @@ -1 +1 @@
                -alpha
                +ALPHA
                """;
        ObjectNode missing = args();
        missing.put("patch", patch);
        missing.putObject("expectedFileHashes");
        assertEquals("INVALID_ARGUMENTS", errorCodeOf(applyPatch, missing));

        ObjectNode extra = args();
        extra.put("patch", patch);
        ObjectNode hashes = extra.putObject("expectedFileHashes");
        hashes.put("a.txt", hash(lease.dir().resolve("a.txt")));
        hashes.put("other.txt", Digest.sha256Hex("z"));
        assertEquals("INVALID_ARGUMENTS", errorCodeOf(applyPatch, extra));
    }

    @Test
    void patchPathTraversalIsRejectedBeforeAnyWrite() throws Exception {
        writeSource("a.txt", "safe\n");
        prepareLease(true);
        String patch = """
                --- /dev/null
                +++ b/../../evil.txt
                @@ -0,0 +1 @@
                +pwned
                """;
        ObjectNode a = args();
        a.put("patch", patch);
        a.putObject("expectedFileHashes").putNull("../../evil.txt");
        assertThrows(SafePaths.PathEscapeException.class, () -> applyPatch.execute(a, ctx()));
        assertFalse(Files.exists(workRoot.resolve("evil.txt")));
    }

    // ---------- 路径逃逸 / 符号链接 ----------

    @Test
    void relativePathEscapesAreRejected() throws Exception {
        writeSource("inside.txt", "ok\n");
        prepareLease(true);

        ObjectNode read = args();
        read.put("path", "../../../../../../../../Windows/win.ini");
        assertThrows(SafePaths.PathEscapeException.class, () -> readFile.execute(read, ctx()));

        ObjectNode list = args();
        list.put("path", "..");
        assertThrows(SafePaths.PathEscapeException.class, () -> listFiles.execute(list, ctx()));

        ObjectNode cmd = args();
        cmd.put("command", "echo hi");
        cmd.put("workdir", "../../..");
        assertThrows(SafePaths.PathEscapeException.class, () -> runCommand.execute(cmd, ctx()));

        ObjectNode abs = args();
        abs.put("path", "C:/Windows");
        assertThrows(SafePaths.PathEscapeException.class, () -> listFiles.execute(abs, ctx()));
    }

    @Test
    void symlinkEscapeIsRejectedButLinkItselfMayBeListed() throws Exception {
        Path outsideDir = Files.createDirectory(workRoot.resolve("outside-secret"));
        Files.writeString(outsideDir.resolve("secret.txt"), "top secret\n");
        Path link = sourceRoot.resolve("link-out");
        try {
            Files.createSymbolicLink(link, outsideDir);
        } catch (Exception e) {
            Assumptions.abort("当前环境不允许创建符号链接: " + e.getMessage());
        }
        writeSource("inside.txt", "ok\n");
        prepareLease(true);

        ObjectNode list = args();
        list.put("path", ".");
        ToolOutcome listing = listFiles.execute(list, ctx());
        assertTrue(listing.text().contains("link-out"));

        ObjectNode read = args();
        read.put("path", "link-out/secret.txt");
        assertThrows(SafePaths.PathEscapeException.class, () -> readFile.execute(read, ctx()));
    }

    // ---------- 真实命令 / 退出码 / 超时 / 截断 ----------

    @Test
    void runCommandExecutesRealCommandWithExitZero() throws Exception {
        prepareLease(true);
        ObjectNode a = args();
        a.put("command", "echo dsh-real-command-marker");
        ToolOutcome outcome = runCommand.execute(a, ctx());
        assertEquals("SUCCEEDED", outcome.status(), outcome.summary());
        assertEquals(Integer.valueOf(0), outcome.exitCode());
        assertTrue(outcome.text().contains("dsh-real-command-marker"));
        assertEquals("local", outcome.structuredContent().path("sandbox").asText());
    }

    @Test
    void nonZeroExitIsFailedWithRealExitCode() throws Exception {
        prepareLease(true);
        ObjectNode a = args();
        a.put("command", "exit 7");
        ToolOutcome outcome = runCommand.execute(a, ctx());
        assertEquals("FAILED", outcome.status());
        assertEquals("COMMAND_FAILED", outcome.errorCode());
        assertEquals(Integer.valueOf(7), outcome.exitCode());
    }

    @Test
    void timeoutKillsProcessTreeAndConfirmsCleanup() throws Exception {
        prepareLease(true);
        ObjectNode a = args();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        a.put("command", windows ? "ping -n 30 127.0.0.1 > nul" : "sleep 30");
        a.put("timeoutSeconds", 1);
        long started = System.nanoTime();
        ToolOutcome outcome = runCommand.execute(a, ctx());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertEquals("FAILED", outcome.status());
        assertEquals("TIMEOUT", outcome.errorCode());
        assertNull(outcome.exitCode(), "超时不得伪造退出码");
        assertTrue(outcome.structuredContent().path("cleanupConfirmed").asBoolean(),
                "进程树清理必须被确认");
        assertTrue(elapsedMs < 20000, "超时回收应在有界时间内完成，实际 " + elapsedMs + "ms");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void outputBeyondLimitIsMarkedTruncated() throws Exception {
        // Windows 与 POSIX 分别用 type/cat，Windows 路径/编码差异较大，截断语义由 POSIX 用例固化
        writeSource("big.txt", "a".repeat(6000) + "\n");
        prepareLease(true);
        ObjectNode a = args();
        a.put("command", "cat big.txt");
        ToolOutcome outcome = runCommand.execute(a, ctx());
        assertTrue(outcome.truncated(), "超过 4KiB 的输出必须标记 truncated");
        // A3 的上限约束原始保留字节；截断说明单独附加，不能把它误算作超额日志。
        assertEquals(6001L, outcome.structuredContent().path("outputTotalBytes").asLong());
        assertEquals(1905L, outcome.structuredContent().path("outputDroppedBytes").asLong());
        assertTrue(outcome.text().contains("中间 1905 字节已截断"));
        String retained = outcome.text().replaceFirst("\n\\[……中间 [^\n]*……]\\n", "");
        assertEquals(4096, retained.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(retained.endsWith("\n"), "尾部原始输出必须保留");
    }

    @Test
    void unknownWorkdirIsRejected() throws Exception {
        prepareLease(true);
        ObjectNode a = args();
        a.put("command", "echo hi");
        a.put("workdir", "no-such-dir");
        assertEquals("WORKDIR_NOT_FOUND", errorCodeOf(runCommand, a));
    }

    // ---------- D-01：凭据环境变量不进入命令子进程 ----------

    @Test
    void localSandboxStripsCredentialEnvironmentVariables() throws Exception {
        prepareLease(true);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        // 直接模拟"宿主环境含凭据"：ProcessBuilder 初始环境继承自 JVM，手动注入后走同一剥离路径
        ProcessBuilder builder = new ProcessBuilder(LocalProcessSandboxRunner.commandLine(windows ? "set" : "env"))
                .directory(lease.dir().toFile());
        builder.environment().put("DSH_CREDENTIAL_LEAK_TEST", "sk-leak-secret-123");
        builder.environment().put("DSH_ORDINARY_MARKER", "ordinary-visible");
        LocalProcessSandboxRunner.sanitizeEnvironment(builder.environment());

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
                windows ? java.nio.charset.Charset.defaultCharset() : java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertFalse(output.contains("sk-leak-secret-123"), "凭据值不得进入命令子进程: " + output);
        assertFalse(output.contains("DSH_CREDENTIAL_LEAK_TEST"),
                "DSH_CREDENTIAL_* 变量不得进入命令子进程: " + output);
        assertTrue(output.contains("DSH_ORDINARY_MARKER=ordinary-visible"),
                "剥离凭据时不得清空其余环境变量: " + output);
    }

    // ---------- D-01：符号链接/目录联接项目源根不得进入副本 ----------

    @Test
    void symbolicLinkProjectRootIsRejected() throws Exception {
        Path realTarget = Files.createDirectory(workRoot.resolve("real-project"));
        Files.writeString(realTarget.resolve("a.txt"), "external content\n");
        Path link = workRoot.resolve("link-project");
        try {
            Files.createSymbolicLink(link, realTarget);
        } catch (Exception e) {
            Assumptions.abort("当前环境不允许创建符号链接: " + e.getMessage());
        }
        ProjectEntity project = new ProjectEntity("proj_link", "链接项目", link.toString(),
                true, Instant.now(), Instant.now());
        WorkspaceManager.WorkspaceNotAvailableException e = assertThrows(
                WorkspaceManager.WorkspaceNotAvailableException.class,
                () -> workspaceManager.prepare("run_link_root", project));
        assertEquals("WORKSPACE_UNAVAILABLE", e.code());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void junctionProjectRootIsRejectedOnWindows() throws Exception {
        // Windows junction：普通用户可创建、Files.isSymbolicLink 识别不了，必须靠 toRealPath 兜底
        Path realTarget = Files.createDirectory(workRoot.resolve("real-project-j"));
        Files.writeString(realTarget.resolve("a.txt"), "external content via junction\n");
        Path link = workRoot.resolve("junction-project");
        createJunction(link, realTarget);
        try {
            ProjectEntity project = new ProjectEntity("proj_junc", "联接项目", link.toString(),
                    true, Instant.now(), Instant.now());
            WorkspaceManager.WorkspaceNotAvailableException e = assertThrows(
                    WorkspaceManager.WorkspaceNotAvailableException.class,
                    () -> workspaceManager.prepare("run_junction_root", project));
            assertEquals("WORKSPACE_UNAVAILABLE", e.code());
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void junctionSubtreeInsideProjectIsNotCopiedIntoLease() throws Exception {
        // 项目树内部的 junction 指向宿主外部目录：副本不得跟随复制外部内容
        Path outside = Files.createDirectory(workRoot.resolve("outside-via-junction"));
        Files.writeString(outside.resolve("secret.txt"), "host-only-secret-content\n");
        Path junctionInSource = sourceRoot.resolve("ext-link");
        createJunction(junctionInSource, outside);
        writeSource("keep.txt", "normal project file\n");
        try {
            prepareLease(true);
            assertTrue(Files.exists(lease.dir().resolve("keep.txt")), "普通项目文件必须正常复制");
            assertFalse(Files.exists(lease.dir().resolve("ext-link").resolve("secret.txt")),
                    "junction 指向的宿主外部内容不得进入任务副本");
            String copiedTree = Files.walk(lease.dir())
                    .filter(Files::isRegularFile)
                    .map(p -> {
                        try { return Files.readString(p); } catch (Exception ex) { return ""; }
                    })
                    .reduce("", String::concat);
            assertFalse(copiedTree.contains("host-only-secret-content"),
                    "副本任何位置都不得出现 junction 目标内容");
        } finally {
            Files.deleteIfExists(junctionInSource);
        }
    }

    // ---------- 信任与项目根 ----------

    @Test
    void untrustedProjectCannotOpenWorkspace() {
        assertThrows(WorkspaceManager.WorkspaceNotAvailableException.class,
                () -> prepareLease(false));
    }

    @Test
    void missingProjectRootIsRejected() throws Exception {
        ProjectEntity project = new ProjectEntity(PROJECT_ID, "坏项目",
                sourceRoot.resolve("does-not-exist").toString(), true, Instant.now(), Instant.now());
        WorkspaceManager.WorkspaceNotAvailableException e = assertThrows(
                WorkspaceManager.WorkspaceNotAvailableException.class,
                () -> workspaceManager.prepare(RUN_ID, project));
        assertEquals("WORKSPACE_UNAVAILABLE", e.code());
    }

    // ---------- git_status / git_diff 相对基线 ----------

    @Test
    void gitStatusAndDiffShowChangesAndNewFiles() throws Exception {
        writeSource("tracked.txt", "v1\n");
        git(sourceRoot, "init");
        git(sourceRoot, "config", "user.email", "dsh@example.com");
        git(sourceRoot, "config", "user.name", "DSH");
        git(sourceRoot, "add", "tracked.txt");
        git(sourceRoot, "commit", "-m", "baseline");
        prepareLease(true);

        ToolOutcome clean = gitStatus.execute(args(), ctx());
        assertTrue(clean.structuredContent().path("clean").asBoolean(), "副本刚建立时应无差异");

        Path tracked = lease.dir().resolve("tracked.txt");
        String patch = """
                --- a/tracked.txt
                +++ b/tracked.txt
                @@ -1 +1 @@
                -v1
                +v2
                """;
        ObjectNode pa = args();
        pa.put("patch", patch);
        ObjectNode hashes = pa.putObject("expectedFileHashes");
        hashes.put("tracked.txt", hash(tracked));
        assertEquals("SUCCEEDED", applyPatch.execute(pa, ctx()).status());
        Files.writeString(lease.dir().resolve("brand-new.txt"), "new file payload\n");

        ToolOutcome status = gitStatus.execute(args(), ctx());
        String statusText = status.text();
        assertTrue(statusText.contains("tracked.txt"), statusText);
        assertTrue(statusText.contains("brand-new.txt"), statusText);

        ToolOutcome diff = gitDiff.execute(args(), ctx());
        assertEquals("SUCCEEDED", diff.status());
        assertTrue(diff.text().contains("-v1"));
        assertTrue(diff.text().contains("+v2"));
        assertTrue(diff.text().contains("new file mode 100644"));
        assertTrue(diff.text().contains("+new file payload"));

        ToolOutcome filteredDiff = gitDiff.execute(args().set("paths",
                mapper.createArrayNode().add("tracked.txt")), ctx());
        assertTrue(filteredDiff.text().contains("+v2"));
        assertFalse(filteredDiff.text().contains("new file mode"));
    }

    @Test
    void gitStatusOutsideRepositoryFailsGracefully() throws Exception {
        writeSource("plain.txt", "no git here\n");
        prepareLease(true);
        ToolOutcome outcome = gitStatus.execute(args(), ctx());
        assertEquals("FAILED", outcome.status());
        assertEquals("GIT_ERROR", outcome.errorCode());
    }

    // ---------- update_plan 产物不污染源码副本 ----------

    @Test
    void updatePlanWritesProductOutsideCopy() throws Exception {
        writeSource("tracked.txt", "v1\n");
        git(sourceRoot, "init");
        git(sourceRoot, "config", "user.email", "dsh@example.com");
        git(sourceRoot, "config", "user.name", "DSH");
        git(sourceRoot, "add", "tracked.txt");
        git(sourceRoot, "commit", "-m", "baseline");
        prepareLease(true);

        ObjectNode a = args();
        var steps = a.putArray("steps");
        steps.addObject().put("text", "第一步").put("status", "DONE");
        steps.addObject().put("text", "第二步").put("status", "IN_PROGRESS");
        ToolOutcome outcome = updatePlan.execute(a, ctx());
        assertEquals("SUCCEEDED", outcome.status());
        assertTrue(Files.exists(lease.metaDir().resolve("plan.json")));
        assertTrue(outcome.text().contains("[x] 第一步"));

        ToolOutcome status = gitStatus.execute(args(), ctx());
        assertTrue(status.structuredContent().path("clean").asBoolean(),
                "计划产物不得出现在源码副本的 git 状态中");
    }

    // ---------- Docker 模式（需守护进程与镜像，默认跳过，显式 -Ddsh.docker.test=true 取证） ----------

    /** 假设 Docker 守护进程与 dsh-sandbox:latest 可用，并准备好工作区；否则跳过（跳过不计通过）。 */
    private DockerSandboxRunner dockerReady() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("dsh.docker.test"),
                "设置 -Ddsh.docker.test=true 且本机有 Docker 守护进程与 dsh-sandbox:latest 镜像时运行");
        Process info = new ProcessBuilder("docker", "image", "inspect", "dsh-sandbox:latest")
                .redirectErrorStream(true).start();
        Assumptions.assumeTrue(info.waitFor(10, TimeUnit.SECONDS) && info.exitValue() == 0,
                "缺少 dsh-sandbox:latest 镜像（cd DSH && docker build -t dsh-sandbox:latest sandbox）");
        prepareLease(true);
        return new DockerSandboxRunner(properties);
    }

    private static void assumeContainerLaunched(CommandOutcome outcome) {
        Assumptions.assumeTrue(outcome.termination() != CommandSpec.Termination.LAUNCH_FAILED,
                "Docker 守护进程不可用: " + outcome.stderr());
    }

    private CommandOutcome dockerCommand(DockerSandboxRunner docker, String command, int timeout) {
        return docker.execute(new CommandSpec(com.dsh.common.IdGen.next("op_"),
                command, null, timeout), lease);
    }

    @Test
    void dockerRunnerExecutesRealCommandWhenImageAvailable() throws Exception {
        DockerSandboxRunner docker = dockerReady();
        CommandOutcome outcome = dockerCommand(docker, "echo dsh-docker-marker", 30);
        assumeContainerLaunched(outcome);
        assertEquals(CommandSpec.Termination.COMPLETED, outcome.termination());
        assertEquals(Integer.valueOf(0), outcome.exitCode());
        assertTrue(outcome.stdout().contains("dsh-docker-marker"));
    }

    @Test
    void dockerRunnerEnforcesNonRootCapabilitiesAndResourceLimits() throws Exception {
        DockerSandboxRunner docker = dockerReady();

        CommandOutcome id = dockerCommand(docker, "id -u -n && id -u", 30);
        assumeContainerLaunched(id);
        assertEquals(0, id.exitCode());
        assertTrue(id.stdout().contains("dsh"), id.stdout());
        assertTrue(id.stdout().lines().anyMatch(l -> "1000".equals(l.trim())), "必须以 uid 1000 运行: " + id.stdout());

        CommandOutcome hardening = dockerCommand(docker,
                "grep CapEff /proc/self/status; grep NoNewPrivs /proc/self/status", 30);
        assumeContainerLaunched(hardening);
        assertTrue(hardening.stdout().contains("0000000000000000"), "必须丢弃所有 Linux capabilities: " + hardening.stdout());
        assertTrue(hardening.stdout().contains("NoNewPrivs:") && hardening.stdout().contains("1"),
                "必须禁止提权: " + hardening.stdout());

        CommandOutcome cgroup = dockerCommand(docker,
                "cat /sys/fs/cgroup/memory.max; cat /sys/fs/cgroup/cpu.max; cat /sys/fs/cgroup/pids.max", 30);
        assumeContainerLaunched(cgroup);
        String limits = cgroup.stdout().replace('\r', ' ');
        assertTrue(limits.contains("536870912"), "内存上限应为 512MiB: " + limits);
        assertTrue(limits.contains("200000 100000"), "CPU 上限应为 2.0 核: " + limits);
        assertTrue(limits.matches("(?s).*\\s256\\s.*"), "PID 上限应为 256: " + limits);

        CommandOutcome writes = dockerCommand(docker, "touch /workspace/.d01-write-probe && echo WS_WRITABLE", 30);
        assumeContainerLaunched(writes);
        assertTrue(writes.stdout().contains("WS_WRITABLE"), "非 root 用户必须能写挂载的任务副本: " + writes.stderr());
    }

    @Test
    void dockerRunnerDeniesNetworkSocketsSecretsAndHostPaths() throws Exception {
        DockerSandboxRunner docker = dockerReady();

        // 网络：--network none 下任何出站连接立即失败
        CommandOutcome net = dockerCommand(docker,
                "timeout 5 bash -c 'cat < /dev/null > /dev/tcp/8.8.8.8/53' && echo NET_OPEN || echo NET_DENIED", 30);
        assumeContainerLaunched(net);
        assertTrue(net.stdout().contains("NET_DENIED"), "容器默认必须无网络: " + net.stdout() + net.stderr());

        // Docker socket / 凭据目录 / 凭据环境变量都不可达
        CommandOutcome secrets = dockerCommand(docker,
                "test ! -e /var/run/docker.sock && echo NO_SOCK; "
                        + "test ! -e /run/secrets && echo NO_SECRETS; "
                        + "test ! -e /root/.ssh && echo NO_SSH; "
                        + "(env | grep -c DSH_CREDENTIAL || true)", 30);
        assumeContainerLaunched(secrets);
        assertTrue(secrets.stdout().contains("NO_SOCK"), secrets.stdout());
        assertTrue(secrets.stdout().contains("NO_SECRETS"), secrets.stdout());
        assertTrue(secrets.stdout().contains("NO_SSH"), secrets.stdout());
        assertTrue(secrets.stdout().lines().anyMatch(l -> "0".equals(l.trim())),
                "容器环境不得出现 DSH_CREDENTIAL_*: " + secrets.stdout());

        // 挂载白名单：/workspace 恰好一条 bind，docker.sock 不在挂载表
        CommandOutcome mounts = dockerCommand(docker,
                "cat /proc/mounts | grep ' /workspace ' | wc -l; "
                        + "(grep -c docker.sock /proc/mounts || true)", 30);
        assumeContainerLaunched(mounts);
        List<String> mountLines = mounts.stdout().lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        assertTrue(mountLines.contains("1"), "必须只挂载任务副本一个工作卷: " + mounts.stdout());
        assertTrue(mountLines.contains("0"), "不得挂载 Docker socket: " + mounts.stdout());

        // 符号链接/相对路径向上解析到的是容器 rootfs，不是宿主；宿主 sibling 路径不可达
        CommandOutcome escape = dockerCommand(docker,
                "ln -s ../../../../ /workspace/up-root; "
                        + "test -e /workspace/../host-secret/secret.txt && echo HOST_SECRET_READABLE || echo HOST_UNREACHABLE; "
                        + "test -e /workspace/up-root/host-secret/secret.txt && echo HOST_VIA_LINK || echo NO_HOST_VIA_LINK; "
                        + "ls /workspace/up-root | grep -Ec '^(bin|etc|proc)$'", 30);
        assumeContainerLaunched(escape);
        assertTrue(escape.stdout().contains("HOST_UNREACHABLE"), escape.stdout());
        assertTrue(escape.stdout().contains("NO_HOST_VIA_LINK"), escape.stdout());
    }

    @Test
    void dockerRunnerTimeoutForceRemovesContainer() throws Exception {
        DockerSandboxRunner docker = dockerReady();
        String operationId = com.dsh.common.IdGen.next("op_");
        CommandOutcome outcome = docker.execute(
                new CommandSpec(operationId, "sleep 60", null, 2), lease);
        assumeContainerLaunched(outcome);
        assertEquals(CommandSpec.Termination.TIMEOUT, outcome.termination());
        assertNull(outcome.exitCode(), "超时不得伪造退出码");
        assertTrue(outcome.cleanupConfirmed(), "超时后必须确认容器已回收");

        String containerName = "dsh-" + operationId.replace('_', '-');
        Process inspect = new ProcessBuilder("docker", "inspect", "-f", "{{.Id}}", containerName)
                .redirectErrorStream(true).start();
        boolean stillExists = inspect.waitFor(10, TimeUnit.SECONDS) && inspect.exitValue() == 0;
        assertFalse(stillExists, "超时容器不得残留在 docker ps -a 中: " + containerName);
    }

    /** 创建 Windows 目录联接（junction）：普通用户权限即可，用于重解析点绕过反例。 */
    private static void createJunction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd", "/c",
                "mklink /J \"" + link + "\" \"" + target + "\"").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0,
                "mklink /J 失败: " + output);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean done = process.waitFor(30, TimeUnit.SECONDS);
        if (!done || process.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 失败: " + output);
        }
    }
}
