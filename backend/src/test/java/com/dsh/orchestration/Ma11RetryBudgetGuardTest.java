package com.dsh.orchestration;

import com.dsh.application.DshProperties;
import com.dsh.common.IdGen;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.orchestration.plan.PlanService;
import com.dsh.orchestration.task.TaskSchedulingService;
import com.dsh.orchestration.task.TaskStates;
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

/**
 * MA-11c-1 {@code BUDGET_LIMIT} 重试收口测试（默认 safetyFactor=1.5，Fake，不产生真实费用）。
 *
 * <p>背景（验收记录 §11.7 宽预算诊断）：子 Run 撞 {@code BUDGET_LIMIT} 后任务被
 * {@code REQUEUED_WITH_BACKOFF}，重开子 Run 并重新冻结输入快照；默认切片下 16/16 子 Run 全部
 * 撞上限、rework 24，子 Run token 的 71% 属于零产出重试。收口判据：父 Run 剩余可分配额度
 * （父上限 − 已结算 − 进行中预约 − 主任务保留）&lt; 上次切片 × 安全系数时直接落 FAILED。</p>
 *
 * <p>全部 service 层构造状态，不依赖 worker 时间窗口与真实模型。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "dsh.policy.require-for-side-effects=NONE")
class Ma11RetryBudgetGuardTest {

    @Autowired private PlanService planService;
    @Autowired private TaskSchedulingService schedulingService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ModelProfileRepository profileRepository;
    @Autowired private ThreadRepository threadRepository;
    @Autowired private TurnRepository turnRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private DshProperties properties;

    @TempDir Path projectRoot;

    @Test
    void defaultSafetyFactorIsOnePointFive() {
        assertEquals(1.5, properties.orchestration().retryBudgetSafetyFactor(), 0.0001,
                "未显式配置时收口系数应回退默认 1.5");
    }

    /** 父额度充足（可分配 ≥ 切片×1.5）：保持 MA-08 行为，重排队等补偿扫描重投。 */
    @Test
    void budgetAllowsRetryStillRequeues() {
        Chain chain = buildChain(40, 200, 400_000);
        String planId = savePlan(chain, List.of(task("A", "独立任务A", List.of())));
        TaskEntity taskA = taskRepository.findByPlanIdAndTaskId(planId, "A").orElseThrow();
        // 子 Run 切片 = 8 mr / 30 tc / 60000 tok（测试默认 subMax*）
        String childRunId = saveChildRun(chain, 8, 30, 60_000);
        // 父已结算 4 mr / 10 tc / 20000 tok，主保留 4 mr / 8 tc / 40000 tok
        setParentUsage(chain.runId(), 4, 10, 20_000, 0);
        TaskAttemptEntity attempt = runningAttempt(planId, "A", taskA, childRunId);

        schedulingService.requeue(attempt.getAttemptId(), "BUDGET_LIMIT");

        TaskAttemptEntity after = attemptRepository.findById(attempt.getAttemptId()).orElseThrow();
        assertEquals("QUEUED", after.getState(),
                "父额度充足时 BUDGET_LIMIT 应继续重排队（可分配 mr=32≥12、tc=182≥45、tok=340000≥90000）");
        assertEquals(TaskStates.READY, taskState(planId, "A"));
    }

    /** 父额度不足（mr 可分配 6 &lt; 8×1.5=12）：不再重排队，落 FAILED。 */
    @Test
    void budgetExhaustedStopsRetrying() {
        Chain chain = buildChain(40, 200, 400_000);
        String planId = savePlan(chain, List.of(task("B", "独立任务B", List.of())));
        TaskEntity taskB = taskRepository.findByPlanIdAndTaskId(planId, "B").orElseThrow();
        String childRunId = saveChildRun(chain, 8, 30, 60_000);
        // 父已用 30 mr → 可分配 = 40-30-4 = 6 < 12
        setParentUsage(chain.runId(), 30, 10, 20_000, 0);
        TaskAttemptEntity attempt = runningAttempt(planId, "B", taskB, childRunId);

        schedulingService.requeue(attempt.getAttemptId(), "BUDGET_LIMIT");

        TaskAttemptEntity after = attemptRepository.findById(attempt.getAttemptId()).orElseThrow();
        assertEquals("FAILED", after.getState(), "父额度不足以支撑同等重试时应落 FAILED，不重排队");
        assertEquals(TaskStates.FAILED, taskState(planId, "B"));
        // 不再新增 attemptNo（重排队路径才会递增）
        assertEquals(attempt.getAttemptNo(), after.getAttemptNo(), "收口路径不应递增 attemptNo");
    }

    /** token 维不足同样收口（三维独立判定）：可分配 tok=30000 &lt; 60000×1.5=90000。 */
    @Test
    void tokenDimensionShortfallStopsRetrying() {
        Chain chain = buildChain(40, 200, 400_000);
        String planId = savePlan(chain, List.of(task("C", "独立任务C", List.of())));
        TaskEntity taskC = taskRepository.findByPlanIdAndTaskId(planId, "C").orElseThrow();
        String childRunId = saveChildRun(chain, 8, 30, 60_000);
        // 父已结算 330000 + 进行中预约 0 + 主保留 40000 → 可分配 = 400000-330000-40000 = 30000 < 90000
        setParentUsage(chain.runId(), 4, 10, 330_000, 0);
        TaskAttemptEntity attempt = runningAttempt(planId, "C", taskC, childRunId);

        schedulingService.requeue(attempt.getAttemptId(), "BUDGET_LIMIT");

        TaskAttemptEntity after = attemptRepository.findById(attempt.getAttemptId()).orElseThrow();
        assertEquals("FAILED", after.getState(), "token 维不足也应收口");
    }

    /** 信息不足（无 childRunId）时保守保持原行为，不收口。 */
    @Test
    void missingChildRunKeepsLegacyBehavior() {
        Chain chain = buildChain(40, 200, 400_000);
        String planId = savePlan(chain, List.of(task("D", "独立任务D", List.of())));
        TaskEntity taskD = taskRepository.findByPlanIdAndTaskId(planId, "D").orElseThrow();
        setParentUsage(chain.runId(), 30, 10, 330_000, 0);
        TaskAttemptEntity attempt = runningAttempt(planId, "D", taskD, null);

        schedulingService.requeue(attempt.getAttemptId(), "BUDGET_LIMIT");

        TaskAttemptEntity after = attemptRepository.findById(attempt.getAttemptId()).orElseThrow();
        assertEquals("QUEUED", after.getState(), "无子 Run 可比对时保守重排队，不改变 MA-08 行为");
    }

    /** 非 BUDGET_LIMIT 原因不受影响（CONCURRENCY_LIMIT 仍重排队）。 */
    @Test
    void otherRetryableReasonUnaffected() {
        Chain chain = buildChain(40, 200, 400_000);
        String planId = savePlan(chain, List.of(task("E", "独立任务E", List.of())));
        TaskEntity taskE = taskRepository.findByPlanIdAndTaskId(planId, "E").orElseThrow();
        String childRunId = saveChildRun(chain, 8, 30, 60_000);
        setParentUsage(chain.runId(), 30, 150, 330_000, 0);
        TaskAttemptEntity attempt = runningAttempt(planId, "E", taskE, childRunId);

        schedulingService.requeue(attempt.getAttemptId(), "CONCURRENCY_LIMIT");

        TaskAttemptEntity after = attemptRepository.findById(attempt.getAttemptId()).orElseThrow();
        assertEquals("QUEUED", after.getState(), "CONCURRENCY_LIMIT 不参与预算收口");
    }

    // ------------------------------------------------------- helpers

    private TaskAttemptEntity runningAttempt(String planId, String taskId, TaskEntity task,
                                             String childRunId) {
        Instant now = Instant.now();
        TaskAttemptEntity attempt = new TaskAttemptEntity(
                IdGen.next("att_"), planId, taskId, task.getTaskVersion(), childRunId, 1,
                "worker-pool", now.plusSeconds(60), 1L, null,
                "RUNNING", now.minusSeconds(5), now.minusSeconds(5));
        attemptRepository.save(attempt);
        task.setState(TaskStates.RUNNING);
        task.setUpdatedAt(now);
        taskRepository.save(task);
        return attempt;
    }

    private String saveChildRun(Chain chain, int mr, int tc, int tok) {
        String childRunId = IdGen.next("sub_run_ma11c1_");
        Instant now = Instant.now();
        RunEntity child = new RunEntity(childRunId, chain.turnId(), chain.runId(), "CODER",
                "FAILED", "BUDGET_LIMIT", 1, IdGen.next("ws_"), "digest-ma11c1",
                mr, tc, tok, now.plusSeconds(600), now, now);
        runRepository.save(child);
        return childRunId;
    }

    private void setParentUsage(String runId, int usedMr, int usedTc, Integer settledTok,
                                int reservedTok) {
        RunEntity parent = runRepository.findById(runId).orElseThrow();
        parent.setBudgetModelRequestsUsed(usedMr);
        parent.setBudgetToolCallsUsed(usedTc);
        parent.setBudgetSettledTokens(settledTok);
        parent.setBudgetReservedTokens(reservedTok);
        parent.setUpdatedAt(Instant.now());
        runRepository.save(parent);
    }

    private String taskState(String planId, String taskId) {
        return taskRepository.findByPlanIdAndTaskId(planId, taskId)
                .map(TaskEntity::getState).orElse("<missing>");
    }

    private TaskDraft task(String id, String goal, List<String> deps) {
        return new TaskDraft(id, goal, deps, List.of(), "deliver-" + id,
                List.of("self-check-" + id), null, "DELEGATED", true);
    }

    private String savePlan(Chain chain, List<TaskDraft> tasks) {
        return planService.saveDraft(chain.runId(),
                new PlanDraft(null, null, "MA-11c-1 重试收口计划", tasks)).planId();
    }

    private Chain buildChain(int maxMr, int maxTc, int maxTok) {
        Instant now = Instant.now();
        String projectId = "proj-ma11c1-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA11C1", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-ma11c1-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, "fake-fake-ma11c1",
                "fake", "fake-ma11c1", "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));
        String threadId = IdGen.next("thr_ma11c1_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA11C1 会话",
                profileId, now, now));
        String runId = IdGen.next("run_ma11c1_");
        String turnId = IdGen.next("turn_ma11c1_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest-ma11c1-parent", maxMr, maxTc, maxTok,
                now.plusSeconds(600), now, now));
        return new Chain(projectId, threadId, turnId, runId);
    }

    private record Chain(String projectId, String threadId, String turnId, String runId) {
    }
}
