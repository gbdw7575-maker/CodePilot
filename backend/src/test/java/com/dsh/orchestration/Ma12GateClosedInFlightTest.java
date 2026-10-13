package com.dsh.orchestration;

import com.dsh.agent.control.CancellationTokenRegistry;
import com.dsh.agent.control.RunControlService;
import com.dsh.agent.tool.ToolDispatcher;
import com.dsh.api.dto.event.ToolOutcomeDto;
import com.dsh.common.IdGen;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.orchestration.plan.PlanService;
import com.dsh.orchestration.task.TaskStates;
import com.dsh.storage.PlanRepository;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * MA-12 AT-19：<b>关闭新模式时仍有在途任务</b>（规范 §12 门槛 4/5、§13 回退条款、
 * 验收矩阵 AT-19）。
 *
 * <p>AT-19 要求观察到：停止新增、取消/结果查询可用；数据和用户修改保留。</p>
 *
 * <p>本类在 {@code dsh.orchestration.enabled=false} 下装配，覆盖四件事：</p>
 * <ol>
 *   <li><b>结果/计划可读</b>：已有 plan/task/终态 result 仍能经
 *       {@code GET /runs/{id}/plans} 读出，证据有效性照常计算——
 *       读取路径（{@code PlanController} → {@code PlanViewService} →
 *       {@code TaskQueryService}）是无条件 bean，不受开关门控。</li>
 *   <li><b>取消可用</b>：{@code POST /runs/{id}/cancel} 仍被接受并推进状态机；
 *       父 Run 取消后对在途子 Run 的级联取消（{@code cascadeCancelFromParent}，
 *       {@code ChildRunLifecycle} 使用的公开入口）在关闭态仍可把子 Run 收敛到终态。</li>
 *   <li><b>停止新增</b>：调度工具在关闭态不可执行（执行点 UNKNOWN_TOOL），
 *       与 {@code Ma05GateClosedTest} 的不可发现断言互补。</li>
 *   <li><b>数据与用户修改保留</b>：关闭态下取消/读取不删除持久化计划行，
 *       也不改动工作区内用户文件。</li>
 * </ol>
 *
 * <p>断言对象全部是持久化行与 API 响应，不依赖模型行为，故本类可完全用 Fake 验收。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "dsh.orchestration.enabled=false",
        "dsh.policy.require-for-side-effects=NONE"
})
class Ma12GateClosedInFlightTest {

    /** 关闭态仍可执行的调度工具必须返回 UNKNOWN_TOOL（执行点拒绝）。 */
    private static final List<String> SCHEDULING_TOOLS = List.of(
            "dispatch_tasks", "await_tasks", "read_task_result", "cancel_tasks");

    @Autowired private PlanService planService;
    @Autowired private RunControlService runControlService;
    @Autowired private CancellationTokenRegistry tokenRegistry;
    @Autowired private ToolDispatcher dispatcher;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private TaskResultRepository resultRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ThreadRepository threadRepository;
    @Autowired private TurnRepository turnRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;

    @TempDir Path projectRoot;

    private static final AtomicInteger SEQ = new AtomicInteger();

    // --------------------------------------------------------------- 1. 可读

    @Test
    void inFlightPlanAndSealedResultStayReadableWhenGateClosed() throws Exception {
        Chain chain = buildChain();
        String planId = planService.saveDraft(chain.runId(),
                new PlanDraft(null, null, "MA-12 关闭态可读", List.of(
                        task("A", "已完成任务"),
                        task("B", "在途任务")))).planId();
        Instant now = Instant.now();
        sealSucceeded(planId, "A", "run_ma12_child_a", now);
        // B 仍在途（子 Run 已派出，尚未收敛）
        TaskEntity b = taskRepository.findByPlanIdAndTaskId(planId, "B").orElseThrow();
        b.setState(TaskStates.RUNNING);
        b.setUpdatedAt(now);
        taskRepository.save(b);

        JsonNode root = getPlans(chain.runId(), 200);
        assertEquals(1, root.path("plans").size(), "关闭开关后计划仍可读");
        JsonNode tasks = root.path("plans").get(0).path("tasks");
        assertEquals(2, tasks.size());

        JsonNode a = tasks.get(0);
        assertEquals(TaskStates.SUCCEEDED, a.path("state").asText());
        assertTrue(a.path("evidenceValid").asBoolean(),
                "关闭态读取仍按同版本计算证据有效性，不退化为 false");
        assertFalse(a.path("result").isNull(), "已封存结果在关闭态仍可查询");
        assertEquals("digest-A", a.path("result").path("outputDigest").asText());
        assertEquals("patch-A", a.path("result").path("patchArtifactId").asText());
        assertEquals(3, a.path("result").path("modelRequestsUsed").asInt(),
                "关闭态读取不减损预算口径字段");

        JsonNode bNode = tasks.get(1);
        assertEquals(TaskStates.RUNNING, bNode.path("state").asText(),
                "在途任务在关闭态仍如实可见（不是被清扫成失败）");
        assertTrue(bNode.path("result").isNull());
    }

    // --------------------------------------------------------------- 2. 取消可用

    @Test
    void rootCancelAcceptedAndChildCascadeStillWorksWhenGateClosed() throws Exception {
        Chain chain = buildChain();
        String childRunId = "run_ma12_child_inflight";
        runRepository.save(new RunEntity(childRunId, chain.turnId(), chain.runId(), "WORKER",
                "RUNNING", "PENDING", 1, IdGen.next("ws_"), "digest-child",
                8, 30, 60_000, Instant.now().plusSeconds(600), Instant.now(), Instant.now()));
        // Loop 在线（令牌已注册）→ 活动态取消应走 CANCELLING 而非 BLOCKED
        tokenRegistry.register(chain.runId());

        MvcResult result = mockMvc.perform(post("/api/v1/runs/" + chain.runId() + "/cancel")
                        .header("X-DSH-Client", "local-ui")
                        .header("Idempotency-Key", "ma12-cancel-" + SEQ.incrementAndGet()))
                .andReturn();
        int status = result.getResponse().getStatus();
        assertEquals(202, status, "在途 Run 取消应被接受（202 Accepted）");

        RunEntity parent = runRepository.findById(chain.runId()).orElseThrow();
        assertEquals(RunControlService.ST_CANCELLING, parent.getState(),
                "关闭态下取消仍推进状态机 RUNNING → CANCELLING");
        assertNotNull(parent.getCancelRequestedAt(), "取消请求时间被记录，前端可区分已请求取消");

        // 父取消对在途子 Run 的级联（ChildRunLifecycle 使用的公开入口，无门控 bean）
        runControlService.cascadeCancelFromParent(childRunId);
        RunEntity child = runRepository.findById(childRunId).orElseThrow();
        assertEquals(RunControlService.ST_CANCELLED, child.getState(),
                "关闭态仍能把在途子 Run 收敛到终态，不留悬挂子 Run");
        assertEquals("PARENT_CANCELLED", child.getStopReason());
        assertTrue(child.isCleanupConfirmed(), "级联取消必须确认清理，避免租约泄漏");

        // 终态后再取消幂等（不产生第二次状态迁移）
        mockMvc.perform(post("/api/v1/runs/" + chain.runId() + "/cancel")
                        .header("X-DSH-Client", "local-ui")
                        .header("Idempotency-Key", "ma12-cancel-" + SEQ.incrementAndGet()))
                .andReturn();
        assertEquals(RunControlService.ST_CANCELLING,
                runRepository.findById(chain.runId()).orElseThrow().getState(),
                "重复取消不改变已处于 CANCELLING 的 Run");
    }

    // --------------------------------------------------------------- 3. 停止新增

    @Test
    void newDelegationIsStillStoppedWhenGateClosed() {
        Chain chain = buildChain();
        for (String tool : SCHEDULING_TOOLS) {
            ToolOutcomeDto out = dispatcher.dispatch(chain.runId(), chain.threadId(),
                    chain.runId(), chain.projectId(), Instant.now().plusSeconds(600),
                    tool, "{}", "call-" + tool + "-" + SEQ.incrementAndGet());
            assertEquals("FAILED", out.status(), "关闭态调度工具不得执行: " + tool);
            assertEquals("UNKNOWN_TOOL", out.errorCode(),
                    "关闭态调度工具执行点必须落 UNKNOWN_TOOL: " + tool);
        }
    }

    // --------------------------------------------------------------- 4. 数据保留

    @Test
    void userWorkspaceAndPlanRowsArePreservedWhenGateClosed() throws Exception {
        Chain chain = buildChain();
        String planId = planService.saveDraft(chain.runId(),
                new PlanDraft(null, null, "MA-12 数据保留", List.of(task("A", "在途任务")))).planId();

        // 工作区内用户修改（关闭回退不得删除或覆盖）
        Path userFile = projectRoot.resolve("src").resolve("App.java");
        Files.createDirectories(userFile.getParent());
        Files.writeString(userFile, "// 用户改动，回退不得覆盖\n", StandardCharsets.UTF_8);

        long plansBefore = planRepositoryCount();
        long tasksBefore = taskRepository.count();

        tokenRegistry.register(chain.runId());
        mockMvc.perform(post("/api/v1/runs/" + chain.runId() + "/cancel")
                        .header("X-DSH-Client", "local-ui")
                        .header("Idempotency-Key", "ma12-keep-" + SEQ.incrementAndGet()))
                .andReturn();
        getPlans(chain.runId(), 200);

        assertTrue(Files.exists(userFile), "关闭态回退不得删除用户工作区文件");
        assertEquals("// 用户改动，回退不得覆盖\n",
                Files.readString(userFile, StandardCharsets.UTF_8),
                "用户文件内容必须保持原样");
        assertEquals(plansBefore, planRepositoryCount(), "取消与读取不得删除计划行");
        assertEquals(tasksBefore, taskRepository.count(), "取消与读取不得删除任务行");
        assertTrue(taskRepository.findByPlanIdAndTaskId(planId, "A").isPresent(),
                "历史任务在关闭态仍可追溯");
    }

    // --------------------------------------------------------------- helpers

    private long planRepositoryCount() {
        return planRepository.count();
    }

    private JsonNode getPlans(String runId, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/runs/" + runId + "/plans")
                        .header("X-DSH-Client", "local-ui"))
                .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(),
                "响应体: " + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private TaskDraft task(String id, String goal) {
        return new TaskDraft(id, goal, List.of(), List.of(), "deliver-" + id,
                List.of("self-check-" + id), null, "DELEGATED", true);
    }

    /** 直接封存终态结果行（关闭态不能经 TaskSchedulingService.complete：该 bean 有门控）。 */
    private void sealSucceeded(String planId, String taskId, String childRunId, Instant baseTime) {
        TaskEntity task = taskRepository.findByPlanIdAndTaskId(planId, taskId).orElseThrow();
        String attemptId = IdGen.next("att_ma12_");
        attemptRepository.save(new TaskAttemptEntity(attemptId, planId, taskId,
                task.getTaskVersion(), childRunId, 1, "ma12-worker",
                baseTime.plusSeconds(600), 1L, null, "SUCCEEDED", baseTime, baseTime));
        resultRepository.save(new TaskResultEntity(attemptId, planId, taskId, task.getTaskVersion(),
                childRunId, TaskStates.SUCCEEDED, null, null,
                "[\"patch-" + taskId + "\"]", "patch-" + taskId, "digest-" + taskId,
                null, null, 3, 7, 1234, 2500L, baseTime));
        task.setState(TaskStates.SUCCEEDED);
        task.setUpdatedAt(baseTime);
        taskRepository.save(task);
    }

    private Chain buildChain() {
        Instant now = Instant.now();
        String projectId = "proj-ma12-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA12", projectRoot.toString(),
                true, now, now));
        String threadId = IdGen.next("thr_ma12_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA12 会话",
                "prof-ma12", now, now));
        String runId = IdGen.next("run_ma12_");
        String turnId = IdGen.next("turn_ma12_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING",
                1, IdGen.next("ws_"), "digest-ma12", 30, 120, 320_000,
                now.plusSeconds(600), now, now));
        return new Chain(projectId, threadId, turnId, runId);
    }

    private record Chain(String projectId, String threadId, String turnId, String runId) {
    }
}
