package com.dsh.orchestration;

import com.dsh.application.DshProperties;
import com.dsh.common.IdGen;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.orchestration.plan.PlanService;
import com.dsh.orchestration.task.TaskSchedulingService;
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
 * MA-11c-1 回退开关：{@code dsh.orchestration.retry-budget-safety-factor=0} 显式关闭收口，
 * 恢复 MA-08 既有行为（{@code BUDGET_LIMIT} 一律重排队）。用于验证回退路径可用。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "dsh.policy.require-for-side-effects=NONE",
        "dsh.orchestration.retry-budget-safety-factor=0"
})
class Ma11RetryBudgetGuardDisabledTest {

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
    void factorZeroRestoresLegacyRequeue() {
        assertEquals(0.0, properties.orchestration().retryBudgetSafetyFactor(), 0.0001,
                "显式配 0 应保留为 0（关闭收口），不被兜底成 1.5");
        Chain chain = buildChain();
        String planId = planService.saveDraft(chain.runId(),
                new PlanDraft(null, null, "MA-11c-1 关闭收口", List.of(
                        new TaskDraft("A", "独立任务A", List.of(), List.of(), "deliver-A",
                                List.of("self-check-A"), null, "DELEGATED", true)))).planId();
        TaskEntity taskA = taskRepository.findByPlanIdAndTaskId(planId, "A").orElseThrow();
        Instant now = Instant.now();
        String childRunId = IdGen.next("sub_run_ma11c1off_");
        runRepository.save(new RunEntity(childRunId, chain.turnId(), chain.runId(), "CODER",
                "FAILED", "BUDGET_LIMIT", 1, IdGen.next("ws_"), "digest-off",
                8, 30, 60_000, now.plusSeconds(600), now, now));
        RunEntity parent = runRepository.findById(chain.runId()).orElseThrow();
        parent.setBudgetModelRequestsUsed(30); // 可分配 mr=6 < 12，收口开启时会落 FAILED
        runRepository.save(parent);
        TaskAttemptEntity attempt = new TaskAttemptEntity(
                IdGen.next("att_"), planId, "A", taskA.getTaskVersion(), childRunId, 1,
                "worker-pool", now.plusSeconds(60), 1L, null,
                "RUNNING", now.minusSeconds(5), now.minusSeconds(5));
        attemptRepository.save(attempt);
        taskA.setState("RUNNING");
        taskRepository.save(taskA);

        schedulingService.requeue(attempt.getAttemptId(), "BUDGET_LIMIT");

        TaskAttemptEntity after = attemptRepository.findById(attempt.getAttemptId()).orElseThrow();
        assertEquals("QUEUED", after.getState(), "系数为 0 时应恢复 MA-08 的重排队行为");
    }

    private Chain buildChain() {
        Instant now = Instant.now();
        String projectId = "proj-ma11off-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA11OFF", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-ma11off-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, "fake-fake-ma11off",
                "fake", "fake-ma11off", "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));
        String threadId = IdGen.next("thr_ma11off_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA11OFF 会话",
                profileId, now, now));
        String runId = IdGen.next("run_ma11off_");
        String turnId = IdGen.next("turn_ma11off_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest-ma11off", 40, 200, 400_000,
                now.plusSeconds(600), now, now));
        return new Chain(threadId, turnId, runId);
    }

    private record Chain(String threadId, String turnId, String runId) {
    }
}
