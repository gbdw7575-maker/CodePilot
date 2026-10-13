package com.dsh.api;

import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DSH-007 Diff 验收：工作区变更（新增/修改）反映在 Diff 中，二进制文件标记 binary。
 */
class DiffIntegrationTest extends ApiTestBase {

    @Autowired
    private RunRepository runRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private WorkspaceManager workspaceManager;

    private String bootstrapRunWithWorkspace() throws Exception {
        return bootstrapRunWithWorkspace(Map.of());
    }

    private String bootstrapRunWithWorkspace(Map<String, String> baselineFiles) throws Exception {
        Path root = Path.of("target", "test-run", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(root);
        for (Map.Entry<String, String> entry : baselineFiles.entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
        String rootPath = root.toString().replace('\\', '/');
        String projectId = createProject("Diff-Proj-" + UUID.randomUUID(), rootPath);
        trustProject(projectId, 1);
        String profileId = createProfile("diff-test-" + UUID.randomUUID(), "fake-tool");
        String threadId = createThread(projectId, profileId);
        JsonNode accepted = submitTurn(threadId, "call echo tool");
        String runId = accepted.get("rootRunId").asText();

        // fake-tool 的 echo 不触发工作区准备，手动准备工作区以测试 Diff
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        WorkspaceLease lease = workspaceManager.prepare(runId, project);
        RunEntity run = runRepository.findById(runId).orElseThrow();
        run.setWorkspaceRef(lease.workspaceId());
        runRepository.save(run);
        return runId;
    }

    @Test
    void addedFileAppearsInDiff() throws Exception {
        String runId = bootstrapRunWithWorkspace();
        RunEntity run = runRepository.findById(runId).orElseThrow();
        Path workspaceDir = workspaceManager.resolveByRef(run.getWorkspaceRef()).dir();

        Files.writeString(workspaceDir.resolve("newfile.txt"), "hello diff\n");

        mockMvc.perform(get("/api/v1/runs/" + runId + "/diff")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.files[0].path").value("newfile.txt"))
                .andExpect(jsonPath("$.files[0].status").value("ADDED"))
                .andExpect(jsonPath("$.files[0].binary").value(false))
                .andExpect(jsonPath("$.patch").value(org.hamcrest.Matchers.containsString("+hello diff")))
                .andExpect(jsonPath("$.artifactId").isNotEmpty());
    }

    @Test
    void binaryFileIsMarked() throws Exception {
        String runId = bootstrapRunWithWorkspace();
        RunEntity run = runRepository.findById(runId).orElseThrow();
        Path workspaceDir = workspaceManager.resolveByRef(run.getWorkspaceRef()).dir();

        Files.write(workspaceDir.resolve("data.bin"), new byte[]{0, 1, 2, 3, 0});

        mockMvc.perform(get("/api/v1/runs/" + runId + "/diff")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[?(@.path=='data.bin')].binary").value(true))
                .andExpect(jsonPath("$.patch").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("data.bin"))));
    }

    @Test
    void modifiedFilePatchContainsRealRemovedAndAddedLines() throws Exception {
        String runId = bootstrapRunWithWorkspace(Map.of("src/example.txt", "first\nold\nlast\n"));
        RunEntity run = runRepository.findById(runId).orElseThrow();
        Path workspaceDir = workspaceManager.resolveByRef(run.getWorkspaceRef()).dir();
        Files.writeString(workspaceDir.resolve("src/example.txt"), "first\nnew\nlast\n");

        String response = mockMvc.perform(get("/api/v1/runs/" + runId + "/diff")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].status").value("MODIFIED"))
                .andExpect(jsonPath("$.patch").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("-old\n"),
                        org.hamcrest.Matchers.containsString("+new\n"))))
                .andExpect(jsonPath("$.truncated").value(false))
                .andReturn().getResponse().getContentAsString();

        String patch = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response).get("patch").asText();
        // 用系统临时目录而非 target/：DSH 根 git init 后 target/ 位于仓库内，
        // git apply 会把补丁路径解析进仓库命名空间导致应用行为异常
        Path applyDir = Files.createTempDirectory("dsh-diff-apply-");
        Files.createDirectories(applyDir.resolve("src"));
        Files.writeString(applyDir.resolve("src/example.txt"), "first\nold\nlast\n");
        Files.writeString(applyDir.resolve("change.patch"), patch);
        Process process = new ProcessBuilder("git", "-c", "core.autocrlf=false",
                "-C", applyDir.toString(), "apply", "change.patch")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        assertEquals("first\nnew\nlast\n", Files.readString(applyDir.resolve("src/example.txt")));
    }

    @Test
    void deletedFilePatchContainsRemovedContent() throws Exception {
        String runId = bootstrapRunWithWorkspace(Map.of("obsolete.txt", "before\n"));
        RunEntity run = runRepository.findById(runId).orElseThrow();
        Path workspaceDir = workspaceManager.resolveByRef(run.getWorkspaceRef()).dir();
        Files.delete(workspaceDir.resolve("obsolete.txt"));

        mockMvc.perform(get("/api/v1/runs/" + runId + "/diff")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].status").value("DELETED"))
                .andExpect(jsonPath("$.patch").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("+++ /dev/null"),
                        org.hamcrest.Matchers.containsString("-before\n"))));
    }

    @Test
    void cacheArtifactsAreExcludedFromDiff() throws Exception {
        String runId = bootstrapRunWithWorkspace(Map.of("src/app.py", "print('ok')\n"));
        RunEntity run = runRepository.findById(runId).orElseThrow();
        Path workspaceDir = workspaceManager.resolveByRef(run.getWorkspaceRef()).dir();

        // 模拟跑 pytest / 构建后生成的缓存产物（真实负载中污染补丁的来源）
        Files.createDirectories(workspaceDir.resolve(".pytest_cache/v/cache"));
        Files.writeString(workspaceDir.resolve(".pytest_cache/v/cache/lastfailed"), "{}");
        Files.createDirectories(workspaceDir.resolve("src/__pycache__"));
        Files.write(workspaceDir.resolve("src/__pycache__/app.cpython-311.pyc"),
                new byte[]{1, 2, 3});
        Files.createDirectories(workspaceDir.resolve("target/classes"));
        Files.writeString(workspaceDir.resolve("target/classes/x.txt"), "build output");

        mockMvc.perform(get("/api/v1/runs/" + runId + "/diff")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files.length()").value(0))
                .andExpect(jsonPath("$.patch").value(""));
    }

    @Test
    void crlfOnlyChangeProducesEmptyDiff() throws Exception {
        String runId = bootstrapRunWithWorkspace(Map.of("src/app.py", "line1\nline2\nline3\n"));
        RunEntity run = runRepository.findById(runId).orElseThrow();
        Path workspaceDir = workspaceManager.resolveByRef(run.getWorkspaceRef()).dir();

        // 仅行尾 LF→CRLF（Windows 编辑器常见），不算作者改动
        Files.writeString(workspaceDir.resolve("src/app.py"), "line1\r\nline2\r\nline3\r\n");

        mockMvc.perform(get("/api/v1/runs/" + runId + "/diff")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files.length()").value(0))
                .andExpect(jsonPath("$.patch").value(""));
    }

    @Test
    void middleLineChangeProducesMinimalHunkNotWholeFileRewrite() throws Exception {
        java.util.Map<String, String> baseline = new java.util.TreeMap<>();
        for (int i = 1; i <= 20; i++) {
            baseline.put("f.txt", (baseline.get("f.txt") == null ? "" : baseline.get("f.txt"))
                    + "row" + i + "\n");
        }
        String runId = bootstrapRunWithWorkspace(baseline);
        RunEntity run = runRepository.findById(runId).orElseThrow();
        Path workspaceDir = workspaceManager.resolveByRef(run.getWorkspaceRef()).dir();
        StringBuilder updated = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            updated.append(i == 10 ? "rowFIXED\n" : "row" + i + "\n");
        }
        Files.writeString(workspaceDir.resolve("f.txt"), updated.toString());

        String response = mockMvc.perform(get("/api/v1/runs/" + runId + "/diff")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].status").value("MODIFIED"))
                .andReturn().getResponse().getContentAsString();
        String patch = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response).get("patch").asText();

        // 真实变更行在，远离变更点的行不在（旧实现会把 20 行全部 -/+ 重写）
        org.junit.jupiter.api.Assertions.assertTrue(patch.contains("-row10\n"));
        org.junit.jupiter.api.Assertions.assertTrue(patch.contains("+rowFIXED\n"));
        org.junit.jupiter.api.Assertions.assertFalse(patch.contains("-row1\n"), patch);
        org.junit.jupiter.api.Assertions.assertFalse(patch.contains("-row20\n"), patch);
        org.junit.jupiter.api.Assertions.assertEquals(1,
                patch.split("@@ -", -1).length - 1, "应只有一个最小 hunk");
    }

    @Test
    void noWorkspaceReturnsEmptyDiff() throws Exception {
        String rootPath = "D:/code/MyAgent/DSH/backend/target/test-run";
        Files.createDirectories(Path.of(rootPath));
        String projectId = createProject("Empty-Diff-" + UUID.randomUUID(), rootPath);
        trustProject(projectId, 1);
        String profileId = createProfile("empty-" + UUID.randomUUID(), "fake-answer");
        String threadId = createThread(projectId, profileId);
        JsonNode accepted = submitTurn(threadId, "hello");
        String runId = accepted.get("rootRunId").asText();

        mockMvc.perform(get("/api/v1/runs/" + runId + "/diff")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files").isArray())
                .andExpect(jsonPath("$.files.length()").value(0))
                .andExpect(jsonPath("$.patch").value(""))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void diffArtifactIsDownloadable() throws Exception {
        String runId = bootstrapRunWithWorkspace();
        RunEntity run = runRepository.findById(runId).orElseThrow();
        Path workspaceDir = workspaceManager.resolveByRef(run.getWorkspaceRef()).dir();
        Files.writeString(workspaceDir.resolve("a.txt"), "content\n");

        String diffResponse = mockMvc.perform(get("/api/v1/runs/" + runId + "/diff")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andReturn().getResponse().getContentAsString();
        String artifactId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(diffResponse).get("artifactId").asText();
        assertNotNull(artifactId);

        mockMvc.perform(get("/api/v1/artifacts/" + artifactId + "/content")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk());
    }

    @Test
    void downloadedPatchReplaysFileLifecycleAndRenameExactly() throws Exception {
        Map<String, String> before = Map.of(
                "old name.txt", "renamed\r\n内容",
                "empty-deleted.txt", "",
                "cleared.txt", "clear me\n",
                "filled.txt", "",
                "eol.txt", "unchanged");
        Map<String, String> after = Map.of(
                "中文/new name.txt", "renamed\r\n内容",
                "empty-added.txt", "",
                "cleared.txt", "",
                "filled.txt", "filled\r\n",
                "eol.txt", "unchanged\n");
        assertDownloadedPatchRoundTrip(before, after);
    }

    @Test
    void downloadedPatchPreservesRealCrlfEditsAndMissingFinalNewline() throws Exception {
        assertDownloadedPatchRoundTrip(
                Map.of("crlf.txt", "first\r\nold\r\nlast\r\n", "tail.txt", "old"),
                Map.of("crlf.txt", "first\r\nnew\r\nlast\r\n", "tail.txt", "new"));
    }

    @Test
    void patchLimitUsesUtf8BytesAndNeverCutsInsideAFileSegment() throws Exception {
        String runId = bootstrapRunWithWorkspace();
        RunEntity run = runRepository.findById(runId).orElseThrow();
        Path workspace = workspaceManager.resolveByRef(run.getWorkspaceRef()).dir();
        Files.writeString(workspace.resolve("large.txt"), "中".repeat(100_000) + "\n");
        mockMvc.perform(get("/api/v1/runs/" + runId + "/diff")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].status").value("ADDED"))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.patch").value(""));
    }

    private void assertDownloadedPatchRoundTrip(Map<String, String> before, Map<String, String> after)
            throws Exception {
        String runId = bootstrapRunWithWorkspace(before);
        RunEntity run = runRepository.findById(runId).orElseThrow();
        Path workspace = workspaceManager.resolveByRef(run.getWorkspaceRef()).dir();
        for (String name : before.keySet()) {
            if (!after.containsKey(name)) Files.delete(workspace.resolve(name));
        }
        for (var entry : after.entrySet()) {
            Path file = workspace.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
        var response = mockMvc.perform(get("/api/v1/runs/" + runId + "/diff")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode diff = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertFalse(diff.get("truncated").asBoolean());
        byte[] downloaded = mockMvc.perform(get("/api/v1/artifacts/" + diff.get("artifactId").asText()
                        + "/content").header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(diff.get("patch").asText().getBytes(StandardCharsets.UTF_8), downloaded);
        var statuses = new java.util.TreeMap<String, String>();
        diff.get("files").forEach(f -> statuses.put(f.get("path").asText(), f.get("status").asText()));
        before.keySet().stream().filter(name -> !after.containsKey(name))
                .forEach(name -> assertEquals("DELETED", statuses.get(name)));
        after.keySet().stream().filter(name -> !before.containsKey(name))
                .forEach(name -> assertEquals("ADDED", statuses.get(name)));

        Path applyDir = Files.createTempDirectory("dsh-export-roundtrip-");
        git(applyDir, "init", "-q");
        for (var entry : before.entrySet()) {
            Path file = applyDir.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
        Files.write(applyDir.resolve("change.patch"), downloaded);
        git(applyDir, "apply", "--check", "change.patch");
        git(applyDir, "apply", "change.patch");
        for (String name : before.keySet()) {
            if (!after.containsKey(name)) assertFalse(Files.exists(applyDir.resolve(name)), name);
        }
        for (var entry : after.entrySet()) {
            assertTrue(Files.exists(applyDir.resolve(entry.getKey())), entry.getKey());
            assertArrayEquals(Files.readAllBytes(workspace.resolve(entry.getKey())),
                    Files.readAllBytes(applyDir.resolve(entry.getKey())), entry.getKey());
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-c", "core.autocrlf=false", "-C", dir.toString()));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), output);
    }
}
