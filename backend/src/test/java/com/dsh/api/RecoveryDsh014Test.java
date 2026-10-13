package com.dsh.api;

import com.dsh.application.RunRecoveryService;
import com.dsh.application.VerificationService;
import com.dsh.agent.control.ApprovalService;
import com.dsh.api.dto.ApprovalDecision;
import com.dsh.common.IdGen;
import com.dsh.common.Digest;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.VerificationEntity;
import com.dsh.storage.VerificationRepository;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-14 进程中断恢复故障注入：对进程所有态（RUNNING/VERIFYING 等）的孤儿 Run，
 * 恢复必须收敛终态、不重复执行已提交动作（验证命令不重复执行、事件恰好一次）、
 * 暂停态（WAITING_INPUT）保持可恢复。
 */
class RecoveryDsh014Test extends ApiTestBase {

    @TempDir
    Path root;

    @Autowired
    private RunRepository runRepository;
    @Autowired
    private VerificationRepository verificationRepository;
    @Autowired
    private RunRecoveryService recoveryService;
    @Autowired
    private VerificationService verificationService;
    @Autowired
    private WorkspaceManager workspaceManager;
    @Autowired
    private ApprovalService approvalService;

    /** 真实跑一个 fake-waiting Turn 至 WAITING_INPUT（保留工作区租约），返回 [threadId, runId]。 */
    private String[] bootstrapWaitingTurn() throws Exception {
        Files.writeString(root.resolve("README.md"), "fixture\n");
        String projectId = createProject("rec-" + UUID.randomUUID(),
                root.toAbsolutePath().toString().replace('\\', '/'));
        trustProject(projectId, 1);
        String profileId = createProfile("fake-waiting-" + UUID.randomUUID(), "fake-waiting");
        String threadId = createThread(projectId, profileId);
        String runId = submitTurn(threadId, "你好").get("rootRunId").asText();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String state = runRepository.findById(runId).orElseThrow().getState();
            if ("WAITING_INPUT".equals(state)) {
                return new String[]{threadId, runId};
            }
            Thread.sleep(20);
        }
        throw new AssertionError("run 未进入 WAITING_INPUT: " + runId);
    }

    private void forceState(String runId, String state, String verificationStatus) {
        RunEntity run = runRepository.findById(runId).orElseThrow();
        run.setState(state);
        run.setVerificationStatus(verificationStatus);
        runRepository.save(run);
    }

    private void saveEvidence(String runId, String status) throws Exception {
        if ("PASSED".equals(status)) {
            // 正例通过真正的验证入口生成绑定证据，计数文件证明恢复不重放命令。
            Files.createDirectories(root.resolve(".dsh"));
            Files.writeString(root.resolve(".dsh/verify.yaml"),
                    "command: printf x >> recovery-counter.txt; echo '1 passed in 0.01s'\nexpectTests: true\n");
            var run = runRepository.findById(runId).orElseThrow();
            Files.createDirectories(workspaceManager.resolveByRef(run.getWorkspaceRef()).dir());
            String id = verificationService.run(runId);
            assertEquals("PASSED", verificationRepository.findById(id).orElseThrow().getStatus());
        } else {
            saveLegacyEvidence(runId, status);
        }
    }

    private void saveLegacyEvidence(String runId, String status) throws Exception {
        String postDigest = "post-digest";
        if ("PASSED".equals(status)) {
            // 恢复成功必须有与现存工作区一致的已提交证据。
            var run = runRepository.findById(runId).orElseThrow();
            Path dir = workspaceManager.resolveByRef(run.getWorkspaceRef()).dir();
            Files.createDirectories(dir);
            postDigest = WorkspaceManager.digestOf(dir);
        }
        verificationRepository.save(new VerificationEntity(
                IdGen.next("ver_"), runId, "v1", postDigest, "pre-digest", "local-local",
                "echo Tests run: 1", status, "PASSED".equals(status) ? 0 : 1,
                1, "PASSED".equals(status) ? 1 : 0, 0, false, "[]",
                Instant.now(), Instant.now()));
    }

    private List<String> eventTypes(String threadId) throws Exception {
        JsonNode page = getEvents(threadId, "?limit=200");
        List<String> types = new ArrayList<>();
        page.get("items").forEach(e -> types.add(e.get("type").asText()));
        return types;
    }

    private long countOf(String threadId, String type) throws Exception {
        return eventTypes(threadId).stream().filter(t -> t.equals(type)).count();
    }

    @Test
    void runningOrphanFailsRecoveryRequiredWithoutDuplicateEvents() throws Exception {
        String[] ids = bootstrapWaitingTurn();
        forceState(ids[1], "RUNNING", "NOT_REQUIRED");

        recoveryService.recoverOne(ids[1]);

        RunEntity run = runRepository.findById(ids[1]).orElseThrow();
        assertEquals("FAILED", run.getState());
        assertEquals("RECOVERY_REQUIRED", run.getStopReason());
        assertEquals(1, countOf(ids[0], "run.failed"), "run.failed 事件应恰好一次");
        JsonNode failed = getEvents(ids[0], "?limit=200").path("items");
        for (JsonNode event : failed) {
            if ("run.failed".equals(event.path("type").asText())) {
                assertEquals(run.getTraceId(), event.path("traceId").asText());
                assertTrue(event.path("spanId").asText().startsWith("span_"));
            }
        }

        // 幂等：重复恢复不再产生事件
        recoveryService.recoverOne(ids[1]);
        assertEquals(1, countOf(ids[0], "run.failed"), "重复恢复不得重复发 run.failed");
    }

    @Test
    void verifyingOrphanWithPassedEvidenceCompletesWithoutRerunningCommand() throws Exception {
        String[] ids = bootstrapWaitingTurn();
        forceState(ids[1], "VERIFYING", "PASSED");
        saveEvidence(ids[1], "PASSED");

        recoveryService.recoverOne(ids[1]);

        RunEntity run = runRepository.findById(ids[1]).orElseThrow();
        assertEquals("COMPLETED", run.getState());
        assertEquals("VERIFICATION_PASSED", run.getCompletionBasis());
        assertEquals(1, countOf(ids[0], "run.completed"));
        assertEquals(1, verificationRepository
                        .findByRunIdOrderByStartedAtDesc(ids[1], Pageable.unpaged()).getTotalElements(),
                "验证命令不得重复执行（证据仍只有 1 条）");

        // VerificationService 幂等边界：对已提交证据的 Run 再调 run() 直接返回既有记录
        String id1 = verificationService.run(ids[1]);
        String id2 = verificationService.run(ids[1]);
        assertEquals(id1, id2);
        assertEquals(1, verificationRepository
                .findByRunIdOrderByStartedAtDesc(ids[1], Pageable.unpaged()).getTotalElements());
        Path dir = workspaceManager.resolveByRef(run.getWorkspaceRef()).dir();
        assertEquals("x", Files.readString(dir.resolve("recovery-counter.txt")),
                "恢复以及复用均不得重新执行已有有效绑定的验证命令");
    }

    @Test
    void verifyingOrphanWithLegacyUnboundPassFailsWithoutReplayingCommand() throws Exception {
        String[] ids = bootstrapWaitingTurn();
        forceState(ids[1], "VERIFYING", "PASSED");
        saveLegacyEvidence(ids[1], "PASSED");
        recoveryService.recoverOne(ids[1]);
        recoveryService.recoverOne(ids[1]);
        RunEntity run = runRepository.findById(ids[1]).orElseThrow();
        assertEquals("FAILED", run.getState());
        assertEquals("VERIFICATION_STALE", run.getStopReason());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/runs/" + ids[1]).header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.stopReason").value("VERIFICATION_STALE"));
        assertEquals(1, verificationRepository
                .findByRunIdOrderByStartedAtDesc(ids[1], Pageable.unpaged()).getTotalElements());
        assertEquals(0, countOf(ids[0], "run.completed"));
        assertEquals(1, countOf(ids[0], "run.failed"));
    }

    @Test
    void verifyingOrphanWithFailedEvidenceFailsByEvidence() throws Exception {
        String[] ids = bootstrapWaitingTurn();
        forceState(ids[1], "VERIFYING", "FAILED");
        saveEvidence(ids[1], "FAILED");

        recoveryService.recoverOne(ids[1]);

        RunEntity run = runRepository.findById(ids[1]).orElseThrow();
        assertEquals("FAILED", run.getState());
        assertEquals("VERIFICATION_FAILED", run.getStopReason());
        assertEquals(1, verificationRepository
                        .findByRunIdOrderByStartedAtDesc(ids[1], Pageable.unpaged()).getTotalElements(),
                "验证命令不得重复执行");
    }

    @Test
    void verifyingOrphanWithStalePassedEvidenceFailsWithoutRerunningCommand() throws Exception {
        String[] ids = bootstrapWaitingTurn();
        forceState(ids[1], "VERIFYING", "PASSED");
        saveEvidence(ids[1], "PASSED");
        RunEntity before = runRepository.findById(ids[1]).orElseThrow();
        Path dir = workspaceManager.resolveByRef(before.getWorkspaceRef()).dir();
        Files.writeString(dir.resolve("changed-after-verification.txt"), "changed\n");

        recoveryService.recoverOne(ids[1]);

        RunEntity after = runRepository.findById(ids[1]).orElseThrow();
        assertEquals("FAILED", after.getState());
        assertEquals("VERIFICATION_STALE", after.getStopReason());
        assertEquals(1, verificationRepository
                .findByRunIdOrderByStartedAtDesc(ids[1], Pageable.unpaged()).getTotalElements());
        assertEquals(0, countOf(ids[0], "run.completed"));
        assertEquals(1, countOf(ids[0], "run.failed"));
    }

    @Test
    void consumedApprovalWithoutToolResultFailsRecoveryWithoutReplayingTool() throws Exception {
        String[] ids = bootstrapWaitingTurn();
        forceState(ids[1], "WAITING_APPROVAL", "NOT_REQUIRED");
        String turnId = runRepository.findById(ids[1]).orElseThrow().getTurnId();
        var approval = approvalService.request(turnId, ids[1], "call-uncertain", "apply_patch",
                "待执行写工具", "{\"path\":\"src/Main.java\"}", "parameters", "workspace", "MEDIUM");
        approvalService.decide(approval.getId(), ApprovalDecision.Decision.APPROVE,
                "parameters", "workspace");
        assertTrue(approvalService.consume(approval.getId(), "parameters", "workspace"));

        recoveryService.recoverOne(ids[1]);

        RunEntity run = runRepository.findById(ids[1]).orElseThrow();
        assertEquals("FAILED", run.getState());
        assertEquals("RECOVERY_REQUIRED", run.getStopReason());
        assertEquals(1, countOf(ids[0], "run.failed"));
        assertEquals(0, countOf(ids[0], "tool.completed"));
    }

    @Test
    void approvedButUnconsumedApprovalResumesOnceAfterRecovery() throws Exception {
        String[] ids = bootstrapWaitingTurn();
        forceState(ids[1], "WAITING_APPROVAL", "NOT_REQUIRED");
        var run = runRepository.findById(ids[1]).orElseThrow();
        String arguments = "{\"text\":\"recovered\"}";
        var lease = workspaceManager.find(ids[1]);
        String workspaceDigest = lease == null ? "" : workspaceManager.currentDigest(lease);
        var approval = approvalService.request(run.getTurnId(), ids[1], "call-recovered", "echo",
                "恢复后只执行一次", arguments, Digest.sha256Hex(arguments), workspaceDigest, "MEDIUM");
        approvalService.decide(approval.getId(), ApprovalDecision.Decision.APPROVE,
                Digest.sha256Hex(arguments), workspaceDigest);

        recoveryService.recoverOne(ids[1]);

        assertEquals("CONSUMED", approvalService.listByTurn(run.getTurnId()).get(0).getStatus());
        assertEquals(1, countOf(ids[0], "tool.completed"));
        recoveryService.recoverOne(ids[1]);
        assertEquals(1, countOf(ids[0], "tool.completed"), "再次恢复不能重放已消费工具调用");
    }

    @Test
    void verifyingOrphanWithoutEvidenceFailsRecoveryRequiredAndDoesNotRunCommand() throws Exception {
        String[] ids = bootstrapWaitingTurn();
        forceState(ids[1], "VERIFYING", "PENDING");

        recoveryService.recoverOne(ids[1]);

        RunEntity run = runRepository.findById(ids[1]).orElseThrow();
        assertEquals("FAILED", run.getState());
        assertEquals("RECOVERY_REQUIRED", run.getStopReason());
        assertEquals(0, verificationRepository
                        .findByRunIdOrderByStartedAtDesc(ids[1], Pageable.unpaged()).getTotalElements(),
                "无证据时不得补跑验证命令");
        assertEquals(1, countOf(ids[0], "run.failed"));
    }

    @Test
    void waitingInputRunIsNotTouchedByRecovery() throws Exception {
        String[] ids = bootstrapWaitingTurn();

        recoveryService.recoverOne(ids[1]);

        RunEntity run = runRepository.findById(ids[1]).orElseThrow();
        assertEquals("WAITING_INPUT", run.getState(), "暂停态等待外部输入，恢复不得干预");
        assertEquals(0, countOf(ids[0], "run.failed"));
        assertEquals(0, countOf(ids[0], "run.completed"));
    }
}
