package com.dsh.orchestration;

import com.dsh.agent.control.CancellationToken;
import com.dsh.agent.model.FakeModelGateway;
import com.dsh.agent.model.FinishReason;
import com.dsh.agent.model.ModelGateway;
import com.dsh.agent.model.ModelRequest;
import com.dsh.agent.model.ModelResponse;
import com.dsh.agent.tool.ToolDispatcher;
import com.dsh.agent.tool.RunToolMountStore;
import com.dsh.api.dto.Usage;
import com.dsh.api.dto.event.ToolOutcomeDto;
import com.dsh.application.ArtifactStore;
import com.dsh.application.ModelProfileService;
import com.dsh.common.IdGen;
import com.dsh.storage.ArtifactEntity;
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
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MA-03 验收：AT-03（两个 child 不同私有标记与依赖输入——模型请求上下文无串扰、
 * 不跨 Run 读产物、工具结果配对）与 AT-04（worker 猜测派生/计划工具、伪造 scope、
 * 越界路径/符号链接——执行层拒绝，无越权文件或新 child）。
 */
@SpringBootTest
class Ma03ChildExecutionCapabilityTest {

    @Autowired
    private SubagentService subagentService;
    @Autowired
    private ToolDispatcher dispatcher;
    @Autowired
    private RunToolMountStore mountStore;
    @Autowired
    private WorkspaceManager workspaceManager;
    @Autowired
    private ArtifactStore artifactStore;
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
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CaptureGateway captureGateway;

    @TempDir
    Path projectRootA;
    @TempDir
    Path projectRootB;
    @TempDir
    Path outsideRoot;

    // ---------------------------------------------------------------- AT-03

    /** 两个 child 使用不同私有标记与依赖输入：上下文无串扰，工具结果一一配对，父会话零污染。 */
    @Test
    void twoChildrenHaveIsolatedContextsAndPairedToolResults() {
        Chain a = buildChain("fake-tool-ma03", FakeModelGateway.PROVIDER, projectRootA);

        String markerA = "MA03-MARKER-ALPHA-4242";
        String markerB = "MA03-MARKER-BRAVO-8787";
        SubagentResult childA = subagentService.run(new SubagentRequest(a.runId(),
                "ARCHITECT", "任务A " + markerA + " 依赖输入 ALPHA-INPUT-1", List.of(), null));
        SubagentResult childB = subagentService.run(new SubagentRequest(a.runId(),
                "ARCHITECT", "任务B " + markerB + " 依赖输入 BRAVO-INPUT-2", List.of(), null));
        assertEquals("COMPLETED", childA.state(), childA.stopReason());
        assertEquals("COMPLETED", childB.state(), childB.stopReason());

        String threadA = threadOf(childA.runId());
        String threadB = threadOf(childB.runId());
        String textA = joinThread(threadA);
        String textB = joinThread(threadB);

        // 私有标记与依赖输入只出现在自己的子会话
        assertTrue(textA.contains(markerA), "A 的私有标记必须在 A 的子上下文");
        assertTrue(textA.contains("ALPHA-INPUT-1"));
        assertFalse(textA.contains(markerB), "B 的私有标记不得串入 A 的子上下文");
        assertFalse(textA.contains("BRAVO-INPUT-2"));
        assertTrue(textB.contains(markerB), "B 的私有标记必须在 B 的子上下文");
        assertTrue(textB.contains("BRAVO-INPUT-2"));
        assertFalse(textB.contains(markerA), "A 的私有标记不得串入 B 的子上下文");
        assertFalse(textB.contains("ALPHA-INPUT-1"));

        // 父会话零子消息污染
        assertTrue(messageRepository.listByThread(a.threadId()).isEmpty(),
                "子 Agent 消息不得进入父会话");

        // 工具结果配对：每个子会话恰有 1 个 assistant_tool 调用与 1 条 role=tool 结果，callId 相同且唯一
        assertSinglePairedToolResult(threadA);
        assertSinglePairedToolResult(threadB);
    }

    /** worker 的模型请求目录不含计划/派生/调度工具（可见性层），但保留工作工具。 */
    @Test
    void childModelRequestCatalogHidesMainOnlyTools() {
        captureGateway.requests.clear();
        Chain a = buildChain("capture-model", CaptureGateway.PROVIDER, projectRootA);

        SubagentResult child = subagentService.run(new SubagentRequest(a.runId(),
                "ARCHITECT", "MA03-CATALOG-MARKER 只回答，不调工具", List.of(), null));
        assertEquals("COMPLETED", child.state(), child.stopReason());

        List<String> toolNames = captureGateway.requests.stream()
                .flatMap(req -> req.tools().stream().map(t -> t.name()))
                .toList();
        assertFalse(toolNames.isEmpty(), "子 Run 模型请求必须携带过滤后的工具目录");
        for (String denied : List.of("run_subagent", "start_orchestration", "fanout_subagents",
                "update_plan", "dispatch_tasks", "await_tasks", "read_task_result",
                "cancel_tasks")) {
            assertFalse(toolNames.contains(denied),
                    "worker 模型目录不得出现 MAIN 专属工具: " + denied);
        }
        assertTrue(toolNames.contains("read_file"), "worker 工作工具应保留");
        assertTrue(toolNames.contains("write_file"), "worker 工作工具应保留");
    }

    /** seedWorkspaceRef 只能引用同根族、同项目 Run 的工作区；跨根/跨项目/伪造均拒绝且零复制。 */
    @Test
    void seedWorkspaceMustBelongToSameRootFamilyAndProject() throws Exception {
        Files.writeString(projectRootA.resolve("ma03-project-a-marker.txt"), "A-project\n");
        Chain a = buildChain("fake-answer", FakeModelGateway.PROVIDER, projectRootA);
        Chain b = buildChain("fake-answer", FakeModelGateway.PROVIDER, projectRootB);

        // 正例：父工作区作为同根族种子，内容物化到子副本
        WorkspaceLease parentLease = workspaceManager.prepare(
                a.runId(), projectRepository.findById(a.projectId()).orElseThrow());
        // 与生产路径一致（FanoutService/Dispatcher）：prepare 后把 workspaceRef 回绑 Run
        RunEntity parentRun = runRepository.findById(a.runId()).orElseThrow();
        parentRun.setWorkspaceRef(parentLease.workspaceId());
        runRepository.save(parentRun);
        SubagentResult seeded = subagentService.run(new SubagentRequest(a.runId(),
                "CODER", "基于种子工作区回答", List.of(), parentLease.workspaceId()));
        assertEquals("COMPLETED", seeded.state(), seeded.stopReason());
        // 子 Loop 终态会释放内存租约（目录保留作证据），按返回的 workspaceRef 解析副本
        Path childWorkspace = workspaceManager.resolveByRef(seeded.workspaceRef()).dir();
        assertTrue(Files.exists(childWorkspace.resolve("ma03-project-a-marker.txt")),
                "授权种子工作区内容必须物化到子副本");

        // 反例 1：跨根 Run/跨项目引用 A 的工作区
        SubagentResult foreign = subagentService.run(new SubagentRequest(b.runId(),
                "CODER", "越权种子", List.of(), parentLease.workspaceId()));
        assertEquals("FAILED", foreign.state());
        assertEquals("PERMISSION_DENIED", foreign.stopReason());
        assertTrue(runRepository.listChildren(b.runId()).isEmpty(),
                "越权种子不得创建子 Run");

        // 反例 2：伪造不存在的 ws 引用（靠猜 ID 读其他副本）
        String forged = "ws_ma03_forged_" + UUID.randomUUID();
        SubagentResult forgedResult = subagentService.run(new SubagentRequest(b.runId(),
                "CODER", "伪造种子", List.of(), forged));
        assertEquals("FAILED", forgedResult.state());
        assertEquals("PERMISSION_DENIED", forgedResult.stopReason());
        assertFalse(Files.exists(workspaceManager.resolveByRef(forged).dir()),
                "拒绝前不得发生任何复制");
    }

    /** artifactRefs 必须存在且同项目/同根族；越权与不存在分别有界失败。 */
    @Test
    void artifactRefsAreAuthorizedAgainstProjectAndRootFamily() {
        Chain a = buildChain("fake-answer", FakeModelGateway.PROVIDER, projectRootA);
        Chain b = buildChain("fake-answer", FakeModelGateway.PROVIDER, projectRootB);

        // 越权：B 根 Run 的 Run 级产物
        ArtifactEntity foreign = artifactStore.save(b.runId(), b.projectId(), "b.txt",
                "text/plain", "foreign".getBytes(StandardCharsets.UTF_8), false);
        SubagentResult denied = subagentService.run(new SubagentRequest(a.runId(),
                "ARCHITECT", "引用外族产物", List.of(foreign.getId()), null));
        assertEquals("FAILED", denied.state());
        assertEquals("PERMISSION_DENIED", denied.stopReason());
        assertTrue(runRepository.listChildren(a.runId()).isEmpty());

        // 不存在
        SubagentResult missing = subagentService.run(new SubagentRequest(a.runId(),
                "ARCHITECT", "引用不存在产物", List.of("art_ma03_missing_" + UUID.randomUUID()), null));
        assertEquals("FAILED", missing.state());
        assertEquals("INVALID_ARGUMENT", missing.stopReason());

        // 正例 1：同根族 Run 级产物（父 Run 自身）——引用进入子任务提示
        ArtifactEntity family = artifactStore.save(a.runId(), a.projectId(), "a.txt",
                "text/plain", "family".getBytes(StandardCharsets.UTF_8), false);
        SubagentResult ok1 = subagentService.run(new SubagentRequest(a.runId(),
                "ARCHITECT", "使用本族产物", List.of(family.getId()), null));
        assertEquals("COMPLETED", ok1.state(), ok1.stopReason());
        assertTrue(joinThread(threadOf(ok1.runId())).contains(family.getId()),
                "授权产物引用必须进入子任务提示");

        // 正例 2：同项目项目级产物（runId 为空）
        ArtifactEntity projectScoped = artifactStore.save(null, a.projectId(), "p.txt",
                "text/plain", "project".getBytes(StandardCharsets.UTF_8), false);
        SubagentResult ok2 = subagentService.run(new SubagentRequest(a.runId(),
                "ARCHITECT", "使用项目级产物", List.of(projectScoped.getId()), null));
        assertEquals("COMPLETED", ok2.state(), ok2.stopReason());
    }

    // ---------------------------------------------------------------- AT-04

    /** worker 在执行点猜测 MAIN 专属工具（含旧挂载、计划改写）：一律 DENIED，无新 child。 */
    @Test
    void workerExecutionPointDeniesAllMainOnlyTools() {
        Chain a = buildChain("fake-answer", FakeModelGateway.PROVIDER, projectRootA);
        Instant deadline = Instant.now().plusSeconds(600);
        RunEntity worker = persistWorker(a, "CODER");

        mountStore.mount(worker.getId(), "run_subagent");
        mountStore.mount(worker.getId(), "fanout_subagents");
        for (String tool : List.of("run_subagent", "fanout_subagents", "update_plan")) {
            ToolOutcomeDto outcome = dispatcher.dispatch(worker.getId(), a.threadId(),
                    worker.getTurnId(), a.projectId(), deadline, tool, "{}", "call-" + tool);
            assertEquals("DENIED", outcome.status(), "worker 调用 " + tool + " 应拒绝");
            assertEquals("PERMISSION_DENIED", outcome.errorCode(),
                    "worker 调用 " + tool + " 应为 PERMISSION_DENIED");
        }
        assertTrue(runRepository.listChildren(worker.getId()).isEmpty(),
                "拒绝派生不得产生任何子 Run");

        // 对照：MAIN 通过角色门后才进入 Schema 校验（INVALID_ARGUMENTS，而非 DENIED）
        mountStore.mount(a.runId(), "run_subagent");
        ToolOutcomeDto mainGate = dispatcher.dispatch(a.runId(), a.threadId(), a.turnId(),
                a.projectId(), deadline, "run_subagent", "{}", "call-main");
        assertEquals("INVALID_ARGUMENTS", mainGate.errorCode());
        ToolOutcomeDto mainPlan = dispatcher.dispatch(a.runId(), a.threadId(), a.turnId(),
                a.projectId(), deadline, "update_plan", "{\"steps\":[]}", "call-main-plan");
        assertEquals("INVALID_ARGUMENTS", mainPlan.errorCode());
    }

    /** 服务入口双重验证：worker 绕过工具层直调 SubagentService 被拒绝，不建子 Run、不动预算。 */
    @Test
    void serviceEntryDeniesWorkerSpawn() {
        Chain a = buildChain("fake-answer", FakeModelGateway.PROVIDER, projectRootA);
        RunEntity worker = persistWorker(a, "CODER");

        SubagentResult result = subagentService.run(new SubagentRequest(
                worker.getId(), "ARCHITECT", "worker 试图再派生", List.of(), null));
        assertEquals("FAILED", result.state());
        assertEquals("WORKER_SPAWN_DENIED", result.stopReason());
        assertTrue(result.runId().isEmpty(), "WORKER_SPAWN_DENIED 不得预生成子 Run id");
        assertTrue(runRepository.listChildren(worker.getId()).isEmpty());

        RunEntity reloaded = runRepository.findById(worker.getId()).orElseThrow();
        assertEquals(0, reloaded.getBudgetModelRequestsUsed());
        assertEquals(0, reloaded.getBudgetReservedTokens());
    }

    /** worker 经 ../ 穿越或符号链接写副本外：执行层拒绝，副本外无文件变化、无新 child。 */
    @Test
    void workerPathEscapeAndSymlinkAreRejectedWithoutUnauthorizedWrites() throws Exception {
        Files.writeString(projectRootA.resolve("base.txt"), "base\n");
        Chain a = buildChain("fake-answer", FakeModelGateway.PROVIDER, projectRootA);
        Instant deadline = Instant.now().plusSeconds(600);
        RunEntity worker = persistWorker(a, "CODER");

        // ../ 穿越：Dispatcher 先 prepare 工作区，SafePaths 拒绝
        ToolOutcomeDto escape = dispatcher.dispatch(worker.getId(), a.threadId(),
                worker.getTurnId(), a.projectId(), deadline, "write_file",
                "{\"path\":\"../MA03-ESCAPE-SENTINEL.txt\",\"content\":\"PWNED\"}", "call-escape");
        assertEquals("FAILED", escape.status());
        assertEquals("PATH_OUTSIDE_WORKSPACE", escape.errorCode());
        assertFalse(Files.exists(workspaceManager.workRoot().resolve("MA03-ESCAPE-SENTINEL.txt")),
                "越界路径不得在副本外产生文件");

        // 重解析点越界：优先文件符号链接；Windows 普通账号无 symlink 权限时
        // 用 mklink /J 目录联接（junction，普通用户可建）兜底，保证反例在本机确定执行。
        Path secret = outsideRoot.resolve("ma03-secret.txt");
        Files.writeString(secret, "SECRET\n");
        WorkspaceLease lease = workspaceManager.find(worker.getId());
        assertNotNull(lease, "前一次 dispatch 应已为 worker 准备工作区");
        String writeRelativePath;
        boolean reparseSupported;
        Path symlinkFile = lease.dir().resolve("ma03-link.txt");
        try {
            Files.createSymbolicLink(symlinkFile, secret);
            writeRelativePath = "ma03-link.txt";
            reparseSupported = true;
        } catch (IOException | UnsupportedOperationException symlinkFailed) {
            writeRelativePath = null;
            reparseSupported = false;
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                Path outsideDir = Files.createDirectories(outsideRoot.resolve("junction-target"));
                Path junction = lease.dir().resolve("ma03-junction");
                try {
                    Process process = new ProcessBuilder("cmd", "/c",
                            "mklink /J \"" + junction + "\" \"" + outsideDir + "\"")
                            .redirectErrorStream(true).start();
                    String output = new String(process.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8);
                    if (process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                            && process.exitValue() == 0) {
                        writeRelativePath = "ma03-junction/ma03-secret.txt";
                        reparseSupported = true;
                    } else {
                        System.getLogger(Ma03ChildExecutionCapabilityTest.class.getName())
                                .log(System.Logger.Level.WARNING, "mklink /J 失败: {0}", output);
                    }
                } catch (IOException | InterruptedException junctionFailed) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        assumeTrue(reparseSupported, "当前环境既不允许符号链接也无法创建 junction，跳过重解析点越界用例");

        ToolOutcomeDto viaLink = dispatcher.dispatch(worker.getId(), a.threadId(),
                worker.getTurnId(), a.projectId(), deadline, "write_file",
                "{\"path\":\"" + writeRelativePath + "\",\"content\":\"PWNED\"}", "call-link");
        assertEquals("FAILED", viaLink.status());
        assertEquals("PATH_OUTSIDE_WORKSPACE", viaLink.errorCode());
        assertEquals("SECRET\n", Files.readString(secret), "重解析点指向的副本外文件必须保持不变");
        assertFalse(Files.exists(outsideRoot.resolve("junction-target").resolve("ma03-secret.txt")),
                "junction 目标目录不得出现越权写入");
        assertTrue(runRepository.listChildren(worker.getId()).isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    private Chain buildChain(String model, String provider, Path projectRoot) {
        Instant now = Instant.now();
        String projectId = "proj-ma03-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA03能力", projectRoot.toString(),
                true, now, now));

        String profileId = "prof-ma03-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, provider + "-" + model,
                provider, model, "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));

        String threadId = IdGen.next("thr_ma03_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "父会话", profileId, now, now));
        String runId = IdGen.next("run_ma03_");
        String turnId = IdGen.next("turn_ma03_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest-ma03", 12, 30, 200_000,
                now.plusSeconds(600), now, now));
        return new Chain(projectId, profileId, threadId, turnId, runId);
    }

    /** 持久化一个带完整 thread/turn 的 worker 子 Run（服务入口会沿 turn/thread 加载）。 */
    private RunEntity persistWorker(Chain parent) {
        return persistWorker(parent, "CODER");
    }

    private RunEntity persistWorker(Chain parent, String role) {
        Instant now = Instant.now();
        String threadId = IdGen.next("sub_thr_ma03_");
        threadRepository.save(new ThreadEntity(threadId, parent.projectId(),
                "子会话/" + role, parent.profileId(), now, now));
        String runId = IdGen.next("sub_run_ma03_");
        String turnId = IdGen.next("sub_turn_ma03_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "worker 任务",
                "RUNNING", "PENDING", threadId, now, now));
        RunEntity worker = new RunEntity(runId, turnId, parent.runId(), role, "RUNNING",
                "PENDING", 1, IdGen.next("ws_"), "digest-ma03", 8, 20, 60_000,
                now.plusSeconds(600), now, now);
        return runRepository.save(worker);
    }

    private String threadOf(String runId) {
        RunEntity run = runRepository.findById(runId).orElseThrow();
        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElseThrow();
        return turn.getThreadId();
    }

    private String joinThread(String threadId) {
        StringBuilder sb = new StringBuilder();
        for (MessageEntity m : messageRepository.listByThread(threadId)) {
            sb.append(m.getRole()).append(' ').append(m.getContent()).append('\n');
        }
        return sb.toString();
    }

    private void assertSinglePairedToolResult(String threadId) {
        List<MessageEntity> messages = messageRepository.listByThread(threadId);
        List<MessageEntity> assistantTool = messages.stream()
                .filter(m -> "assistant_tool".equals(m.getRole())).toList();
        List<MessageEntity> toolResults = messages.stream()
                .filter(m -> "tool".equals(m.getRole())).toList();
        assertEquals(1, assistantTool.size(), "子会话应恰有一条 assistant_tool 消息");
        assertEquals(1, toolResults.size(), "子会话应恰有一条 tool 结果消息（一一配对）");
        try {
            JsonNode calls = objectMapper.readTree(assistantTool.get(0).getContent()).path("calls");
            assertEquals(1, calls.size());
            String callId = calls.get(0).path("callId").asText();
            String pairedCallId = objectMapper.readTree(toolResults.get(0).getContent())
                    .path("callId").asText();
            assertEquals(callId, pairedCallId, "tool 结果必须与发起调用 callId 配对");
            assertTrue(callId.startsWith("call_"), "callId 形态异常: " + callId);
        } catch (Exception e) {
            throw new AssertionError("工具消息 JSON 损坏，无法验证配对", e);
        }
    }

    private record Chain(String projectId, String profileId, String threadId,
                         String turnId, String runId) {
    }

    /** 测试专用抓取网关：记录每次模型请求（含工具目录），并直接给终止回答，不访问网络。 */
    @TestConfiguration
    static class CaptureConfig {
        @Bean
        CaptureGateway ma03CaptureGateway() {
            return new CaptureGateway();
        }
    }

    static class CaptureGateway implements ModelGateway {
        static final String PROVIDER = "ma03-capture";
        final List<ModelRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public String provider() {
            return PROVIDER;
        }

        @Override
        public ModelResponse generate(ModelRequest request, CancellationToken token) {
            requests.add(request);
            return new ModelResponse("capture 子 Agent 直接完成", List.of(), FinishReason.STOP,
                    new Usage("KNOWN", 1, 1, null, null, null, null, null),
                    IdGen.next("mresp_"));
        }
    }
}
