package com.dsh.orchestration;

import com.dsh.agent.control.CancellationToken;
import com.dsh.agent.model.FakeModelGateway;
import com.dsh.agent.model.FinishReason;
import com.dsh.agent.model.ModelGateway;
import com.dsh.agent.model.ModelRequest;
import com.dsh.agent.model.ModelResponse;
import com.dsh.agent.model.ModelToolCall;
import com.dsh.api.dto.Usage;
import com.dsh.application.ArtifactStore;
import com.dsh.common.Digest;
import com.dsh.common.IdGen;
import com.dsh.storage.ArtifactEntity;
import com.dsh.storage.InputSnapshotEntity;
import com.dsh.storage.InputSnapshotRepository;
import com.dsh.storage.MessageEntity;
import com.dsh.storage.MessageRepository;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.ResourceLeaseEntity;
import com.dsh.storage.ResourceLeaseRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-04 验收：
 * <ul>
 *   <li>AT-05（服务层片段）：CONCURRENCY_LIMIT 有界失败不建 Run 不动账；终态租约落 RELEASED。</li>
 *   <li>AT-06：输入快照冻结（基线/产物 sha256/归一化范围）；执行点 write_file/apply_patch
 *       白名单拒绝且无文件；run_command 越界在封存点判 SCOPE_VIOLATION（证据保留、无 patch）；
 *       白名单内正向写入产出 patch；非法 scope 在预约前 INVALID_ARGUMENT。</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = "dsh.policy.require-for-side-effects=NONE")
class Ma04ScopeAndSnapshotIntegrationTest {

    @Autowired
    private SubagentService subagentService;
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
    private InputSnapshotRepository snapshotRepository;
    @Autowired
    private ResourceLeaseRepository leaseRepository;
    @Autowired
    private WorkerConcurrencyManager concurrencyManager;
    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path projectRoot;

    private static final String SCOPE_ALLOWED = "{\"write\":[\"allowed/\"]}";
    private static final String SCOPE_SRC = "{\"write\":[\"src/\"]}";
    private static final String SCOPE_READONLY = "{\"write\":[]}";

    // ------------------------------------------------------- 输入快照

    @Test
    void inputSnapshotFreezesBaselineArtifactDigestsAndNullScope() throws Exception {
        Chain chain = buildChain("fake-answer-ma04", FakeModelGateway.PROVIDER);
        byte[] content = "snapshot-bytes\n".getBytes(StandardCharsets.UTF_8);
        ArtifactEntity artifact = artifactStore.save(chain.runId(), chain.projectId(),
                "dep.txt", "text/plain", content, false);

        SubagentResult result = subagentService.run(new SubagentRequest(chain.runId(),
                "ARCHITECT", "基于依赖产物直接回答", List.of(artifact.getId()), null));
        assertEquals("COMPLETED", result.state(), result.stopReason());

        InputSnapshotEntity snapshot = snapshotRepository
                .findFirstByRunIdOrderByCreatedAtDesc(result.runId()).orElseThrow();
        assertEquals(chain.runId(), snapshot.getRootRunId());
        assertEquals(chain.projectId(), snapshot.getProjectId());
        assertEquals(result.workspaceRef(), snapshot.getSeedWorkspaceRef());
        assertEquals("", snapshot.getBaseDigest(), "无种子副本时基线摘要为空串");
        assertNull(snapshot.getWriteScopeJson(), "不带 scope 的既有协议=整副本可写，存 null");

        JsonNode artifacts = objectMapper.readTree(snapshot.getInputArtifactsJson());
        assertEquals(1, artifacts.size());
        assertEquals(artifact.getId(), artifacts.get(0).path("artifactId").asText());
        assertEquals("text/plain", artifacts.get(0).path("mediaType").asText());
        assertEquals(Digest.sha256Hex(content), artifacts.get(0).path("sha256").asText(),
                "冻结的产物摘要必须是内容 sha256");
    }

    // ------------------------------------------------------- AT-06 正向

    @Test
    void inScopeWriteSucceedsAndProducesConsumablePatch() throws Exception {
        Chain chain = buildChain("fake-write-ma04", FakeModelGateway.PROVIDER);

        SubagentResult result = subagentService.run(new SubagentRequest(chain.runId(),
                "CODER", "fanout-file allowed/a.txt :: hello-ma04", List.of(), null,
                SCOPE_ALLOWED));
        assertEquals("COMPLETED", result.state(), result.stopReason());
        assertNotNull(result.patchArtifactId(), "白名单内写入封存通过，必须产出 patch 产物");
        assertEquals(List.of(result.patchArtifactId()), result.artifactRefs());
        assertNotNull(artifactStore.get(result.patchArtifactId()));

        Path childWorkspace = workspaceManager.resolveByRef(result.workspaceRef()).dir();
        Path written = childWorkspace.resolve("allowed/a.txt");
        assertTrue(Files.exists(written), "授权路径文件必须真实落盘");
        assertTrue(Files.readString(written).contains("hello-ma04"));

        // 归一化后的范围进入冻结快照
        InputSnapshotEntity snapshot = snapshotRepository
                .findFirstByRunIdOrderByCreatedAtDesc(result.runId()).orElseThrow();
        assertTrue(snapshot.getWriteScopeJson().contains("allowed/"));
    }

    // ------------------------------------------------------- AT-06 执行点拒绝

    @Test
    void outOfScopeWriteFileIsDeniedAtExecutionPointWithoutAnyFile() {
        Chain chain = buildChain("fake-write-ma04", FakeModelGateway.PROVIDER);

        SubagentResult result = subagentService.run(new SubagentRequest(chain.runId(),
                "CODER", "fanout-file evil/b.txt :: pwned-ma04", List.of(), null,
                SCOPE_ALLOWED));
        // 工具被有界拒绝后 Loop 正常收尾：子 Run 自身 COMPLETED，但没有可汇合改动
        assertEquals("COMPLETED", result.state(), result.stopReason());
        assertNull(result.patchArtifactId(), "越界写入不得产出 patch");
        assertTrue(result.artifactRefs().isEmpty());

        Path childWorkspace = workspaceManager.resolveByRef(result.workspaceRef()).dir();
        assertFalse(Files.exists(childWorkspace.resolve("evil/b.txt")),
                "执行点拒绝必须保证文件从未产生");
        assertTrue(joinThread(threadOf(result.runId())).contains("写范围"),
                "拒绝原因必须回填模型上下文");
    }

    @Test
    void outOfScopeApplyPatchIsDeniedAtExecutionPointWithoutAnyFile() {
        Chain chain = buildChain("fake-patch-ma04", FakeModelGateway.PROVIDER);

        SubagentResult result = subagentService.run(new SubagentRequest(chain.runId(),
                "CODER", "please fix the bug now", List.of(), null, SCOPE_SRC));
        assertEquals("COMPLETED", result.state(), result.stopReason());
        assertNull(result.patchArtifactId());

        Path childWorkspace = workspaceManager.resolveByRef(result.workspaceRef()).dir();
        assertFalse(Files.exists(childWorkspace.resolve("fix.txt")),
                "补丁目标不在写范围：执行点拒绝，文件不得产生");
        assertTrue(joinThread(threadOf(result.runId())).contains("写范围"));
    }

    @Test
    void readOnlyScopeDeniesAnyWorkspaceWrite() {
        Chain chain = buildChain("fake-write-ma04", FakeModelGateway.PROVIDER);

        SubagentResult result = subagentService.run(new SubagentRequest(chain.runId(),
                "REVIEWER", "只读审阅，不要改文件", List.of(), null, SCOPE_READONLY));
        assertEquals("COMPLETED", result.state(), result.stopReason());
        assertNull(result.patchArtifactId(), "空写白名单=只读，任何写入都不得汇合");
        Path childWorkspace = workspaceManager.resolveByRef(result.workspaceRef()).dir();
        assertFalse(Files.exists(childWorkspace.resolve("written.txt")));
    }

    // ----------------------------------------------------- AT-06 封存对账

    @Test
    void runCommandWriteOutsideScopeFailsAtSealingButKeepsEvidence() {
        Chain chain = buildChain("shell-violation-model", ShellWriteGateway.PROVIDER);

        SubagentResult result = subagentService.run(new SubagentRequest(chain.runId(),
                "CODER", "用命令生成一个文件", List.of(), null, SCOPE_ALLOWED));

        // shell 落点无法静态证明：执行点不拦，封存点按真实 Diff 判越界
        assertEquals("FAILED", result.state());
        assertEquals("SCOPE_VIOLATION", result.stopReason());
        assertNull(result.patchArtifactId(), "越界改动不得产出可消费 patch");
        assertTrue(result.artifactRefs().isEmpty());
        Path childWorkspace = workspaceManager.resolveByRef(result.workspaceRef()).dir();
        assertTrue(Files.exists(childWorkspace.resolve("evil.txt")),
                "证据目录必须保留越界文件供审计，不偷偷删除");
        assertTrue(result.summary().contains("evil.txt"));
        // 预算仍按真实消耗实结（工作发生过），不是无账失败
        assertTrue(result.modelRequestsUsed() >= 2);
    }

    // ----------------------------------------------------- 非法范围 / 配额

    @Test
    void invalidScopeJsonRejectedBeforeRunAndBudgetReservation() {
        Chain chain = buildChain("fake-answer-ma04", FakeModelGateway.PROVIDER);

        for (String invalid : List.of(
                "{\"write\":[\"C:/Windows/evil\"]}",
                "{\"write\":[\"../escape.txt\"]}",
                "{\"write\":[\"/abs/path\"]}",
                "{not-json")) {
            SubagentResult result = subagentService.run(new SubagentRequest(chain.runId(),
                    "CODER", "非法范围任务", List.of(), null, invalid));
            assertEquals("FAILED", result.state(), "scope=" + invalid);
            assertEquals("INVALID_ARGUMENT", result.stopReason(), "scope=" + invalid);
        }
        assertTrue(runRepository.listChildren(chain.runId()).isEmpty(),
                "非法 scope 在预约/建 Run 前拒绝，不得有子 Run");
        Object[] counters = runRepository.readBudgetCounters(chain.runId()).get(0);
        assertArrayEquals(new Object[]{0, 0, null, 0}, counters,
                "非法 scope 不得移动父账预算");
    }

    @Test
    void concurrencyLimitIsBoundedWithoutChildRunOrBudgetMovement() {
        Chain chain = buildChain("fake-answer-ma04", FakeModelGateway.PROVIDER);

        // 默认全局上限 8：用不同根/提供方键占满全局许可（不触碰本测试根的 root/provider 池）
        List<WorkerConcurrencyManager.Lease> occupied = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                occupied.add(concurrencyManager.acquire(
                        "root-ma04-busy-" + UUID.randomUUID(), "holder-busy-" + i,
                        "provider-busy-" + i, Instant.now().plusSeconds(300)));
            }
            SubagentResult result = subagentService.run(new SubagentRequest(chain.runId(),
                    "CODER", "配额已满时启动", List.of(), null));
            assertEquals("FAILED", result.state());
            assertEquals("CONCURRENCY_LIMIT", result.stopReason());
            assertTrue(runRepository.listChildren(chain.runId()).isEmpty(),
                    "并发已满不得创建子 Run");
            Object[] counters = runRepository.readBudgetCounters(chain.runId()).get(0);
            assertArrayEquals(new Object[]{0, 0, null, 0}, counters,
                    "CONCURRENCY_LIMIT 不得预约/移动任何预算");
        } finally {
            occupied.forEach(concurrencyManager::release);
        }
    }

    @Test
    void resourceLeaseIsHeldDuringRunAndReleasedAtTerminalState() {
        Chain chain = buildChain("fake-answer-ma04", FakeModelGateway.PROVIDER);

        SubagentResult result = subagentService.run(new SubagentRequest(chain.runId(),
                "ARCHITECT", "正常完成的子任务", List.of(), null));
        assertEquals("COMPLETED", result.state(), result.stopReason());

        assertEquals(0, leaseRepository.countByRootRunIdAndState(
                chain.runId(), ResourceLeaseEntity.HELD), "终态后不得残留 HELD 租约");
        ResourceLeaseEntity released = leaseRepository
                .findFirstByHolderRunIdAndStateOrderByAcquiredAtDesc(
                        result.runId(), ResourceLeaseEntity.RELEASED)
                .orElseThrow(() -> new AssertionError("子 Run 必须留有 RELEASED 租约行"));
        assertEquals(FakeModelGateway.PROVIDER, released.getProviderKey());
        assertNotNull(released.getReleasedAt());
    }

    // ------------------------------------------------------- helpers

    private Chain buildChain(String model, String provider) {
        Instant now = Instant.now();
        String projectId = "proj-ma04-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA04配额", projectRoot.toString(),
                true, now, now));

        String profileId = "prof-ma04-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, provider + "-" + model,
                provider, model, "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));

        String threadId = IdGen.next("thr_ma04_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "父会话", profileId, now, now));
        String runId = IdGen.next("run_ma04_");
        String turnId = IdGen.next("turn_ma04_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest-ma04", 12, 30, 200_000,
                now.plusSeconds(600), now, now));
        return new Chain(projectId, profileId, threadId, turnId, runId);
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

    private record Chain(String projectId, String profileId, String threadId,
                         String turnId, String runId) {
    }

    /** 首轮用 run_command 写一个文件，收到结果后终止：用于封存点越界对账。 */
    @TestConfiguration
    static class ShellGatewayConfig {
        @Bean
        ShellWriteGateway ma04ShellWriteGateway() {
            return new ShellWriteGateway();
        }
    }

    static class ShellWriteGateway implements ModelGateway {
        static final String PROVIDER = "ma04-shell";

        @Override
        public String provider() {
            return PROVIDER;
        }

        @Override
        public ModelResponse generate(ModelRequest request, CancellationToken token) {
            boolean afterTool = request.messages().stream()
                    .anyMatch(m -> "tool".equals(m.role()));
            if (afterTool) {
                return new ModelResponse("命令执行完毕。", List.of(), FinishReason.STOP,
                        new Usage("KNOWN", 12, 5, null, null, null, null, null),
                        IdGen.next("mresp_"));
            }
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            String command = windows ? "echo ma04seal>evil.txt" : "printf 'ma04seal\\n' > evil.txt";
            String arguments = "{\"command\":" + "\"" + command.replace("\\", "\\\\") + "\"}";
            return new ModelResponse("",
                    List.of(new ModelToolCall(IdGen.next("call_"), "run_command", arguments)),
                    FinishReason.TOOL_CALLS,
                    new Usage("KNOWN", 11, 0, null, null, null, null, null),
                    IdGen.next("mresp_"));
        }
    }
}
