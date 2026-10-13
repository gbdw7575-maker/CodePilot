package com.dsh.orchestration.plan;

import com.dsh.common.IdGen;
import com.dsh.orchestration.plan.PlanDtos.InputRef;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.ResourceScope;
import com.dsh.orchestration.plan.PlanDtos.SavedPlan;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.storage.PlanRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-02 G-BACKEND + AT-02：PlanService 计划草案持久化、revision 乐观并发与任务图语义校验。
 * 不启动模型、不创建子 Run；任务状态在本切片只会是 DRAFT，活跃态护栏用直接写库模拟。
 */
@SpringBootTest
class Ma02PlanServiceTest {

    @Autowired
    private PlanService planService;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private RunRepository runRepository;
    @Autowired
    private TurnRepository turnRepository;
    @Autowired
    private ThreadRepository threadRepository;
    @Autowired
    private ProjectRepository projectRepository;

    @TempDir
    Path projectRoot;

    private String runId;

    @BeforeEach
    void setUpChain() {
        Instant now = Instant.now();
        String projectId = "proj-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA02", projectRoot.toString(),
                true, now, now));
        String threadId = IdGen.next("thr_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA02",
                "prof-" + UUID.randomUUID(), now, now));
        runId = IdGen.next("run_");
        String turnId = IdGen.next("turn_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING",
                1, IdGen.next("ws_"), "a".repeat(64), 24, 80, 200_000, now.plusSeconds(600), now, now));
    }

    private TaskDraft task(String id, List<String> deps) {
        return new TaskDraft(id, "目标-" + id, deps, List.of(), "交付物-" + id,
                List.of("验收-" + id), null, "DELEGATED", true);
    }

    @Test
    void createDraftAssignsPlanIdRevision1AndAllTasksDraft() {
        SavedPlan saved = planService.saveDraft(runId,
                new PlanDraft(null, null, "登录改造", List.of(task("A", List.of()), task("B", List.of("A")))));

        assertTrue(saved.planId().startsWith("plan_"));
        assertEquals(1, saved.revision());
        assertEquals("DRAFT", saved.status());
        assertEquals(2, saved.tasks().size());
        assertTrue(saved.tasks().stream().allMatch(t -> t.state().equals("DRAFT") && t.taskVersion() == 1));
        assertEquals(2, taskRepository.findByPlanIdOrderByTaskIdAsc(saved.planId()).size());
    }

    @Test
    void updateDraftReplacesTasksAndIncrementsRevision() {
        SavedPlan first = planService.saveDraft(runId,
                new PlanDraft(null, null, null, List.of(task("A", List.of()))));

        SavedPlan second = planService.saveDraft(runId, new PlanDraft(first.planId(), 1,
                "修订目标", List.of(task("A", List.of()), task("B", List.of("A")))));

        assertEquals(2, second.revision());
        assertEquals("修订目标", second.goal());
        assertEquals(2, taskRepository.findByPlanIdOrderByTaskIdAsc(first.planId()).size());
        var plan = planRepository.findById(first.planId()).orElseThrow();
        assertEquals(2, plan.getRevision());
    }

    @Test
    void graphViolationsAreRejectedWithFrozenReasonCodes() {
        assertRejected("DUPLICATE_TASK_ID",
                new PlanDraft(null, null, null, List.of(task("A", List.of()), task("A", List.of()))));
        assertRejected("SELF_DEPENDENCY",
                new PlanDraft(null, null, null, List.of(task("A", List.of("A")))));
        assertRejected("UNKNOWN_DEPENDENCY",
                new PlanDraft(null, null, null,
                        List.of(task("A", List.of()), task("B", List.of("X")))));
        assertRejected("CYCLE_DETECTED",
                new PlanDraft(null, null, null,
                        List.of(task("A", List.of("B")), task("B", List.of("A")))));
        TaskDraft badRef = new TaskDraft("B", "目标", List.of("A"),
                List.of(new InputRef("Z", "deliverable")), "交付", List.of("验收"),
                null, "DELEGATED", true);
        assertRejected("INVALID_INPUT_REF",
                new PlanDraft(null, null, null, List.of(task("A", List.of()), badRef)));

        List<TaskDraft> thirteen = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            thirteen.add(task("T" + i, List.of()));
        }
        assertRejected("TASK_GRAPH_TOO_LARGE", new PlanDraft(null, null, null, thirteen));
    }

    @Test
    void fieldLevelViolationsAreRejected() {
        assertRejected("VALIDATION_FAILED",
                new PlanDraft(null, null, null, List.of())); // 空任务
        assertRejected("VALIDATION_FAILED",
                new PlanDraft(null, null, null, List.of(
                        new TaskDraft("A", "  ", List.of(), List.of(), "d", List.of("a"),
                                null, "DELEGATED", true)))); // 空白 goal
        assertRejected("VALIDATION_FAILED",
                new PlanDraft(null, null, null, List.of(
                        new TaskDraft("A", "g", List.of(), List.of(), "d", List.of(),
                                null, "DELEGATED", true)))); // 缺验收
        assertRejected("VALIDATION_FAILED",
                new PlanDraft(null, null, null, List.of(
                        new TaskDraft("A B", "g", List.of(), List.of(), "d", List.of("a"),
                                null, "DELEGATED", true)))); // taskId 含空格
        assertRejected("VALIDATION_FAILED",
                new PlanDraft(null, null, null, List.of(
                        new TaskDraft("A", "g", List.of(), List.of(), "d", List.of("a"),
                                null, "PARALLEL", true)))); // 未知 executionMode
        assertRejected("TASK_GRAPH_TOO_LARGE",
                new PlanDraft(null, null, null, List.of(
                        new TaskDraft("A", "g", List.of(), List.of(), "d",
                                List.of("a", "b", "c", "d", "e", "f", "g", "h", "i"),
                                null, "DELEGATED", true)))); // 9 条验收
    }

    @Test
    void revisionMustBePairAndCurrent() {
        SavedPlan first = planService.saveDraft(runId,
                new PlanDraft(null, null, null, List.of(task("A", List.of()))));

        // 只给 planId 或只给 expectedRevision
        assertRejected("REVISION_WITHOUT_PLAN_ID",
                new PlanDraft(first.planId(), null, null, List.of(task("A", List.of()))));
        assertRejected("REVISION_WITHOUT_PLAN_ID",
                new PlanDraft(null, 1, null, List.of(task("A", List.of()))));
        // 旧 revision
        assertRejected("REVISION_MISMATCH",
                new PlanDraft(first.planId(), 7, null, List.of(task("A", List.of()))));
        // revision=0 非法
        assertRejected("VALIDATION_FAILED",
                new PlanDraft(first.planId(), 0, null, List.of(task("A", List.of()))));
    }

    @Test
    void planIsScopedToRootRun() {
        SavedPlan first = planService.saveDraft(runId,
                new PlanDraft(null, null, null, List.of(task("A", List.of()))));

        String otherRun = createSecondRun();
        PlanServiceException e = assertThrows(PlanServiceException.class, () ->
                planService.saveDraft(otherRun,
                        new PlanDraft(first.planId(), 1, null, List.of(task("A", List.of())))));
        assertEquals("PLAN_NOT_FOUND", e.reasonCode(), "模型传入的 planId 不得跨 Run 引用");
    }

    @Test
    void activeTaskCannotBeSilentlyMutatedOrRemoved() {
        SavedPlan first = planService.saveDraft(runId,
                new PlanDraft(null, null, null,
                        List.of(task("A", List.of()), task("B", List.of("A")))));
        // 模拟 MA-05 之后 A 已进入 RUNNING（当前产品流程不会产生，前向护栏）
        TaskEntity runningA = taskRepository.findByPlanIdAndTaskId(first.planId(), "A").orElseThrow();
        runningA.setState("RUNNING");
        taskRepository.saveAndFlush(runningA);

        TaskDraft changedA = new TaskDraft("A", "被篡改的目标", List.of(), List.of(),
                "交付物-A", List.of("验收-A"), null, "DELEGATED", true);
        assertRejected("TASK_LOCKED",
                new PlanDraft(first.planId(), 1, null, List.of(changedA, task("B", List.of("A")))));
        assertRejected("TASK_LOCKED",
                new PlanDraft(first.planId(), 1, null, List.of(task("B", List.of())))); // 移除 A

        // A 的冻结字段保持不变，允许修订 DRAFT 任务 B
        TaskDraft bChanged = task("B", List.of("A"));
        TaskDraft aSame = task("A", List.of());
        SavedPlan second = planService.saveDraft(runId,
                new PlanDraft(first.planId(), 1, null, List.of(aSame, bChanged)));
        assertEquals(2, second.revision());
    }

    @Test
    void inputRefsAndResourceScopeRoundTrip() {
        TaskDraft b = new TaskDraft("B", "实现", List.of("A"),
                List.of(new InputRef("A", "deliverable")), "补丁",
                List.of("契约对齐"), new ResourceScope(List.of("docs/"), List.of("src/")),
                "INLINE", false);
        SavedPlan saved = planService.saveDraft(runId,
                new PlanDraft(null, null, null, List.of(task("A", List.of()), b)));

        TaskEntity row = taskRepository.findByPlanIdOrderByTaskIdAsc(saved.planId()).stream()
                .filter(t -> t.getTaskId().equals("B")).findFirst().orElseThrow();
        assertTrue(row.getInputRefs().contains("\"output\":\"deliverable\""));
        assertTrue(row.getResourceScope().contains("src/"));
        assertEquals("INLINE", row.getExecutionMode());
        // MA-02：任务状态只能是 DRAFT；RUNNING 等推进留给调度切片，模型与 update_plan 都不能写入
        assertEquals("DRAFT", row.getState());
    }

    private String createSecondRun() {
        Instant now = Instant.now();
        String projectId = "proj2-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA02-2", projectRoot.toString(),
                true, now, now));
        String threadId = IdGen.next("thr2_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA02-2",
                "prof2", now, now));
        String rid = IdGen.next("run2_");
        String turnId = IdGen.next("turn2_");
        turnRepository.save(new TurnEntity(turnId, threadId, rid, "任务2",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(rid, turnId, null, "MAIN", "RUNNING", "PENDING",
                1, IdGen.next("ws_"), "a".repeat(64), 24, 80, 200_000, now.plusSeconds(600), now, now));
        return rid;
    }

    private void assertRejected(String reasonCode, PlanDraft draft) {
        PlanServiceException e = assertThrows(PlanServiceException.class,
                () -> planService.saveDraft(runId, draft));
        assertEquals(reasonCode, e.reasonCode(), "消息: " + e.getMessage());
        assertTrue(planRepository.count() >= 0);
    }
}
