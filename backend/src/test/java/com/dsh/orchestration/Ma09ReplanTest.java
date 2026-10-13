package com.dsh.orchestration;

import com.dsh.application.DshProperties;
import com.dsh.common.IdGen;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.SavedPlan;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.orchestration.plan.PlanService;
import com.dsh.orchestration.plan.PlanServiceException;
import com.dsh.orchestration.task.TaskSchedulingService;
import com.dsh.orchestration.task.TaskStates;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-09 受控重规划与版本失效传播测试（规范 AT-02/AT-11/AT-16）。
 *
 * <p>AT-11/AT-02 版本失效传播：上游重跑 SUCCEEDED 后，下游 SUCCEEDED 任务的旧证据
 * （inputDigest 基于旧上游产物）不再有效 → 主动回退 BLOCKED；旧 result 行不改写。</p>
 *
 * <p>AT-16 必需任务移除保护：FAILED/BLOCKED 的必需任务不能被 update_plan 静默移除
 * （REQUIRED_TASK_REMOVED）；CANCELLED 的必需任务可移除（已留审计痕迹）。
 * PARTIAL 不当成功：必需任务 FAILED 时 maybeIntegrate 不触发集成。</p>
 *
 * <p>全部 service 层构造状态，不依赖 worker 时间窗口，不产生真实模型费用。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "dsh.policy.require-for-side-effects=NONE")
class Ma09ReplanTest {

    @Autowired private PlanService planService;
    @Autowired private TaskSchedulingService schedulingService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private TaskResultRepository resultRepository;
    @Autowired private IntegrationRecordRepository integrationRecordRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ModelProfileRepository profileRepository;
    @Autowired private ThreadRepository threadRepository;
    @Autowired private TurnRepository turnRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private DshProperties properties;

    @TempDir Path projectRoot;

    // -------------------------- AT-11/AT-02 版本失效传播 + 旧 result 不改写

    @Test
    void upstreamRerunInvalidatesDownstreamAndPreservesOldResult() {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(
                task("A", "上游任务A", List.of()),
                task("B", "下游任务B依赖A", List.of("A"))));
        Instant now = Instant.now();

        // 1. A 首次 SUCCEEDED，outputDigest="digest-A-v1"
        completeSucceeded(planId, "A", "run_A_1", "patch-A", "digest-A-v1", now);
        assertEquals(TaskStates.SUCCEEDED, state(planId, "A"));

        // 2. B SUCCEEDED，B 的 inputDigest = sha256("A:digest-A-v1\n")
        completeSucceeded(planId, "B", "run_B_1", "patch-B", "digest-B", now);
        assertEquals(TaskStates.SUCCEEDED, state(planId, "B"));

        // 3. 重跑 A，outputDigest="digest-A-v2"（与 v1 不同）
        completeSucceeded(planId, "A", "run_A_2", "patch-A2", "digest-A-v2", now);
        assertEquals(TaskStates.SUCCEEDED, state(planId, "A"));

        // 4. B 的旧证据失效 → BLOCKED
        assertEquals(TaskStates.BLOCKED, state(planId, "B"),
                "上游 A 重跑后下游 B 的 SUCCEEDED 证据失效 → BLOCKED");

        // 5. B 的旧 result 行不被改写（历史可追溯）
        TaskResultEntity bResult = resultRepository
                .findTopByPlanIdAndTaskIdOrderByCreatedAtDesc(planId, "B").orElseThrow();
        assertEquals("SUCCEEDED", bResult.getState(),
                "旧 result 行不改写（AT-02 历史证据不被改写）");
        assertTrue(bResult.getInputDigest() != null,
                "旧 inputDigest 仍保留（用于追溯失效前的证据基础）");
    }

    // -------------------------- AT-16 必需任务 FAILED 不可移除

    @Test
    void requiredTaskFailedCannotBeRemoved() {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(task("A", "必需任务A", List.of())));
        TaskEntity taskA = taskRepository.findByPlanIdAndTaskId(planId, "A").orElseThrow();
        taskA.setState(TaskStates.FAILED);
        taskRepository.save(taskA);

        PlanDraft update = new PlanDraft(planId, 1, "修订目标", List.of(
                new TaskDraft("B", "替代任务", List.of(), List.of(), "deliver-B",
                        List.of("self-check-B"), null, "DELEGATED", false)));
        PlanServiceException ex = assertThrows(PlanServiceException.class,
                () -> planService.saveDraft(chain.runId(), update));
        assertEquals("REQUIRED_TASK_REMOVED", ex.reasonCode(),
                "FAILED 必需任务不可静默移除");
    }

    // -------------------------- AT-16 必需任务 BLOCKED 不可移除

    @Test
    void requiredTaskBlockedCannotBeRemoved() {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(task("A", "必需任务A", List.of())));
        TaskEntity taskA = taskRepository.findByPlanIdAndTaskId(planId, "A").orElseThrow();
        taskA.setState(TaskStates.BLOCKED);
        taskRepository.save(taskA);

        PlanDraft update = new PlanDraft(planId, 1, "修订目标", List.of(
                new TaskDraft("B", "替代任务", List.of(), List.of(), "deliver-B",
                        List.of("self-check-B"), null, "DELEGATED", false)));
        PlanServiceException ex = assertThrows(PlanServiceException.class,
                () -> planService.saveDraft(chain.runId(), update));
        assertEquals("REQUIRED_TASK_REMOVED", ex.reasonCode(),
                "BLOCKED 必需任务不可静默移除（等主 Agent 重规划解除）");
    }

    // -------------------------- AT-16 必需任务 CANCELLED 可移除

    @Test
    void requiredTaskCancelledCanBeRemoved() {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(task("A", "必需任务A", List.of())));
        TaskEntity taskA = taskRepository.findByPlanIdAndTaskId(planId, "A").orElseThrow();
        taskA.setState(TaskStates.CANCELLED);
        taskRepository.save(taskA);

        PlanDraft update = new PlanDraft(planId, 1, "修订目标", List.of(
                new TaskDraft("B", "替代任务", List.of(), List.of(), "deliver-B",
                        List.of("self-check-B"), null, "DELEGATED", false)));
        SavedPlan saved = planService.saveDraft(chain.runId(), update);
        assertEquals(2, saved.revision(), "CANCELLED 必需任务可移除（已留审计痕迹）");
    }

    // -------------------------- AT-16 PARTIAL 不当成功

    @Test
    void partialFailureDoesNotTriggerIntegration() throws InterruptedException {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(
                task("A", "必需任务A", List.of()),
                new TaskDraft("B", "可选任务B", List.of(), List.of(), "deliver-B",
                        List.of("self-check-B"), null, "DELEGATED", false)));
        Instant now = Instant.now();

        // A FAILED（必需任务失败）
        completeFailed(planId, "A", "run_A_fail", now);
        assertEquals(TaskStates.FAILED, state(planId, "A"));

        // B SUCCEEDED（可选任务成功）
        completeSucceeded(planId, "B", "run_B_ok", "patch-B", "digest-B", now);
        assertEquals(TaskStates.SUCCEEDED, state(planId, "B"));

        // 等待异步集成钩子执行（maybeIntegrate 在 integrationExecutor 单线程上运行）
        Thread.sleep(1000);

        // maybeIntegrate 守卫：必需任务 A 未 SUCCEEDED → 不触发 integrate
        assertTrue(integrationRecordRepository
                        .findFirstByRootRunIdOrderByCreatedAtDesc(chain.runId()).isEmpty(),
                "必需任务未全部 SUCCEEDED → 不触发集成（PARTIAL 不当成功，AT-16）");
    }

    // ------------------------------------------------------- helpers

    private TaskDraft task(String id, String goal, List<String> deps) {
        return new TaskDraft(id, goal, deps, List.of(), "deliver-" + id,
                List.of("self-check-" + id), null, "DELEGATED", true);
    }

    private String savePlan(Chain chain, List<TaskDraft> tasks) {
        return planService.saveDraft(chain.runId(),
                new PlanDraft(null, null, "MA-09 重规划测试计划", tasks)).planId();
    }

    private String state(String planId, String taskId) {
        return taskRepository.findByPlanIdAndTaskId(planId, taskId)
                .map(TaskEntity::getState).orElse("<missing>");
    }

    /** 构造 RUNNING attempt + 任务 RUNNING 状态，调用 complete() 使任务 SUCCEEDED。 */
    private void completeSucceeded(String planId, String taskId, String childRunId,
                                   String patchArtifactId, String workspaceDigest,
                                   Instant baseTime) {
        TaskEntity task = taskRepository.findByPlanIdAndTaskId(planId, taskId).orElseThrow();
        TaskAttemptEntity attempt = new TaskAttemptEntity(
                IdGen.next("att_"), planId, taskId, task.getTaskVersion(),
                childRunId, 1, "test-worker", baseTime.plusSeconds(600), 1L, null,
                "RUNNING", baseTime, baseTime);
        attemptRepository.save(attempt);
        if (!TaskStates.RUNNING.equals(task.getState())) {
            task.setState(TaskStates.RUNNING);
            task.setUpdatedAt(baseTime);
            taskRepository.save(task);
        }
        SubagentResult result = new SubagentResult(
                childRunId, "CODER", "COMPLETED", null,
                "self-check-" + taskId + " done", List.of(), patchArtifactId,
                "", workspaceDigest, 0, 0, null, 0);
        schedulingService.complete(attempt.getAttemptId(), result);
    }

    /** 构造 RUNNING attempt + 任务 RUNNING 状态，调用 complete() 使任务 FAILED。 */
    private void completeFailed(String planId, String taskId, String childRunId,
                                Instant baseTime) {
        TaskEntity task = taskRepository.findByPlanIdAndTaskId(planId, taskId).orElseThrow();
        TaskAttemptEntity attempt = new TaskAttemptEntity(
                IdGen.next("att_"), planId, taskId, task.getTaskVersion(),
                childRunId, 1, "test-worker", baseTime.plusSeconds(600), 1L, null,
                "RUNNING", baseTime, baseTime);
        attemptRepository.save(attempt);
        if (!TaskStates.RUNNING.equals(task.getState())) {
            task.setState(TaskStates.RUNNING);
            task.setUpdatedAt(baseTime);
            taskRepository.save(task);
        }
        SubagentResult result = new SubagentResult(
                childRunId, "CODER", "FAILED", "BUILD_ERROR",
                taskId + " build failed", List.of(), null, "", "", 0, 0, null, 0);
        schedulingService.complete(attempt.getAttemptId(), result);
    }

    private Chain buildChain() {
        Instant now = Instant.now();
        String projectId = "proj-ma09rp-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA09RP", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-ma09rp-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, "fake-fake-ma05",
                "fake", "fake-ma05", "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));
        String threadId = IdGen.next("thr_ma09rp_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA09RP 会话",
                profileId, now, now));
        String runId = IdGen.next("run_ma09rp_");
        String turnId = IdGen.next("turn_ma09rp_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest-ma09rp", 30, 120, 320_000,
                now.plusSeconds(600), now, now));
        return new Chain(projectId, threadId, turnId, runId);
    }

    private record Chain(String projectId, String threadId, String turnId, String runId) {
    }
}
