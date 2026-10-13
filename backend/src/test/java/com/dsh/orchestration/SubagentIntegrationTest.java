package com.dsh.orchestration;

import com.dsh.agent.control.CancellationTokenRegistry;
import com.dsh.agent.model.FakeModelGateway;
import com.dsh.agent.tool.ToolDispatcher;
import com.dsh.api.dto.event.ToolOutcomeDto;
import com.dsh.application.ModelProfileService;
import com.dsh.common.IdGen;
import com.dsh.storage.MessageEntity;
import com.dsh.storage.MessageRepository;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-010 子 Agent 集成：隔离上下文/预算实结、预算不足拒绝、取消沿派生链传播、
 * worker 角色执行/发现编排工具被拒。
 */
@SpringBootTest
class SubagentIntegrationTest {

    @Autowired
    private SubagentService subagentService;
    @Autowired
    private ToolDispatcher dispatcher;
    @Autowired
    private com.dsh.agent.tool.RunToolMountStore mountStore;
    @Autowired
    private CancellationTokenRegistry tokenRegistry;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ModelProfileRepository profileRepository;
    @Autowired
    private ThreadRepository threadRepository;
    @Autowired
    private TurnRepository turnRepository;
    @Autowired
    private RunRepository runRepository;
    @Autowired
    private MessageRepository messageRepository;

    @TempDir
    Path projectRoot;

    private Chain buildChain(String model, int maxMr, int maxTc, int maxTok) {
        Instant now = Instant.now();
        String projectId = "proj-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "子Agent集成", projectRoot.toString(),
                true, now, now));

        String profileId = "prof-" + UUID.randomUUID();
        ModelProfileEntity profile = new ModelProfileEntity(profileId, "fake-" + model,
                FakeModelGateway.PROVIDER, model, "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now);
        profileRepository.save(profile);

        String threadId = IdGen.next("thr_parent_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "父会话", profileId, now, now));

        String runId = IdGen.next("run_parent_");
        String turnId = IdGen.next("turn_parent_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));

        RunEntity run = new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), ModelProfileService.configDigest(profile),
                maxMr, maxTc, maxTok, now.plusSeconds(600), now, now);
        runRepository.save(run);
        return new Chain(projectId, profileId, threadId, turnId, runId);
    }

    @Test
    void architectSubRunIsIsolatedAndSettlesActualBudget() {
        Chain c = buildChain("fake-answer", 10, 10, 100_000);

        SubagentResult result = subagentService.run(
                new SubagentRequest(c.runId(), "ARCHITECT", "设计一个加法函数",
                        java.util.List.of(), null));

        assertEquals("COMPLETED", result.state(), result.stopReason());
        assertEquals("ARCHITECT", result.role());
        assertTrue(result.runId().startsWith("sub_run_"));
        assertNull(result.patchArtifactId(), "未使用工作区的子 Run 不应产生 patch 产物");

        RunEntity child = runRepository.findById(result.runId()).orElseThrow();
        assertEquals("ARCHITECT", child.getRole());
        assertEquals(c.runId(), child.getParentRunId());
        assertNotEquals(c.threadId(), child.getTurnId());
        assertEquals(1, child.getBudgetModelRequestsUsed(), "子 Run 独立预算封顶为切片，实际 1 轮");
        // MA-04：切片=min(配置子上限 8, 父可分配=10−主任务保留 4=6)，保留额不可被 worker 占用
        assertEquals(6, child.getBudgetMaxModelRequests(),
                "子 Run 模型请求上限=扣除主任务保留后的切片");
        assertTrue(child.getBudgetMaxCumulativeTokens() <= 60_000);

        // 上下文隔离：子 thread 有完整消息，父 thread 零消息
        TurnEntity childTurn = turnRepository.findById(child.getTurnId()).orElseThrow();
        java.util.List<MessageEntity> childMessages =
                messageRepository.listByThread(childTurn.getThreadId());
        assertTrue(childMessages.size() >= 2, "子会话应有 user+assistant 消息");
        assertTrue(messageRepository.listByThread(c.threadId()).isEmpty(),
                "子 Agent 消息不得污染父会话");

        // 预算实结：父账计入子实际用量，且不超过父上限
        RunEntity parent = runRepository.findById(c.runId()).orElseThrow();
        assertEquals(1, parent.getBudgetModelRequestsUsed());
        assertEquals(0, parent.getBudgetReservedTokens(), "结算后预约 token 必须退订");
    }

    @Test
    void parentWithoutRemainingBudgetGetsBoundedFailureWithoutChildRun() {
        Chain c = buildChain("fake-answer", 4, 10, 100_000);
        // 模拟父账模型请求已用尽
        RunEntity parent = runRepository.findById(c.runId()).orElseThrow();
        parent.setBudgetModelRequestsUsed(4);
        runRepository.saveAndFlush(parent);

        SubagentResult result = subagentService.run(
                new SubagentRequest(c.runId(), "ARCHITECT", "不应被执行", java.util.List.of(), null));

        assertEquals("FAILED", result.state());
        assertEquals("BUDGET_LIMIT", result.stopReason());
        assertTrue(result.runId().isEmpty(), "预算不足不得创建子 Run");
    }

    @Test
    void invalidRoleIsRejectedBounded() {
        Chain c = buildChain("fake-answer", 10, 10, 100_000);
        SubagentResult result = subagentService.run(
                new SubagentRequest(c.runId(), "HACKER", "越权", java.util.List.of(), null));
        assertEquals("FAILED", result.state());
        assertEquals("INVALID_ARGUMENT", result.stopReason());
    }

    @Test
    void parentCancellationPropagatesToChildAtSafetyPoint() throws Exception {
        Chain c = buildChain("fake-loop-forever", 10, 10, 100_000);
        tokenRegistry.register(c.runId());

        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        Future<SubagentResult> future = executor.submit(() -> subagentService.run(
                new SubagentRequest(c.runId(), "ARCHITECT", "无限轮等待取消",
                        java.util.List.of(), null)));

        // 等子 Run 真正进入 RUNNING
        RunEntity child = waitForChildRunning(c.runId());
        assertNotNull(child);
        assertEquals("RUNNING", child.getState(),
                "取消必须在 begin 之后、安全点处触发，实际状态: " + child.getState());

        tokenRegistry.requestCancel(c.runId());
        try {
            SubagentResult result = future.get(30, TimeUnit.SECONDS);

            assertEquals("CANCELLED", result.state());
            assertEquals("PARENT_CANCELLED", result.stopReason());
            RunEntity reloaded = runRepository.findById(result.runId()).orElseThrow();
            assertEquals("CANCELLED", reloaded.getState());
            assertEquals("PARENT_CANCELLED", reloaded.getStopReason());
            // 取消后子账仍实结到父账（已发生的工具调用不被抹掉），预约退订
            RunEntity parent = runRepository.findById(c.runId()).orElseThrow();
            assertEquals(0, parent.getBudgetReservedTokens());
        } finally {
            executor.shutdownNow();
            tokenRegistry.release(c.runId());
        }
    }

    @Test
    void workerRoleIsDeniedOrchestrationToolsAtExecutionAndDiscovery() {
        Chain main = buildChain("fake-answer", 10, 10, 100_000);
        Instant now = Instant.now();
        String workerRunId = IdGen.next("sub_run_worker_");
        RunEntity worker = new RunEntity(workerRunId, "turn-worker", main.runId(), "CODER",
                "RUNNING", "PENDING", 1, IdGen.next("ws_"), "digest",
                8, 8, 60_000, now.plusSeconds(600), now, now);
        runRepository.save(worker);

        // 即使 worker 历史上已挂载编排工具，执行点实时拒绝（挂载检查在角色检查之前，先挂载）
        mountStore.mount(workerRunId, "run_subagent");
        mountStore.mount(workerRunId, "start_orchestration");
        ToolOutcomeDto denied = dispatcher.dispatch(workerRunId, main.threadId(), "turn-worker",
                main.projectId(), now.plusSeconds(600), "run_subagent",
                "{\"role\":\"ARCHITECT\",\"task\":\"越权派生\"}", "call-denied");
        assertEquals("DENIED", denied.status());
        assertEquals("PERMISSION_DENIED", denied.errorCode());

        // 参数缺 role：角色门在 Schema 校验之前，worker 仍被拒绝
        ToolOutcomeDto deniedBad = dispatcher.dispatch(workerRunId, main.threadId(), "turn-worker",
                main.projectId(), now.plusSeconds(600), "start_orchestration", "{}", "call-bad");
        assertEquals("PERMISSION_DENIED", deniedBad.errorCode());

        // MAIN 角色通过角色门（先挂载，扩展工具挂载检查在角色门之前；随后才是 Schema 校验失败）
        mountStore.mount(main.runId(), "start_orchestration");
        ToolOutcomeDto mainPassesGate = dispatcher.dispatch(main.runId(), main.threadId(),
                main.turnId(), main.projectId(), now.plusSeconds(600),
                "start_orchestration", "{}", "call-main-gate");
        assertEquals("INVALID_ARGUMENTS", mainPassesGate.errorCode());

        // 发现侧：worker 搜不到编排工具，MAIN 可以
        ToolOutcomeDto workerSearch = dispatcher.dispatch(workerRunId, main.threadId(),
                "turn-worker", main.projectId(), now.plusSeconds(600),
                "tool_search", "{\"query\":\"subagent\"}", "call-worker-search");
        assertTrue(!workerSearch.structuredContent().path("matched").toString()
                        .contains("run_subagent"),
                "worker 不应发现 run_subagent");

        ToolOutcomeDto mainSearch = dispatcher.dispatch(main.runId(), main.threadId(),
                main.turnId(), main.projectId(), now.plusSeconds(600),
                "tool_search", "{\"query\":\"subagent\"}", "call-main-search");
        assertTrue(mainSearch.structuredContent().path("matched").toString()
                        .contains("run_subagent"),
                "MAIN 应能发现 run_subagent，实际: "
                        + mainSearch.structuredContent().path("matched"));
    }

    private RunEntity waitForChildRunning(String parentRunId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            for (RunEntity run : runRepository.findAll()) {
                if (!parentRunId.equals(run.getParentRunId())) {
                    continue;
                }
                // 必须等到 begin 提交 RUNNING：QUEUED 阶段取消会早于 Loop 安全点。
                // fake-loop-forever 在模型调用处挂起，RUNNING 窗口持续到取消触发；
                // 若意外进入终态（如 BUDGET_LIMIT/FAILED），立即返回以暴露真实状态。
                if ("RUNNING".equals(run.getState())
                        || "CANCELLED".equals(run.getState())
                        || "FAILED".equals(run.getState())
                        || "COMPLETED".equals(run.getState())
                        || "PAUSED".equals(run.getState())) {
                    return run;
                }
            }
            Thread.sleep(10);
        }
        return null;
    }

    private record Chain(String projectId, String profileId, String threadId,
                         String turnId, String runId) {
    }
}
