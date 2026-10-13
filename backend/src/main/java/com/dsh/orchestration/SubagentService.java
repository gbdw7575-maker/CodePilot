package com.dsh.orchestration;

import com.dsh.agent.control.BudgetLedger;
import com.dsh.agent.control.CancellationTokenRegistry;
import com.dsh.application.DshProperties;
import com.dsh.application.VerificationPlan;
import com.dsh.application.WorkspaceDiffService;
import com.dsh.api.dto.Diff;
import com.dsh.api.dto.RunRole;
import com.dsh.common.IdGen;
import com.dsh.storage.ArtifactEntity;
import com.dsh.storage.ArtifactRepository;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.TaskAttemptRepository;
import com.dsh.storage.TaskAttemptEntity;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 子 Agent 执行服务（DSH-010，开发文档 §12）。
 *
 * <p>为一次 run_subagent 调用建立隔离的子 thread/turn/run，原子预约父预算切片，
 * 在同一线程同步执行完整子 Loop（取消经令牌链传播），终态后把产物 diff 落子 Run、
 * 实结预算并返回 {@link SubagentResult}。子上下文与父消息天然隔离，事件经
 * EventMeta.parentRunId 关联，不新增事件类型。</p>
 */
@Service
public class SubagentService {

    private static final Logger log = LoggerFactory.getLogger(SubagentService.class);

    /** 根族链向上查找的最大步数（与 PlanService 同口径，防御环/脏数据）。 */
    private static final int ROOT_WALK_LIMIT = 32;

    private static final Set<String> TRUSTED_ROLES =
            Set.of(RunRole.ARCHITECT.name(), RunRole.CODER.name(),
                    RunRole.TESTER.name(), RunRole.REVIEWER.name());

    /** 角色 → 系统视角提示词（可信内置，用户任务仅作为数据嵌入）。 */
    private static final Map<String, String> ROLE_PROMPTS = Map.of(
            RunRole.ARCHITECT.name(),
            "你是架构师子 Agent。职责：分析需求与现有代码，给出技术方案、模块划分、关键接口与风险。"
                    + "不要直接修改代码；输出必须包含：方案概述、改动点清单、风险与回滚考虑。",
            RunRole.CODER.name(),
            "你是工程师子 Agent。职责：在工作区内完成给定编码任务并自检（编译/测试）。"
                    + "只做任务要求的最小改动；输出必须包含：改动文件清单、验证方式与结果、遗留问题。",
            RunRole.TESTER.name(),
            "你是测试工程师子 Agent。工作区是工程师产物的冻结快照（基线为工程师副本基线）。"
                    + "职责：阅读改动并运行/编写测试验证，输出：验证结论（通过/不通过）、证据、失败详情。"
                    + "除测试本身需要的改动外不要修改产品代码。",
            RunRole.REVIEWER.name(),
            "你是评审员子 Agent。工作区是工程师产物的冻结快照。职责：审查正确性、可维护性、"
                    + "安全与与需求的一致性。输出必须包含：结论（APPROVE/REQUEST_CHANGES）、"
                    + "分级问题清单（文件+原因）、总体风险评估。"
    );

    private final RunRepository runRepository;
    private final TurnRepository turnRepository;
    private final ThreadRepository threadRepository;
    private final ProjectRepository projectRepository;
    private final ModelProfileRepository profileRepository;
    private final BudgetLedger budgetLedger;
    private final CancellationTokenRegistry tokenRegistry;
    private final WorkspaceManager workspaceManager;
    private final WorkspaceDiffService workspaceDiffService;
    private final ChildRunFactory childRunFactory;
    private final ChildRunLifecycle childLifecycle;
    private final DshProperties properties;
    private final RunCapabilityPolicy capabilityPolicy;
    private final ArtifactRepository artifactRepository;
    private final WorkerConcurrencyManager concurrencyManager;
    private final InputSnapshotService inputSnapshotService;
    private final WriteScopePolicy writeScopePolicy;
    private final TaskAttemptRepository taskAttemptRepository;
    private final DependencyMaterializationService dependencyMaterializationService;

    public SubagentService(RunRepository runRepository, TurnRepository turnRepository,
                           ThreadRepository threadRepository, ProjectRepository projectRepository,
                           ModelProfileRepository profileRepository, BudgetLedger budgetLedger,
                           CancellationTokenRegistry tokenRegistry,
                           WorkspaceManager workspaceManager,
                           WorkspaceDiffService workspaceDiffService,
                           ChildRunFactory childRunFactory, ChildRunLifecycle childLifecycle,
                           DshProperties properties, RunCapabilityPolicy capabilityPolicy,
                           ArtifactRepository artifactRepository,
                           WorkerConcurrencyManager concurrencyManager,
                           InputSnapshotService inputSnapshotService,
                           WriteScopePolicy writeScopePolicy,
                           TaskAttemptRepository taskAttemptRepository,
                           DependencyMaterializationService dependencyMaterializationService) {
        this.runRepository = runRepository;
        this.turnRepository = turnRepository;
        this.threadRepository = threadRepository;
        this.projectRepository = projectRepository;
        this.profileRepository = profileRepository;
        this.budgetLedger = budgetLedger;
        this.tokenRegistry = tokenRegistry;
        this.workspaceManager = workspaceManager;
        this.workspaceDiffService = workspaceDiffService;
        this.childRunFactory = childRunFactory;
        this.childLifecycle = childLifecycle;
        this.properties = properties;
        this.capabilityPolicy = capabilityPolicy;
        this.artifactRepository = artifactRepository;
        this.concurrencyManager = concurrencyManager;
        this.inputSnapshotService = inputSnapshotService;
        this.writeScopePolicy = writeScopePolicy;
        this.taskAttemptRepository = taskAttemptRepository;
        this.dependencyMaterializationService = dependencyMaterializationService;
    }

    /**
     * 同步执行一个子 Run。永不向调用方抛出：预算不足/深度超限等返回 state=FAILED 的有界结果；
     * 子 Loop 内部异常已由 AgentLoop 转为子 Run FAILED 终态。
     *
     * <p>NOT_SUPPORTED 是关键：子 Loop 内部混用 REQUIRED/REQUIRES_NEW 短事务步骤，若在父
     * runTools 事务内同步执行，子步骤会加入父长事务，锁根子 thread 事件游标并与 REQUIRES_NEW
     * 步骤自锁。挂起外层事务后，reserve/settle/各步骤均为独立短事务，行为与顶层 Run 一致。</p>
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public SubagentResult run(SubagentRequest request) {
        long startNanos = System.nanoTime();
        String role = request.role() == null ? "" : request.role().trim().toUpperCase();
        if (!TRUSTED_ROLES.contains(role)) {
            return syntheticFailure("", role, "INVALID_ARGUMENT",
                    "未知子 Agent 角色: " + request.role(), startNanos);
        }
        RunEntity parent = runRepository.findById(request.parentRunId()).orElse(null);
        if (parent == null) {
            return syntheticFailure("", role, "NOT_FOUND",
                    "父 Run 不存在: " + request.parentRunId(), startNanos);
        }
        TurnEntity parentTurn = turnRepository.findById(parent.getTurnId()).orElseThrow();
        ThreadEntity parentThread = threadRepository.findById(parentTurn.getThreadId()).orElseThrow();

        // MA-03 服务入口双重验证（规范 §7：Dispatcher 与服务入口双重验证）：
        // 只有 MAIN 角色 Run 可派生。即使绕过工具层直调本服务（maxDepth=2 时深度检查
        // 拦不住 worker 再派生一层），也在此 fail-closed：不创建子 Run、不动预算。
        if (!capabilityPolicy.isMainRole(parent.getRole())) {
            return syntheticFailure("", role, "WORKER_SPAWN_DENIED",
                    "worker 子角色 Run 不能派生子 Agent（仅主 Agent MAIN 可派生）: "
                            + request.parentRunId(), startNanos);
        }

        String parentRootRunId = rootRunIdOf(parent);

        // MA-03 产物引用授权（规范 §4.1/§7：禁止模型自行指定跨租户/跨 Run 资源）：
        // 引用必须存在；项目级产物须同项目；Run 级产物属主必须在同一根 Run 族内。
        String artifactError = authorizeArtifactRefs(request.artifactRefs(),
                parentThread.getProjectId(), parentRootRunId);
        if (artifactError != null) {
            String[] parts = artifactError.split(":", 2);
            return syntheticFailure("", role, parts[0], parts[1], startNanos);
        }

        int depth = depthOf(parent);
        if (depth + 1 > properties.orchestration().maxDepth()) {
            return syntheticFailure("", role, "DEPTH_LIMIT",
                    "子 Agent 嵌套深度超过上限 " + properties.orchestration().maxDepth(), startNanos);
        }

        // 预算切片（MA-04，规范 §10.1）：可分配=根上限−已结算−进行中预约−主任务保留。
        // 配置子上限与“扣除主保留后的父剩余”取小；token 未知，按切片全额保守预约。
        DshProperties.Orchestration.Quota quota = properties.orchestration().quota();
        int mrRemain = parent.getBudgetMaxModelRequests() - parent.getBudgetModelRequestsUsed()
                - quota.mainReserveModelRequests();
        int tcRemain = parent.getBudgetMaxToolCalls() - parent.getBudgetToolCallsUsed()
                - quota.mainReserveToolCalls();
        int tokRemain = parent.getBudgetMaxCumulativeTokens()
                - nz(parent.getBudgetSettledTokens()) - parent.getBudgetReservedTokens()
                - quota.mainReserveTokens();
        int sliceMr = Math.min(Math.max(mrRemain, 0), properties.orchestration().subMaxModelRequests());
        int sliceTc = Math.min(Math.max(tcRemain, 0), properties.orchestration().subMaxToolCalls());
        int sliceTok = Math.min(Math.max(tokRemain, 0), properties.orchestration().subMaxTokens());
        if (sliceMr <= 0 || sliceTc <= 0 || sliceTok <= 0) {
            return syntheticFailure("", role, "BUDGET_LIMIT",
                    "父 Run 剩余预算（含主任务保留 mr=" + quota.mainReserveModelRequests()
                            + "/tc=" + quota.mainReserveToolCalls()
                            + "/tok=" + quota.mainReserveTokens()
                            + "）不足以启动子 Agent", startNanos);
        }
        Instant now = Instant.now();
        Instant deadline = parent.getBudgetDeadlineAt() == null
                ? now.plusSeconds(properties.orchestration().subTimeoutSeconds())
                : minInstant(parent.getBudgetDeadlineAt(),
                        now.plusSeconds(properties.orchestration().subTimeoutSeconds()));
        if (!deadline.isAfter(now)) {
            return syntheticFailure("", role, "BUDGET_LIMIT",
                    "父 Run 已到截止时间，不能启动子 Agent", startNanos);
        }

        String childRunId = IdGen.next("sub_run_");
        String workspaceRef = IdGen.next("ws_");
        String baseDigest = "";

        // 种子工作区：从写者冻结副本复制并继承基线（Tester/Reviewer）。
        // MA-03：复制前先做服务端授权——源副本属主 Run 必须存在、同项目且在同一根 Run 族内，
        // 拒绝模型靠猜测 ws_* 引用跨 Run/跨项目读产物（授权先于任何字节复制）。
        if (request.seedWorkspace()) {
            RunEntity seedOwner = runRepository
                    .findFirstByWorkspaceRefOrderByCreatedAtDesc(request.seedWorkspaceRef())
                    .orElse(null);
            if (seedOwner == null
                    || !isSameProject(seedOwner, parentThread.getProjectId())
                    || !parentRootRunId.equals(rootRunIdOf(seedOwner))) {
                return syntheticFailure(childRunId, role, "PERMISSION_DENIED",
                        "种子工作区未授权：只能引用同一根 Run 族内、同项目 Run 的工作区副本: "
                                + request.seedWorkspaceRef(), startNanos);
            }
            try {
                ProjectEntity project = projectRepository
                        .findById(parentThread.getProjectId()).orElseThrow();
                WorkspaceLease seeded = workspaceManager
                        .prepareFromWorkspace(childRunId, project, request.seedWorkspaceRef());
                workspaceRef = seeded.workspaceId();
                // MA-04：种子副本基线即冻结输入摘要（执行/封存对照版本）。
                baseDigest = seeded.baselineDigest() == null ? "" : seeded.baselineDigest();
            } catch (RuntimeException e) {
                return syntheticFailure(childRunId, role, "WORKSPACE_UNAVAILABLE",
                        "种子工作区准备失败: " + e.getMessage(), startNanos);
            }
        }

        ModelProfileEntity profile = profileRepository
                .findById(parentThread.getModelProfileId()).orElseThrow();

        // MA-04 输入快照（规范 §7/§8.1）：预约/启动前冻结基线、产物哈希与归一化写范围。
        // 写范围非法属模型可纠正的参数错误：不建 Run、不动预算、不获取租约。
        WriteScopePolicy.Scope writeScope;
        try {
            writeScope = inputSnapshotService.freeze(childRunId, parentRootRunId,
                    parentThread.getProjectId(), workspaceRef, baseDigest,
                    request.artifactRefs(), request.writeScopeJson()).scope();
        } catch (IllegalArgumentException e) {
            return syntheticFailure(childRunId, role, "INVALID_ARGUMENT",
                    "输入冻结失败: " + e.getMessage(), startNanos);
        }

        // MA-07 AT-12 依赖产物物化（§8.1）：子任务启动前把上游 SUCCEEDED 产物实际写入
        // 子工作区 upstream/ 目录，使下游能真正编译/使用而非仅凭 prompt 摘要。多依赖
        // 同名冲突进入 INPUT_CONFLICT→BLOCKED（§10.2）。物化在 freeze 之后、并发/预算
        // 预约之前：冲突即有界失败，不占并发槽位与预算。
        DependencyMaterializationService.MaterializeResult materialized;
        try {
            materialized = dependencyMaterializationService.materialize(childRunId,
                    parentThread.getProjectId(), request.artifactRefs());
        } catch (RuntimeException e) {
            return syntheticFailure(childRunId, role, "WORKSPACE_UNAVAILABLE",
                    "上游产物物化异常: " + e.getMessage(), startNanos);
        }
        if (!materialized.ok()) {
            return syntheticFailure(childRunId, role, materialized.errorCode(),
                    "上游产物物化失败: " + materialized.errorMessage(), startNanos);
        }

        // MA-04 资源租约（规范 §6.1/§6.2）：全局/根 Run/提供方三级并发同时满足才放行，
        // 已满有界失败；后续一切出口（含预约失败/启动异常）都在 finally 幂等释放。
        WorkerConcurrencyManager.Lease concurrencyLease;
        try {
            concurrencyLease = concurrencyManager.acquire(parentRootRunId, childRunId,
                    profile.getProvider(), deadline);
        } catch (WorkerConcurrencyManager.QuotaFullException e) {
            return syntheticFailure(childRunId, role, "CONCURRENCY_LIMIT",
                    e.getMessage(), startNanos);
        }

        boolean restoreInterrupt = false;
        try {
            if (!budgetLedger.reserveChildBudget(parent.getId(), sliceMr, sliceTc, sliceTok,
                    quota.mainReserveModelRequests(), quota.mainReserveToolCalls(),
                    quota.mainReserveTokens())) {
                return syntheticFailure(childRunId, role, "BUDGET_LIMIT",
                        "父 Run 预算预约失败（含主任务保留的三维原子条件更新未通过）", startNanos);
            }

            String firstMessage = buildFirstMessage(role, request.task(), request.artifactRefs(),
                    request.seedWorkspaceRef(), writeScope, parentThread.getProjectId(), parent.getVerificationRequirement() != null);
            try {
                childRunFactory.create(childRunId, parent, parentThread, profile, role, firstMessage,
                        workspaceRef, sliceMr, sliceTc, sliceTok, deadline);

                // MA-05 取消传播绑定：子 Run 一落库立即把 childRunId 写回 attempt（独立事务自动
                // 提交；本方法整体 NOT_SUPPORTED 无活动事务），使取消 RUNNING 任务时能据此
                // 请求模型取消令牌，不必等终态收敛。
                if (request.attemptId() != null) {
                    taskAttemptRepository.findById(request.attemptId()).ifPresent(a -> {
                        a.setChildRunId(childRunId);
                        a.setUpdatedAt(Instant.now());
                        taskAttemptRepository.save(a);
                    });
                }

                // 取消传播链必须在 begin 前挂好；register 对已存在令牌幂等。
                tokenRegistry.registerLinked(parent.getId(), childRunId);
                log.info("子 Agent 启动 parent={} child={} role={} 预算切片 mr={}/tc={}/tok={}",
                        parent.getId(), childRunId, role, sliceMr, sliceTc, sliceTok);
                childLifecycle.execute(parent.getId(), childRunId);
                restoreInterrupt |= Thread.interrupted();
            } catch (RuntimeException e) {
                restoreInterrupt |= Thread.interrupted();
                // 环境级失败仍计入已发生消耗；未知 token 按切片保守结算，不能按零释放。
                log.error("子 Agent 启动/执行异常 parent={} child={} role={}",
                        parent.getId(), childRunId, role, e);
                try {
                    RunEntity failedChild = runRepository.findById(childRunId).orElse(null);
                    budgetLedger.settleChildBudget(parent.getId(), sliceMr,
                            failedChild == null ? 0 : failedChild.getBudgetModelRequestsUsed(), sliceTc,
                            failedChild == null ? 0 : failedChild.getBudgetToolCallsUsed(), sliceTok,
                            failedChild == null ? 0 : failedChild.getBudgetSettledTokens() == null
                                    ? sliceTok : failedChild.getBudgetSettledTokens());
                } catch (RuntimeException releaseError) {
                    log.warn("子 Agent 失败后释放预算预约失败 parent={}: {}",
                            parent.getId(), releaseError.getMessage());
                }
                return syntheticFailure(childRunId, role, "ENVIRONMENT_ERROR",
                        "子 Agent 执行异常: " + e.getMessage(), startNanos);
            }

            RunEntity child = childRunFactory.load(childRunId);

            // 产物 diff：仅在工作区真实存在时计算；无改动不引用 patch 产物。
            String patchArtifactId = null;
            String workspaceDigest = "";
            List<String> scopeViolations = List.of();
            if (workspaceExists(childRunId, child.getWorkspaceRef())) {
                try {
                    Diff diff = workspaceDiffService.compute(childRunId);
                    workspaceDigest = diff.workspaceDigest() == null ? "" : diff.workspaceDigest();
                    if (diff.files() != null && !diff.files().isEmpty()) {
                        // MA-04 §8.2：shell/补丁等实际落点无法在执行前证明时，封存点按真实
                        // Diff 与冻结写范围对账，越界改动不产出可消费 patch（证据目录保留）。
                        List<String> changed = diff.files().stream()
                                .map(com.dsh.api.dto.DiffFile::path).toList();
                        scopeViolations = writeScopePolicy.violatingPaths(writeScope, changed);
                        if (scopeViolations.isEmpty()) {
                            patchArtifactId = diff.artifactId();
                        } else {
                            log.warn("子 Run 封存写范围越界 child={} violated={}",
                                    childRunId, scopeViolations);
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("子 Run diff 计算失败 child={}: {}", childRunId, e.getMessage());
                }
            }

            int usedMr = child.getBudgetModelRequestsUsed();
            int usedTc = child.getBudgetToolCallsUsed();
            // 规范 §10.1：usage 未知（settledTokens 仍为 null）按切片全额保守实结，
            // 保留保守负债，不能把未知当零释放预约。
            int usedTok = child.getBudgetSettledTokens() == null
                    ? sliceTok : child.getBudgetSettledTokens();
            budgetLedger.settleChildBudget(parent.getId(), sliceMr, usedMr, sliceTc, usedTc,
                    sliceTok, usedTok);

            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            if (!scopeViolations.isEmpty()) {
                String detail = "封存时发现 " + scopeViolations.size() + " 个越出写范围的变更: "
                        + scopeViolations.stream().limit(10).toList()
                        + "（结果不汇合，请按授权范围返工）";
                return new SubagentResult(childRunId, role, "FAILED", "SCOPE_VIOLATION",
                        abbreviate(detail, 4000), List.of(), null,
                        child.getWorkspaceRef(), workspaceDigest,
                        usedMr, usedTc, child.getBudgetSettledTokens(), durationMs);
            }
            List<String> artifactRefs = patchArtifactId == null
                    ? List.of() : List.of(patchArtifactId);
            return new SubagentResult(childRunId, role, child.getState(),
                    child.getStopReason(), abbreviate(child.getSummary(), 4000),
                    artifactRefs, patchArtifactId, child.getWorkspaceRef(), workspaceDigest,
                    usedMr, usedTc, child.getBudgetSettledTokens(), durationMs);
        } finally {
            restoreInterrupt |= Thread.interrupted();
            try { concurrencyManager.release(concurrencyLease); }
            finally { if (restoreInterrupt) Thread.currentThread().interrupt(); }
        }
    }

    private boolean workspaceExists(String childRunId, String workspaceRef) {
        WorkspaceLease lease = workspaceManager.find(childRunId);
        if (lease != null) {
            return true;
        }
        if (workspaceRef == null || workspaceRef.isBlank()) {
            return false;
        }
        try {
            WorkspaceManager.WorkspacePaths paths = workspaceManager.resolveByRef(workspaceRef);
            return java.nio.file.Files.isDirectory(paths.dir());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 父链深度：MAIN 父为 0，每多一层 +1（worker 禁派生，正常最多 1）。 */
    private int depthOf(RunEntity run) {
        int depth = 0;
        String parentId = run.getParentRunId();
        while (parentId != null) {
            depth++;
            RunEntity ancestor = runRepository.findById(parentId).orElse(null);
            if (ancestor == null) {
                break;
            }
            parentId = ancestor.getParentRunId();
        }
        return depth;
    }

    /** 沿父链上溯到根 RunId（MAIN Run 返回自身 id）；链断时以最后可见节点为根。 */
    private String rootRunIdOf(RunEntity run) {
        RunEntity current = run;
        for (int i = 0; i < ROOT_WALK_LIMIT; i++) {
            if (current.getParentRunId() == null) {
                return current.getId();
            }
            RunEntity ancestor = runRepository.findById(current.getParentRunId()).orElse(null);
            if (ancestor == null) {
                return current.getId();
            }
            current = ancestor;
        }
        return current.getId();
    }

    /** 经 Run→Turn→Thread 取项目 id；链路缺失返回 null（调用方按越权处理）。 */
    private String projectIdOf(RunEntity run) {
        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElse(null);
        if (turn == null) {
            return null;
        }
        ThreadEntity thread = threadRepository.findById(turn.getThreadId()).orElse(null);
        return thread == null ? null : thread.getProjectId();
    }

    private boolean isSameProject(RunEntity run, String projectId) {
        return projectId != null && projectId.equals(projectIdOf(run));
    }

    /**
     * MA-03：校验父 Agent 提供的产物引用全部在授权边界内。
     * 返回 null 表示通过；否则返回 "REASON:message"（REASON ∈ INVALID_ARGUMENT/PERMISSION_DENIED）。
     *
     * <p>项目级产物（runId 为空）须同项目；Run 级产物的属主 Run 须在同一根 Run 族内——
     * 兄弟/父子子 Run 的已产出产物可引用，其他根 Run/项目的产物一律拒绝。</p>
     */
    private String authorizeArtifactRefs(List<String> refs, String projectId, String rootRunId) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        for (String ref : refs) {
            ArtifactEntity artifact = artifactRepository.findById(ref).orElse(null);
            if (artifact == null) {
                return "INVALID_ARGUMENT:引用的父运行产物不存在: " + ref;
            }
            if (!projectId.equals(artifact.getProjectId())) {
                return "PERMISSION_DENIED:产物属于其他项目，不能在本根 Run 使用: " + ref;
            }
            if (artifact.getRunId() != null && !artifact.getRunId().isBlank()) {
                RunEntity owner = runRepository.findById(artifact.getRunId()).orElse(null);
                if (owner == null || !rootRunId.equals(rootRunIdOf(owner))) {
                    return "PERMISSION_DENIED:产物属于其他根 Run，不能跨 Run 族读取: " + ref;
                }
            }
        }
        return null;
    }

    private String buildFirstMessage(String role, String task, List<String> artifactRefs,
                                     String seedRef, WriteScopePolicy.Scope writeScope,
                                     String projectId, boolean runScopedAcceptance) {
        StringBuilder sb = new StringBuilder();
        sb.append(ROLE_PROMPTS.get(role)).append("\n\n");
        sb.append("【任务】\n").append(task == null ? "" : task.strip()).append("\n");
        if (artifactRefs != null && !artifactRefs.isEmpty()) {
            // MA-07 AT-12：上游产物已物化到子工作区 upstream/ 目录，可直接读取使用。
            sb.append("\n【上游产物】已物化到工作区 upstream/ 目录（").append(artifactRefs.size())
                    .append(" 个），可直接读取使用；产物 ID：\n- ")
                    .append(String.join("\n- ", artifactRefs)).append("\n");
        }
        if (seedRef != null && !seedRef.isBlank()) {
            sb.append("\n【工作区】你的工作区复制自上游工作区冻结快照（ref=")
                    .append(seedRef).append("），其基线即合并基线。\n");
        }
        appendVerificationContract(sb, projectId);
        if (runScopedAcceptance) sb.append("\n【验收范围】本子任务采用 GENERAL 的项目回归验证；父 Run 登记的缺陷复现由父侧集成结果单独核验，子任务通过不等于根缺陷已修复。\n");
        appendWriteScope(sb, writeScope);
        sb.append("\n完成后直接给出最终答复；你的最终答复会作为子结果返回给父 Agent，")
                .append("不要等待额外输入或人工确认。");
        return sb.toString();
    }

    /**
     * MA-11c-2：把项目自验契约（{@code .dsh/verify.yaml}，缺失时按项目类型自动探测）
     * 显式告知子 Agent。
     *
     * <p>背景（验收记录 §11.7）：子 Agent 此前不知道项目有既定验证入口，只能在
     * {@code /tmp} 等位置自造临时检查程序再编译执行——单轮委派实测 194 次 {@code run_command}
     * 中绝大多数属此类，并带动上下文 4–5 倍膨胀、缓存命中率跌至 2%。验证契约本已存在，
     * 但此前只用于根侧任务验证闸门，未传递到子 Run。这里只陈述既有事实，不新增权限。</p>
     */
    private void appendVerificationContract(StringBuilder sb, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        ProjectEntity project = projectRepository.findById(projectId).orElse(null);
        if (project == null || project.getRootPath() == null) {
            return;
        }
        VerificationPlan plan;
        try {
            plan = VerificationPlan.load(java.nio.file.Path.of(project.getRootPath()));
        } catch (RuntimeException e) {
            return;
        }
        sb.append("\n【验证方式】本项目既有验证命令（工作区内执行，勿另建）：\n  ")
                .append(plan.commandSummary()).append("\n");
        if (!plan.expectTests()) {
            sb.append("该命令不要求发现测试用例，成功即视为自验通过。\n");
        }
        sb.append("**必须使用上述命令验证你的改动，不要为验证而在工作区外或临时目录")
                .append("编写新的检查程序、脚本或辅助类**——那会造成额外文件与上下文开销，")
                .append("且不属于任务交付物。若该命令因环境原因无法执行，请在答复中如实说明，")
                .append("不要用自造脚手架替代。\n");
    }

    /** MA-11c-2：把冻结写范围显式告知子 Agent，避免封存点判 SCOPE_VIOLATION 才返工。 */
    private void appendWriteScope(StringBuilder sb, WriteScopePolicy.Scope writeScope) {
        if (writeScope == null) {
            return;
        }
        if (writeScope.unrestricted()) {
            sb.append("\n【写范围】本任务未限制写范围，但**只允许修改工作区内的相对路径**；")
                    .append("写入工作区外的绝对路径（如 /tmp）会在封存点被判越界，")
                    .append("导致整个任务失败且不可重试。\n");
            return;
        }
        sb.append("\n【写范围】只允许修改下列工作区内相对路径：\n- ")
                .append(String.join("\n- ", writeScope.writePatterns())).append("\n");
        if (writeScope.writePatterns().isEmpty()) {
            sb.append("（本任务为只读/审阅任务，不得修改任何文件。）\n");
        }
        sb.append("**写入工作区外的绝对路径（如 /tmp）会被封存点判 SCOPE_VIOLATION，")
                .append("整个任务失败且不可重试。**\n");
    }

    private SubagentResult syntheticFailure(String runId, String role, String reason,
                                            String message, long startNanos) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info("子 Agent 有界快败 runId={} role={} reason={} detail={}",
                runId, role, reason, message);
        return new SubagentResult(runId, role, "FAILED", reason, message,
                List.of(), null, IdGen.next("ws_"), "", 0, 0, null, durationMs);
    }

    private static Instant minInstant(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
