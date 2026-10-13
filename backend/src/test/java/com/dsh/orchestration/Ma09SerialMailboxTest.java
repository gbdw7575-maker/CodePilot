package com.dsh.orchestration;

import com.dsh.application.DshProperties;
import com.dsh.common.IdGen;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.orchestration.plan.PlanService;
import com.dsh.orchestration.task.TaskAwaitService;
import com.dsh.orchestration.task.TaskSchedulingService;
import com.dsh.orchestration.task.TaskStates;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.dsh.orchestration.task.TaskWorkerExecutor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-09 串行邮箱与依赖就绪测试（规范 AT-08）。
 *
 * <p>AT-08 三要点：
 * <ol>
 *   <li>A 快、B 慢、C 只依赖 A → C 在 B 完成前启动（依赖就绪即运行，不等 B）</li>
 *   <li>完成事件并发到达 → 主 Run 串行恢复（同一主 Run 同时至多一条执行循环）</li>
 *   <li>同一主 Run 至多一个等待者（park 拒绝第二个等待者）</li>
 * </ol>
 *
 * <p>本测试覆盖要点 1（依赖就绪 promote）和要点 3（park 串行邮箱约束）。
 * 要点 2 的 CAS 串行恢复在 Ma05AwaitParkLoopIntegrationTest 全链路已覆盖。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "dsh.policy.require-for-side-effects=NONE")
class Ma09SerialMailboxTest {

    // 本类验证真实 promote/park，隔离自动领取；其他缓存上下文不能消费本类的 QUEUED 行。
    private static final String MAILBOX_SCHEMA = "ma09_mailbox_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource
    static void mailboxDatabase(DynamicPropertyRegistry registry) {
        String url = System.getenv().getOrDefault("DB_TEST_URL", "jdbc:postgresql://localhost:5432/dsh_test");
        registry.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + MAILBOX_SCHEMA);
        registry.add("spring.datasource.hikari.connection-init-sql",
                () -> "CREATE SCHEMA IF NOT EXISTS " + MAILBOX_SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> MAILBOX_SCHEMA);
    }

    @MockitoBean private TaskWorkerExecutor workerExecutor;

    @Autowired private PlanService planService;
    @Autowired private TaskSchedulingService schedulingService;
    @Autowired private TaskAwaitService awaitService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskWaitRepository waitRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ModelProfileRepository profileRepository;
    @Autowired private ThreadRepository threadRepository;
    @Autowired private TurnRepository turnRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private DshProperties properties;

    @TempDir Path projectRoot;

    // -------------------------- AT-08 要点3：同一主 Run 至多一个等待者

    @Test
    void parkRejectsSecondWaiterForSameRootRun() {
        Chain chain = buildChain();
        savePlan(chain, List.of(task("A", "独立任务A", List.of())));
        Instant future = Instant.now().plusSeconds(30);

        // 第一次 park：成功
        awaitService.park(chain.runId(), chain.turnId(), "call-at08-1",
                List.of("A"), "ALL", 0, "{}", future);
        assertTrue(waitRepository
                        .findFirstByRootRunIdAndStateOrderByCreatedAtDesc(
                                chain.runId(), TaskWaitEntity.WAITING).isPresent(),
                "第一次 park 应成功落 WAITING 行");

        // 第二次 park：同一 rootRunId 已有未完成等待 → 拒绝
        assertThrows(IllegalArgumentException.class, () ->
                awaitService.park(chain.runId(), chain.turnId(), "call-at08-2",
                        List.of("A"), "ALL", 0, "{}", future),
                "同一主 Run 已有未完成 await_tasks 等待时拒绝第二个 park（串行邮箱）");
    }

    // -------------------------- AT-08 要点1：C 只依赖 A → A 完成后 C 就绪（不等 B）

    @Test
    void dependencyReadyAllowsCBeforeBCompletes() {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(
                task("A", "快速任务A", List.of()),
                task("B", "慢速任务B", List.of()),
                task("C", "依赖A的任务C", List.of("A"))));
        Instant now = Instant.now();

        // 手动设置：A 已 SUCCEEDED，B 仍在 RUNNING（慢），C 在 PENDING（等 A 就绪）
        setTaskState(planId, "A", TaskStates.SUCCEEDED, now);
        setTaskState(planId, "B", TaskStates.RUNNING, now);
        setTaskState(planId, "C", TaskStates.PENDING, now);

        // promote：检查 PENDING 任务的依赖
        schedulingService.promote(chain.runId());

        // C 只依赖 A（已 SUCCEEDED）→ C 被提升到 READY
        assertEquals(TaskStates.READY, state(planId, "C"),
                "C 只依赖 A，A 已 SUCCEEDED → C 提升到 READY（不等 B 完成）");
        assertTrue(schedulingService.allQueuedAttempts().stream().anyMatch(a ->
                        planId.equals(a.getPlanId()) && "C".equals(a.getTaskId())),
                "C 的 READY 必须有真实 QUEUED attempt，不能仅修改状态");
        // B 仍在 RUNNING（未被 promote 影响）
        assertEquals(TaskStates.RUNNING, state(planId, "B"),
                "B 仍在 RUNNING（C 的就绪不依赖 B）");
        // A 保持 SUCCEEDED
        assertEquals(TaskStates.SUCCEEDED, state(planId, "A"));
    }

    // ------------------------------------------------------- helpers

    private TaskDraft task(String id, String goal, List<String> deps) {
        return new TaskDraft(id, goal, deps, List.of(), "deliver-" + id,
                List.of("self-check-" + id), null, "DELEGATED", true);
    }

    private String savePlan(Chain chain, List<TaskDraft> tasks) {
        return planService.saveDraft(chain.runId(),
                new PlanDraft(null, null, "MA-09 串行邮箱测试计划", tasks)).planId();
    }

    private String state(String planId, String taskId) {
        return taskRepository.findByPlanIdAndTaskId(planId, taskId)
                .map(TaskEntity::getState).orElse("<missing>");
    }

    private void setTaskState(String planId, String taskId, String newState, Instant now) {
        TaskEntity task = taskRepository.findByPlanIdAndTaskId(planId, taskId).orElseThrow();
        task.setState(newState);
        task.setUpdatedAt(now);
        taskRepository.save(task);
    }

    private Chain buildChain() {
        Instant now = Instant.now();
        String projectId = "proj-ma09sm-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA09SM", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-ma09sm-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, "fake-fake-ma05",
                "fake", "fake-ma05", "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));
        String threadId = IdGen.next("thr_ma09sm_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA09SM 会话",
                profileId, now, now));
        String runId = IdGen.next("run_ma09sm_");
        String turnId = IdGen.next("turn_ma09sm_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest-ma09sm", 30, 120, 320_000,
                now.plusSeconds(600), now, now));
        return new Chain(projectId, threadId, turnId, runId);
    }

    private record Chain(String projectId, String threadId, String turnId, String runId) {
    }
}
