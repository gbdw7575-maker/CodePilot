package com.dsh.orchestration;

import com.dsh.application.DshProperties;
import com.dsh.common.IdGen;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.orchestration.plan.PlanService;
import com.dsh.orchestration.task.TaskSchedulingService;
import com.dsh.orchestration.task.TaskStates;
import com.dsh.storage.IntegrationRecordEntity;
import com.dsh.storage.IntegrationRecordRepository;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.TaskAttemptEntity;
import com.dsh.storage.TaskAttemptRepository;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.TaskRepository;
import com.dsh.storage.TaskResultEntity;
import com.dsh.storage.TaskResultRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-08 AT-15 五窗口 + §10.2 限定重试测试（规范 §10.3/§10.2）。
 *
 * <p>窗口2（运行中断租约回收）：LeaseReaper 扫描过期 RUNNING attempt →
 * CANCELLING 直接收敛 CANCELLED；否则 requeue 回 QUEUED（清租约，等补偿扫描重投）。</p>
 *
 * <p>窗口3（产物已写 fencing 拒绝）：旧 fencingToken 的 SUCCEEDED attempt 已被后续 claim
 * 超越（maxFencingToken 更大），其产物不可信 → integrate 拒绝写入 RECOVERED_BLOCKED。</p>
 *
 * <p>窗口4（完成事件重复幂等）：complete 对已终态 attempt 直接忽略，不重复落 result。</p>
 *
 * <p>§10.2 限定重试：ENVIRONMENT_ERROR 不重放（落 FAILED）；attemptNo 超 maxRetryAttempts
 * 落 RETRY_LIMIT_EXCEEDED；可重试原因递增 attemptNo + 退避（leaseUntil 存 nextEligibleAt）。</p>
 *
 * <p>窗口5（合并中断已有）在 MA-07 Ma07IntegrationRecoveryTest 已覆盖，本切片不重复。</p>
 *
 * <p>全部 service 层构造状态，不依赖 worker 时间窗口，不产生真实模型费用。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "dsh.policy.require-for-side-effects=NONE")
class Ma08LeaseRecoveryTest {

    @Autowired private PlanService planService;
    @Autowired private TaskSchedulingService schedulingService;
    @Autowired private IntegrationService integrationService;
    @Autowired private IntegrationRecordRepository integrationRecordRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private TaskResultRepository resultRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ModelProfileRepository profileRepository;
    @Autowired private ThreadRepository threadRepository;
    @Autowired private TurnRepository turnRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private DshProperties properties;

    @TempDir Path projectRoot;

    // -------------------------- AT-15 窗口2：租约过期回收（非取消 → requeue）

    @Test
    void leaseExpiredRequeuesRunningAttempt() {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(task("A", "独立任务A", List.of())));
        TaskEntity taskA = taskRepository.findByPlanIdAndTaskId(planId, "A").orElseThrow();
        Instant now = Instant.now();
        // 手动构造 RUNNING attempt + 过期租约（模拟 worker 崩溃，leaseUntil 已过）
        TaskAttemptEntity attempt = new TaskAttemptEntity(
                IdGen.next("att_"), planId, "A", taskA.getTaskVersion(), null, 1,
                "worker-pool", now.minusSeconds(60), 1L, null,
                "RUNNING", now.minusSeconds(120), now.minusSeconds(120));
        attemptRepository.save(attempt);
        taskA.setState(TaskStates.RUNNING);
        taskA.setUpdatedAt(now);
        taskRepository.save(taskA);

        boolean reaped = schedulingService.reapExpiredLease(attempt.getAttemptId(), now);

        assertTrue(reaped, "过期租约应被回收");
        TaskAttemptEntity after = attemptRepository.findById(attempt.getAttemptId()).orElseThrow();
        assertEquals("QUEUED", after.getState(), "非取消的过期 attempt 应 requeue 回 QUEUED");
        assertNull(after.getLeaseUntil(), "requeue 应清空租约");
        assertNull(after.getLeaseOwner(), "requeue 应清空 leaseOwner");
        // fencingToken 保留单调递增（不清除），迟到产物由 fencing 写校验拒绝
        assertNotNull(after.getFencingToken(), "fencingToken 保留不清除");
        assertEquals(TaskStates.READY, state(planId, "A"),
                "task 应从 RUNNING 回到 READY（等补偿扫描重投）");
    }

    // -------------------------- AT-15 窗口2：租约过期回收（CANCELLING → 收敛 CANCELLED）

    @Test
    void leaseExpiredCancelledWhenCancelling() {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(task("A", "独立任务A", List.of())));
        TaskEntity taskA = taskRepository.findByPlanIdAndTaskId(planId, "A").orElseThrow();
        Instant now = Instant.now();
        TaskAttemptEntity attempt = new TaskAttemptEntity(
                IdGen.next("att_"), planId, "A", taskA.getTaskVersion(), "run_child", 1,
                "worker-pool", now.minusSeconds(60), 1L, null,
                "RUNNING", now.minusSeconds(120), now.minusSeconds(120));
        attemptRepository.save(attempt);
        taskA.setState(TaskStates.CANCELLING);
        taskA.setUpdatedAt(now);
        taskRepository.save(taskA);

        boolean reaped = schedulingService.reapExpiredLease(attempt.getAttemptId(), now);

        assertTrue(reaped, "过期租约应被回收");
        TaskAttemptEntity after = attemptRepository.findById(attempt.getAttemptId()).orElseThrow();
        assertEquals("CANCELLED", after.getState(),
                "CANCELLING 的过期 attempt 应收敛 CANCELLED（不再等子 Run 安全点）");
        assertNull(after.getLeaseUntil(), "收敛应清空租约");
        assertEquals(TaskStates.CANCELLED, state(planId, "A"),
                "task 应收敛 CANCELLED");
    }

    // -------------------------- AT-15 窗口3：旧 fencingToken 产物拒绝写入

    @Test
    void staleFencingTokenRejectedByIntegrate() {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(task("A", "独立任务A", List.of())));
        TaskEntity taskA = taskRepository.findByPlanIdAndTaskId(planId, "A").orElseThrow();
        Instant now = Instant.now();

        // att1: SUCCEEDED + fencingToken=1（已被后续 claim 超越）
        TaskAttemptEntity att1 = new TaskAttemptEntity(
                IdGen.next("att_"), planId, "A", taskA.getTaskVersion(), "run_child_1", 1,
                null, null, 1L, null,
                "SUCCEEDED", now.minusSeconds(120), now.minusSeconds(60));
        attemptRepository.save(att1);
        // att2: RUNNING + fencingToken=2（让 maxFencingToken=2，超越 att1）
        TaskAttemptEntity att2 = new TaskAttemptEntity(
                IdGen.next("att_"), planId, "A", taskA.getTaskVersion(), null, 2,
                "worker-pool", now.plusSeconds(600), 2L, null,
                "RUNNING", now.minusSeconds(30), now.minusSeconds(30));
        attemptRepository.save(att2);

        // task A SUCCEEDED + result 指向 att1（fencingToken=1 < maxFencing=2）
        taskA.setState(TaskStates.SUCCEEDED);
        taskA.setUpdatedAt(now);
        taskRepository.save(taskA);
        resultRepository.save(new TaskResultEntity(att1.getAttemptId(), planId, "A",
                taskA.getTaskVersion(), "run_child_1", "SUCCEEDED", null, null,
                "[]", null, "digest-a", null, null,
                1, 0, null, 0, now.minusSeconds(60)));

        // maxFencingToken=2 > att1.fencingToken=1 → att1 是 stale → integrate 拒绝
        IntegrationService.IntegrationOutcome outcome = integrationService.integrate(chain.runId());

        assertEquals(IntegrationService.STATE_RECOVERED_BLOCKED, outcome.state(),
                "旧 fencingToken 的迟到产物应被拒绝写入，RECOVERED_BLOCKED，msg=" + outcome.message());

        // 应有 RECOVERED_BLOCKED record 落库
        IntegrationRecordEntity record = integrationRecordRepository
                .findFirstByRootRunIdOrderByCreatedAtDesc(chain.runId()).orElseThrow();
        assertEquals(IntegrationService.STATE_RECOVERED_BLOCKED, record.getState());
    }

    // -------------------------- AT-15 窗口4：complete 对已终态 attempt 幂等

    @Test
    void completeIdempotentOnTerminalAttempt() {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(task("A", "独立任务A", List.of())));
        TaskEntity taskA = taskRepository.findByPlanIdAndTaskId(planId, "A").orElseThrow();
        Instant now = Instant.now();
        TaskAttemptEntity attempt = new TaskAttemptEntity(
                IdGen.next("att_"), planId, "A", taskA.getTaskVersion(), null, 1,
                "worker-pool", now.plusSeconds(600), 1L, null,
                "RUNNING", now, now);
        attemptRepository.save(attempt);
        taskA.setState(TaskStates.RUNNING);
        taskA.setUpdatedAt(now);
        taskRepository.save(taskA);

        SubagentResult cancelledResult = new SubagentResult("run_child", "CODER",
                "CANCELLED", "CANCELLED", "子 Run 取消", List.of(), null, "", "",
                0, 0, null, 0);

        // 第一次 complete：attempt RUNNING → CANCELLED + result 行落库
        schedulingService.complete(attempt.getAttemptId(), cancelledResult);
        assertEquals(TaskStates.CANCELLED, state(planId, "A"));
        assertEquals("CANCELLED", attemptRepository.findById(attempt.getAttemptId())
                .orElseThrow().getState());
        assertTrue(resultRepository.findById(attempt.getAttemptId()).isPresent(),
                "第一次 complete 应落 result 行");

        // 第二次 complete：attempt 已 CANCELLED → 幂等忽略，不重复落 result
        schedulingService.complete(attempt.getAttemptId(), cancelledResult);

        assertEquals("CANCELLED", attemptRepository.findById(attempt.getAttemptId())
                .orElseThrow().getState(), "幂等后 attempt 仍 CANCELLED");
        // result 行不重复（id=attemptId，幂等 return 不覆盖）
        TaskResultEntity result = resultRepository.findById(attempt.getAttemptId()).orElseThrow();
        assertEquals("CANCELLED", result.getState(), "result 状态不变（幂等）");
    }

    // -------------------------- §10.2 ENVIRONMENT_ERROR 不重放

    @Test
    void requeueEnvErrorFailsWithoutReplay() {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(task("A", "独立任务A", List.of())));
        TaskEntity taskA = taskRepository.findByPlanIdAndTaskId(planId, "A").orElseThrow();
        Instant now = Instant.now();
        TaskAttemptEntity attempt = new TaskAttemptEntity(
                IdGen.next("att_"), planId, "A", taskA.getTaskVersion(), null, 1,
                "worker-pool", now.plusSeconds(600), 1L, null,
                "RUNNING", now, now);
        attemptRepository.save(attempt);
        taskA.setState(TaskStates.RUNNING);
        taskA.setUpdatedAt(now);
        taskRepository.save(taskA);

        schedulingService.requeue(attempt.getAttemptId(), "ENVIRONMENT_ERROR");

        assertEquals(TaskStates.FAILED, attemptRepository.findById(attempt.getAttemptId())
                        .orElseThrow().getState(),
                "ENVIRONMENT_ERROR 不重放，attempt 落 FAILED");
        assertEquals(TaskStates.FAILED, state(planId, "A"),
                "task 落 FAILED（ENVIRONMENT_ERROR_NOT_REPLAYED）");
    }

    // -------------------------- §10.2 RETRY_LIMIT_EXCEEDED

    @Test
    void requeueFailsWhenAttemptNoExceedsMax() {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(task("A", "独立任务A", List.of())));
        TaskEntity taskA = taskRepository.findByPlanIdAndTaskId(planId, "A").orElseThrow();
        Instant now = Instant.now();
        int maxRetry = properties.orchestration().maxRetryAttempts();
        // attemptNo 已达 maxRetry，再 requeue 应触发 RETRY_LIMIT_EXCEEDED
        TaskAttemptEntity attempt = new TaskAttemptEntity(
                IdGen.next("att_"), planId, "A", taskA.getTaskVersion(), null, maxRetry,
                "worker-pool", now.plusSeconds(600), 1L, null,
                "RUNNING", now, now);
        attemptRepository.save(attempt);
        taskA.setState(TaskStates.RUNNING);
        taskA.setUpdatedAt(now);
        taskRepository.save(taskA);

        schedulingService.requeue(attempt.getAttemptId(), "CONCURRENCY_LIMIT");

        assertEquals(TaskStates.FAILED, attemptRepository.findById(attempt.getAttemptId())
                        .orElseThrow().getState(),
                "attemptNo=" + maxRetry + " 超 maxRetry=" + maxRetry + "，attempt 落 FAILED");
        assertEquals(TaskStates.FAILED, state(planId, "A"),
                "task 落 FAILED（RETRY_LIMIT_EXCEEDED）");
    }

    // -------------------------- §10.2 退避：requeue 递增 attemptNo + 设置 nextEligibleAt

    @Test
    void requeueWithBackoffDelaysNextEligibleAt() {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(task("A", "独立任务A", List.of())));
        TaskEntity taskA = taskRepository.findByPlanIdAndTaskId(planId, "A").orElseThrow();
        Instant before = Instant.now();
        int maxRetry = properties.orchestration().maxRetryAttempts();
        // 假设 maxRetry >= 2（默认 3），attemptNo=1 requeue 后应递增到 2（未超限）
        assertTrue(maxRetry >= 2, "maxRetryAttempts 应 >=2 才能测退避路径");
        TaskAttemptEntity attempt = new TaskAttemptEntity(
                IdGen.next("att_"), planId, "A", taskA.getTaskVersion(), null, 1,
                "worker-pool", before.plusSeconds(600), 1L, null,
                "RUNNING", before, before);
        attemptRepository.save(attempt);
        taskA.setState(TaskStates.RUNNING);
        taskA.setUpdatedAt(before);
        taskRepository.save(taskA);

        schedulingService.requeue(attempt.getAttemptId(), "CONCURRENCY_LIMIT");

        TaskAttemptEntity after = attemptRepository.findById(attempt.getAttemptId()).orElseThrow();
        assertEquals("QUEUED", after.getState(), "可重试原因应 requeue 回 QUEUED");
        assertEquals(2, after.getAttemptNo(), "attemptNo 应递增到 2");
        assertNotNull(after.getLeaseUntil(), "退避时间应存入 leaseUntil（nextEligibleAt）");
        assertTrue(after.getLeaseUntil().isAfter(before),
                "退避时间应在 requeue 之后（base*2^(attemptNo-1)）");
        assertNull(after.getLeaseOwner(), "requeue 应清空 leaseOwner");
        assertEquals(TaskStates.READY, state(planId, "A"),
                "task 应从 RUNNING 回到 READY");
        // 退避期间 leaseUntil 在未来，pumpRoot/scheduledScan 应跳过（在 TaskWorkerExecutor 已实现）
        assertNotNull(after.getFencingToken(),
                "fencingToken 保留不清除（供后续 fencing 写校验，单调递增基础）");
    }

    // ------------------------------------------------------- helpers

    private TaskDraft task(String id, String goal, List<String> deps) {
        return new TaskDraft(id, goal, deps, List.of(), "deliver-" + id,
                List.of("self-check-" + id), null, "DELEGATED", true);
    }

    private String savePlan(Chain chain, List<TaskDraft> tasks) {
        return planService.saveDraft(chain.runId(),
                new PlanDraft(null, null, "MA-08 租约回收计划", tasks)).planId();
    }

    private String state(String planId, String taskId) {
        return taskRepository.findByPlanIdAndTaskId(planId, taskId)
                .map(TaskEntity::getState).orElse("<missing>");
    }

    private Chain buildChain() {
        Instant now = Instant.now();
        String projectId = "proj-ma08lr-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA08LR", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-ma08lr-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, "fake-fake-ma05",
                "fake", "fake-ma05", "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));
        String threadId = IdGen.next("thr_ma08lr_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA08LR 会话",
                profileId, now, now));
        String runId = IdGen.next("run_ma08lr_");
        String turnId = IdGen.next("turn_ma08lr_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest-ma08lr", 30, 120, 320_000,
                now.plusSeconds(600), now, now));
        return new Chain(projectId, threadId, turnId, runId);
    }

    private record Chain(String projectId, String threadId, String turnId, String runId) {
    }
}
