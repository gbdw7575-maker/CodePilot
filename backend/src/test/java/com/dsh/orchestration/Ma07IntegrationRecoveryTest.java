package com.dsh.orchestration;

import com.dsh.common.IdGen;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.orchestration.plan.PlanService;
import com.dsh.orchestration.task.TaskDispatchService;
import com.dsh.orchestration.task.TaskStates;
import com.dsh.orchestration.task.TaskWorkerExecutor;
import com.dsh.orchestration.task.TaskViews.TaskView;
import com.dsh.orchestration.task.TaskQueryService;
import com.dsh.storage.IntegrationRecordEntity;
import com.dsh.storage.IntegrationRecordRepository;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.TaskRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.dsh.workspace.WorkspaceLease;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-07 AT-13 可恢复集成测试（规范 §8.3 合并协议）。
 *
 * <p>所有必需任务 SUCCEEDED 后触发 IntegrationService.integrate：记录 baseDigest/输入产物/
 * fencingToken/state/appliedPaths journal，复用 WorkspaceMerge.mergeAll 做多写者三方合并；
 * 冲突整批不应用；中断恢复时 IN_PROGRESS record 工作区未被改则安全重做，已部分写入则
 * RECOVERED_BLOCKED 阻塞报告，不静默留半份补丁。</p>
 *
 * <p>全部离线 Fake（fake-ma07 脚本），不产生真实模型费用。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "dsh.policy.require-for-side-effects=NONE")
class Ma07IntegrationRecoveryTest {

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
    @Autowired private WorkspaceManager workspaceManager;
    @Autowired private IntegrationService integrationService;
    @Autowired private IntegrationRecordRepository integrationRecordRepository;

    @TempDir Path projectRoot;

    // ---------------------------------- AT-13 集成 APPLIED

    @Test
    void integrationAppliedWhenAllRequiredSucceeded() {
        Chain chain = buildChain("fake-ma07", "fake");
        // 准备 MAIN Run 工作区（integrate 需要父工作区 lease 存在）
        ProjectEntity project = projectRepository.findById(chain.projectId()).orElseThrow();
        WorkspaceLease rootLease = workspaceManager.prepare(chain.runId(), project);

        String planId = savePlan(chain, List.of(
                task("A", "MA07WORKER-NOUPSTREAM 独立任务A", List.of()),
                task("B", "MA07WORKER-NOUPSTREAM 独立任务B", List.of())));

        assertTrue(dispatch(chain, planId, List.of("A", "B"),
                "rk-at13-applied-" + UUID.randomUUID()).accepted());

        // 等待 A、B 都 SUCCEEDED 并触发集成
        awaitUntil(Duration.ofSeconds(30),
                () -> TaskStates.SUCCEEDED.equals(state(planId, "A"))
                        && TaskStates.SUCCEEDED.equals(state(planId, "B")));

        // 集成由 registerPostCompletion afterCommit 触发；等待 IntegrationRecord APPLIED
        awaitUntil(Duration.ofSeconds(30), () -> {
            IntegrationRecordEntity rec = integrationRecordRepository
                    .findFirstByRootRunIdOrderByCreatedAtDesc(chain.runId()).orElse(null);
            return rec != null && IntegrationService.STATE_APPLIED.equals(rec.getState());
        });

        IntegrationRecordEntity record = integrationRecordRepository
                .findFirstByRootRunIdOrderByCreatedAtDesc(chain.runId()).orElseThrow();
        assertEquals(IntegrationService.STATE_APPLIED, record.getState());
        assertNotNull(record.getResultDigest(), "APPLIED 应有 resultDigest");
        assertNotNull(record.getFencingToken(), "应有 fencingToken 落库");
        // 父工作区应包含 worker 写入的 task-output.txt（集成合并产物）
        Path outputFile = rootLease.dir().resolve("task-output.txt");
        assertTrue(Files.isRegularFile(outputFile),
                "父工作区应含集成后的 task-output.txt");
    }

    // ---------------------------------- AT-13 中断恢复（安全重做）

    @Test
    void integrationRecoversFromInProgressWithCleanWorkspace() {
        Chain chain = buildChain("fake-ma07", "fake");
        ProjectEntity project = projectRepository.findById(chain.projectId()).orElseThrow();
        WorkspaceLease rootLease = workspaceManager.prepare(chain.runId(), project);
        String baseDigest = workspaceManager.currentDigest(rootLease);

        // 手动构造一个 IN_PROGRESS record（appliedPaths 空，baseDigest=父摘要）
        // 模拟上次集成崩溃在本切片窗口，但工作区未被改
        Instant now = Instant.now();
        String staleIntegrationId = IdGen.next("intg_");
        IntegrationRecordEntity inProgress = new IntegrationRecordEntity(
                staleIntegrationId, chain.runId(), chain.projectId(), baseDigest, "[]",
                null, IntegrationService.STATE_IN_PROGRESS, "[]", null,
                chain.runId() + ":" + now.minusSeconds(60).toEpochMilli(), now.minusSeconds(60), now);
        integrationRecordRepository.save(inProgress);

        // 无 SUCCEEDED 子任务 → integrate 走幂等/恢复路径
        // 调 integrate：handleRecovery 发现 appliedPaths 空 + 工作区摘要==baseDigest → 安全重做
        // 旧 record 标 RECOVERED_BLOCKED，外层新建 record（无写者→APPLIED 跳过）
        IntegrationService.IntegrationOutcome outcome = integrationService.integrate(chain.runId());

        // 应走重做路径：旧 record RECOVERED_BLOCKED，新 record APPLIED（无写者）
        assertEquals(IntegrationService.STATE_APPLIED, outcome.state(),
                "安全重做后应 APPLIED，实际: " + outcome.state() + " msg=" + outcome.message());

        // 旧 IN_PROGRESS record 应被标记 RECOVERED_BLOCKED（不再 IN_PROGRESS）
        IntegrationRecordEntity oldRecord = integrationRecordRepository
                .findById(staleIntegrationId).orElseThrow();
        assertEquals(IntegrationService.STATE_RECOVERED_BLOCKED, oldRecord.getState(),
                "旧 IN_PROGRESS 应归档为 RECOVERED_BLOCKED");
    }

    // ---------------------------------- AT-13 中断不可恢复（已部分写入）→ RECOVERED_BLOCKED

    @Test
    void integrationBlockedWhenInProgressWithPartialWrite() {
        Chain chain = buildChain("fake-ma07", "fake");
        ProjectEntity project = projectRepository.findById(chain.projectId()).orElseThrow();
        WorkspaceLease rootLease = workspaceManager.prepare(chain.runId(), project);
        String baseDigest = workspaceManager.currentDigest(rootLease);

        // 构造 IN_PROGRESS record 且 appliedPaths 非空（模拟已部分写入）
        Instant now = Instant.now();
        String staleIntegrationId = IdGen.next("intg_");
        String appliedPathsJson;
        try {
            appliedPathsJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(List.of("task-output.txt"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        IntegrationRecordEntity inProgress = new IntegrationRecordEntity(
                staleIntegrationId, chain.runId(), chain.projectId(), baseDigest, "[]",
                null, IntegrationService.STATE_IN_PROGRESS, appliedPathsJson, null,
                chain.runId() + ":" + now.minusSeconds(60).toEpochMilli(), now.minusSeconds(60), now);
        integrationRecordRepository.save(inProgress);

        IntegrationService.IntegrationOutcome outcome = integrationService.integrate(chain.runId());

        // appliedPaths 非空 → 不可安全恢复 → RECOVERED_BLOCKED
        assertEquals(IntegrationService.STATE_RECOVERED_BLOCKED, outcome.state(),
                "已部分写入应 RECOVERED_BLOCKED，实际: " + outcome.state() + " msg=" + outcome.message());
    }

    // ------------------------------------------------------- helpers

    private TaskDraft task(String id, String goal, List<String> deps) {
        return new TaskDraft(id, goal, deps, List.of(), "deliver-" + id,
                List.of("self-check-" + id), null, "DELEGATED", true);
    }

    private String savePlan(Chain chain, List<TaskDraft> tasks) {
        return planService.saveDraft(chain.runId(),
                new PlanDraft(null, null, "MA-07 集成计划", tasks)).planId();
    }

    private TaskDispatchService.DispatchResult dispatch(Chain chain, String planId,
                                                        List<String> ids, String key) {
        return dispatchService.register(chain.runId(), planId, 1, ids, key);
    }

    private String state(String planId, String taskId) {
        return taskRepository.findByPlanIdAndTaskId(planId, taskId)
                .map(TaskEntity::getState).orElse("<missing>");
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
        String projectId = "proj-ma07int-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA07I", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-ma07int-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, provider + "-" + model,
                provider, model, "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));
        String threadId = IdGen.next("thr_ma07int_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA07I 会话",
                profileId, now, now));
        String runId = IdGen.next("run_ma07int_");
        String turnId = IdGen.next("turn_ma07int_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest-ma07int", 30, 120, 320_000,
                now.plusSeconds(600), now, now));
        return new Chain(projectId, threadId, turnId, runId);
    }

    private record Chain(String projectId, String threadId, String turnId, String runId) {
    }
}
