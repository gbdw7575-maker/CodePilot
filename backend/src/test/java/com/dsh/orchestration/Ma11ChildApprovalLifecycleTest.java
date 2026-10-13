package com.dsh.orchestration;

import com.dsh.agent.control.CancellationTokenRegistry;
import com.dsh.agent.model.FakeModelGateway;
import com.dsh.agent.tool.ToolDispatcher;
import com.dsh.api.dto.event.ToolOutcomeDto;
import com.dsh.application.ModelProfileService;
import com.dsh.common.IdGen;
import com.dsh.storage.MessageEntity;
import com.dsh.storage.MessageRepository;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 子审批真实 Fake Loop/数据库集成，暂停期间不得提前结算或释放资源。 */
@SpringBootTest(properties = {"dsh.policy.require-for-side-effects=WORKSPACE_WRITE,EXECUTE",
        "dsh.orchestration.child-approval-timeout-seconds=3", "dsh.policy.approval-timeout-minutes=5"})
class Ma11ChildApprovalLifecycleTest {

    @Autowired
    private SubagentService subagentService;
    @Autowired
    private ToolDispatcher dispatcher;
    @Autowired
    private com.dsh.agent.tool.RunToolMountStore mountStore;
    @Autowired
    private CancellationTokenRegistry tokenRegistry;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ModelProfileRepository profileRepository;
    @Autowired
    private ThreadRepository threadRepository;
    @Autowired
    private TurnRepository turnRepository;
    @Autowired
    private RunRepository runRepository;
    @Autowired
    private MessageRepository messageRepository;

    @TempDir
    Path projectRoot;

    private Chain buildChain(String model, int maxMr, int maxTc, int maxTok) {
        Instant now = Instant.now();
        String projectId = "proj-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "子Agent集成", projectRoot.toString(),
                true, now, now));

        String profileId = "prof-" + UUID.randomUUID();
        ModelProfileEntity profile = new ModelProfileEntity(profileId, "fake-" + model,
                FakeModelGateway.PROVIDER, model, "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now);
        profileRepository.save(profile);

        String threadId = IdGen.next("thr_parent_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "父会话", profileId, now, now));

        String runId = IdGen.next("run_parent_");
        String turnId = IdGen.next("turn_parent_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));

        RunEntity run = new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), ModelProfileService.configDigest(profile),
                maxMr, maxTc, maxTok, now.plusSeconds(600), now, now);
        runRepository.save(run);
        return new Chain(projectId, profileId, threadId, turnId, runId);
    }


    @Autowired private com.dsh.agent.control.ApprovalService approvals;
    @Autowired private com.dsh.storage.ApprovalRepository approvalRows;
    @Autowired private com.dsh.storage.ResourceLeaseRepository leases;
    @Autowired private com.dsh.application.TurnService turns;
    @Autowired private com.dsh.workspace.WorkspaceManager workspaces;
    @Autowired private ChildRunLifecycle lifecycle;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private java.util.concurrent.ExecutorService worker;
    private final java.util.concurrent.atomic.AtomicBoolean interruptedAfterRun = new java.util.concurrent.atomic.AtomicBoolean();

    @org.junit.jupiter.api.BeforeEach
    void startWorker() { worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(); }
    @org.junit.jupiter.api.AfterEach
    void stopWorker() throws Exception {
        worker.shutdownNow();
        org.junit.jupiter.api.Assertions.assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS));
    }

    private Future<SubagentResult> start(Chain c) {
        return worker.submit(() -> {
            var result = subagentService.run(new SubagentRequest(
                    c.runId(), "CODER", "write one file", java.util.List.of(), null));
            interruptedAfterRun.set(Thread.currentThread().isInterrupted());
            return result;
        });
    }
    private RunEntity paused(Chain c) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            for (var child : runRepository.listChildren(c.runId())) {
                if ("WAITING_APPROVAL".equals(child.getState())) return child;
                org.junit.jupiter.api.Assertions.assertFalse(java.util.Set.of("FAILED", "CANCELLED", "COMPLETED")
                        .contains(child.getState()), child.getState() + ":" + child.getStopReason());
            }
            Thread.sleep(10);
        }
        throw new AssertionError("child did not pause");
    }
    private com.dsh.storage.ApprovalEntity pending(RunEntity child) {
        return approvalRows.findByRunIdAndStatus(child.getId(), "PENDING").getFirst();
    }
    private void decide(com.dsh.storage.ApprovalEntity a, com.dsh.api.dto.ApprovalDecision.Decision d) {
        approvals.decide(a.getId(), d, a.getParametersDigest(), a.getWorkspaceDigest());
    }
    private void settled(Chain c, SubagentResult result) {
        var parent = runRepository.findById(c.runId()).orElseThrow();
        var child = runRepository.findById(result.runId()).orElseThrow();
        assertEquals(0, parent.getBudgetReservedTokens());
        assertEquals(child.getBudgetModelRequestsUsed(), parent.getBudgetModelRequestsUsed());
        assertEquals(child.getBudgetToolCallsUsed(), parent.getBudgetToolCallsUsed());
        assertEquals(child.getBudgetSettledTokens(), parent.getBudgetSettledTokens());
        assertEquals(0, leases.countByRootRunIdAndState(c.runId(), "HELD"));
        org.junit.jupiter.api.Assertions.assertFalse(lifecycle.isManaged(child.getId()));
        assertNull(workspaces.find(child.getId()));
    }
    @Test
    void approvalPreservesReservationsAndResumesExactlyOnce() throws Exception {
        Chain c = buildChain("fake-write", 40, 80, 200000);
        var future = start(c); var child = paused(c); var a = pending(child);
        org.junit.jupiter.api.Assertions.assertFalse(future.isDone());
        assertTrue(runRepository.findById(c.runId()).orElseThrow().getBudgetReservedTokens() > 0);
        assertEquals(1, leases.countByRootRunIdAndState(c.runId(), "HELD"));
        assertEquals("PENDING", approvalRows.findById(a.getId()).orElseThrow().getStatus());
        decide(a, com.dsh.api.dto.ApprovalDecision.Decision.APPROVE);
        // API 的重复通知不能调度第二条子 Loop。
        turns.resumeFromApproval(child.getTurnId());
        turns.resumeFromApproval(child.getTurnId());
        var result = future.get(15, TimeUnit.SECONDS);
        assertEquals("COMPLETED", result.state(), result.summary());
        assertEquals(2, result.modelRequestsUsed());
        assertEquals("CONSUMED", approvalRows.findById(a.getId()).orElseThrow().getStatus());
        assertTrue(java.nio.file.Files.exists(workspaces.resolveByRef(result.workspaceRef()).dir().resolve("written.txt")));
        settled(c, result);
    }
    @Test
    void explicitDenialCancelsWithoutWriting() throws Exception {
        Chain c = buildChain("fake-write", 40, 80, 200000);
        var future = start(c); var child = paused(c);
        decide(pending(child), com.dsh.api.dto.ApprovalDecision.Decision.DENY);
        var result = future.get(15, TimeUnit.SECONDS);
        assertEquals("CANCELLED", result.state());
        assertEquals("CHILD_APPROVAL_DENIED", runRepository.findById(child.getId()).orElseThrow().getCancelReason());
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(
                workspaces.resolveByRef(result.workspaceRef()).dir().resolve("written.txt")));
        settled(c, result);
    }
    @Test
    void pendingExecuteIsNotAutomaticallyApprovedAndTimesOut() throws Exception {
        Chain c = buildChain("fake-batch-approval", 40, 80, 200000);
        var future = start(c); var child = paused(c); var a = pending(child);
        assertEquals("run_command", a.getToolName());
        assertEquals("PENDING", a.getStatus());
        var result = future.get(15, TimeUnit.SECONDS);
        assertEquals("CANCELLED", result.state());
        assertEquals("CHILD_APPROVAL_TIMEOUT", runRepository.findById(child.getId()).orElseThrow().getCancelReason());
        assertEquals("EXPIRED", approvalRows.findById(a.getId()).orElseThrow().getStatus());
        settled(c, result);
    }
    @Test
    void parentCancellationExpiresPendingApprovalAndSettlesOnce() throws Exception {
        Chain c = buildChain("fake-write", 40, 80, 200000);
        tokenRegistry.register(c.runId());
        var future = start(c); var child = paused(c); var a = pending(child);
        tokenRegistry.requestCancel(c.runId());
        var result = future.get(15, TimeUnit.SECONDS);
        assertEquals("CANCELLED", result.state());
        assertEquals("PARENT_CANCELLED", result.stopReason());
        assertEquals("EXPIRED", approvalRows.findById(a.getId()).orElseThrow().getStatus());
        settled(c, result);
        tokenRegistry.release(c.runId());
    }
    @Test
    void parentDeadlineBoundsPause() throws Exception {
        Chain c = buildChain("fake-write", 40, 80, 200000);
        var future = start(c); var child = paused(c);
        var parent = runRepository.findById(c.runId()).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(parent, "budgetDeadlineAt", Instant.now().minusSeconds(1)); runRepository.saveAndFlush(parent);
        var result = future.get(15, TimeUnit.SECONDS);
        assertEquals("CANCELLED", result.state());
        assertEquals("CHILD_DEADLINE_EXCEEDED", runRepository.findById(child.getId()).orElseThrow().getCancelReason());
        settled(c, result);
    }
    @Test
    void staleWorkspaceApprovalCannotWrite() throws Exception {
        Chain c = buildChain("fake-write", 40, 80, 200000);
        var future = start(c); var child = paused(c); var a = pending(child);
        var lease = workspaces.prepare(child.getId(), projectRepository.findById(c.projectId()).orElseThrow());
        java.nio.file.Files.writeString(lease.dir().resolve("external.txt"), "changed");
        assertNotNull(workspaces.find(child.getId()), "已准备的工作区在暂停期间仍保留");
        org.junit.jupiter.api.Assertions.assertFalse(future.isDone());
        decide(a, com.dsh.api.dto.ApprovalDecision.Decision.APPROVE);
        var result = future.get(15, TimeUnit.SECONDS);
        assertEquals("INVALIDATED", approvalRows.findById(a.getId()).orElseThrow().getStatus());
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(
                workspaces.resolveByRef(result.workspaceRef()).dir().resolve("written.txt")));
        settled(c, result);
    }
    @Test
    void interruptionCancelsPausedChildWithoutLosingUsage() throws Exception {
        Chain c = buildChain("fake-write", 40, 80, 200000);
        var future = start(c); paused(c); worker.shutdownNow();
        var result = future.get(15, TimeUnit.SECONDS);
        assertEquals("CANCELLED", result.state(), result.summary());
        assertTrue(interruptedAfterRun.get(), "资源结算结束后恢复中断标志");
        settled(c, result);
    }
    @Test
    void orphanChildCannotBeResumedThroughTurnService() {
        Chain c = buildChain("fake-answer", 40, 80, 200000);
        var run = runRepository.findById(c.runId()).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(run, "parentRunId", "missing-parent");
        runRepository.saveAndFlush(run);
        org.junit.jupiter.api.Assertions.assertThrows(com.dsh.api.ApiException.class,
                () -> turns.resumeFromApproval(c.turnId()));
    }
    @Test
    void expiredApprovalCancelsAndCannotBeConsumed() throws Exception {
        Chain c = buildChain("fake-write", 40, 80, 200000);
        var future = start(c); var child = paused(c); var a = pending(child);
        // expiresAt 没有业务修改入口；直接在数据库构造已过期记录，避免反射绕过脏检查。
        jdbc.update("update approvals set expires_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), a.getId());
        assertTrue(approvalRows.findById(a.getId()).orElseThrow().getExpiresAt().isBefore(Instant.now()));
        var result = future.get(15, TimeUnit.SECONDS);
        assertEquals("CANCELLED", result.state());
        assertEquals("CHILD_APPROVAL_EXPIRED", runRepository.findById(child.getId()).orElseThrow().getCancelReason());
        assertEquals("EXPIRED", approvalRows.findById(a.getId()).orElseThrow().getStatus());
        settled(c, result);
    }
    private record Chain(String projectId, String profileId, String threadId, String turnId, String runId) {}
}
