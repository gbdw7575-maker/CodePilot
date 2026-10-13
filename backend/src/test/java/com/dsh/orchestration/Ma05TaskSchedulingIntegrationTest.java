package com.dsh.orchestration;

import com.dsh.agent.tool.ToolDispatcher;
import com.dsh.api.dto.event.ToolOutcomeDto;
import com.dsh.application.DshProperties;
import com.dsh.common.IdGen;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.orchestration.plan.PlanService;
import com.dsh.orchestration.task.TaskAwaitService;
import com.dsh.orchestration.task.TaskCancelService;
import com.dsh.orchestration.task.TaskDispatchService;
import com.dsh.orchestration.task.TaskStates;
import com.dsh.orchestration.task.TaskWorkerExecutor;
import com.dsh.storage.MessageEntity;
import com.dsh.storage.MessageRepository;
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
import com.dsh.storage.TaskWaitEntity;
import com.dsh.storage.TaskWaitRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-05 门控测试（服务级 + worker 池）：
 * <ul>
 *   <li>AT-07：两个独立任务在同一根 Run 真实重叠（时间区间交集，非线程名推断），峰值≥2；</li>
 *   <li>AT-08：只依赖快任务 A 的 C 在慢任务 B 在途时已启动（C 区间与 B 相交）；</li>
 *   <li>AT-09：await 超时窗口结束返回 timedOut=true，任务不被误判成功；唯一结果回填；</li>
 *   <li>整批拒绝反例、requestKey 幂等/冲突、QUEUED 持久化重投、运行中取消收敛、worker 越权拒绝。</li>
 * </ul>
 * 全部离线 Fake（fake-ma05 脚本），不产生真实模型费用。
 */
@SpringBootTest
@TestPropertySource(properties = "dsh.policy.require-for-side-effects=NONE")
class Ma05TaskSchedulingIntegrationTest {

    @Autowired private PlanService planService;
    @Autowired private TaskDispatchService dispatchService;
    @Autowired private TaskAwaitService awaitService;
    @Autowired private TaskCancelService cancelService;
    @Autowired private TaskWorkerExecutor workerExecutor;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private TaskWaitRepository waitRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ModelProfileRepository profileRepository;
    @Autowired private ThreadRepository threadRepository;
    @Autowired private TurnRepository turnRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private WorkerConcurrencyManager concurrencyManager;
    @Autowired private DshProperties properties;
    @Autowired private ToolDispatcher toolDispatcher;

    @TempDir Path projectRoot;

    // ------------------------------------------------------- AT-07 真实重叠

    @Test
    void independentTasksOverlapWithPeakParallelism() {
        Chain chain = buildChain("fake-ma05", "fake");
        String planId = savePlan(chain, List.of(
                task("A", "快任务 MA05WORKER-FAST 立即完成", List.of()),
                task("B", "慢任务 MA05WORKER-SLOW 固定挂起", List.of())));

        TaskDispatchService.DispatchResult result =
                dispatch(chain, planId, List.of("A", "B"), "rk-at07-" + UUID.randomUUID());
        assertTrue(result.accepted());

        awaitUntil(Duration.ofSeconds(30),
                () -> state(planId, "A").equals(TaskStates.SUCCEEDED)
                        && state(planId, "B").equals(TaskStates.SUCCEEDED)
                        && intervalFinished(chain.runId(), "A") && intervalFinished(chain.runId(), "B"));

        List<TaskWorkerExecutor.Metrics.Interval> intervals =
                workerExecutor.metrics().intervals(chain.runId());
        List<TaskWorkerExecutor.Metrics.Interval> terminal = intervals.stream()
                .filter(i -> i.end() != null)
                .toList();
        TaskWorkerExecutor.Metrics.Interval a = byTask(terminal, "A");
        TaskWorkerExecutor.Metrics.Interval b = byTask(terminal, "B");
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(a.overlaps(b),
                "AT-07：A[" + a.start() + "," + a.end() + "] 与 B[" + b.start() + "," + b.end()
                        + "] 必须真实时间重叠");
        assertTrue(workerExecutor.metrics().peakCount(chain.runId()) >= 2,
                "AT-07：根内并发峰值必须 ≥2");
    }

    // ------------------------------------------------------- AT-08 依赖只等直接上游

    @Test
    void downstreamStartsWhenOnlyItsDependencyCompletes() {
        Chain chain = buildChain("fake-ma05", "fake");
        String planId = savePlan(chain, List.of(
                task("A", "快任务 MA05WORKER-FAST 立即完成", List.of()),
                task("B", "慢任务 MA05WORKER-SLOW 固定挂起", List.of()),
                task("C", "下游快任务 MA05WORKER-FAST", List.of("A"))));

        assertTrue(dispatch(chain, planId, List.of("A", "B"),
                "rk-at08-ab-" + UUID.randomUUID()).accepted());
        awaitUntil(Duration.ofSeconds(10),
                () -> TaskStates.SUCCEEDED.equals(state(planId, "A")));

        // A 完成后才登记 C；B 仍在途，C 必须能启动
        assertTrue(dispatch(chain, planId, List.of("C"),
                "rk-at08-c-" + UUID.randomUUID()).accepted());
        awaitUntil(Duration.ofSeconds(30),
                () -> TaskStates.SUCCEEDED.equals(state(planId, "B"))
                        && TaskStates.SUCCEEDED.equals(state(planId, "C"))
                        && intervalFinished(chain.runId(), "B") && intervalFinished(chain.runId(), "C"));

        List<TaskWorkerExecutor.Metrics.Interval> terminal =
                workerExecutor.metrics().intervals(chain.runId()).stream()
                        .filter(i -> i.end() != null).toList();
        TaskWorkerExecutor.Metrics.Interval b = byTask(terminal, "B");
        TaskWorkerExecutor.Metrics.Interval c = byTask(terminal, "C");
        assertNotNull(b);
        assertNotNull(c);
        assertTrue(c.start().isBefore(b.end()),
                "AT-08：C 必须在 B 结束前启动（不被非依赖 B 阻塞）");
        assertTrue(c.overlaps(b), "AT-08：C 执行区间必须与在途的 B 真实重叠");
    }

    /** 终态事务先提交，结束指标在 worker finally 登记；验收必须等待两者都可观察。 */
    private boolean intervalFinished(String runId, String taskId) {
        return byTask(workerExecutor.metrics().intervals(runId).stream()
                .filter(i -> i.end() != null).toList(), taskId) != null;
    }

    // ------------------------------------------------------- AT-09 超时窗口

    @Test
    void awaitTimeoutWindowsReturnsTimedOutWithoutFakeSuccess() {
        Chain chain = buildChain("fake-ma05", "fake");
        String planId = savePlan(chain, List.of(
                task("A", "快任务 MA05WORKER-FAST 立即完成", List.of()),
                task("B", "慢任务 MA05WORKER-SLOW 固定挂起", List.of())));
        assertTrue(dispatch(chain, planId, List.of("A", "B"),
                "rk-at09-" + UUID.randomUUID()).accepted());

        String callId = IdGen.next("call_at09_");
        awaitService.park(chain.runId(), chain.turnId(), callId, List.of("A", "B"),
                "ALL", 0L, "{}", Instant.now().plusMillis(300));
        awaitUntil(Duration.ofSeconds(5), () ->
                waitRepository.findAll().stream()
                        .anyMatch(w -> callId.equals(w.getToolCallId())
                                && !TaskWaitEntity.WAITING.equals(w.getState())));

        TaskWaitEntity wait = waitRepository.findAll().stream()
                .filter(w -> callId.equals(w.getToolCallId())).findFirst().orElseThrow();
        assertEquals(TaskWaitEntity.TIMED_OUT, wait.getState());
        // 慢任务在超时窗口结束时不得被误判成功（允许 READY/RUNNING/PENDING/CANCELLING）
        String bState = state(planId, "B");
        assertFalse(TaskStates.SUCCEEDED.equals(bState), "timedOut 不等于任务成功");
        // 唯一 tool result 已回填，供恢复后的主循环配对
        awaitUntil(Duration.ofSeconds(5), () -> messageRepository.listByThread(chain.threadId())
                .stream().anyMatch(m -> "tool".equals(m.getRole())
                        && m.getContent().contains(callId)
                        && m.getContent().contains("\"timedOut\":true")));

        // 窗口结束不取消任务：B 最终仍正常成功
        awaitUntil(Duration.ofSeconds(20),
                () -> TaskStates.SUCCEEDED.equals(state(planId, "B")));
    }

    // ------------------------------------------------------- 整批拒绝反例

    @Test
    void wholeBatchRejectionsDoNotStartAnything() {
        Chain chain = buildChain("fake-ma05", "fake");
        String planId = savePlan(chain, List.of(
                task("A", "任务A", List.of()),
                task("B", "任务B", List.of("A"))));

        assertEquals("REVISION_MISMATCH",
                dispatch(chain, planId, 99, List.of("A", "B"), "rk-rev-" + UUID.randomUUID())
                        .errorCode());
        assertEquals("DEPENDENCY_NOT_DISPATCHED",
                dispatch(chain, planId, 1, List.of("B"), "rk-dep-" + UUID.randomUUID())
                        .errorCode());
        // 整批拒绝：无派发登记行落库，任务保持 DRAFT，没有 attempt
        assertEquals(TaskStates.DRAFT, state(planId, "A"));
        assertEquals(TaskStates.DRAFT, state(planId, "B"));
        assertTrue(attemptRepository.findByPlanIdAndTaskIdOrderByAttemptNoDesc(planId, "B").isEmpty());

        // INLINE 任务整批拒绝
        String inlinePlan = savePlan(chain, List.of(
                taskWithMode("X", "INLINE 主 Agent 自办任务", List.of(), "INLINE", false)));
        assertEquals("INVALID_STATE",
                dispatch(chain, inlinePlan, 1, List.of("X"), "rk-inline-" + UUID.randomUUID())
                        .errorCode());
    }

    // ------------------------------------------------------- 幂等

    @Test
    void requestKeyIdempotentReplayAndConflict() {
        Chain chain = buildChain("fake-ma05", "fake");
        String planId = savePlan(chain, List.of(
                task("A", "MA05WORKER-FAST 幂等任务", List.of()),
                task("B", "MA05WORKER-SLOW 冲突对照", List.of())));
        String key = "rk-idem-" + UUID.randomUUID();

        TaskDispatchService.DispatchResult first =
                dispatch(chain, planId, List.of("A"), key);
        assertTrue(first.accepted());
        TaskDispatchService.DispatchResult replay =
                dispatch(chain, planId, List.of("A"), key);
        assertTrue(replay.accepted());
        assertTrue(replay.replay());
        assertEquals(first.dispatchId(), replay.dispatchId());

        // 同 key 不同参数
        assertEquals("REQUEST_KEY_CONFLICT",
                dispatch(chain, planId, List.of("B"), key).errorCode());

        // 重放绝不创建第二个执行者
        awaitUntil(Duration.ofSeconds(20),
                () -> TaskStates.SUCCEEDED.equals(state(planId, "A")));
        assertEquals(1, attemptRepository
                .findByPlanIdAndTaskIdOrderByAttemptNoDesc(planId, "A").size());
    }

    // ------------------------------------------------------- QUEUED 持久化重投

    @Test
    void queuedAttemptIsRedeliveredAfterConcurrencyQuotaFrees() {
        Chain chain = buildChain("fake-ma05", "fake");
        String planId = savePlan(chain, List.of(
                task("A", "MA05WORKER-FAST 配额恢复任务", List.of())));

        int globalMax = properties.orchestration().quota().globalMaxWorkers();
        List<WorkerConcurrencyManager.Lease> occupied = new ArrayList<>();
        Instant deadline = Instant.now().plusSeconds(120);
        try {
            for (int i = 0; i < globalMax; i++) {
                occupied.add(concurrencyManager.acquire(
                        "root-busy-" + UUID.randomUUID(), "holder-busy-" + i,
                        "provider-busy-" + i, deadline));
            }
            assertTrue(dispatch(chain, planId, List.of("A"),
                    "rk-queue-" + UUID.randomUUID()).accepted());
            // 满额：worker 领取后 CONCURRENCY_LIMIT 回 QUEUED（持久化待执行记录仍在）
            awaitUntil(Duration.ofSeconds(15), () ->
                    attemptRepository.findByPlanIdAndTaskIdOrderByAttemptNoDesc(planId, "A").stream()
                            .anyMatch(a -> "QUEUED".equals(a.getState())));
        } finally {
            occupied.forEach(concurrencyManager::release);
        }
        // 释放后由持久化 QUEUED 行重新投递（直接调补偿扫描，消除 2s 定时抖动）
        workerExecutor.scheduledScan();
        awaitUntil(Duration.ofSeconds(20),
                () -> TaskStates.SUCCEEDED.equals(state(planId, "A")));
    }

    // ------------------------------------------------------- 取消

    @Test
    void cancelRunningTaskConvergesToCancelled() {
        Chain chain = buildChain("fake-ma05", "fake");
        String planId = savePlan(chain, List.of(
                task("A", "MA05WORKER-SLOW 被取消任务", List.of())));
        assertTrue(dispatch(chain, planId, List.of("A"),
                "rk-cancel-" + UUID.randomUUID()).accepted());
        awaitUntil(Duration.ofSeconds(10),
                () -> TaskStates.RUNNING.equals(state(planId, "A")));
        // 等子 Run 创建并绑定到 attempt（绑定先于模型循环开始），随后走真实取消令牌路径
        awaitUntil(Duration.ofSeconds(10), () -> attemptRepository
                .findByPlanIdAndTaskIdOrderByAttemptNoDesc(planId, "A").stream()
                .findFirst().map(a -> a.getChildRunId() != null).orElse(false));

        List<TaskCancelService.Item> items =
                cancelService.cancel(chain.runId(), List.of("A"), "MA-05 取消验收");
        assertEquals(1, items.size());
        assertTrue(items.get(0).accepted());
        assertEquals(TaskStates.CANCELLING, items.get(0).state());

        awaitUntil(Duration.ofSeconds(10),
                () -> TaskStates.CANCELLED.equals(state(planId, "A")));
    }

    // ------------------------------------------------------- worker 越权执行点

    @Test
    void workerRoleCannotInvokeSchedulingToolsAtExecutionPoint() {
        Chain chain = buildChain("fake-ma05", "fake", "CODER");
        ToolOutcomeDto outcome = toolDispatcher.dispatch(chain.runId(), chain.threadId(),
                chain.turnId(), chain.projectId(), Instant.now().plusSeconds(60),
                "dispatch_tasks",
                "{\"planId\":\"plan_x\",\"expectedRevision\":1,\"taskIds\":[\"A\"],"
                        + "\"requestKey\":\"rk-worker-denied\"}",
                IdGen.next("call_"));
        assertEquals("DENIED", outcome.status());
        assertEquals("PERMISSION_DENIED", outcome.errorCode());
    }

    // ------------------------------------------------------- helpers

    private TaskDraft task(String id, String goal, List<String> deps) {
        return taskWithMode(id, goal, deps, "DELEGATED", true);
    }

    private TaskDraft taskWithMode(String id, String goal, List<String> deps,
                                   String mode, boolean required) {
        return new TaskDraft(id, goal, deps, List.of(), "deliver-" + id,
                List.of("self-check-" + id), null, mode, required);
    }

    private String savePlan(Chain chain, List<TaskDraft> tasks) {
        return planService.saveDraft(chain.runId(),
                new PlanDraft(null, null, "MA-05 验收计划", tasks)).planId();
    }

    private TaskDispatchService.DispatchResult dispatch(Chain chain, String planId,
                                                        List<String> ids, String key) {
        return dispatch(chain, planId, 1, ids, key);
    }

    private TaskDispatchService.DispatchResult dispatch(Chain chain, String planId, int revision,
                                                        List<String> ids, String key) {
        return dispatchService.register(chain.runId(), planId, revision, ids, key);
    }

    private String state(String planId, String taskId) {
        return taskRepository.findByPlanIdAndTaskId(planId, taskId)
                .map(TaskEntity::getState).orElse("<missing>");
    }

    private String taskOf(TaskWorkerExecutor.Metrics.Interval interval) {
        return attemptRepository.findById(interval.attemptId())
                .map(TaskAttemptEntity::getTaskId).orElse("<missing>");
    }

    private TaskWorkerExecutor.Metrics.Interval byTask(
            List<TaskWorkerExecutor.Metrics.Interval> intervals, String taskId) {
        return intervals.stream()
                .filter(i -> taskId.equals(taskOf(i)))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private void awaitUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        AssertionError last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (AssertionError e) {
                last = e;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new AssertionError("条件在 " + timeout + " 内未满足");
    }

    private Chain buildChain(String model, String provider) {
        return buildChain(model, provider, "MAIN");
    }

    private Chain buildChain(String model, String provider, String role) {
        Instant now = Instant.now();
        String projectId = "proj-ma05-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA05", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-ma05-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, provider + "-" + model,
                provider, model, "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));
        String threadId = IdGen.next("thr_ma05_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA05 会话",
                profileId, now, now));
        String runId = IdGen.next("run_ma05_");
        String turnId = IdGen.next("turn_ma05_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, role, "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest-ma05", 30, 120, 320_000,
                now.plusSeconds(600), now, now));
        return new Chain(projectId, threadId, turnId, runId);
    }

    private record Chain(String projectId, String threadId, String turnId, String runId) {
    }
}
