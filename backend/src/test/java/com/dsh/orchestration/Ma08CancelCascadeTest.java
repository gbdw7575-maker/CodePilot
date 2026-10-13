package com.dsh.orchestration;

import com.dsh.common.IdGen;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.orchestration.plan.PlanService;
import com.dsh.orchestration.task.TaskCancelService;
import com.dsh.orchestration.task.TaskDispatchService;
import com.dsh.orchestration.task.TaskSchedulingService;
import com.dsh.orchestration.task.TaskStates;
import com.dsh.orchestration.task.TaskWorkerExecutor;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-08 AT-14 取消级联测试（规范 §10.3：取消 A 后依赖 A 的非终态下游一并取消；
 * 禁止新调度——有 CANCELLING 任务的 plan 不推进；租约结算——终态收敛时释放租约）。
 *
 * <p>测试 1 端到端：A→B→C 链式依赖，A 用 fake-ma05 SLOW worker（挂起响应取消令牌）。
 * dispatch A → A RUNNING → cancel A → 级联 B,C（PENDING→CANCELLED）+ worker 收敛 →
 * A=CANCELLED + attempt 租约清空。验证级联传递性与租约结算。</p>
 *
 * <p>测试 2 service 层：手动构造 A=CANCELLING + RUNNING attempt（租约未过期）+ D 独立 PENDING。
 * 调 promote → hasCancelling 跳过 → D 保持 PENDING（禁止新调度）。验证 CANCELLING 窗口内
 * 不推进任何任务，避免取消竞态中产生新执行者。</p>
 *
 * <p>全部离线 Fake（fake-ma05 SLOW worker），不产生真实模型费用。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "dsh.policy.require-for-side-effects=NONE")
class Ma08CancelCascadeTest {

    @Autowired private PlanService planService;
    @Autowired private TaskDispatchService dispatchService;
    @Autowired private TaskCancelService cancelService;
    @Autowired private TaskSchedulingService schedulingService;
    @Autowired private TaskWorkerExecutor workerExecutor;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ModelProfileRepository profileRepository;
    @Autowired private ThreadRepository threadRepository;
    @Autowired private TurnRepository turnRepository;
    @Autowired private RunRepository runRepository;

    @TempDir Path projectRoot;

    // -------------------------- AT-14 端到端级联 + 租约结算

    @Test
    void cancelCascadesDownstreamChainAndReleasesLease() {
        Chain chain = buildChain("fake-ma05", "fake");
        String planId = savePlan(chain, List.of(
                task("A", "MA05WORKER-SLOW 慢任务挂起响应取消令牌", List.of()),
                task("B", "下游任务B（依赖A）", List.of("A")),
                task("C", "下游任务C（依赖B）", List.of("B"))));

        // 分三次 dispatch：依赖校验要求依赖非 DRAFT（同批 dispatch 时 B 依赖 A 但 A 仍 DRAFT
        // 会被拒）。A 先 dispatch（DRAFT→PENDING），B 再 dispatch（A 非 DRAFT 通过），
        // C 最后 dispatch（B 非 DRAFT 通过）。B,C 依赖未满足，保持 PENDING 等待级联。
        // A 用 SLOW worker，挂起 1800ms 响应取消令牌。
        assertTrue(dispatch(chain, planId, List.of("A"),
                "rk-at14-a-" + UUID.randomUUID()).accepted());
        assertTrue(dispatch(chain, planId, List.of("B"),
                "rk-at14-b-" + UUID.randomUUID()).accepted());
        assertTrue(dispatch(chain, planId, List.of("C"),
                "rk-at14-c-" + UUID.randomUUID()).accepted());

        // 等 A 进入 RUNNING（worker 已 claim）
        awaitUntil(Duration.ofSeconds(30),
                () -> TaskStates.RUNNING.equals(state(planId, "A")));

        assertNotNull(latestAttempt(planId, "A").getLeaseUntil(),
                "RUNNING attempt 应有未过期租约");
        assertNotNull(latestAttempt(planId, "A").getFencingToken(),
                "claim 后应有 fencingToken（单调递增基础）");

        // cancel A：A=CANCELLING + 级联 B,C（PENDING→CANCELLED，同一事务同步发生）
        List<TaskCancelService.Item> items = cancelService.cancel(chain.runId(),
                List.of("A"), "AT-14 级联测试");
        boolean aAccepted = items.stream().anyMatch(i -> "A".equals(i.taskId()) && i.accepted());
        assertTrue(aAccepted, "A 取消应被接受");

        // 级联立即生效：B,C 在 cancel 事务内已落 CANCELLED（PENDING 分支直接终态）
        assertEquals(TaskStates.CANCELLED, state(planId, "B"),
                "B 依赖 A，应被级联取消");
        assertEquals(TaskStates.CANCELLED, state(planId, "C"),
                "C 依赖 B（传递性级联），应被级联取消");

        // 等 SLOW worker 收到取消令牌收敛：A CANCELLING → CANCELLED + attempt 租约清空
        awaitUntil(Duration.ofSeconds(30),
                () -> TaskStates.CANCELLED.equals(state(planId, "A")));

        TaskAttemptEntity attemptAfter = latestAttempt(planId, "A");
        assertEquals(TaskStates.CANCELLED, attemptAfter.getState(),
                "attempt 应收敛 CANCELLED");
        assertNull(attemptAfter.getLeaseUntil(),
                "终态收敛应释放租约（lease 清空）");
        assertNull(attemptAfter.getLeaseOwner(),
                "终态收敛应释放 leaseOwner");
    }

    // -------------------------- AT-14 禁止新调度（hasCancelling 跳过）

    @Test
    void promoteSkipsPlanWithCancellingTask() {
        Chain chain = buildChain("fake-ma05", "fake");
        // A 独立任务（即将手动置 CANCELLING）；D 独立任务（PENDING，期望不被推进）
        String planId = savePlan(chain, List.of(
                task("A", "独立任务A（手动置 CANCELLING）", List.of()),
                task("D", "独立任务D（PENDING，不应被推进）", List.of())));

        // 手动构造 A=CANCELLING + RUNNING attempt + 未过期租约（模拟取消已登记、worker 未收敛窗口）
        // 同时手动把 D 从 DRAFT 推进到 PENDING（promote 只处理 PENDING 任务；D 独立无依赖，
        // 本应被推进到 READY，但 hasCancelling 跳过整个 plan）
        Instant now = Instant.now();
        TaskEntity taskA = taskRepository.findByPlanIdAndTaskId(planId, "A").orElseThrow();
        taskA.setState(TaskStates.CANCELLING);
        taskA.setUpdatedAt(now);
        taskRepository.save(taskA);
        TaskEntity taskD = taskRepository.findByPlanIdAndTaskId(planId, "D").orElseThrow();
        taskD.setState(TaskStates.PENDING);
        taskD.setUpdatedAt(now);
        taskRepository.save(taskD);

        TaskAttemptEntity attemptA = new TaskAttemptEntity(
                IdGen.next("att_"), planId, "A", taskA.getTaskVersion(), null, 1,
                "worker-pool", now.plusSeconds(600), 1L, null,
                "RUNNING", now, now);
        attemptRepository.save(attemptA);

        // D 保持 PENDING（独立任务，依赖空，本应可被 promote 推进到 READY）
        assertEquals(TaskStates.PENDING, state(planId, "D"));

        // 调 promote：hasCancelling=true → 整个 plan 跳过，D 不被推进
        schedulingService.promote(chain.runId());

        assertEquals(TaskStates.PENDING, state(planId, "D"),
                "有 CANCELLING 任务时禁止新调度，D 应保持 PENDING");
        assertFalse(schedulingService.queuedAttempts(chain.runId()).stream()
                        .anyMatch(a -> "D".equals(a.getTaskId())),
                "D 不应被创建 QUEUED attempt");
    }

    // ------------------------------------------------------- helpers

    private TaskDraft task(String id, String goal, List<String> deps) {
        return new TaskDraft(id, goal, deps, List.of(), "deliver-" + id,
                List.of("self-check-" + id), null, "DELEGATED", true);
    }

    private String savePlan(Chain chain, List<TaskDraft> tasks) {
        return planService.saveDraft(chain.runId(),
                new PlanDraft(null, null, "MA-08 取消级联计划", tasks)).planId();
    }

    private TaskDispatchService.DispatchResult dispatch(Chain chain, String planId,
                                                        List<String> ids, String key) {
        return dispatchService.register(chain.runId(), planId, 1, ids, key);
    }

    private String state(String planId, String taskId) {
        return taskRepository.findByPlanIdAndTaskId(planId, taskId)
                .map(TaskEntity::getState).orElse("<missing>");
    }

    private TaskAttemptEntity latestAttempt(String planId, String taskId) {
        List<TaskAttemptEntity> list = attemptRepository
                .findByPlanIdAndTaskIdOrderByAttemptNoDesc(planId, taskId);
        return list.isEmpty() ? null : list.get(0);
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
        Instant now = Instant.now();
        String projectId = "proj-ma08cc-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA08CC", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-ma08cc-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, provider + "-" + model,
                provider, model, "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));
        String threadId = IdGen.next("thr_ma08cc_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA08CC 会话",
                profileId, now, now));
        String runId = IdGen.next("run_ma08cc_");
        String turnId = IdGen.next("turn_ma08cc_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest-ma08cc", 30, 120, 320_000,
                now.plusSeconds(600), now, now));
        return new Chain(projectId, threadId, turnId, runId);
    }

    private record Chain(String projectId, String threadId, String turnId, String runId) {
    }
}
