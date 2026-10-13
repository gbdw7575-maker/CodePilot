package com.dsh.api;

import com.dsh.common.Digest;
import com.dsh.agent.LoopSteps;
import com.dsh.storage.ApprovalRepository;
import com.dsh.storage.RunRepository;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-13：验证证据摘要语义 + 完成依据（completionBasis）区分。
 *
 * <ul>
 *   <li>验证命令若改变工作区，workspaceDigest 存验证后摘要、preVerificationDigest 存验证前摘要，
 *       完成瞬间 stale=false；验证后外部改动 → stale=true。</li>
 *   <li>无工作区改动时，completionBasis 区分 NO_CHANGE（未用过写工具）与
 *       NO_NET_CHANGE_AFTER_WRITE（用过写工具但无净变化）；有改动且验证通过 → VERIFICATION_PASSED。</li>
 * </ul>
 */
class CompletionBasisDsh013Test extends ApiTestBase {

    @TempDir
    Path root;

    @Autowired
    private RunRepository runRepository;
    @Autowired
    private WorkspaceManager workspaceManager;
    @Autowired
    private ApprovalRepository approvalRepository;
    @Autowired
    private LoopSteps loopSteps;

    /** 等待 run 进入终态（COMPLETED 或 FAILED），返回 runId。 */
    private String waitForTerminal(String threadId, String runId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            JsonNode page = getEvents(threadId, "?limit=200");
            List<String> types = new ArrayList<>();
            page.get("items").forEach(e -> types.add(e.get("type").asText()));
            if (types.contains("run.completed") || types.contains("run.failed")) {
                return runId;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("run 未在超时内进入终态: " + runId);
    }

    /** 等待并批准 run 的首个 PENDING 审批（工作区写工具需要审批）。 */
    private void approvePending(String runId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        com.dsh.storage.ApprovalEntity approval = null;
        do {
            var list = approvalRepository.findByRunIdAndStatus(runId, "PENDING");
            if (!list.isEmpty()) {
                approval = list.get(0);
                break;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        if (approval == null) {
            throw new AssertionError("run 未产生 PENDING 审批: " + runId);
        }
        var body = mapper.writeValueAsString(java.util.Map.of(
                "decision", "APPROVE",
                "parametersDigest", approval.getParametersDigest(),
                "workspaceDigest", approval.getWorkspaceDigest()));
        assertEquals(200, postJson("/api/v1/approvals/" + approval.getId() + "/decision",
                body, uniqueKey("approve")).getResponse().getStatus());
    }

    @Test
    void noWorkspaceChangeAndNoWriteToolSetsNoChangeBasis() throws Exception {
        Files.writeString(root.resolve("README.md"), "fixture\n");
        String projectId = createProject("cb-nochange-" + UUID.randomUUID(),
                root.toAbsolutePath().toString().replace('\\', '/'));
        trustProject(projectId, 1);
        // 默认 fake 模型：纯回答，不调用任何工具
        String profileId = createProfile("fake-answer-" + UUID.randomUUID(), "fake-answer");
        String threadId = createThread(projectId, profileId);
        String runId = submitTurn(threadId, "你好").get("rootRunId").asText();

        waitForTerminal(threadId, runId);

        mockMvc.perform(get("/api/v1/runs/" + runId).header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.completionBasis").value("NO_CHANGE"));
    }

    @Test
    void writeAttemptedButNoNetChangeSetsNoNetChangeBasis() throws Exception {
        // 预置 noop.txt，内容与 fake-write noop 写入内容完全一致 → 写后无净变化
        Files.writeString(root.resolve("noop.txt"), "same\n");
        String projectId = createProject("cb-noop-" + UUID.randomUUID(),
                root.toAbsolutePath().toString().replace('\\', '/'));
        trustProject(projectId, 1);
        String profileId = createProfile("fake-write-" + UUID.randomUUID(), "fake-write");
        String threadId = createThread(projectId, profileId);

        String hash = Digest.sha256Hex(Files.readAllBytes(root.resolve("noop.txt")));
        String runId = submitTurn(threadId, "noop " + hash).get("rootRunId").asText();

        approvePending(runId);
        waitForTerminal(threadId, runId);

        mockMvc.perform(get("/api/v1/runs/" + runId).header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.completionBasis").value("NO_NET_CHANGE_AFTER_WRITE"));
    }

    @Test
    void workspaceChangeWithPassingVerificationSetsVerificationPassedBasis() throws Exception {
        Files.writeString(root.resolve("README.md"), "fixture\n");
        Files.createDirectories(root.resolve(".dsh"));
        Files.writeString(root.resolve(".dsh/verify.yaml"),
                "command: echo Tests run: 1\nexpectTests: true\n");
        String projectId = createProject("cb-verify-" + UUID.randomUUID(),
                root.toAbsolutePath().toString().replace('\\', '/'));
        trustProject(projectId, 1);
        String profileId = createProfile("fake-patch-" + UUID.randomUUID(), "fake-patch");
        String threadId = createThread(projectId, profileId);
        String runId = submitTurn(threadId, "请修复 fix").get("rootRunId").asText();

        approvePending(runId);
        waitForTerminal(threadId, runId);

        mockMvc.perform(get("/api/v1/runs/" + runId).header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.completionBasis").value("VERIFICATION_PASSED"))
                .andExpect(jsonPath("$.verificationStatus").value("PASSED"));
    }

    @Test
    void verificationCommandModifyingWorkspaceMarksModifiedAndNotStale() throws Exception {
        Files.writeString(root.resolve("README.md"), "fixture\n");
        Files.createDirectories(root.resolve(".dsh"));
        // 验证命令会在工作区创建 gen.txt → 验证前后摘要不同
        Files.writeString(root.resolve(".dsh/verify.yaml"),
                "command: echo Tests run: 1 & echo generated > gen.txt\nexpectTests: true\n");
        String projectId = createProject("ev-mod-" + UUID.randomUUID(),
                root.toAbsolutePath().toString().replace('\\', '/'));
        trustProject(projectId, 1);
        String profileId = createProfile("fake-patch-" + UUID.randomUUID(), "fake-patch");
        String threadId = createThread(projectId, profileId);
        String runId = submitTurn(threadId, "请修复 fix").get("rootRunId").asText();

        approvePending(runId);
        waitForTerminal(threadId, runId);

        // 验证列表：workspaceModified=true，完成瞬间 stale=false
        mockMvc.perform(get("/api/v1/runs/" + runId + "/verifications")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("PASSED"))
                .andExpect(jsonPath("$.items[0].workspaceModified").value(true))
                .andExpect(jsonPath("$.items[0].stale").value(false));

        // 取 preVerificationDigest 与 workspaceDigest 应不同
        JsonNode list = mapper.readTree(mockMvc.perform(get("/api/v1/runs/" + runId + "/verifications")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andReturn().getResponse().getContentAsString());
        String preDigest = list.get("items").get(0).get("preVerificationDigest").asText();
        String postDigest = list.get("items").get(0).get("workspaceDigest").asText();
        assertNotNull(preDigest);
        assertNotEquals(preDigest, postDigest);
    }

    @Test
    void externalWorkspaceChangeAfterVerificationMarksStale() throws Exception {
        Files.writeString(root.resolve("README.md"), "fixture\n");
        Files.createDirectories(root.resolve(".dsh"));
        Files.writeString(root.resolve(".dsh/verify.yaml"),
                "command: echo Tests run: 1\nexpectTests: true\n");
        String projectId = createProject("ev-stale-" + UUID.randomUUID(),
                root.toAbsolutePath().toString().replace('\\', '/'));
        trustProject(projectId, 1);
        String profileId = createProfile("fake-patch-" + UUID.randomUUID(), "fake-patch");
        String threadId = createThread(projectId, profileId);
        String runId = submitTurn(threadId, "请修复 fix").get("rootRunId").asText();

        approvePending(runId);
        waitForTerminal(threadId, runId);

        // 完成瞬间：命令不产文件，workspaceModified=false，stale=false
        mockMvc.perform(get("/api/v1/runs/" + runId + "/verifications")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].workspaceModified").value(false))
                .andExpect(jsonPath("$.items[0].stale").value(false));

        // 验证后外部改动工作区 → stale=true
        com.dsh.storage.RunEntity run = runRepository.findById(runId).orElseThrow();
        Path wsDir = workspaceManager.resolveByRef(run.getWorkspaceRef()).dir();
        Files.writeString(wsDir.resolve("external.txt"), "changed\n");

        mockMvc.perform(get("/api/v1/runs/" + runId + "/verifications")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].stale").value(true));

        // 模拟提交成功终态前发生外部改动：已通过的验证不能再作为新终态依据。
        run.setState(LoopSteps.ST_VERIFYING);
        run.setVerificationStatus("PASSED");
        runRepository.saveAndFlush(run);
        assertFalse(loopSteps.verificationPassed(runId));
        assertThrows(IllegalStateException.class, () -> loopSteps.completeAfterVerification(runId));
    }
}
