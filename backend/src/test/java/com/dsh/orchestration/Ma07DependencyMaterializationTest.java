package com.dsh.orchestration;

import com.dsh.application.ArtifactStore;
import com.dsh.common.IdGen;
import com.dsh.orchestration.plan.PlanDtos.InputRef;
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
import com.dsh.storage.TaskAttemptEntity;
import com.dsh.storage.TaskAttemptRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.dsh.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-07 AT-12 依赖产物物化测试（规范 §8.1）。
 *
 * <p>子任务启动前把上游 SUCCEEDED 产物实际物化到子工作区 upstream/ 目录，使下游能真正
 * 读取使用而非仅凭 prompt 摘要。多依赖同名产物内容不同 → INPUT_CONFLICT → 任务 BLOCKED
 * （§10.2 输入冲突/需求不明），不启动子 Run。</p>
 *
 * <p>全部离线 Fake（fake-ma07 脚本），不产生真实模型费用。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "dsh.policy.require-for-side-effects=NONE")
class Ma07DependencyMaterializationTest {

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
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private WorkspaceManager workspaceManager;
    @Autowired private ArtifactStore artifactStore;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @TempDir Path projectRoot;

    // ---------------------------------- AT-12 物化 + 下游 SUCCEEDED

    @Test
    void upstreamArtifactMaterializedAndDownstreamSucceeds() {
        Chain chain = buildChain("fake-ma07", "fake");
        String planId = savePlan(chain, List.of(
                task("A", "MA07WORKER-PASS 上游任务", List.of(), List.of()),
                taskWithInputRefs("B", "MA07WORKER-PASS 下游任务", List.of("A"),
                        List.of(new InputRef("A", "task-output.txt")))));

        assertTrue(dispatch(chain, planId, List.of("A"),
                "rk-at12-pass-a-" + UUID.randomUUID()).accepted());
        awaitUntil(Duration.ofSeconds(30),
                () -> TaskStates.SUCCEEDED.equals(state(planId, "A")));

        // A SUCCEEDED 后下游 B 可被派发（promote 释放）
        assertTrue(dispatch(chain, planId, List.of("B"),
                "rk-at12-pass-b-" + UUID.randomUUID()).accepted());
        awaitUntil(Duration.ofSeconds(30),
                () -> TaskStates.SUCCEEDED.equals(state(planId, "B")));

        // B SUCCEEDED 且证据有效（inputDigest 非空：依赖 A 的 outputDigest）
        TaskView viewB = view(planId, "B");
        assertEquals(TaskStates.SUCCEEDED, viewB.state());
        assertTrue(viewB.evidenceValid());
        assertNotNull(viewB.result().inputDigest(), "B 依赖 A，应有 inputDigest");

        // AT-12：B 的子工作区 upstream/ 目录含 A 的产物文件（物化成功才 SUCCEEDED）
        TaskResultEntity resultB = resultRepository
                .findTopByPlanIdAndTaskIdOrderByCreatedAtDesc(planId, "B").orElseThrow();
        String childRunIdB = resultB.getChildRunId();
        assertNotNull(childRunIdB);
        RunEntity childRunB = runRepository.findById(childRunIdB).orElseThrow();
        String workspaceRefB = childRunB.getWorkspaceRef();
        assertNotNull(workspaceRefB);
        Path upstreamDir = workspaceManager.resolveByRef(workspaceRefB).dir()
                .resolve(DependencyMaterializationService.UPSTREAM_DIR);
        assertTrue(Files.isDirectory(upstreamDir),
                "B 工作区应有 upstream/ 目录（物化 A 的产物）");
        // A 的产物是 diff.patch（computeDiff 保存的 patch artifact name）
        Path materialized = upstreamDir.resolve("diff.patch");
        assertTrue(Files.isRegularFile(materialized),
                "B 工作区 upstream/diff.patch 应存在（物化 A 的 patch 产物）");
    }

    // ---------------------------------- AT-12 多依赖同名冲突 → BLOCKED

    @Test
    void dependencyNameConflictBlocked() throws Exception {
        Chain chain = buildChain("fake-ma07", "fake");
        // 两个上游 A1、A2 各自产出同名 task-output.txt 但内容不同——直接构造产物绕过 worker
        String planId = savePlan(chain, List.of(
                task("A1", "上游1", List.of(), List.of()),
                task("A2", "上游2", List.of(), List.of()),
                taskWithInputRefs("B", "下游依赖 A1 A2", List.of("A1", "A2"),
                        List.of(new InputRef("A1", "task-output.txt"),
                                new InputRef("A2", "task-output.txt")))));

        // 手动构造 A1、A2 的子 Run + SUCCEEDED result + 同名不同内容产物
        String a1RunId = IdGen.next("sub_run_");
        String a2RunId = IdGen.next("sub_run_");
        RunEntity rootRun = runRepository.findById(chain.runId()).orElseThrow();
        // A1/A2 不需要真正执行，直接构造 result 行使其 artifactRefs 指向同名不同内容产物
        // 先准备工作区（A1/A2 各自一个）以便 artifactStore.save 能落盘
        var project = projectRepository.findById(chain.projectId()).orElseThrow();
        var leaseA1 = workspaceManager.prepare(a1RunId, project);
        var leaseA2 = workspaceManager.prepare(a2RunId, project);
        // 建 A1/A2 的 RunEntity（parentRunId=chain.runId 同根 Run 族，authorizeArtifactRefs 通过）
        Instant now = Instant.now();
        runRepository.save(new RunEntity(a1RunId, chain.turnId(), chain.runId(), "CODER",
                "COMPLETED", "COMPLETED", 1, leaseA1.workspaceId(), "digest-a1",
                8, 30, 60000, now.plusSeconds(600), now, now));
        runRepository.save(new RunEntity(a2RunId, chain.turnId(), chain.runId(), "CODER",
                "COMPLETED", "COMPLETED", 1, leaseA2.workspaceId(), "digest-a2",
                8, 30, 60000, now.plusSeconds(600), now, now));

        // 两个同名 "out.txt" 但内容不同的产物
        var art1 = artifactStore.save(a1RunId, chain.projectId(), "out.txt",
                "text/plain", "content-from-A1".getBytes(), true);
        var art2 = artifactStore.save(a2RunId, chain.projectId(), "out.txt",
                "text/plain", "content-from-A2-different".getBytes(), true);

        // 构造 A1/A2 的 attempt + result（SUCCEEDED，artifactRefs 指向各自产物）
        String a1Attempt = IdGen.next("att_");
        saveAttemptAndResult(a1Attempt, planId, "A1", a1RunId, art1.getId(), now);
        String a2Attempt = IdGen.next("att_");
        saveAttemptAndResult(a2Attempt, planId, "A2", a2RunId, art2.getId(), now);

        // 标记 A1/A2 任务 SUCCEEDED（物化 collectUpstreamArtifactRefs 只取 SUCCEEDED result）
        taskRepository.findByPlanIdAndTaskId(planId, "A1").ifPresent(t -> {
            t.setState(TaskStates.SUCCEEDED);
            t.setUpdatedAt(now);
            taskRepository.save(t);
        });
        taskRepository.findByPlanIdAndTaskId(planId, "A2").ifPresent(t -> {
            t.setState(TaskStates.SUCCEEDED);
            t.setUpdatedAt(now);
            taskRepository.save(t);
        });

        // 派发 B：物化 A1+A2 同名 out.txt 不同内容 → INPUT_CONFLICT → BLOCKED
        assertTrue(dispatch(chain, planId, List.of("B"),
                "rk-at12-conflict-" + UUID.randomUUID()).accepted());
        awaitUntil(Duration.ofSeconds(30),
                () -> TaskStates.BLOCKED.equals(state(planId, "B"))
                        || TaskStates.FAILED.equals(state(planId, "B")));

        // AT-12：B 物化冲突 → BLOCKED（不启动子 Run）
        TaskView viewB = view(planId, "B");
        assertEquals(TaskStates.BLOCKED, viewB.state(),
                "多依赖同名产物冲突应 BLOCKED，实际: " + viewB.state());
        // attempt/result 如实记 FAILED + INPUT_CONFLICT
        assertNotNull(viewB.result());
        assertEquals("INPUT_CONFLICT", viewB.result().errorCode());
    }

    // ------------------------------------------------------- helpers

    private TaskDraft task(String id, String goal, List<String> deps,
                           List<InputRef> inputRefs) {
        return new TaskDraft(id, goal, deps, inputRefs, "deliver-" + id,
                List.of("self-check-" + id), null, "DELEGATED", true);
    }

    private TaskDraft taskWithInputRefs(String id, String goal, List<String> deps,
                                       List<InputRef> inputRefs) {
        return task(id, goal, deps, inputRefs);
    }

    private String savePlan(Chain chain, List<TaskDraft> tasks) {
        return planService.saveDraft(chain.runId(),
                new PlanDraft(null, null, "MA-07 物化计划", tasks)).planId();
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

    @org.springframework.transaction.annotation.Transactional
    public void saveAttemptAndResult(String attemptId, String planId, String taskId,
                                     String childRunId, String artifactRef, Instant now) {
        TaskAttemptEntity attempt = new TaskAttemptEntity(attemptId, planId, taskId, 1,
                childRunId, 1, "test", null, null, null, TaskStates.SUCCEEDED, now, now);
        attemptRepository.save(attempt);
        String artifactRefsJson;
        try {
            artifactRefsJson = objectMapper.writeValueAsString(List.of(artifactRef));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        TaskResultEntity result = new TaskResultEntity(attemptId, planId, taskId, 1,
                childRunId, TaskStates.SUCCEEDED, null, null, artifactRefsJson, artifactRef,
                "digest-" + taskId, null, null, 0, 0, 0, 0, now);
        resultRepository.save(result);
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
        String projectId = "proj-ma07-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA07", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-ma07-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, provider + "-" + model,
                provider, model, "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));
        String threadId = IdGen.next("thr_ma07_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA07 会话",
                profileId, now, now));
        String runId = IdGen.next("run_ma07_");
        String turnId = IdGen.next("turn_ma07_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest-ma07", 30, 120, 320_000,
                now.plusSeconds(600), now, now));
        return new Chain(projectId, threadId, turnId, runId);
    }

    private record Chain(String projectId, String threadId, String turnId, String runId) {
    }
}
