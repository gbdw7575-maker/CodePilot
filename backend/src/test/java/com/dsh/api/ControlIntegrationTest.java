package com.dsh.api;

import com.dsh.agent.control.ApprovalService;
import com.dsh.agent.control.BudgetLedger;
import com.dsh.agent.control.CancellationToken;
import com.dsh.agent.control.CancellationTokenRegistry;
import com.dsh.agent.control.CheckpointService;
import com.dsh.agent.control.PolicyEngine;
import com.dsh.agent.control.RunControlService;
import com.dsh.api.dto.ApprovalDecision;
import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.RunRole;
import com.dsh.application.DshProperties;
import com.dsh.common.Digest;
import com.dsh.common.IdGen;
import com.dsh.storage.ApprovalEntity;
import com.dsh.storage.ApprovalRepository;
import com.dsh.storage.CheckpointEntity;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-005 控制面验收（开发准备与验收.md §2）：
 * 预算原子不透支、审批不复用、未知副作用不重放、取消传播、检查点恢复不覆盖用户修改。
 */
class ControlIntegrationTest extends ApiTestBase {

    @Autowired
    private RunRepository runRepository;
    @Autowired
    private TurnRepository turnRepository;
    @Autowired
    private ThreadRepository threadRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private BudgetLedger budgetLedger;
    @Autowired
    private PolicyEngine policyEngine;
    @Autowired
    private ApprovalService approvalService;
    @Autowired
    private ApprovalRepository approvalRepository;
    @Autowired
    private CancellationTokenRegistry tokenRegistry;
    @Autowired
    private RunControlService runControlService;
    @Autowired
    private CheckpointService checkpointService;
    @Autowired
    private WorkspaceManager workspaceManager;
    @Autowired
    private DshProperties properties;

    private static final String DIGEST_A = Digest.sha256Hex("params-a");
    private static final String DIGEST_B = Digest.sha256Hex("params-b");
    private static final String WS_DIGEST_A = Digest.sha256Hex("workspace-a");

    /** 直接在数据库创建 Turn + Run（绕过 Loop），用于控制面隔离测试。 */
    private String[] createQueuedRun(String threadId, int maxModelRequests) {
        Instant now = Instant.now();
        String runId = IdGen.next("run_");
        String turnId = IdGen.next("turn_");
        TurnEntity turn = new TurnEntity(turnId, threadId, runId, "控制面测试", "QUEUED",
                "PENDING", threadId, now, now);
        turnRepository.save(turn);
        RunEntity run = new RunEntity(runId, turnId, null, RunRole.MAIN.name(), "QUEUED",
                "PENDING", 1, IdGen.next("ws_"), "config_test",
                maxModelRequests, properties.budget().maxToolCalls(),
                properties.budget().maxCumulativeTokens(),
                now.plusSeconds(properties.budget().deadlineMinutes() * 60L), now, now);
        runRepository.save(run);
        return new String[]{runId, turnId};
    }

    // ===== CTRL-01: 并发额度不透支 =====

    @Test
    void concurrentBudgetReserveDoesNotOverspend() throws Exception {
        String threadId = bootstrapThread();
        int budget = 3;
        String[] ids = createQueuedRun(threadId, budget);
        String runId = ids[0];
        int threads = 10;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return budgetLedger.reserveModelRequests(runId, 1);
                }));
            }
            start.countDown();
            AtomicInteger successes = new AtomicInteger();
            for (Future<Boolean> f : futures) {
                if (f.get(30, TimeUnit.SECONDS)) {
                    successes.incrementAndGet();
                }
            }
            assertEquals(budget, successes.get(), "成功预约数必须等于预算上限，不允许透支");
            RunEntity run = runRepository.findById(runId).orElseThrow();
            assertEquals(budget, run.getBudgetModelRequestsUsed(), "数据库用量必须等于预算上限");
        } finally {
            pool.shutdownNow();
        }
    }

    // ===== CTRL-02: 审批不复用 =====

    @Test
    void approvalCannotBeConsumedWithStaleDigest() throws Exception {
        String threadId = bootstrapThread();
        String[] ids = createQueuedRun(threadId, 5);
        String runId = ids[0];
        String turnId = ids[1];

        ApprovalEntity approval = approvalService.request(turnId, runId, "call_01", "apply_patch",
                "修改 src/Main.java", "{\"path\":\"src/Main.java\"}", DIGEST_A, WS_DIGEST_A, "MEDIUM");
        assertEquals("PENDING", approval.getStatus());

        approvalService.decide(approval.getId(), ApprovalDecision.Decision.APPROVE,
                DIGEST_A, WS_DIGEST_A);
        ApprovalEntity decided = approvalRepository.findById(approval.getId()).orElseThrow();
        assertEquals("APPROVED", decided.getStatus());

        // consume 用错误摘要 → 返回 false，审批置 INVALIDATED
        boolean consumed = approvalService.consume(approval.getId(), DIGEST_B, WS_DIGEST_A);
        assertFalse(consumed, "摘要不匹配时 consume 必须拒绝执行");
        ApprovalEntity invalidated = approvalRepository.findById(approval.getId()).orElseThrow();
        assertEquals("INVALIDATED", invalidated.getStatus(),
                "摘要不匹配后审批必须置为 INVALIDATED，不可复用");

        // 再次用正确摘要 consume 也必须失败（已 INVALIDATED）
        boolean secondConsume = approvalService.consume(approval.getId(), DIGEST_A, WS_DIGEST_A);
        assertFalse(secondConsume, "INVALIDATED 审批不可被再次 consume");
    }

    @Test
    void approvedToolCallCanBeConsumedOnlyOnceBeforeSideEffect() throws Exception {
        String threadId = bootstrapThread();
        String[] ids = createQueuedRun(threadId, 5);
        ApprovalEntity approval = approvalService.request(ids[1], ids[0], "call-once", "apply_patch",
                "一次性写工具", "{\"path\":\"src/Main.java\"}", DIGEST_A, WS_DIGEST_A, "MEDIUM");
        approvalService.decide(approval.getId(), ApprovalDecision.Decision.APPROVE,
                DIGEST_A, WS_DIGEST_A);
        assertTrue(approvalService.consume(approval.getId(), DIGEST_A, WS_DIGEST_A));
        assertEquals("CONSUMED", approvalRepository.findById(approval.getId()).orElseThrow().getStatus());
        assertFalse(approvalService.consume(approval.getId(), DIGEST_A, WS_DIGEST_A),
                "持久化消费标记后不得重新执行同一审批工具调用");
    }

    // ===== CTRL-03: 未知副作用不重放 =====

    @Test
    void unknownSideEffectsAreDenied() {
        // DELEGATE 始终 DENY
        assertEquals(PolicyEngine.Decision.DENY, policyEngine.evaluate("DELEGATE"),
                "DELEGATE 副作用必须 DENY");

        // 未知字符串 DENY
        assertEquals(PolicyEngine.Decision.DENY, policyEngine.evaluate("NUKE_EVERYTHING"),
                "未知副作用必须 DENY，不允许重放");

        // null/空 → ALLOW（安全默认）
        assertEquals(PolicyEngine.Decision.ALLOW, policyEngine.evaluate(null),
                "空副作用应 ALLOW");
        assertEquals(PolicyEngine.Decision.ALLOW, policyEngine.evaluate(""),
                "空字符串应 ALLOW");

        // READ_ONLY / NONE → ALLOW
        assertEquals(PolicyEngine.Decision.ALLOW, policyEngine.evaluate("READ_ONLY"));
        assertEquals(PolicyEngine.Decision.ALLOW, policyEngine.evaluate("NONE"));

        // WORKSPACE_WRITE / EXECUTE → REQUIRE_APPROVAL（测试配置含两者）
        assertEquals(PolicyEngine.Decision.REQUIRE_APPROVAL, policyEngine.evaluate("WORKSPACE_WRITE"));
        assertEquals(PolicyEngine.Decision.REQUIRE_APPROVAL, policyEngine.evaluate("EXECUTE"));
    }

    // ===== CTRL-04: 取消传播 =====

    @Test
    void cancelPropagatesToInMemoryToken() throws Exception {
        String threadId = bootstrapThread();
        String[] ids = createQueuedRun(threadId, 5);
        String runId = ids[0];

        // 模拟 Loop 已启动：注册令牌 + 置 RUNNING
        tokenRegistry.register(runId);
        RunEntity run = runRepository.findById(runId).orElseThrow();
        run.setState("RUNNING");
        run.setUpdatedAt(Instant.now());
        runRepository.saveAndFlush(run);

        CancellationToken token = tokenRegistry.get(runId);
        assertNotNull(token, "取消前令牌必须存在");
        assertFalse(token.isCancelled());

        RunEntity cancelled = runControlService.cancel(runId, "USER_CANCELLED");
        assertEquals("CANCELLING", cancelled.getState(),
                "有活动令牌时取消应进入 CANCELLING（而非 BLOCKED）");
        assertTrue(token.isCancelled(), "内存令牌必须被置位，Loop 安全点将检测到");

        // 清理
        runControlService.finalizeCancellation(runId);
        RunEntity finalRun = runRepository.findById(runId).orElseThrow();
        assertEquals("CANCELLED", finalRun.getState());
    }

    @Test
    void cancelFromWaitingApprovalWithoutTokenFinalizesCancelled() throws Exception {
        String threadId = bootstrapThread();
        String[] ids = createQueuedRun(threadId, 5);
        String runId = ids[0];

        // 不注册令牌（WAITING_APPROVAL 时 Loop 本就不在内存）
        RunEntity run = runRepository.findById(runId).orElseThrow();
        run.setState("WAITING_APPROVAL");
        run.setUpdatedAt(Instant.now());
        runRepository.saveAndFlush(run);

        RunEntity result = runControlService.cancel(runId, "USER_CANCELLED");
        assertEquals("CANCELLED", result.getState(),
                "暂停态取消无清理不确定性，必须直接落 CANCELLED，不能停在 BLOCKED");

        TurnEntity turn = turnRepository.findById(ids[1]).orElseThrow();
        assertEquals("CANCELLED", turn.getState(), "Turn 必须同步终态");
        ThreadEntity thread = threadRepository.findById(threadId).orElseThrow();
        assertNull(thread.getActiveTurnId(), "会话活跃指针必须清空，前端才能解锁输入");
    }

    @Test
    void cancelRunningWithoutTokenEntersBlocked() throws Exception {
        String threadId = bootstrapThread();
        String[] ids = createQueuedRun(threadId, 5);
        String runId = ids[0];

        // 活动态（RUNNING）但内存无令牌：模拟 Loop 崩溃/断线
        RunEntity run = runRepository.findById(runId).orElseThrow();
        run.setState("RUNNING");
        run.setUpdatedAt(Instant.now());
        runRepository.saveAndFlush(run);

        RunEntity result = runControlService.cancel(runId, "USER_CANCELLED");
        assertEquals("BLOCKED", result.getState(),
                "活动态 Loop 失联时取消必须进入 BLOCKED，等待人工 resume");

        // BLOCKED 可以 resume
        RunEntity resumed = runControlService.resume(runId);
        assertEquals("RUNNING", resumed.getState());
        assertEquals(2, resumed.getAttempt(), "resume 必须 attempt+1");
    }

    // ===== CTRL-05: 恢复不覆盖用户修改 =====

    @Test
    void restoreRejectsStaleDigestAndPreservesUserChanges() throws Exception {
        // 创建带真实文件的项目目录（与现有测试一致：使用 target 下路径）
        String dirName = "test-ctrl05-" + UUID.randomUUID().toString().substring(0, 8);
        Path sourceRoot = Path.of("target").toAbsolutePath().resolve(dirName);
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("README.md"), "原始内容");

        String projectId = createProject("ctrl05-" + UUID.randomUUID(), sourceRoot.toString().replace('\\', '/'));
        trustProject(projectId, 1);
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();

        // 创建已信任项目下的会话 + Turn + Run
        String profileId = createProfile("ctrl05-profile-" + UUID.randomUUID());
        String threadId = createThread(projectId, profileId);
        String[] ids = createQueuedRun(threadId, 5);
        String runId = ids[0];

        // 准备工作区副本
        WorkspaceLease lease = workspaceManager.prepare(runId, project);
        assertNotNull(lease);

        // 更新 Run.workspaceRef 为实际 workspaceId（checkpointService.restore 靠它定位）
        RunEntity run = runRepository.findById(runId).orElseThrow();
        run.setWorkspaceRef(lease.workspaceId());
        runRepository.saveAndFlush(run);

        // capture 检查点（摘要 = D1）
        CheckpointEntity checkpoint = checkpointService.capture(runId, lease);
        String checkpointDigest = checkpoint.getWorkspaceDigest();
        assertNotNull(checkpointDigest);

        // 用户修改工作区（摘要变为 D2 ≠ D1）
        Files.writeString(lease.dir().resolve("user-edit.txt"), "用户修改");
        String currentDigest = WorkspaceManager.digestOf(lease.dir());
        assertFalse(currentDigest.equals(checkpointDigest),
                "修改后工作区摘要必须不同于检查点摘要");

        // 用当前摘要（D2）作为 expectedWorkspaceDigest 恢复 → 必须拒绝（D2 ≠ D1）
        ApiException ex = assertThrows(ApiException.class,
                () -> checkpointService.restore(checkpoint.getId(), currentDigest));
        assertEquals(ErrorCode.WORKSPACE_CONFLICT, ex.code(),
                "摘要不匹配必须返回 WORKSPACE_CONFLICT，不允许覆盖用户修改");

        // 验证工作区未被修改（用户修改仍在）
        assertTrue(Files.exists(lease.dir().resolve("user-edit.txt")),
                "恢复失败后用户修改必须保留");
        assertTrue(Files.exists(lease.dir().resolve("README.md")),
                "原始文件必须保留");

        // 用检查点摘要（D1）恢复 → 成功（用户修改被检查点内容覆盖）
        com.dsh.api.dto.RestoreResult result = checkpointService.restore(
                checkpoint.getId(), checkpointDigest);
        assertNotNull(result);
        assertEquals(checkpoint.getId(), result.checkpointId());
        assertFalse(Files.exists(lease.dir().resolve("user-edit.txt")),
                "成功恢复后用户修改应被检查点内容覆盖");
        assertTrue(Files.exists(lease.dir().resolve("README.md")),
                "检查点中的文件应恢复");

        // 清理
        tokenRegistry.release(runId);
    }
}
