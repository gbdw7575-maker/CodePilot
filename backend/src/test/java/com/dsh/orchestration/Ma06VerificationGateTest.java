package com.dsh.orchestration;

import com.dsh.common.IdGen;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.orchestration.plan.PlanService;
import com.dsh.orchestration.task.TaskDispatchService;
import com.dsh.orchestration.task.TaskQueryService;
import com.dsh.orchestration.task.TaskStates;
import com.dsh.orchestration.task.TaskViews.TaskView;
import com.dsh.orchestration.task.TaskWorkerExecutor;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
 * MA-06 验收闸门测试（规范 §9：VERIFYING 状态、AT-10/AT-11）。
 *
 * <p>子 Run COMPLETED 不自动等同任务 SUCCEEDED。验收闸门在子 Run 终态后执行：
 * 检查证据完整性（产物/验证记录）与验收条目覆盖。验收通过才 SUCCEEDED 并封存
 * 不可变产物；证据缺失或测试失败则 FAILED，不释放下游依赖（AT-10）。
 * 封存后上游输入或任务版本变化时旧结果可追溯但不再有效（AT-11）。</p>
 *
 * <p>全部离线 Fake（fake-ma06 脚本），不产生真实模型费用。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "dsh.policy.require-for-side-effects=NONE")
class Ma06VerificationGateTest {

    @Autowired private PlanService planService;
    @Autowired private TaskDispatchService dispatchService;
    @Autowired private TaskWorkerExecutor workerExecutor;
    @Autowired private TaskRepository taskRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ModelProfileRepository profileRepository;
    @Autowired private ThreadRepository threadRepository;
    @Autowired private TurnRepository turnRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private TaskQueryService queryService;
    @Autowired private TaskResultRepository resultRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @TempDir Path projectRoot;

    // ------------------------------------------------------- AT-10 证据缺失

    @Test
    void workerSelfSuccessButNoEvidence_failsWithEvidenceMissing() {
        Chain chain = buildChain("fake-ma06", "fake");
        String planId = savePlan(chain, List.of(
                task("A", "MA06WORKER-NOEVIDENCE 无产物自述成功", List.of()),
                task("B", "MA06WORKER-PASS 下游任务", List.of("A"))));

        assertTrue(dispatch(chain, planId, List.of("A"),
                "rk-at10-noev-" + UUID.randomUUID()).accepted());

        awaitUntil(Duration.ofSeconds(30),
                () -> TaskStates.FAILED.equals(state(planId, "A")));

        // A 验收失败：EVIDENCE_MISSING（worker 自述成功但无产物变更）
        TaskView viewA = view(planId, "A");
        assertEquals(TaskStates.FAILED, viewA.state());
        assertEquals("EVIDENCE_MISSING", viewA.result().errorCode());
        assertNull(viewA.result().verificationRef());

        // AT-10：A FAILED → 下游 B 不被释放（派发被拒，B 保持 DRAFT）
        TaskDispatchService.DispatchResult bDispatch =
                dispatch(chain, planId, List.of("B"),
                        "rk-at10-noev-b-" + UUID.randomUUID());
        assertFalse(bDispatch.accepted(), "A FAILED 后 B 不应被派发");
        assertEquals(TaskStates.DRAFT, state(planId, "B"));
    }

    @Test
    void corruptedStoredAcceptanceCannotReleaseDownstreamDespiteRealWorkerPatch() {
        Chain chain = buildChain("fake-ma06", "fake");
        String planId = savePlan(chain, List.of(
                task("A", "MA06WORKER-PASS 写入成功但登记损坏", List.of()),
                task("B", "MA06WORKER-PASS 下游任务", List.of("A"))));
        assertEquals(1, jdbcTemplate.update(
                "UPDATE agent_tasks SET acceptance = ? WHERE plan_id = ? AND task_id = ?",
                "[]", planId, "A"));

        assertTrue(dispatch(chain, planId, List.of("A"),
                "m1a-corrupt-a-" + UUID.randomUUID()).accepted());
        awaitUntil(Duration.ofSeconds(30),
                () -> TaskStates.FAILED.equals(state(planId, "A")));
        TaskView viewA = view(planId, "A");
        assertEquals("EVIDENCE_MISSING", viewA.result().errorCode());
        assertNotNull(viewA.result().patchArtifactId(), "A has an actual worker patch; it cannot prove missing requirements");
        assertNull(viewA.result().verificationRef());
        assertFalse(dispatch(chain, planId, List.of("B"),
                "m1a-corrupt-b-" + UUID.randomUUID()).accepted());
        assertEquals(TaskStates.DRAFT, state(planId, "B"));
    }

    // ------------------------------------------------------- AT-10 验收条目未覆盖

    @Test
    void workerWithArtifactButBadSummary_failsWithVerificationFailed() {
        Chain chain = buildChain("fake-ma06", "fake");
        String planId = savePlan(chain, List.of(
                task("A", "MA06WORKER-BADSUMMARY 验收条目未覆盖", List.of()),
                task("B", "MA06WORKER-PASS 下游任务", List.of("A"))));

        assertTrue(dispatch(chain, planId, List.of("A"),
                "rk-at10-bad-" + UUID.randomUUID()).accepted());

        awaitUntil(Duration.ofSeconds(30),
                () -> TaskStates.FAILED.equals(state(planId, "A")));

        // A 有产物（patchArtifactId 非空）但摘要未覆盖验收条目关键词 → VERIFICATION_FAILED
        TaskView viewA = view(planId, "A");
        assertEquals(TaskStates.FAILED, viewA.state());
        assertEquals("VERIFICATION_FAILED", viewA.result().errorCode());
        assertNotNull(viewA.result().patchArtifactId(),
                "BADSUMMARY worker 有产物变更，证据存在但验收未通过");

        // AT-10：A FAILED → 下游 B 不被释放
        TaskDispatchService.DispatchResult bDispatch =
                dispatch(chain, planId, List.of("B"),
                        "rk-at10-bad-b-" + UUID.randomUUID());
        assertFalse(bDispatch.accepted(), "A FAILED 后 B 不应被派发");
        assertEquals(TaskStates.DRAFT, state(planId, "B"));
    }

    // ------------------------------------------------------- AT-10 验收通过 + 下游释放

    @Test
    void workerWithArtifactAndGoodSummary_succeedsAndReleasesDownstream() {
        Chain chain = buildChain("fake-ma06", "fake");
        String planId = savePlan(chain, List.of(
                task("A", "MA06WORKER-PASS 验收通过任务", List.of()),
                task("B", "MA06WORKER-PASS 下游任务", List.of("A"))));

        assertTrue(dispatch(chain, planId, List.of("A"),
                "rk-at10-pass-a-" + UUID.randomUUID()).accepted());

        awaitUntil(Duration.ofSeconds(30),
                () -> TaskStates.SUCCEEDED.equals(state(planId, "A")));

        // A 验收通过 → SUCCEEDED，封存不可变证据
        TaskView viewA = view(planId, "A");
        assertEquals(TaskStates.SUCCEEDED, viewA.state());
        assertNull(viewA.result().errorCode());
        assertTrue(viewA.evidenceValid());
        assertNotNull(viewA.result().patchArtifactId());
        // A 无依赖 → inputDigest 为 null（封存时无上游摘要）
        assertNull(viewA.result().inputDigest());

        // AT-10：A SUCCEEDED → 下游 B 可被派发并经 promote 释放
        assertTrue(dispatch(chain, planId, List.of("B"),
                "rk-at10-pass-b-" + UUID.randomUUID()).accepted());

        awaitUntil(Duration.ofSeconds(30),
                () -> TaskStates.SUCCEEDED.equals(state(planId, "B")));

        TaskView viewB = view(planId, "B");
        assertEquals(TaskStates.SUCCEEDED, viewB.state());
        assertTrue(viewB.evidenceValid());
        // B 依赖 A → inputDigest 非空（sha256 of A 的 outputDigest）
        assertNotNull(viewB.result().inputDigest());
    }

    // ------------------------------------------------------- AT-11 封存证据有效性

    @Test
    void sealedResultInvalidatedWhenUpstreamDigestChanges() {
        Chain chain = buildChain("fake-ma06", "fake");
        String planId = savePlan(chain, List.of(
                task("A", "MA06WORKER-PASS 上游任务", List.of()),
                task("B", "MA06WORKER-PASS 下游任务", List.of("A"))));

        assertTrue(dispatch(chain, planId, List.of("A"),
                "rk-at11-a-" + UUID.randomUUID()).accepted());
        awaitUntil(Duration.ofSeconds(30),
                () -> TaskStates.SUCCEEDED.equals(state(planId, "A")));

        assertTrue(dispatch(chain, planId, List.of("B"),
                "rk-at11-b-" + UUID.randomUUID()).accepted());
        awaitUntil(Duration.ofSeconds(30),
                () -> TaskStates.SUCCEEDED.equals(state(planId, "B")));

        // B 封存时 evidenceValid=true（inputDigest 与 A 的 outputDigest 一致）
        TaskView viewB = view(planId, "B");
        assertEquals(TaskStates.SUCCEEDED, viewB.state());
        assertTrue(viewB.evidenceValid());
        assertNotNull(viewB.result().inputDigest());

        // 确认 A 有 outputDigest（worker 写了文件，工作区摘要非空）
        TaskResultEntity resultA = resultRepository
                .findTopByPlanIdAndTaskIdOrderByCreatedAtDesc(planId, "A")
                .orElseThrow();
        assertNotNull(resultA.getOutputDigest(), "A 应有 outputDigest");

        // AT-11：模拟上游产物变化——直接改 A 的 output_digest 列
        jdbcTemplate.update(
                "UPDATE agent_task_results SET output_digest = ? WHERE attempt_id = ?",
                "tampered-digest-at11", resultA.getAttemptId());

        // B 的封存证据不再有效（recomputed inputDigest ≠ stored inputDigest）
        TaskView viewBAfter = view(planId, "B");
        assertFalse(viewBAfter.evidenceValid(),
                "上游产物变化后，B 的封存证据应不再有效（AT-11）");
        // 但旧结果仍可追溯（state 不变，result 仍在）
        assertEquals(TaskStates.SUCCEEDED, viewBAfter.state());
        assertNotNull(viewBAfter.result());
        assertNotNull(viewBAfter.result().inputDigest());
    }

    // ------------------------------------------------------- helpers

    private TaskDraft task(String id, String goal, List<String> deps) {
        return new TaskDraft(id, goal, deps, List.of(), "deliver-" + id,
                List.of("self-check-" + id), null, "DELEGATED", true);
    }

    private String savePlan(Chain chain, List<TaskDraft> tasks) {
        return planService.saveDraft(chain.runId(),
                new PlanDraft(null, null, "MA-06 验收计划", tasks)).planId();
    }

    private TaskDispatchService.DispatchResult dispatch(Chain chain, String planId,
                                                        List<String> ids, String key) {
        return dispatchService.register(chain.runId(), planId, 1, ids, key);
    }

    private String state(String planId, String taskId) {
        return taskRepository.findByPlanIdAndTaskId(planId, taskId)
                .map(TaskEntity::getState).orElse("<missing>");
    }

    private TaskView view(String planId, String taskId) {
        TaskEntity task = taskRepository.findByPlanIdAndTaskId(planId, taskId)
                .orElseThrow();
        return queryService.view(task);
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
        String projectId = "proj-ma06-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA06", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-ma06-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, provider + "-" + model,
                provider, model, "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));
        String threadId = IdGen.next("thr_ma06_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA06 会话",
                profileId, now, now));
        String runId = IdGen.next("run_ma06_");
        String turnId = IdGen.next("turn_ma06_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest-ma06", 30, 120, 320_000,
                now.plusSeconds(600), now, now));
        return new Chain(projectId, threadId, turnId, runId);
    }

    private record Chain(String projectId, String threadId, String turnId, String runId) {
    }
}
