package com.dsh.agent.tool;

import com.dsh.application.DshProperties;
import com.dsh.common.Digest;
import com.dsh.sandbox.LocalProcessSandboxRunner;
import com.dsh.storage.ProjectEntity;
import com.dsh.workspace.SafePaths;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * write_file 基础写工具：新建/覆盖/哈希预检（TOOL-01 口径），全部真实文件 IO。
 */
class WriteFileToolTest {

    private static final String RUN_ID = "run_wf1";
    private static final String PROJECT_ID = "proj_wf1";

    @TempDir
    Path sourceRoot;
    @TempDir
    Path workRoot;

    ObjectMapper mapper = new ObjectMapper();
    DshProperties properties;
    WorkspaceManager workspaceManager;
    WorkspaceLease lease;
    WriteFileTool tool = new WriteFileTool();

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
        ProjectEntity project = new ProjectEntity(PROJECT_ID, "写工具测试", sourceRoot.toString(),
                true, Instant.now(), Instant.now());
        lease = workspaceManager.prepare(RUN_ID, project);
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(RUN_ID, "thr1", "turn1", "call1", PROJECT_ID, lease,
                Instant.now().plusSeconds(600), new LocalProcessSandboxRunner(properties),
                properties.sandbox(), mapper);
    }

    private ObjectNode args(String path, String content, String expectedHash) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("path", path);
        node.put("content", content);
        node.set("expectedFileHash", expectedHash == null ? NullNode.instance
                : JsonNodeFactory.instance.textNode(expectedHash));
        return node;
    }

    private String sha256Of(Path file) throws Exception {
        return Digest.sha256Hex(Files.readAllBytes(file));
    }

    @Test
    void createsNewFileAndReportsDigest() throws Exception {
        ToolOutcome outcome = tool.execute(args("docs/new.txt", "hello\n", null), ctx());
        assertEquals("SUCCEEDED", outcome.status());
        Path target = lease.dir().resolve("docs/new.txt");
        assertEquals("hello\n", Files.readString(target));
        assertTrue(outcome.structuredContent().get("created").asBoolean());
        assertFalse(outcome.structuredContent().get("workspaceDigest").asText().isBlank());
    }

    @Test
    void refusesOverwriteWithoutExpectedHash() throws Exception {
        Path target = lease.dir().resolve("a.txt");
        Files.writeString(target, "user content");
        ToolException e = assertThrows(ToolException.class,
                () -> tool.execute(args("a.txt", "overwritten", null), ctx()));
        assertEquals("HASH_CONFLICT", e.errorCode());
        assertEquals("user content", Files.readString(target), "冲突时不得有任何写入");
        assertTrue(e.getMessage().contains("sha256="), "提示应包含当前哈希供下一轮覆盖");
    }

    @Test
    void overwritesWithMatchingHashAndRejectsStaleHash() throws Exception {
        Path target = lease.dir().resolve("a.txt");
        Files.writeString(target, "v1");
        String hash = sha256Of(target);

        // 过期哈希 → 拒绝且内容不变
        ToolException stale = assertThrows(ToolException.class,
                () -> tool.execute(args("a.txt", "v2", "deadbeef"), ctx()));
        assertEquals("HASH_CONFLICT", stale.errorCode());
        assertEquals("v1", Files.readString(target));

        // 正确哈希 → 覆盖成功
        ToolOutcome ok = tool.execute(args("a.txt", "v2", hash), ctx());
        assertEquals("SUCCEEDED", ok.status());
        assertEquals("v2", Files.readString(target));
        assertFalse(ok.structuredContent().get("created").asBoolean());
    }

    @Test
    void rejectsExpectedHashForMissingFile() {
        ToolException e = assertThrows(ToolException.class,
                () -> tool.execute(args("missing.txt", "x", "deadbeef"), ctx()));
        assertEquals("HASH_CONFLICT", e.errorCode());
    }

    @Test
    void rejectsPathEscape() {
        assertThrows(SafePaths.PathEscapeException.class,
                () -> tool.execute(args("../outside.txt", "x", null), ctx()));
        assertThrows(SafePaths.PathEscapeException.class,
                () -> tool.execute(args("C:/abs.txt", "x", null), ctx()));
    }

    @Test
    void customDefinitionCoversSchemaBasics() {
        ToolDefinition def = tool.customDefinition();
        assertEquals("write_file", def.name());
        assertEquals("WORKSPACE_WRITE", def.sideEffect());
        assertTrue(def.inputSchema().get("required").toString().contains("path"));
        assertTrue(def.inputSchema().get("required").toString().contains("content"));
    }
}
