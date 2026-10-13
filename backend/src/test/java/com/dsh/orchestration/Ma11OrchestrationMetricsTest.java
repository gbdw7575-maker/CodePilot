package com.dsh.orchestration;

import com.dsh.common.IdGen;
import com.dsh.evals.EvalOrchestrationMetrics;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.orchestration.plan.PlanService;
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
 * MA-11 编排指标只读提取器测试（AT-18/§12）。
 *
 * <p>编排开关开启，直接持久化：根 Run + 2 个直属子 Run、2 条 CONFLICTS（外加 1 条 APPLIED
 * 不计冲突）、计划任务 A 两次 attempt（返工 1）与任务 B 一次 attempt（不返工）。
 * 期望 childRunCount=2、conflictCount=2、reworkAttempts=1；不存在的根 Run 返回全 0。
 * 全部为仓储只读统计，不调度、不产生模型调用。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "dsh.orchestration.enabled=true",
        "dsh.policy.require-for-side-effects=NONE"
})
class Ma11OrchestrationMetricsTest {

    @Autowired private EvalOrchestrationMetrics metrics;
    @Autowired private PlanService planService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ModelProfileRepository profileRepository;
    @Autowired private ThreadRepository threadRepository;
    @Autowired private TurnRepository turnRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private IntegrationRecordRepository integrationRecordRepository;

    @TempDir Path projectRoot;

    @Test
    void collectsChildrenConflictsAndReworkFromPersistedRows() {
        Instant now = Instant.now();
        String projectId = "proj-ma11m-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA11M", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-ma11m-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, "fake-fake-ma11",
                "fake", "fake-ma11", "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));
        String rootThreadId = IdGen.next("thr_ma11m_");
        threadRepository.save(new ThreadEntity(rootThreadId, projectId, "MA11M 根会话",
                profileId, now, now));
        String rootRunId = IdGen.next("run_ma11m_");
        String rootTurnId = IdGen.next("turn_ma11m_");
        turnRepository.save(new TurnEntity(rootTurnId, rootThreadId, rootRunId, "父任务",
                "RUNNING", "PENDING", rootThreadId, now, now));
        runRepository.save(new RunEntity(rootRunId, rootTurnId, null, "MAIN", "COMPLETED",
                "ANSWER_COMPLETE", 1, IdGen.next("ws_"), "digest-ma11m", 24, 80, 200_000,
                now.plusSeconds(1200), now, now));

        // 2 个直属子 Run
        persistChild(projectId, profileId, rootRunId, "CODER", now);
        persistChild(projectId, profileId, rootRunId, "REVIEWER", now);

        // 计划：A 无依赖，B 依赖 A
        String planId = planService.saveDraft(rootRunId, new PlanDraft(null, null, "MA-11 指标计划",
                List.of(task("A", "独立任务 A"), task("B", "依赖任务 B", "A")))).planId();
        TaskEntity taskA = taskRepository.findByPlanIdAndTaskId(planId, "A").orElseThrow();
        TaskEntity taskB = taskRepository.findByPlanIdAndTaskId(planId, "B").orElseThrow();

        // A 重排一次：attemptNo 1 与 2（返工 +1）；B 仅首 attempt（不返工）
        attemptRepository.save(new TaskAttemptEntity(IdGen.next("att_"), planId, "A",
                taskA.getTaskVersion(), null, 1, null, null, null, null,
                "QUEUED", now.minusSeconds(30), now.minusSeconds(30)));
        attemptRepository.save(new TaskAttemptEntity(IdGen.next("att_"), planId, "A",
                taskA.getTaskVersion(), null, 2, "worker-pool", now.plusSeconds(60), 2L, null,
                "RUNNING", now.minusSeconds(10), now.minusSeconds(10)));
        attemptRepository.save(new TaskAttemptEntity(IdGen.next("att_"), planId, "B",
                taskB.getTaskVersion(), null, 1, null, null, null, null,
                "QUEUED", now.minusSeconds(20), now.minusSeconds(20)));

        // 2 条 CONFLICTS（整批不应用）+ 1 条 APPLIED（不计冲突）
        integrationRecordRepository.save(integration(rootRunId, projectId,
                "CONFLICTS", "int-conf-1", now.minusSeconds(40)));
        integrationRecordRepository.save(integration(rootRunId, projectId,
                "CONFLICTS", "int-conf-2", now.minusSeconds(20)));
        integrationRecordRepository.save(integration(rootRunId, projectId,
                "APPLIED", "int-applied", now.minusSeconds(5)));

        EvalOrchestrationMetrics.Snapshot snapshot = metrics.collect(rootRunId);
        assertEquals(2, snapshot.childRunCount(), "直属子 Run 数");
        assertEquals(2, snapshot.conflictCount(), "仅 CONFLICTS 状态计入冲突，APPLIED 不计");
        assertEquals(1, snapshot.reworkAttempts(), "任务 A 返工 1 次，任务 B 首派不返工");

        // 不存在的根 Run：三项全 0（等价 SINGLE 运行口径）
        EvalOrchestrationMetrics.Snapshot empty = metrics.collect(IdGen.next("run_missing_"));
        assertEquals(0, empty.childRunCount());
        assertEquals(0, empty.conflictCount());
        assertEquals(0, empty.reworkAttempts());
    }

    private void persistChild(String projectId, String profileId, String rootRunId,
                              String role, Instant now) {
        String threadId = IdGen.next("sub_thr_ma11m_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "子会话/" + role,
                profileId, now, now));
        String runId = IdGen.next("sub_run_ma11m_");
        String turnId = IdGen.next("sub_turn_ma11m_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "worker 任务",
                "COMPLETED", "ANSWER_COMPLETE", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, rootRunId, role, "COMPLETED",
                "ANSWER_COMPLETE", 1, IdGen.next("ws_"), "digest-child", 8, 20, 60_000,
                now.plusSeconds(600), now, now));
    }

    private IntegrationRecordEntity integration(String rootRunId, String projectId,
                                                String state, String integrationId, Instant at) {
        return new IntegrationRecordEntity(integrationId, rootRunId, projectId,
                "base-digest", "[]", "CONFLICTS".equals(state) ? null : "result-digest",
                state, "CONFLICTS".equals(state) ? "[]" : "[\"src/a.txt\"]",
                null, rootRunId + ":" + at.toEpochMilli(), at, at);
    }

    private TaskDraft task(String id, String goal, String... deps) {
        return new TaskDraft(id, goal, List.of(deps), List.of(), "deliver-" + id,
                List.of("self-check-" + id), null, "DELEGATED", true);
    }
}
