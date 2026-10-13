package com.dsh.agent;

import com.dsh.agent.control.ApprovalService;
import com.dsh.agent.control.CheckpointService;
import com.dsh.agent.control.PolicyEngine;
import com.dsh.agent.model.FinishReason;
import com.dsh.agent.model.CachePrefix;
import com.dsh.agent.model.ModelMessage;
import com.dsh.agent.model.ModelRequest;
import com.dsh.agent.model.ModelResponse;
import com.dsh.agent.model.ModelToolCall;
import com.dsh.agent.tool.AwaitTasksTool;
import com.dsh.agent.tool.RunToolMountStore;
import com.dsh.agent.tool.ToolDefinition;
import com.dsh.agent.tool.ToolDispatcher;
import com.dsh.agent.tool.ToolRegistry;
import com.dsh.api.dto.Usage;
import com.dsh.api.dto.event.EventPayloads;
import com.dsh.api.dto.event.EventType;
import com.dsh.api.dto.event.ContentBlock;
import com.dsh.api.dto.event.ToolOutcomeDto;
import com.dsh.application.ArtifactStore;
import com.dsh.application.DshProperties;
import com.dsh.application.event.EventMeta;
import com.dsh.application.event.EventStore;
import com.dsh.application.trace.TraceContext;
import com.dsh.memory.MemoryAssembler;
import com.dsh.memory.MemoryPolicy;
import com.dsh.rules.RuleLoader;
import com.dsh.rules.RulePrompt;
import com.dsh.common.Digest;
import com.dsh.common.IdGen;
import com.dsh.storage.ApprovalEntity;
import com.dsh.storage.MessageEntity;
import com.dsh.storage.MessageRepository;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.dsh.storage.TaskWaitEntity;
import com.dsh.storage.VerificationRepository;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AgentLoop 的事务边界集合（内部接口契约 §3）：
 * 每个方法是一个短事务，业务状态变更与事件在同一事务提交；
 * 模型调用本身在事务外执行，只有完整响应与账本在事务内落库。
 */
@Service
public class LoopSteps {
    @org.springframework.beans.factory.annotation.Autowired
    private com.dsh.orchestration.acceptance.RootTaskAcceptanceModes taskAcceptanceModes;

    public static final String ST_QUEUED = "QUEUED";
    public static final String ST_PREPARING = "PREPARING";
    public static final String ST_RUNNING = "RUNNING";
    public static final String ST_VERIFYING = "VERIFYING";
    public static final String ST_WAITING_INPUT = "WAITING_INPUT";
    public static final String ST_WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String ST_CANCELLING = "CANCELLING";
    public static final String ST_CANCELLED = "CANCELLED";
    public static final String ST_BLOCKED = "BLOCKED";
    public static final String ST_COMPLETED = "COMPLETED";
    public static final String ST_FAILED = "FAILED";

    public static final String ROLE_ASSISTANT_TOOL = "assistant_tool";
    public static final String ROLE_TOOL = "tool";

    private final RunRepository runRepository;
    private final TurnRepository turnRepository;
    private final ThreadRepository threadRepository;
    private final MessageRepository messageRepository;
    private final ModelProfileRepository profileRepository;
    private final EventStore eventStore;
    private final ToolDispatcher toolDispatcher;
    private final ObjectMapper objectMapper;
    private final PolicyEngine policyEngine;
    private final ApprovalService approvalService;
    private final WorkspaceManager workspaceManager;
    private final CheckpointService checkpointService;
    private final ToolRegistry toolRegistry;
    private final RunToolMountStore runToolMountStore;
    private final TraceContext traceContext;
    private final RuleLoader ruleLoader;
    private final DshProperties properties;
    private final ArtifactStore artifactStore;
    private final VerificationRepository verificationRepository;
    private final com.dsh.application.VerificationEvidenceContext evidenceContext;
    private final CompletionReviewService completionReview;
    private final MemoryPolicy memoryPolicy;
    private final MemoryAssembler memoryAssembler;
    private final com.dsh.orchestration.RunCapabilityPolicy capabilityPolicy;

    /**
     * 历史压缩策略（A1/A2）。默认 {@link CompactionMode#WINDOW_LASTUSER}，与既有行为逐字一致；
     * {@link CompactionMode#BUDGET_WATERMARK} 为修复口径。
     */
    private final CompactionMode compactionMode;
    /** 累计预算水位百分比（%）；&lt;=0 表示不按水位触发。仅在 BUDGET_WATERMARK 口径下生效。 */
    private final int compactionWatermarkPercent;
    /** 保护区内逐字保留的最近历史单元数。仅在 BUDGET_WATERMARK 口径下生效。 */
    private final int protectedTailUnits;
    /** C23: early artifact-backed result folding; zero keeps the existing request view. */
    private final int replayProtectedTailUnits;
    /**
     * 是否折叠「未归档」的历史工具结果。默认 false：保持既有视图不变。
     *
     * <p>为什么需要它：D-08 只把超过 {@value #LARGE_TOOL_OUTPUT_CHARS} 字符的输出另存产物，
     * 而现有折叠路径只对有产物的结果生效。实测（8459，生产默认档）一轮请求中工具结果占
     * 23,789 / 55,532 token（43%），59 条工具结果里却只有 2 条有产物 ⇒ 折叠几乎不生效，
     * 真正的问题是**大量小输出持续累积**（均值约 1,776 字符）。
     */
    private final boolean replayFoldUnarchived;
    /** 折叠未归档结果时保留的片段字符数（有界，非完整）。 */
    private final int unarchivedFoldExcerptChars;

    /** D-08：工具输出正文超过此字符数时，完整输出另存 ArtifactStore，消息只存摘要+产物引用。 */
    public static final int LARGE_TOOL_OUTPUT_CHARS = 8000;

    public LoopSteps(RunRepository runRepository, TurnRepository turnRepository,
                     ThreadRepository threadRepository, MessageRepository messageRepository,
                     ModelProfileRepository profileRepository, EventStore eventStore,
                     ToolDispatcher toolDispatcher, ObjectMapper objectMapper,
                     PolicyEngine policyEngine, ApprovalService approvalService,
                     WorkspaceManager workspaceManager, CheckpointService checkpointService,
                     ToolRegistry toolRegistry, RunToolMountStore runToolMountStore,
                     TraceContext traceContext, RuleLoader ruleLoader, DshProperties properties,
                     ArtifactStore artifactStore, VerificationRepository verificationRepository,
                     MemoryPolicy memoryPolicy, MemoryAssembler memoryAssembler,
                     com.dsh.orchestration.RunCapabilityPolicy capabilityPolicy,
                     @Value("${dsh.loop.history-compaction-mode:WINDOW_LASTUSER}")
                     String historyCompactionMode,
                     @Value("${dsh.loop.compaction-watermark-percent:70}")
                     int compactionWatermarkPercent,
                     @Value("${dsh.loop.compaction-protected-tail-units:5}")
                     int compactionProtectedTailUnits,
                     @Value("${dsh.loop.replay-protected-tail-units:0}") int replayProtectedTailUnits,
                     @Value("${dsh.loop.replay-fold-unarchived:false}") boolean replayFoldUnarchived,
                     @Value("${dsh.loop.replay-unarchived-excerpt-chars:600}")
                     int unarchivedFoldExcerptChars,
                     com.dsh.application.VerificationEvidenceContext evidenceContext, CompletionReviewService completionReview) {
        this.runRepository = runRepository;
        this.turnRepository = turnRepository;
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
        this.profileRepository = profileRepository;
        this.eventStore = eventStore;
        this.toolDispatcher = toolDispatcher;
        this.objectMapper = objectMapper;
        this.policyEngine = policyEngine;
        this.approvalService = approvalService;
        this.workspaceManager = workspaceManager;
        this.checkpointService = checkpointService;
        this.toolRegistry = toolRegistry;
        this.runToolMountStore = runToolMountStore;
        this.traceContext = traceContext;
        this.ruleLoader = ruleLoader;
        this.properties = properties;
        this.artifactStore = artifactStore;
        this.verificationRepository = verificationRepository;
        this.evidenceContext = evidenceContext;
        this.completionReview = completionReview;
        this.memoryPolicy = memoryPolicy;
        this.memoryAssembler = memoryAssembler;
        this.capabilityPolicy = capabilityPolicy;
        this.compactionMode = CompactionMode.parse(historyCompactionMode);
        this.compactionWatermarkPercent = compactionWatermarkPercent;
        this.protectedTailUnits = Math.max(0, compactionProtectedTailUnits);
        this.replayProtectedTailUnits = Math.max(0, replayProtectedTailUnits);
        this.replayFoldUnarchived = replayFoldUnarchived;
        this.unarchivedFoldExcerptChars = Math.max(0, unarchivedFoldExcerptChars);
    }

    /** 一轮模型调用后 Loop 的去向。 */
    public enum RoundResult {
        /** 工具结果已回填，继续下一轮模型调用。 */
        CONTINUE,
        /** 暂停于 WAITING_INPUT，等待补充输入触发恢复。 */
        PAUSED,
        /** 暂停于 WAITING_APPROVAL，等待审批决策触发恢复。 */
        PAUSED_FOR_APPROVAL,
        /**
         * MA-05：暂停于 await_tasks 等待任务终态。Run 保持 RUNNING、线程与事务释放，
         * 唤醒后由 LoopScheduler 串行恢复（规范 §9 串行邮箱等待语义）。
         */
        PAUSED_FOR_TASKS,
        /** 已到终态。 */
        TERMINAL
    }

    /** 模型轮次前的只读快照（实体在事务外只读使用）。 */
    public record RoundSnapshot(String runId, String state, String provider, String model,
                                int maxOutputTokens, String thinking, int modelRequestsUsed,
                                int maxModelRequests, int settledTokens, int maxCumulativeTokens,
                                Instant deadline, int reservedTokens) {
        public RoundSnapshot(String runId, String state, String provider, String model,
                             int maxOutputTokens, String thinking, int modelRequestsUsed,
                             int maxModelRequests, int settledTokens, int maxCumulativeTokens,
                             Instant deadline) {
            this(runId, state, provider, model, maxOutputTokens, thinking, modelRequestsUsed,
                    maxModelRequests, settledTokens, maxCumulativeTokens, deadline, 0);
        }
    }

    /**
     * QUEUED → PREPARING → RUNNING，Turn 同事务转 RUNNING；非 QUEUED 启动是 no-op。
     * REQUIRES_NEW：同步调度在 afterCommit 回调内执行，外层（已提交）事务资源此刻尚未解绑，
     * REQUIRED 会误加入无活动连接的旧上下文；每一步都挂起外层并开启真实新事务。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean begin(String runId) {
        RunEntity run = runRepository.findById(runId).orElse(null);
        if (run == null || !ST_QUEUED.equals(run.getState())) {
            return false;
        }
        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElseThrow();
        ThreadEntity thread = threadRepository.findByIdForUpdate(turn.getThreadId()).orElseThrow();
        EventMeta meta = meta(thread, run);
        run = transition(run, ST_PREPARING, meta);
        run = transition(run, ST_RUNNING, meta);

        turn.setState(ST_RUNNING);
        turn.setUpdatedAt(Instant.now());
        turnRepository.save(turn);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RoundSnapshot snapshot(String runId) {
        RunEntity run = runRepository.findById(runId).orElseThrow();
        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElseThrow();
        ModelProfileEntity profile = profileRepository.findById(
                threadRepository.findById(turn.getThreadId()).orElseThrow().getModelProfileId()).orElseThrow();
        int settled = run.getBudgetSettledTokens() == null ? 0 : run.getBudgetSettledTokens();
        return new RoundSnapshot(runId, run.getState(), profile.getProvider(), profile.getModel(),
                profile.getOptionsMaxOutputTokens(), profile.getOptionsThinking(),
                run.getBudgetModelRequestsUsed(), run.getBudgetMaxModelRequests(),
                settled, run.getBudgetMaxCumulativeTokens(), run.getBudgetDeadlineAt(),
                run.getBudgetReservedTokens());
    }

    /**
     * 返回非空表示预算已不允许再发起模型调用，值为失败说明。
     *
     * <p>MA-11 语义明确：maxCumulativeTokens 是 Run 的累计支出上限，分两处判定——</p>
     * <ol>
     *   <li><b>准入</b>（本方法）：下一轮模型请求发起前，已结算 {@code >=} 上限即拒绝；
     *       任一维度触顶都拒绝，因为任何新请求都会把实际支出推到上限之外。</li>
     *   <li><b>终态</b>（{@link #budgetBreachAfterSettlement}）：响应结算后已结算 {@code >} 上限
     *       时，不得以成功终态收尾。</li>
     * </ol>
     * 两处合并后上限才是严格支出上限；只保留准入判定时，最后一次响应可以超额仍判成功。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<String> exceededBudget(RoundSnapshot snapshot) {
        if (snapshot.modelRequestsUsed() >= snapshot.maxModelRequests()) {
            return Optional.of("模型请求预算耗尽");
        }
        if ((long) snapshot.settledTokens() + snapshot.reservedTokens() >= snapshot.maxCumulativeTokens()) {
            return Optional.of("累计 Token 预算耗尽");
        }
        if (Instant.now().isAfter(snapshot.deadline())) {
            return Optional.of("运行已超过预算截止时间");
        }
        return Optional.empty();
    }

    /**
     * MA-11 终态预算判定：本次响应结算后累计 token 是否已越过上限。
     * 越过（严格大于）上限时返回失败说明；等于上限视为恰好用尽，允许以终态收尾。
     * maxCumulativeTokens 非正视为未设上限，不做终态判定。
     */
    static Optional<String> budgetBreachAfterSettlement(long settledTokens, long maxCumulativeTokens) {
        if (maxCumulativeTokens <= 0 || settledTokens <= maxCumulativeTokens) {
            return Optional.empty();
        }
        return Optional.of("累计 Token 结算后越过预算上限（已结算 " + settledTokens
                + " > 上限 " + maxCumulativeTokens + "），本次响应不得以成功终态收尾");
    }

    static Optional<String> budgetBreachAfterSettlement(RunEntity run) {
        long settled = run.getBudgetSettledTokens() == null ? 0L : run.getBudgetSettledTokens();
        return budgetBreachAfterSettlement(settled, run.getBudgetMaxCumulativeTokens());
    }

    /**
     * D-19 预算软提醒：消耗达 70%/85% 阈值时返回追加到 system 末尾的段落（含分隔标题），
     * 引导模型从探索收敛到产出最终补丁。低于 70% 返回 null（不修改 system）。
     *
     * <p>取累计 Token 与模型请求次数两个维度的最大消耗率作为档位依据，
     * 任一维度触达即提醒；阈值只升不降，避免在边界附近抖动。
     * 非任何基准特化：仅基于 Run 自身的预算与剩余，不引用任何外部任务集。
     *
     * <p><b>前缀稳定性（2026-10-06 修复）</b>：本段被拼进 system 消息，而
     * {@code prefixHash} 覆盖 system 内容（见本文件 assemble:
     * {@code CachePrefix.compute(messages.get(0).content(), tools)}）。
     * 此前逐点插值实时百分比，导致越过 70% 后<b>每个请求</b>文本都变一次，
     * 前缀缓存每轮全部失效。实测四个运行：哈希开始逐轮变化的请求序号，
     * 与 tokenRate 越过 70% 的请求序号精确重合（42/41、52/51、48/48、50/50），
     * 且该处 rate 均为 71~72%。
     * 现改为只输出档位值，使同一档位内文本逐字节稳定 —— 这正是本段上方注释
     * （assemble 内 D-19 调用处）原本声明的「前缀缓存至多失效 2 次」。
     */
    static String budgetAdvisoryText(RoundSnapshot snapshot) {
        long maxTokens = snapshot.maxCumulativeTokens();
        long maxRequests = snapshot.maxModelRequests();
        int tokenRate = maxTokens <= 0 ? 0
                : (int) Math.min(100, snapshot.settledTokens() * 100L / maxTokens);
        int requestRate = maxRequests <= 0 ? 0
                : (int) Math.min(100, snapshot.modelRequestsUsed() * 100L / maxRequests);
        int rate = Math.max(tokenRate, requestRate);
        if (rate < 70) {
            return null;
        }
        String level = rate >= 85 ? "剩余预算即将耗尽" : "预算已消耗过半";
        String guidance = rate >= 85
                ? "立即停止进一步探索与重复验证，基于已收集到的信息产出最终补丁"
                  + "（write_file/apply_patch）；若确实无法定位根因，优先输出已确认的部分修复"
                  + "而非继续尝试新方向。"
                : "请开始聚焦到产出最终修改：收敛探索范围，优先修复已定位的根因，"
                  + "避免长时间继续搜索或读取尚未涉及的文件。";
        // 只输出档位值，不输出实时百分比：同一档位内文本逐字节稳定，前缀缓存至多失效 2 次。
        int tier = rate >= 85 ? 85 : 70;
        return "\n\n## 运行状态提醒\n"
                + level + "（累计消耗已达 " + tier + "% 档位）。" + guidance + "\n";
    }

    /**
     * A1/A2 历史压缩策略口径。改这类开关必须保持既有默认行为不变（回退用），
     * 未知配置值保守回退到默认口径，避免拼写错误悄悄改变运行时行为。
     */
    /** Read-only snapshot of the actual normalized values used by this Loop instance. */
    public java.util.Map<String,Object> executionSettings() {
        var settings = new java.util.HashMap<String,Object>(java.util.Map.of("loop.historyCompactionMode",compactionMode.name(),
                "loop.compactionWatermarkPercent",compactionWatermarkPercent,
                "loop.compactionProtectedTailUnits",protectedTailUnits,
                "loop.replayProtectedTailUnits",replayProtectedTailUnits));
        settings.putAll(completionReview.executionSettings());
        return java.util.Map.copyOf(settings);
    }

    enum CompactionMode {
        /** 既有口径：只在超窗时压缩；保护区＝最后一条 user 消息及其之后。 */
        WINDOW_LASTUSER,
        /**
         * 修复口径：除超窗外，另按累计预算水位触发压缩（A1）；
         * 保护区改为「最近 K 个执行单元」并始终逐字保留 user 消息（A2）。
         */
        BUDGET_WATERMARK;

        /** 宽松解析：null/空白/未知取值 → {@link #WINDOW_LASTUSER}。 */
        static CompactionMode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return WINDOW_LASTUSER;
            }
            String normalized = raw.trim();
            for (CompactionMode mode : values()) {
                if (mode.name().equalsIgnoreCase(normalized)) {
                    return mode;
                }
            }
            return WINDOW_LASTUSER;
        }
    }

    /**
     * A1：累计预算水位是否已到达。
     *
     * <p>为什么需要它：压缩原本只由「单请求上下文超窗」触发，而窗口是 12 万 token 级、
     * 评测侧累计预算是百万级 —— 每次请求只重发数万 token，**窗口检查在「累计预算先耗尽」
     * 的场景里永远不会触发**，累计消耗对压缩机制完全不可见（实测单请求上下文峰值 61,945
     * 远低于有效窗口 121,600，压缩一次都没生效）。
     *
     * <p>消耗率口径与 D-19 预算软提醒完全一致（同一组分母），避免两处结论互相矛盾。
     * 某一维度无上限时按「未知」处理（不计入），两个维度都未知则返回 false。
     */
    static boolean budgetWatermarkReached(RoundSnapshot snapshot, int watermarkPercent) {
        if (watermarkPercent <= 0) {
            return false;
        }
        long maxTokens = snapshot.maxCumulativeTokens();
        long maxRequests = snapshot.maxModelRequests();
        int tokenRate = maxTokens <= 0 ? -1
                : (int) Math.min(100, snapshot.settledTokens() * 100L / maxTokens);
        int requestRate = maxRequests <= 0 ? -1
                : (int) Math.min(100, snapshot.modelRequestsUsed() * 100L / maxRequests);
        return Math.max(tokenRate, requestRate) >= watermarkPercent;
    }

    /** 最后一条 user 消息在 stored 中的下标；无 user 返回 -1。 */
    private static int lastUserIndex(List<MessageEntity> stored) {
        for (int i = stored.size() - 1; i >= 0; i--) {
            if ("user".equals(stored.get(i).getRole())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A2：可折叠区间的上界（stored 下标，不含）。
     *
     * <p>旧口径把保护区锚在「最后一条 user 消息及其之后」。DSH 评测与 SWE-bench 都是
     * 「一条 user 消息 + 几十步执行」，此时该锚点等于**全部历史**，压缩两阶段都成了空操作：
     * 阶段一判据 {@code i < lastUserIdx} 恒为 false，阶段二在 {@code [1, lastUserIdx)} 内取不到
     * 任何单元。
     *
     * <p>新口径改为「保留最近 {@code protectedTailUnits} 个执行单元」，使单轮长任务也能折叠
     * 早期执行历史。一个执行单元＝连续的 tool 结果 + 其前一条消息，与
     * {@link #splitHistoryUnits} 的分组保持一致。
     *
     * @param protectedTailUnits &lt;=0 时等价于旧口径（返回最后一条 user 的下标）
     */
    static int compactableUpperBound(List<MessageEntity> stored, int protectedTailUnits) {
        if (protectedTailUnits <= 0) {
            return lastUserIndex(stored);
        }
        int i = stored.size() - 1;
        int units = 0;
        while (i >= 0 && units < protectedTailUnits) {
            while (i >= 0 && ROLE_TOOL.equals(stored.get(i).getRole())) {
                i--;
            }
            if (i >= 0) {
                i--; // 该单元的头部消息（assistant_tool 或普通消息）
            }
            units++;
        }
        return Math.max(0, i + 1);
    }

    /**
     * 从持久化消息（含内部工具消息）装配下一轮模型上下文。
     * D-04：前置稳定 system 前缀（固定基线 + 项目规则章节，带来源/版本）；
     * 目录作用域取当前 Turn 的用户原文和已成功访问的文件路径；用户原文逐字不改动。
     * D-08：超窗时先折叠旧大工具输出为摘要+产物引用，再对仍超窗的旧历史做摘要缩减。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ModelRequest assemble(String runId, RoundSnapshot snapshot) {
        return prepare(runId,snapshot).request();
    }

    public record PreparedRequest(ModelRequest request, CompletionReviewService.EntryContext entryContext,
                                  ContextProjectionMetrics contextMetrics) {
        public PreparedRequest(ModelRequest request, CompletionReviewService.EntryContext entryContext) {
            this(request, entryContext, null);
        }
    }

    /** C25: production projection boundary reused by deterministic public-history replay. */
    List<ModelMessage> foldReplayOutputs(String runId, String projectId, List<ModelMessage> messages,
                                         HistoryCheckpoint.Context checkpoint, int protectedTailUnits) {
        if (protectedTailUnits <= 0) return messages;
        Map<String, Boolean> verified = new java.util.HashMap<>();
        return ToolOutputReplay.fold(messages, protectedTailUnits, view -> {
            MessageEntity source = checkpoint.sources.get(view);
            if (source == null) return view;
            try {
                ObjectNode raw = (ObjectNode) objectMapper.readTree(source.getContent());
                String id = raw.path("fullOutputArtifactId").asText("");
                if (id.isEmpty()) {
                    // 未归档的结果没有产物可回读，默认保持原视图；仅开启开关后折叠为有界片段。
                    return replayFoldUnarchived ? foldUnarchivedToolResult(raw, view, unarchivedFoldExcerptChars) : view;
                }
                var artifact = artifactStore.get(id);
                if (!runId.equals(artifact.getRunId()) || !projectId.equals(artifact.getProjectId())
                        || !artifact.isDownloadable() || artifact.getMediaType() == null
                        || !artifact.getMediaType().toLowerCase(java.util.Locale.ROOT).split(";", 2)[0].strip().equals("text/plain")
                        || !verified.computeIfAbsent(id, key -> artifactStore.verifyIntegrity(key, HistoryCheckpoint.MAX_RECOVERY_BYTES)))
                    return view;
                String structuredId = raw.path("structuredArtifactId").asText("");
                if (!structuredId.isEmpty()) {
                    var structuredArtifact = artifactStore.get(structuredId);
                    if (!runId.equals(structuredArtifact.getRunId())
                            || !projectId.equals(structuredArtifact.getProjectId())
                            || !structuredArtifact.isDownloadable()
                            || !"application/json".equals(structuredArtifact.getMediaType())
                            || !verified.computeIfAbsent(structuredId,
                                key -> artifactStore.verifyIntegrity(key, HistoryCheckpoint.MAX_RECOVERY_BYTES))) return view;
                }
                // Legacy fullOutputBytes stored character count; the new view uses actual sealed bytes.
                raw.put("fullOutputBytes", artifact.getSizeBytes());
                ModelMessage folded = readToolResult(raw.toString(), true);
                // Text evidence is recoverable from the full-output artifact. Inline structured
                // observations and non-text blocks may have no such archive: retain them verbatim.
                StringBuilder retained = new StringBuilder(folded.content());
                if (raw.hasNonNull("structuredContent"))
                    retained.append("\n[原始结构化观察]\n").append(raw.get("structuredContent"));
                for (JsonNode block : raw.path("content")) {
                    if (!"text".equals(block.path("type").asText()))
                        retained.append("\n[原始非文本内容]\n").append(block);
                }
                folded = ModelMessage.toolResult(view.toolCallId(), view.toolName(), retained.toString());
                checkpoint.bind(folded, source);
                return folded;
            } catch (RuntimeException | java.io.IOException unavailable) {
                return view; // Best effort: never discard the only available observation.
            }
        });
    }

    /**
     * 折叠「未归档」的历史工具结果。
     *
     * <p>折叠只作用于**送入模型的请求视图**：提交到 transcript 的原文一字不改，
     * 工具也可以重跑、文件可以重读。保留有界片段而不是清空，是为了让模型仍然知道
     * 「这一步做过什么、大致结果如何」，不至于连历史都没有。
     *
     * <p>安全性由 {@link ToolOutputReplay#fold} 的既有护栏保证：折叠结果必须保持
     * role / toolCallId / toolName / toolCalls / reasoningContent 一致，且估算 token
     * 必须**严格变小**，否则一律回退原消息 —— 因此开启本开关最坏情况是「没省下」，
     * 不会比关闭时更差，也不会破坏工具配对。
     */
    static ModelMessage foldUnarchivedToolResult(ObjectNode raw, ModelMessage view, int excerptChars) {
        StringBuilder sb = new StringBuilder();
        String status = raw.path("status").asText("");
        if (!status.isEmpty()) {
            sb.append('[').append(status).append(']');
        }
        if (raw.path("isError").asBoolean(false)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("[工具调用错误]");
        }
        String summary = raw.path("summary").asText("");
        if (!summary.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(summary);
        }
        String errorCode = raw.path("errorCode").asText("");
        if (!errorCode.isEmpty()) sb.append("\nerrorCode: ").append(errorCode);
        JsonNode exit = raw.get("exitCode");
        if (exit != null && !exit.isNull()) sb.append("\nexitCode: ").append(exit.asInt());

        // 文本正文取有界片段；非文本块与结构化观察没有产物可回读，原样保留。
        StringBuilder observed = new StringBuilder();
        StringBuilder retained = new StringBuilder();
        for (JsonNode block : raw.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                observed.append(HistoryCheckpoint.diagnosticExcerpt(block.path("text").asText())).append('\n');
            } else {
                retained.append("\n[原始非文本内容]\n").append(block);
            }
        }
        if (raw.hasNonNull("structuredContent"))
            retained.append("\n[原始结构化观察]\n").append(raw.get("structuredContent"));

        sb.append("\n[历史工具输出已折叠，以下为有界片段，不等于完整输出]")
                .append("\n[完整原文仍保留在本运行记录中；需要时可重跑该工具或重新读取文件]\n")
                .append(HistoryCheckpoint.bounded(observed.toString(), excerptChars))
                .append(retained);
        return ModelMessage.toolResult(view.toolCallId(), view.toolName(), sb.toString());
    }

    /** C13：请求前捕获的绑定随响应返回控制面，不加入ModelRequest或供应商协议。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreparedRequest prepare(String runId, RoundSnapshot snapshot) {
        RunEntity run = runRepository.findById(runId).orElseThrow();
        var entryContext=completionReview.entryContext(run);
        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElseThrow();
        ThreadEntity thread = threadRepository.findById(turn.getThreadId()).orElseThrow();
        boolean defectTask = (run.getRole() == null || "MAIN".equals(run.getRole()))
                && (run.getVerificationRequirement() != null
                ? "DEFECT_FIX".equals(com.dsh.application.RunVerificationRequirement.decode(run.getVerificationRequirement()).purpose())
                : entryContext != null && entryContext.plan().defectFix());
        if (defectTask && !java.nio.file.Files.isDirectory(workspaceManager.resolveByRef(run.getWorkspaceRef()).dir())) {
            if (run.getBudgetModelRequestsUsed() > 0)
                throw new IllegalStateException("缺陷验收的已冻结工作区丢失，不能重新选择基线");
            toolDispatcher.prepareWorkspace(run, thread.getProjectId());
            entryContext = completionReview.entryContext(run);
        }
        List<MessageEntity> stored = messageRepository.listByThread(turn.getThreadId());
        String ruleHints = ruleScopeHints(stored, turn.getId());
        HistoryCheckpoint.Context checkpoint = new HistoryCheckpoint.Context(objectMapper, stored);

        // 先以完整视图装配（工具输出正文保留，供模型基于真实内容继续）
        List<ModelMessage> messages = buildMessagesFromStored(stored, false, 0, checkpoint);
        // Fold the request view only; committed transcript and recent paired batches stay intact.
        List<ModelMessage> beforeReplay = messages;
        messages = foldReplayOutputs(runId, thread.getProjectId(), messages, checkpoint, replayProtectedTailUnits);
        var replayStage = ContextProjectionMetrics.ReplayStage.capture(beforeReplay, messages);
        RuleLoader.LoadedRules rules = ruleLoader.load(thread.getProjectId(), ruleHints);
        String baseSystem = RulePrompt.build(rules);
        ModelProfileEntity profile = profileRepository.findById(thread.getModelProfileId()).orElseThrow();
        // D-09 缓存布局：核心工具全量挂载 + 本 Run 经 tool_search 挂载的扩展工具（DSH-009，挂载按 Run 隔离）；
        // 按 name 排序保证跨请求顺序稳定，使 system+工具前缀可命中 DeepSeek 前缀缓存。
        List<ToolDefinition> tools = new ArrayList<>(toolRegistry.mountedDefinitions());
        for (ToolDefinition extension : runToolMountStore.mountedFor(runId, toolRegistry)) {
            if (tools.stream().noneMatch(d -> d.name().equals(extension.name()))) {
                tools.add(extension);
            }
        }
        tools.sort(Comparator.comparing(ToolDefinition::name));
        // MA-03 能力过滤（规范 §7：系统执行规则及当前角色允许的能力）：worker 子 Run 的模型
        // 请求目录不含计划改写/派生/调度工具。可见性过滤不替代执行点复查（Dispatcher）
        // 与服务入口复查（SubagentService），三层独立，防止靠历史定义或猜测名字越权。
        if (!capabilityPolicy.isMainRole(run.getRole())) {
            tools = tools.stream()
                    .filter(def -> !capabilityPolicy.isMainOnlyTool(def.name()))
                    .collect(java.util.stream.Collectors.toList());
        }

        if (completionReview.active(runId)) tools.removeIf(def -> !CompletionReviewService.READ_TOOLS.contains(def.name()));

        int window = properties.budget().contextWindowTokens();
        // 粗估不是供应商分词器；为 JSON 包装、不可见协议开销和语言差异留 5% 安全余量。
        int effectiveWindow = window - Math.max(1, window / 20);
        int systemBaseTokens = TokenEstimator.estimateMessage(ModelMessage.system(baseSystem));
        int toolsTokens = TokenEstimator.estimateTools(tools);
        int reservedOutput = snapshot.maxOutputTokens();
        int historyTokens = TokenEstimator.estimateMessages(messages);

        // S3 Policy 治理层：L4(语义)+L5(情景) 记忆段落按 MemoryPolicy 配额注入，
        // 位于 L6 基线（RulePrompt）之后、D-19 预算软提醒之前；配额耗尽或无内容时不追加。
        int memoryBudget = memoryPolicy.memoryBudget(effectiveWindow, systemBaseTokens,
                toolsTokens, reservedOutput, historyTokens);
        String memorySegment = memoryAssembler.assembleMemory(
                thread.getProjectId(), lastUserIntent(stored), memoryBudget);

        // D-19 预算软提醒：消耗过半后向 system 末尾追加收敛引导，避免模型在预算耗尽前持续发散探索。
        // 同一档位内文本逐字节稳定，档位只随预算消耗单调上升（0→70→85），前缀缓存至多失效 2 次。
        String budgetAdvisory = budgetAdvisoryText(snapshot);
        String progressHint = RollingProgressDetector.analyze(stored, turn.getId(), objectMapper).hint();
        String system = baseSystem + memorySegment
                + (budgetAdvisory == null ? "" : budgetAdvisory)
                + (progressHint == null ? "" : progressHint)
                + completionReview.entryPrompt(entryContext) + completionReview.prompt(runId);
        messages.add(0, ModelMessage.system(system));

        int systemTokens = TokenEstimator.estimateMessage(messages.get(0));
        int totalEstimated = TokenEstimator.calibrateInput(
                systemTokens + toolsTokens + historyTokens) + reservedOutput;

        // D-09：稳定前缀哈希（system 内容 + 排序后工具定义），用于校验同任务前缀一致性与缓存命中率
        String prefixHash = CachePrefix.compute(messages.get(0).content(), tools);

        boolean overWindow = totalEstimated > effectiveWindow;
        // A1：累计预算水位触发。窗口检查在「累计预算先耗尽」的场景里永远不会触发
        // （窗口 12 万级、累计预算百万级，每次请求只重发数万 token），故补一条按累计消耗的触发条件。
        // 水位路径是**尽力而为**：压不动就保持原样，绝不抛出、绝不比现状更差。
        boolean overWatermark = !overWindow
                && compactionMode == CompactionMode.BUDGET_WATERMARK
                && budgetWatermarkReached(snapshot, compactionWatermarkPercent);

        if (overWindow || overWatermark) {
            // A2：新口径下保护区由「最近 K 个单元」界定，单轮长任务才真正可折叠；旧口径传 0 保持不变。
            int tailUnits = compactionMode == CompactionMode.BUDGET_WATERMARK ? protectedTailUnits : 0;

            List<ModelMessage> compacted = compactHistory(stored, messages.get(0), systemTokens,
                    toolsTokens, reservedOutput, effectiveWindow, tailUnits, overWindow, historyTokens, checkpoint);
            // 超窗路径必须保证「启用新口径不比旧口径更差」：长会话里「最近 K 个单元」可能比
            // 「最后一条 user 及其之后」覆盖更多消息，压不回窗口时应回退到旧口径再试一次，
            // 只有旧口径也收敛不了才报错（否则新开关会把原本能跑的场景变成硬失败）。
            if (compacted == null && overWindow && tailUnits != 0) {
                compacted = compactHistory(stored, messages.get(0), systemTokens, toolsTokens,
                        reservedOutput, effectiveWindow, 0, true, historyTokens, checkpoint);
            }

            if (compacted != null) {
                messages = compacted;
                historyTokens = TokenEstimator.estimateMessages(
                        compacted.subList(1, compacted.size()));
                totalEstimated = TokenEstimator.calibrateInput(
                        systemTokens + toolsTokens + historyTokens) + reservedOutput;
            }
            // 水位路径压不动：保持原样继续，不回退成更差的行为。
        }

        // B03: 使用装配时的余额；最终原子预约还会重新检查，防止并发派发复用余额。
        long remaining = (long) run.getBudgetMaxCumulativeTokens()
                - nz(run.getBudgetSettledTokens()) - run.getBudgetReservedTokens();
        int estimatedInput = totalEstimated - reservedOutput;
        boolean cumulativePressure = CumulativeBudgetAdmission.inputCost(estimatedInput) + reservedOutput > remaining;
        if (cumulativePressure) {
            int tailUnits = compactionMode == CompactionMode.BUDGET_WATERMARK ? protectedTailUnits : 0;
            // 先争取保留配置输出额度；仍放不下时按最小输出再尝试一次。
            for (int desiredOutput : new int[]{reservedOutput,
                    CumulativeBudgetAdmission.minimumOutput(reservedOutput)}) {
                int target = (int) Math.min(effectiveWindow,
                        (long) CumulativeBudgetAdmission.inputLimit(remaining, desiredOutput) + desiredOutput);
                List<ModelMessage> compacted = compactHistory(stored, messages.get(0), systemTokens,
                        toolsTokens, desiredOutput, target, tailUnits, false, historyTokens, checkpoint);
                if (compacted != null) {
                    int compactedHistory = TokenEstimator.estimateMessages(compacted.subList(1, compacted.size()));
                    // partial collapse 可被采用；继续尝试摘要，让实际成本有机会降到目标以内。
                    List<ModelMessage> summarized = compressHistoryToWindow(compacted, systemTokens, toolsTokens,
                            desiredOutput, TokenEstimator.rawWindow(target, desiredOutput), tailUnits, checkpoint);
                    if (summarized != null && TokenEstimator.estimateMessages(summarized.subList(1, summarized.size())) < compactedHistory) {
                        compacted = summarized;
                        compactedHistory = TokenEstimator.estimateMessages(compacted.subList(1, compacted.size()));
                    }
                    if (compactedHistory < historyTokens) {
                        messages = compacted;
                        historyTokens = compactedHistory;
                        estimatedInput = TokenEstimator.calibrateInput(systemTokens + toolsTokens + historyTokens);
                    }
                }
                if (CumulativeBudgetAdmission.inputCost(estimatedInput) + reservedOutput <= remaining) break;
            }
            reservedOutput = CumulativeBudgetAdmission.outputLimit(estimatedInput, reservedOutput, remaining);
        }
        if ((long) estimatedInput + reservedOutput > effectiveWindow) {
            throw new ContextLimitException("上下文超出可用窗口 " + effectiveWindow
                    + " 且无法通过折叠大输出与摘要历史压回（system+tools+受保护历史+预留输出已超窗）");
        }
        // Only the finally selected projection is sealed; trial compression has no storage side effects.
        for (int i=1; i<messages.size(); i++) {
            if (!"assistant".equals(messages.get(i).role()) || messages.get(i).toolCalls()==null
                    || !messages.get(i).toolCalls().isEmpty()) continue;
            if (!checkpoint.projections.contains(messages.get(i).content())) continue;
            ObjectNode node = HistoryCheckpoint.parse(messages.get(i).content(), objectMapper);
            if (node == null || !"assistant".equals(messages.get(i).role())
                    || !checkpoint.candidates.containsKey(node.path("sourceDigest").asText())
                    || !HistoryCheckpoint.RECOVERY_PLACEHOLDER.equals(node.path("recovery").path("artifactId").asText())) continue;
            String key = node.path("sourceDigest").asText();
            byte[] evidence = checkpoint.recovery(key);
            var archive = artifactStore.saveCheckpointEvidence(runId, thread.getProjectId(), evidence);
            ((ObjectNode)node.path("recovery")).put("artifactId", archive.getId());
            messages.set(i, ModelMessage.assistant(HistoryCheckpoint.PREFIX + node));
        }
        estimatedInput = TokenEstimator.calibrateInput(systemTokens + toolsTokens
                + TokenEstimator.estimateMessages(messages.subList(1,messages.size())));
        reservedOutput = CumulativeBudgetAdmission.outputLimit(estimatedInput, reservedOutput, remaining);
        var contextMetrics = ContextProjectionMetrics.finish(replayStage, replayProtectedTailUnits, compactionMode.name(),
                messages, toolsTokens, estimatedInput, reservedOutput, overWindow, overWatermark, cumulativePressure);
        return new PreparedRequest(new ModelRequest(snapshot.provider(), snapshot.model(), List.copyOf(messages),
                reservedOutput, snapshot.thinking(), tools,
                profile.getCredentialRef(), profile.getBaseUrl(), profile.isOptionsStreaming(),
                estimatedInput, prefixHash),entryContext,contextMetrics);
    }

    /**
     * D-08 两阶段压缩的一次尝试：阶段一折叠旧大工具输出为「摘要+产物引用」，
     * 阶段二把仍放不下的旧历史汇总成一条摘要消息。
     *
     * @param tailUnits     保护区口径：{@code 0} = 旧口径（最后一条 user 及其之后）；
     *                      {@code >0} = A2 新口径（最近该数量的执行单元）
     * @param mustFitWindow true（超窗路径）要求压回窗口内；false（水位路径）只要求确有减少，
     *                      否则阶段一恒成立会让阶段二永不执行——这正是水位路径失效的陷阱
     * @return 可采用的压缩结果；{@code null} 表示本次口径压不动，由调用方决定回退到旧口径或报错
     */
    private List<ModelMessage> compactHistory(List<MessageEntity> stored, ModelMessage systemMessage,
                                              int systemTokens, int toolsTokens, int reservedOutput,
                                              int effectiveWindow, int tailUnits,
                                              boolean mustFitWindow, int historyTokens, HistoryCheckpoint.Context checkpoint) {
        List<ModelMessage> collapsed = buildMessagesFromStored(stored, true, tailUnits, checkpoint);
        collapsed.add(0, systemMessage);
        int collapsedHistory = TokenEstimator.estimateMessages(collapsed.subList(1, collapsed.size()));
        boolean collapsedFits = TokenEstimator.calibrateInput(
                collapsedHistory + systemTokens + toolsTokens) + reservedOutput <= effectiveWindow;
        if (mustFitWindow ? collapsedFits : collapsedHistory < historyTokens) {
            return collapsed;
        }

        List<ModelMessage> compressed = compressHistoryToWindow(collapsed, systemTokens, toolsTokens,
                reservedOutput, TokenEstimator.rawWindow(effectiveWindow, reservedOutput), tailUnits, checkpoint);
        if (compressed == null) {
            return null;
        }
        int compressedHistory = TokenEstimator.estimateMessages(
                compressed.subList(1, compressed.size()));
        boolean compressedUsable = mustFitWindow
                ? TokenEstimator.calibrateInput(
                        compressedHistory + systemTokens + toolsTokens) + reservedOutput <= effectiveWindow
                : compressedHistory < historyTokens;
        return compressedUsable ? compressed : null;
    }

    /** S3：取最近一条 user 消息原文作为当前用户意图，供 L5 情景记忆召回分词。无 user 时返回空。 */
    private String lastUserIntent(List<MessageEntity> stored) {
        for (int i = stored.size() - 1; i >= 0; i--) {
            if ("user".equals(stored.get(i).getRole())) {
                return stored.get(i).getContent();
            }
        }
        return "";
    }

    /** 仅当前 Turn 的用户输入及成功读/写文件的路径决定目录规则；工具正文不参与路径解析。 */
    private String ruleScopeHints(List<MessageEntity> stored, String turnId) {
        StringBuilder hints = new StringBuilder();
        Set<String> successfulCalls = new HashSet<>();
        for (MessageEntity message : stored) {
            if (!turnId.equals(message.getTurnId())) continue;
            if ("user".equals(message.getRole())) {
                hints.append(message.getContent()).append('\n');
            } else if (ROLE_TOOL.equals(message.getRole())) {
                try {
                    JsonNode result = objectMapper.readTree(message.getContent());
                    if ("SUCCEEDED".equals(result.path("status").asText())) {
                        successfulCalls.add(result.path("callId").asText());
                    }
                } catch (Exception ignored) {
                    // 损坏的工具消息不扩大规则作用域。
                }
            }
        }
        for (MessageEntity message : stored) {
            if (!turnId.equals(message.getTurnId()) || !ROLE_ASSISTANT_TOOL.equals(message.getRole())) continue;
            try {
                JsonNode calls = objectMapper.readTree(message.getContent()).path("calls");
                for (JsonNode call : calls) {
                    if (!successfulCalls.contains(call.path("callId").asText())) continue;
                    String name = call.path("name").asText();
                    if (!"read_file".equals(name) && !"write_file".equals(name)) continue;
                    String path = objectMapper.readTree(call.path("arguments").asText("{}")).path("path").asText();
                    if (!path.isBlank() && !path.contains("`")) hints.append('`').append(path).append("`\n");
                }
            } catch (Exception ignored) {
                // 损坏的内部调用消息不扩大规则作用域。
            }
        }
        return hints.toString();
    }

    /**
     * 从持久化消息构建模型消息列表（不含 system）。
     * compactOldLargeOutputs=true 时，最后一条 user 之前的旧工具结果若带有 fullOutputArtifactId，
     * 折叠为摘要+产物引用（裁旧大输出）；最近工具结果仍保留完整正文。
     */
    private List<ModelMessage> buildMessagesFromStored(List<MessageEntity> stored,
                                                       boolean compactOldLargeOutputs) {
        return buildMessagesFromStored(stored, compactOldLargeOutputs, 0);
    }

    /**
     * 从持久化消息构建模型消息列表（不含 system）。
     * compactOldLargeOutputs=true 时，可折叠区间内的旧工具结果若带有 fullOutputArtifactId，
     * 折叠为摘要+产物引用（裁旧大输出）；保护区内的工具结果仍保留完整正文。
     *
     * @param protectedTailUnits &lt;=0 时保护区＝最后一条 user 消息及其之后（既有口径）；
     *                           &gt;0 时保护区＝最近该数量的执行单元（A2，供单轮长任务折叠）
     */
    private List<ModelMessage> buildMessagesFromStored(List<MessageEntity> stored,
                                                       boolean compactOldLargeOutputs,
                                                       int protectedTailUnits) {
        return buildMessagesFromStored(stored,compactOldLargeOutputs,protectedTailUnits,null);
    }

    private List<ModelMessage> buildMessagesFromStored(List<MessageEntity> stored,
                                                       boolean compactOldLargeOutputs,
                                                       int protectedTailUnits, HistoryCheckpoint.Context checkpoint) {
        int foldBound = compactableUpperBound(stored, protectedTailUnits);
        List<ModelMessage> messages = new ArrayList<>();
        for (int i = 0; i < stored.size(); i++) {
            MessageEntity m = stored.get(i);
            boolean isOld = compactOldLargeOutputs && i < foldBound;
            switch (m.getRole()) {
                case "user" -> messages.add(ModelMessage.user(m.getContent()));
                case "assistant" -> messages.add(ModelMessage.assistant(m.getContent()));
                case ROLE_ASSISTANT_TOOL -> messages.add(readAssistantToolCalls(m.getContent()));
                case ROLE_TOOL -> {
                    ModelMessage view = readToolResult(m.getContent(), isOld);
                    if (checkpoint != null) checkpoint.bind(view,m);
                    messages.add(view);
                }
                default -> {
                    // 未知内部角色忽略，不送入模型
                }
            }
        }
        return messages;
    }

    /**
     * D-07 有界缩减：从历史前端移除完整对话单元，直到 system+tools+历史+预留输出 ≤ window。
     * 受保护：system（index 0）与最后一条 user 及其之后的消息；工具调用/结果必须成组移除，
     * 绝不残留孤立 tool 结果或孤立 assistant_tool 调用，避免破坏工具配对。
     * 返回缩减后的消息列表；若连受保护部分都超窗则返回 null。
     */
    static List<ModelMessage> trimHistoryToWindow(List<ModelMessage> messages, int systemTokens,
                                                    int toolsTokens, int reservedOutput, int window) {
        int available = window - systemTokens - toolsTokens - reservedOutput;
        if (available <= 0) {
            return null;
        }

        // 最后一条 user 消息及之后受保护
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx <= 0) {
            return null;
        }

        // 将 [1, lastUserIdx) 切分为可整体移除的单元
        List<List<ModelMessage>> units = splitHistoryUnits(messages, lastUserIdx);

        int historyTokens = TokenEstimator.estimateMessages(messages.subList(1, messages.size()));
        while (historyTokens > available && !units.isEmpty()) {
            List<ModelMessage> removed = units.remove(0);
            historyTokens -= TokenEstimator.estimateMessages(removed);
        }
        if (historyTokens > available) {
            return null; // 全部历史移除后仍超窗
        }

        List<ModelMessage> result = new ArrayList<>();
        result.add(messages.get(0));
        for (List<ModelMessage> unit : units) {
            result.addAll(unit);
        }
        for (int k = lastUserIdx; k < messages.size(); k++) {
            result.add(messages.get(k));
        }
        return result;
    }

    /** C05: preserve all user text and intact live tool units; account for checkpoint overhead. */
    static List<ModelMessage> compressHistoryToWindow(List<ModelMessage> messages, int systemTokens,
                                                     int toolsTokens, int reservedOutput, int window) {
        return compressHistoryToWindow(messages, systemTokens, toolsTokens, reservedOutput, window, 0);
    }

    static List<ModelMessage> compressHistoryToWindow(List<ModelMessage> messages, int systemTokens,
                                                     int toolsTokens, int reservedOutput, int window,
                                                     int protectedTailUnits) {
        return compressHistoryToWindow(messages, systemTokens, toolsTokens, reservedOutput, window,
                protectedTailUnits, new HistoryCheckpoint.Context(new ObjectMapper(), List.of()));
    }

    private static List<ModelMessage> compressHistoryToWindow(List<ModelMessage> messages, int systemTokens,
                                                              int toolsTokens, int reservedOutput, int window,
                                                              int protectedTailUnits, HistoryCheckpoint.Context checkpoint) {
        int available = window - systemTokens - toolsTokens - reservedOutput;
        if (available <= 0 || messages.size() <= 1) return null;
        int originalHistory = TokenEstimator.estimateMessages(messages.subList(1, messages.size()));
        if (originalHistory <= available) return protectedTailUnits > 0 ? null : new ArrayList<>(messages);
        List<List<ModelMessage>> units = splitHistoryUnits(messages, messages.size());
        int keepFrom;
        if (protectedTailUnits > 0) keepFrom = Math.max(0, units.size() - protectedTailUnits);
        else {
            int lastUser = -1;
            for (int i = units.size() - 1; i >= 0; i--) {
                if (containsUserMessage(units.get(i))) { lastUser = i; break; }
            }
            if (lastUser < 0) return null;
            keepFrom = lastUser;
        }
        List<List<ModelMessage>> removed = new ArrayList<>();
        Set<Integer> removedIdx = new HashSet<>();
        for (int i=0; i<keepFrom; i++) {
            List<ModelMessage> unit=units.get(i);
            if (containsUserMessage(unit) || !completeHistoryUnit(unit)) continue;
            removed.add(unit); removedIdx.add(i);
            String summary = HistoryCheckpoint.render(removed, checkpoint);
            if (summary == null) return null;
            List<ModelMessage> result = new ArrayList<>();
            result.add(messages.get(0)); result.add(ModelMessage.assistant(summary));
            for (int j=0; j<units.size(); j++) if(!removedIdx.contains(j)) result.addAll(units.get(j));
            int estimate=TokenEstimator.estimateMessages(result.subList(1,result.size()));
            if (estimate <= available && estimate < originalHistory) return result;
        }
        return null; // A checkpoint that does not fit or does not reduce cost must not be used.
    }

    private static boolean containsUserMessage(List<ModelMessage> unit) {
        return unit.stream().anyMatch(m -> "user".equals(m.role()));
    }

    /** Partial/duplicate/mismatched pairs remain intact rather than becoming a fabricated completed unit. */
    private static boolean completeHistoryUnit(List<ModelMessage> unit) {
        ModelMessage head=unit.getFirst();
        if (head.toolCalls()==null || head.toolCalls().isEmpty()) return !"tool".equals(head.role());
        Set<String> expected = new HashSet<>();
        for (ModelToolCall call : head.toolCalls()) if(!expected.add(call.callId())) return false;
        Set<String> actual = new HashSet<>();
        for(int i=1;i<unit.size();i++) {
            ModelMessage m=unit.get(i);
            if(!"tool".equals(m.role()) || !actual.add(m.toolCallId())) return false;
        }
        return actual.equals(expected);
    }

    /** One assistant call batch and all subsequent tool results form an indivisible history unit. */
    private static List<List<ModelMessage>> splitHistoryUnits(List<ModelMessage> messages, int upperBound) {
        List<List<ModelMessage>> units = new ArrayList<>();
        int idx = 1;
        while (idx < upperBound) {
            ModelMessage m = messages.get(idx);
            if ("assistant".equals(m.role()) && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                List<ModelMessage> unit = new ArrayList<>(); unit.add(m); idx++;
                while (idx < upperBound && "tool".equals(messages.get(idx).role())) unit.add(messages.get(idx++));
                units.add(unit);
            } else units.add(List.of(messages.get(idx++)));
        }
        return units;
    }

    /** Compatibility entry for projection tests; production supplies committed tool metadata. */
    static String buildHistorySummary(List<List<ModelMessage>> removedUnits) {
        return HistoryCheckpoint.render(removedUnits, new HistoryCheckpoint.Context(new ObjectMapper(), List.of()));
    }

    /**
     * 落盘一次完整模型响应：用量账本、model.completed；
     * 有工具调用则串行执行并回填（tool.started/tool.completed + 内部消息）；
     * 无工具调用按追问/完成/截断分流并写终态事件。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RoundResult apply(String runId, ModelResponse response, int durationMs) {
        return apply(runId, response, durationMs, 0);
    }

    /** B03: 已预约的请求计数不再重复增加，Token 结算只释放本次占用。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RoundResult apply(String runId, ModelResponse response, int durationMs, int reservationTokens) {
        return apply(runId,response,durationMs,reservationTokens,null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RoundResult apply(String runId, ModelResponse response, int durationMs, int reservationTokens,
                             CompletionReviewService.EntryContext entryContext) {
        RunEntity initial = runRepository.findById(runId).orElseThrow();
        var source = taskAcceptanceModes != null && initial.getParentRunId() == null && "MAIN".equals(initial.getRole())
                ? taskAcceptanceModes.lockSource(runId) : null;
        RunEntity run = source == null ? runRepository.findByIdForUpdate(runId).orElseThrow() : source.root();
        TurnEntity turn = source == null ? turnRepository.findById(run.getTurnId()).orElseThrow() : source.turn();
        ThreadEntity thread = source == null ? threadRepository.findByIdForUpdate(turn.getThreadId()).orElseThrow() : source.thread();
        EventMeta meta = meta(thread, run);
        Instant now = Instant.now();

        settleModelUsage(run, response.usage(), reservationTokens);
        run.setUpdatedAt(now);
        runRepository.saveAndFlush(run);

        // 预约到响应之间取消/状态迁移：结算已发生支出，不落回答或执行工具。
        if (!ST_RUNNING.equals(run.getState()) && (reservationTokens > 0 || strongTaskCompletionBlocked(run))) return RoundResult.TERMINAL;

        String profileModel = snapshot(runId).model();
        eventStore.append(meta, EventType.MODEL_COMPLETED, new EventPayloads.ModelCompleted(
                profileModel, response.providerResponseId(), response.finishReason(),
                durationMs, response.usage()));

        // MA-11 严格预算：结算后立即判定是否越过累计上限。越过上限的响应不得以成功终态收尾
        // （此前只在下一轮请求前检查，最后一次响应可超阈值仍判成功，预算只是请求准入阈值）。
        Optional<String> breach = budgetBreachAfterSettlement(run);
        if (breach.isPresent()) {
            run.setBudgetUsageStatus("OVER_CAP");
        }

        if (completionReview.exhausted(runId)) {
            return fail(run, turn, thread, meta, "COMPLETION_REVIEW_LIMIT", "收尾复核响应次数已用尽，尚无有效READY结论");
        }
        var reviewDecision = completionReview.accept(run, response);
        switch (reviewDecision.action()) {
            case INVALID, STALE, REWORK -> { return RoundResult.CONTINUE; }
            case READY -> {
                if (breach.isPresent()) return fail(run, turn, thread, meta, "BUDGET_LIMIT", breach.get());
                return completeAnswer(run, turn, thread, meta, reviewDecision.summary(), now);
            }
            default -> { }
        }
        String finishReason = response.finishReason() == null ? FinishReason.STOP : response.finishReason();
        switch (finishReason) {
            case FinishReason.TOOL_CALLS -> {
                if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
                    // 工具轮不在此处拦截：工具仍需执行并回填结果，下一轮准入即会判 BUDGET_LIMIT，
                    // 与此处失败的终态一致；提前返回会留下未配对的工具调用。
                    return runTools(run, turn, thread, meta, response.toolCalls(),
                            response.reasoningContent(), now, entryContext);
                }
                if (breach.isPresent()) {
                    return fail(run, turn, thread, meta, "BUDGET_LIMIT", breach.get());
                }
                return completeAnswer(run, turn, thread, meta, response.text(), now);
            }
            case FinishReason.WAIT_INPUT -> {
                if (isBlank(response.text())) {
                    return fail(run, turn, thread, meta, "PROVIDER_ERROR", "模型要求补充输入但未给出追问内容");
                }
                return pauseForInput(run, turn, meta, response.text(), now);
            }
            case FinishReason.LENGTH -> {
                return fail(run, turn, thread, meta, "CONTEXT_LIMIT", "模型输出被截断，不能按完整回答收尾");
            }
            case FinishReason.PROTOCOL_ERROR -> {
                return fail(run, turn, thread, meta, "PROVIDER_ERROR", "模型协议错误或工具参数 JSON 不完整");
            }
            default -> {
                if (isBlank(response.text())) {
                    return fail(run, turn, thread, meta, "PROVIDER_ERROR", "模型返回了空响应");
                }
                if (breach.isPresent()) {
                    return fail(run, turn, thread, meta, "BUDGET_LIMIT", breach.get());
                }
                return completeAnswer(run, turn, thread, meta, response.text(), now);
            }
        }
    }

    /** 终态失败（可从事务外调用，自带事务）；已是终态则 no-op。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String runId, String stopReason, String message) {
        RunEntity run = runRepository.findById(runId).orElse(null);
        if (run == null || isTerminal(run.getState())) {
            return;
        }
        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElseThrow();
        ThreadEntity thread = threadRepository.findByIdForUpdate(turn.getThreadId()).orElseThrow();
        fail(run, turn, thread, meta(thread, run), stopReason, message);
    }

    /**
     * 补充输入恢复：WAITING_INPUT → RUNNING（同事务 input_applied 事件）。
     * 调用方已持有同会话事务；非 WAITING_INPUT 状态返回 false（输入仅排队）。
     */
    @Transactional
    public boolean resumeFromWaiting(String turnId, String messageId) {
        TurnEntity turn = turnRepository.findById(turnId).orElseThrow();
        RunEntity run = runRepository.findById(turn.getRootRunId()).orElseThrow();
        if (!ST_WAITING_INPUT.equals(run.getState())) {
            return false;
        }
        ThreadEntity thread = threadRepository.findByIdForUpdate(turn.getThreadId()).orElseThrow();
        EventMeta meta = meta(thread, run);

        run.setState(ST_RUNNING);
        run.setUpdatedAt(Instant.now());
        run = runRepository.saveAndFlush(run);
        eventStore.append(meta, EventType.RUN_STATE_CHANGED, new EventPayloads.StateChanged(
                ST_WAITING_INPUT, ST_RUNNING, run.getVersion() + 1));
        eventStore.append(meta, EventType.RUN_INPUT_APPLIED,
                new EventPayloads.InputApplied(messageId, run.getAttempt()));

        turn.setState(ST_RUNNING);
        turn.setUpdatedAt(Instant.now());
        turnRepository.save(turn);
        return true;
    }

    private RoundResult runTools(RunEntity run, TurnEntity turn, ThreadEntity thread, EventMeta meta,
                                 List<ModelToolCall> toolCalls, String reasoningContent, Instant now,
                                 CompletionReviewService.EntryContext entryContext) {
        if (run.getBudgetToolCallsUsed() + toolCalls.size() > run.getBudgetMaxToolCalls()) {
            return fail(run, turn, thread, meta, "BUDGET_LIMIT", "工具调用预算不足，未执行的调用不能冒充完成");
        }

        // 内部 assistant 消息：记录本轮完整 tool_calls + reasoningContent（不进公开消息流）
        List<ObjectNode> callNodes = new ArrayList<>();
        for (ModelToolCall call : toolCalls) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("callId", call.callId());
            node.put("name", call.name());
            node.put("arguments", call.argumentsJson());
            callNodes.add(node);
        }
        ObjectNode assistantToolMsg = objectMapper.createObjectNode();
        assistantToolMsg.set("calls", objectMapper.valueToTree(callNodes));
        if (entryContext!=null && run.getId().equals(entryContext.runId()))
            assistantToolMsg.set("completionEntry",completionReview.entryMarker(entryContext));
        if (reasoningContent != null) {
            assistantToolMsg.put("reasoningContent", reasoningContent);
        }
        messageRepository.save(new MessageEntity(IdGen.next("msg_"), thread.getId(), turn.getId(),
                ROLE_ASSISTANT_TOOL, assistantToolMsg.toString(), nextMessageSequence(thread.getId()), now));

        int executed = 0;
        for (ModelToolCall call : toolCalls) {
            try (TraceContext.SpanScope ignored = traceContext.startChildSpan()) {
            EventMeta toolMeta = meta(thread, run);
            String argumentsSummary = abbreviate(call.argumentsJson(), 200);
            String sideEffect = toolDispatcher.sideEffectOf(call.name());
            if (!completionReview.allowsTool(run.getId(), call.name())) {
                eventStore.append(toolMeta, EventType.TOOL_STARTED,
                        new EventPayloads.ToolStarted(call.callId(), call.name(), argumentsSummary));
                ToolOutcomeDto denied = new ToolOutcomeDto("DENIED", "COMPLETION_REVIEW_READ_ONLY",
                        "复核只允许只读工具；需修改先提交带未解决项的REWORK", List.of(), null, List.of(), false, 0, null,
                        true, call.callId(), call.name());
                eventStore.append(toolMeta, EventType.TOOL_COMPLETED,
                        new EventPayloads.ToolCompleted(call.callId(), call.name(), denied));
                saveToolResult(call.callId(), call.name(), denied, thread, turn, true);
                executed++;
                continue;
            }
            PolicyEngine.Decision decision = policyEngine.evaluate(sideEffect);

            if (decision == PolicyEngine.Decision.DENY) {
                eventStore.append(toolMeta, EventType.TOOL_STARTED,
                        new EventPayloads.ToolStarted(call.callId(), call.name(), argumentsSummary));
                ToolOutcomeDto denied = new ToolOutcomeDto("DENIED", "POLICY_DENIED",
                        "工具副作用被策略拒绝: " + sideEffect, List.of(), null, List.of(), false, 0, null,
                        true, call.callId(), call.name());
                eventStore.append(toolMeta, EventType.TOOL_COMPLETED,
                        new EventPayloads.ToolCompleted(call.callId(), call.name(), denied));
                saveToolResult(call.callId(), call.name(), denied, thread, turn, true);
                executed++;
                continue;
            }

            if (decision == PolicyEngine.Decision.REQUIRE_APPROVAL) {
                // 先提交已执行工具的预算
                if (executed > 0) {
                    mergeFreshBudget(run);
                    run.setBudgetToolCallsUsed(run.getBudgetToolCallsUsed() + executed);
                    runRepository.saveAndFlush(run);
                }
                return pauseForApproval(run, turn, thread, toolMeta, call, argumentsSummary, sideEffect);
            }

            // ALLOW：写工具前 capture checkpoint
            if ("WORKSPACE_WRITE".equals(sideEffect)) {
                WorkspaceLease lease = workspaceManager.find(run.getId());
                if (lease != null) {
                    checkpointService.capture(run.getId(), lease);
                }
            }

            eventStore.append(toolMeta, EventType.TOOL_STARTED,
                    new EventPayloads.ToolStarted(call.callId(), call.name(), argumentsSummary));
            ToolOutcomeDto outcome = toolDispatcher.dispatch(run.getId(), thread.getId(),
                        turn.getId(), thread.getProjectId(), run.getBudgetDeadlineAt(),
                        call.name(), call.argumentsJson(), call.callId());
            // MA-05（规范 §9）：await_tasks 条件未满足且窗口非零时不在本事务回填结果：
            // 等待行已在工具内落库（WAITING），此处不写 TOOL_COMPLETED/tool 消息，
            // 先落盘已执行工具预算后让出主循环线程；唯一结果由 TaskAwaitService 唤醒时回填。
            if (AwaitTasksTool.PARKED_STATUS.equals(outcome.status())) {
                if (executed > 0) {
                    mergeFreshBudget(run);
                    run.setBudgetToolCallsUsed(run.getBudgetToolCallsUsed() + executed);
                    runRepository.saveAndFlush(run);
                }
                return RoundResult.PAUSED_FOR_TASKS;
            }
            eventStore.append(toolMeta, EventType.TOOL_COMPLETED,
                    new EventPayloads.ToolCompleted(call.callId(), call.name(), outcome));
            saveToolResult(call.callId(), call.name(), outcome, thread, turn);
            executed++;
            }
        }

        mergeFreshBudget(run);
        run.setBudgetToolCallsUsed(run.getBudgetToolCallsUsed() + executed);
        run.setUpdatedAt(Instant.now());
        runRepository.saveAndFlush(run);
        return RoundResult.CONTINUE;
    }

    /**
     * DSH-010：工具执行期间子 Run 预算分账（reserve/settle）以独立短事务直接更新过本 Run 行，
     * 内存中的 run 实体预算列已过期；落盘前以 DB 为准合并，避免把分账结果脏写覆盖。
     * 本轮父级工具次数（executed）由调用方在此基线上叠加。
     */
    private void mergeFreshBudget(RunEntity run) {
        // 标量查询走 DB（不命中一级缓存），读到子预算分账后的最新值；findById 会返回过期托管实体。
        Object[] c = runRepository.readBudgetCounters(run.getId()).get(0);
        run.setBudgetModelRequestsUsed((Integer) c[0]);
        run.setBudgetToolCallsUsed((Integer) c[1]);
        run.setBudgetSettledTokens((Integer) c[2]);
        run.setBudgetReservedTokens((Integer) c[3]);
    }

    /** 审批暂停：创建 PENDING 审批，Run → WAITING_APPROVAL。 */
    private RoundResult pauseForApproval(RunEntity run, TurnEntity turn, ThreadEntity thread,
                                         EventMeta meta, ModelToolCall call,
                                         String argumentsSummary, String sideEffect) {
        String parametersDigest = Digest.sha256Hex(call.argumentsJson());
        WorkspaceLease lease = workspaceManager.find(run.getId());
        String workspaceDigest = lease != null ? workspaceManager.currentDigest(lease) : "";
        String risk = "EXECUTE".equals(sideEffect) ? "HIGH" : "MEDIUM";

        eventStore.append(meta, EventType.TOOL_STARTED,
                new EventPayloads.ToolStarted(call.callId(), call.name(), argumentsSummary));
        approvalService.request(turn.getId(), run.getId(), call.callId(), call.name(),
                abbreviate(call.argumentsJson(), 2000), call.argumentsJson(),
                parametersDigest, workspaceDigest, risk);

        Instant now = Instant.now();
        run.setState(ST_WAITING_APPROVAL);
        run.setUpdatedAt(now);
        run = runRepository.saveAndFlush(run);
        eventStore.append(meta, EventType.RUN_STATE_CHANGED, new EventPayloads.StateChanged(
                ST_RUNNING, ST_WAITING_APPROVAL, run.getVersion() + 1));

        turn.setState(ST_WAITING_APPROVAL);
        turn.setUpdatedAt(now);
        turnRepository.save(turn);
        return RoundResult.PAUSED_FOR_APPROVAL;
    }

    /**
     * 审批决策后恢复：WAITING_APPROVAL → RUNNING（同事务执行工具并回填结果）。
     * afterCommit 回调中旧事务资源尚未解绑，必须开启新事务；非 WAITING_APPROVAL 返回 false。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean resumeFromApproval(String turnId) {
        TurnEntity turn = turnRepository.findById(turnId).orElseThrow();
        RunEntity run = runRepository.findById(turn.getRootRunId()).orElseThrow();
        if (!ST_WAITING_APPROVAL.equals(run.getState())) {
            return false;
        }
        // decide 已将审批置为 APPROVED；查找 APPROVED 审批（非 PENDING）
        Optional<ApprovalEntity> pending = approvalService.findApproved(run.getId());
        if (pending.isEmpty()) {
            return false;
        }
        ApprovalEntity approval = pending.get();
        ThreadEntity thread = threadRepository.findByIdForUpdate(turn.getThreadId()).orElseThrow();
        EventMeta meta = meta(thread, run);

        // consume 复核：摘要匹配且 APPROVED 才执行
        String parametersDigest = Digest.sha256Hex(approval.getArgumentsJson());
        WorkspaceLease lease = workspaceManager.find(run.getId());
        String workspaceDigest = lease != null ? workspaceManager.currentDigest(lease) : "";
        try (TraceContext.SpanScope ignored = traceContext.startChildSpan()) {
            EventMeta toolMeta = meta(thread, run);
            boolean approved = approvalService.consume(approval.getId(), parametersDigest, workspaceDigest);
            ToolOutcomeDto outcome;
            if (approved) {
                outcome = toolDispatcher.dispatch(run.getId(), thread.getId(), turn.getId(),
                        thread.getProjectId(), run.getBudgetDeadlineAt(),
                        approval.getToolName(), approval.getArgumentsJson(), approval.getToolCallId());
            } else {
                outcome = new ToolOutcomeDto("DENIED", "APPROVAL_INVALIDATED",
                        "审批已失效或被拒绝", List.of(), null, List.of(), false, 0, null,
                        true, approval.getToolCallId(), approval.getToolName());
            }
            eventStore.append(toolMeta, EventType.TOOL_COMPLETED,
                    new EventPayloads.ToolCompleted(approval.getToolCallId(), approval.getToolName(), outcome));
            saveToolResult(approval.getToolCallId(), approval.getToolName(), outcome, thread, turn, !approved);
        }

        // 模型一次可返回多个 tool_calls。审批暂停只执行当前调用；同批后续调用
        // 必须逐一回填受控结果，才能满足 DeepSeek 的 tool_call_id 一一配对要求。
        // 后续调用不自动执行，由模型在看到审批结果后决定是否重新发起。
        List<ModelToolCall> deferred = callsAfterApproval(thread.getId(), turn.getId(),
                approval.getToolCallId());
        for (ModelToolCall call : deferred) {
            try (TraceContext.SpanScope ignored = traceContext.startChildSpan()) {
                EventMeta deferredMeta = meta(thread, run);
                eventStore.append(deferredMeta, EventType.TOOL_STARTED,
                        new EventPayloads.ToolStarted(call.callId(), call.name(),
                                abbreviate(call.argumentsJson(), 200)));
                ToolOutcomeDto skipped = new ToolOutcomeDto("DENIED", "DEFERRED_AFTER_APPROVAL",
                        "同批前序工具需要审批；此调用未执行，如仍需要请重新发起",
                        List.of(), null, List.of(), false, 0, null,
                        true, call.callId(), call.name());
                eventStore.append(deferredMeta, EventType.TOOL_COMPLETED,
                        new EventPayloads.ToolCompleted(call.callId(), call.name(), skipped));
                saveToolResult(call.callId(), call.name(), skipped, thread, turn, true);
            }
        }

        run.setBudgetToolCallsUsed(run.getBudgetToolCallsUsed() + 1 + deferred.size());
        run.setState(ST_RUNNING);
        run.setUpdatedAt(Instant.now());
        run = runRepository.saveAndFlush(run);
        eventStore.append(meta, EventType.RUN_STATE_CHANGED, new EventPayloads.StateChanged(
                ST_WAITING_APPROVAL, ST_RUNNING, run.getVersion() + 1));

        turn.setState(ST_RUNNING);
        turn.setUpdatedAt(Instant.now());
        turnRepository.save(turn);
        return true;
    }

    private List<ModelToolCall> callsAfterApproval(String threadId, String turnId, String callId) {
        List<MessageEntity> stored = messageRepository.listByThread(threadId);
        for (int i = stored.size() - 1; i >= 0; i--) {
            MessageEntity message = stored.get(i);
            if (!ROLE_ASSISTANT_TOOL.equals(message.getRole()) || !turnId.equals(message.getTurnId())) {
                continue;
            }
            List<ModelToolCall> calls = readAssistantToolCalls(message.getContent()).toolCalls();
            for (int j = 0; j < calls.size(); j++) {
                if (callId.equals(calls.get(j).callId())) {
                    return List.copyOf(calls.subList(j + 1, calls.size()));
                }
            }
        }
        return List.of();
    }

    /**
     * MA-05（规范 §9）：await_tasks 停车后的<strong>唯一</strong>结果回填。由
     * TaskAwaitService.tryComplete 在 CAS 完成等待行的短事务内调用（本方法不另开事务）。
     *
     * <p>Run 保持 RUNNING（不新增 RunState，遵守 MA-01 冻结）：这里只补齐配对的
     * TOOL_COMPLETED 事件与 role=tool 消息（停车时只写了 TOOL_STARTED），并把同批后续
     * tool_calls 逐一回填 DEFERRED 以满足 tool_call_id 配对；恢复由调用方在事务提交后
     * 经全局单线程 LoopScheduler 触发，同一主 Run 同时至多一条执行循环。</p>
     */
    public void fillParkedAwaitResult(TaskWaitEntity wait, ObjectNode structured, String summary) {
        TurnEntity turn = turnRepository.findById(wait.getTurnId()).orElseThrow();
        RunEntity run = runRepository.findById(wait.getRootRunId()).orElseThrow();
        ThreadEntity thread = threadRepository.findByIdForUpdate(turn.getThreadId()).orElseThrow();
        EventMeta meta = meta(thread, run);
        String callId = wait.getToolCallId();

        try (TraceContext.SpanScope ignored = traceContext.startChildSpan()) {
            ToolOutcomeDto outcome = new ToolOutcomeDto("SUCCEEDED", null, summary,
                    List.of(new ContentBlock.TextBlock(summary)), structured, List.of(),
                    false, 0, null, false, callId, "await_tasks");
            eventStore.append(meta, EventType.TOOL_COMPLETED,
                    new EventPayloads.ToolCompleted(callId, "await_tasks", outcome));
            saveToolResult(callId, "await_tasks", outcome, thread, turn);
        }

        // 同批后续调用在停车时未执行：逐一回填受控 DEFERRED 结果（同审批恢复口径），
        // 由模型看到结果后决定是否重新发起。
        List<ModelToolCall> deferred = callsAfterApproval(thread.getId(), turn.getId(), callId);
        for (ModelToolCall call : deferred) {
            try (TraceContext.SpanScope ignored = traceContext.startChildSpan()) {
                EventMeta deferredMeta = meta(thread, run);
                eventStore.append(deferredMeta, EventType.TOOL_STARTED,
                        new EventPayloads.ToolStarted(call.callId(), call.name(),
                                abbreviate(call.argumentsJson(), 200)));
                ToolOutcomeDto skipped = new ToolOutcomeDto("DENIED", "DEFERRED_AFTER_TASK_WAIT",
                        "同批前序 await_tasks 进入任务等待停车；此调用未执行，如仍需要请重新发起",
                        List.of(), null, List.of(), false, 0, null,
                        true, call.callId(), call.name());
                eventStore.append(deferredMeta, EventType.TOOL_COMPLETED,
                        new EventPayloads.ToolCompleted(call.callId(), call.name(), skipped));
                saveToolResult(call.callId(), call.name(), skipped, thread, turn);
            }
        }

        mergeFreshBudget(run);
        run.setBudgetToolCallsUsed(run.getBudgetToolCallsUsed() + 1 + deferred.size());
        run.setUpdatedAt(Instant.now());
        runRepository.saveAndFlush(run);
    }

    private RoundResult pauseForInput(RunEntity run, TurnEntity turn, EventMeta meta,
                                      String question, Instant now) {
        MessageEntity message = new MessageEntity(IdGen.next("msg_"),
                turn.getThreadId(), turn.getId(), "assistant", question,
                nextMessageSequence(turn.getThreadId()), now);
        messageRepository.save(message);
        eventStore.append(meta, EventType.ASSISTANT_MESSAGE_COMPLETED,
                new EventPayloads.MessageCompleted(message.getId(), question));

        run.setState(ST_WAITING_INPUT);
        run.setUpdatedAt(now);
        run = runRepository.saveAndFlush(run);
        int publicVersion = run.getVersion() + 1;
        eventStore.append(meta, EventType.RUN_STATE_CHANGED, new EventPayloads.StateChanged(
                ST_RUNNING, ST_WAITING_INPUT, publicVersion));

        turn.setState(ST_WAITING_INPUT);
        turn.setUpdatedAt(now);
        turnRepository.save(turn);
        return RoundResult.PAUSED;
    }

    /** C29: no-diff final answers cannot bypass an explicit defect acceptance requirement. */
    private boolean defectVerificationRequired(RunEntity run, ThreadEntity thread) {
        try {
            if (run.getVerificationRequirement() != null)
                return "DEFECT_FIX".equals(com.dsh.application.RunVerificationRequirement.decode(run.getVerificationRequirement()).purpose());
            var context = completionReview.entryContext(run);
            return context != null && context.plan().defectFix();
        } catch (RuntimeException invalidPlan) {
            return true; // Require independent verification; malformed configuration is never NO_CHANGE success.
        }
    }

    private RoundResult completeAnswer(RunEntity run, TurnEntity turn, ThreadEntity thread,
                                       EventMeta meta, String text, Instant now) {
        if (strongTaskCompletionBlocked(run)) return fail(run, turn, thread, meta, "EVIDENCE_MISSING",
                "EXECUTION_NOT_AVAILABLE: 强任务尚无独立检查与完整证据，禁止纯回答或旧验证完成");
        MessageEntity message = new MessageEntity(IdGen.next("msg_"),
                turn.getThreadId(), turn.getId(), "assistant", text,
                nextMessageSequence(turn.getThreadId()), now);
        messageRepository.save(message);
        eventStore.append(meta, EventType.ASSISTANT_MESSAGE_COMPLETED,
                new EventPayloads.MessageCompleted(message.getId(), text));

        String summary = abbreviate(text, 2000);
        WorkspaceLease lease = workspaceManager.find(run.getId());
        boolean requiresVerification = (run.getRole() == null || "MAIN".equals(run.getRole()))
                && (defectVerificationRequired(run, thread) || lease != null
                && (!lease.baselineDigest().equals(workspaceManager.currentDigest(lease))
                || verificationRepairPending(run, turn) || completionReview.satisfied(run.getId()) && completionReview.state(run.getId()) != null));
        if (requiresVerification) completionReview.initializeForVerification(run);
        String nextState = requiresVerification ? ST_VERIFYING : ST_COMPLETED;
        String verificationStatus = requiresVerification ? "PENDING" : "NOT_REQUIRED";
        // D-13：无工作区改动时，区分「合法无需改动」与「声称改代码但无 Diff」。
        // 不自动把无改动等同成功或失败；模型最终文本即为实际结果说明。
        String completionBasis = null;
        if (!requiresVerification) {
            completionBasis = workspaceWriteAttempted(thread.getId(), turn.getId())
                    ? "NO_NET_CHANGE_AFTER_WRITE" : "NO_CHANGE";
        }
        run.setState(nextState);
        run.setVerificationStatus(verificationStatus);
        run.setStopReason(requiresVerification ? null : "ANSWER_COMPLETE");
        run.setCompletionBasis(completionBasis);
        run.setSummary(summary);
        run.setBudgetUsageStatus("SETTLED");
        run.setUpdatedAt(now);
        run = runRepository.saveAndFlush(run);
        int publicVersion = run.getVersion() + 1;

        // 契约不变量：终态同时写 state_changed 与对应终态事件，version 相同、eventId 不同
        eventStore.append(meta, EventType.RUN_STATE_CHANGED, new EventPayloads.StateChanged(
                ST_RUNNING, nextState, publicVersion));
        if (requiresVerification) {
            turn.setState(ST_VERIFYING);
            turn.setVerificationStatus("PENDING");
            turn.setUpdatedAt(now);
            turnRepository.save(turn);
            return RoundResult.TERMINAL;
        }
        eventStore.append(meta, EventType.RUN_COMPLETED, new EventPayloads.RunCompleted(
                ST_COMPLETED, verificationStatus, completionBasis, summary, publicVersion));

        turn.setState(ST_COMPLETED);
        turn.setVerificationStatus("NOT_REQUIRED");
        turn.setStopReason("ANSWER_COMPLETE");
        turn.setActiveKey(null);
        turn.setUpdatedAt(now);
        turnRepository.save(turn);

        thread.setActiveTurnId(null);
        thread.setUpdatedAt(now);
        threadRepository.save(thread);
        runToolMountStore.release(run.getId());
        return RoundResult.TERMINAL;
    }

    /** 只有当前验证通过的 VERIFYING Run 才能发成功终态。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean verificationPassed(String runId) {
        RunEntity run = runRepository.findById(runId).orElseThrow();
        if (strongTaskCompletionBlocked(run)) return false;
        return ST_VERIFYING.equals(run.getState()) && "PASSED".equals(run.getVerificationStatus())
                && hasFreshVerificationEvidence(run);
    }

    /** 只有当前验证通过的 VERIFYING Run 才能发成功终态。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeAfterVerification(String runId) {
        var source = taskAcceptanceModes.lockSource(runId);
        RunEntity run = source.root();
        if (!ST_VERIFYING.equals(run.getState())) throw new IllegalStateException("Run 不在验证收尾状态: " + runId);
        if (strongTaskCompletionBlocked(run)) {
            fail(run, source.turn(), source.thread(), meta(source.thread(), run), "EVIDENCE_MISSING",
                    "EXECUTION_NOT_AVAILABLE: 旧PASSED不能替代强任务独立验收证据");
            return;
        }
        if (!ST_VERIFYING.equals(run.getState()) || !"PASSED".equals(run.getVerificationStatus())
                || !hasFreshVerificationEvidence(run) || !completionReview.satisfied(runId)) {
            throw new IllegalStateException("Run 尚未通过验证: " + runId);
        }
        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElseThrow();
        ThreadEntity thread = threadRepository.findByIdForUpdate(turn.getThreadId()).orElseThrow();
        Instant now = Instant.now();
        run.setState(ST_COMPLETED);
        run.setStopReason("VERIFICATION_PASSED");
        run.setCompletionBasis("VERIFICATION_PASSED");
        run.setUpdatedAt(now);
        run = runRepository.saveAndFlush(run);
        int version = run.getVersion() + 1;
        EventMeta meta = meta(thread, run);
        eventStore.append(meta, EventType.RUN_STATE_CHANGED,
                new EventPayloads.StateChanged(ST_VERIFYING, ST_COMPLETED, version));
        eventStore.append(meta, EventType.RUN_COMPLETED,
                new EventPayloads.RunCompleted(ST_COMPLETED, "PASSED", "VERIFICATION_PASSED",
                        run.getSummary(), version));
        turn.setState(ST_COMPLETED);
        turn.setVerificationStatus("PASSED");
        turn.setStopReason("VERIFICATION_PASSED");
        turn.setActiveKey(null);
        turn.setUpdatedAt(now);
        turnRepository.save(turn);
        thread.setActiveTurnId(null);
        thread.setUpdatedAt(now);
        threadRepository.save(thread);
        runToolMountStore.release(runId);
    }
    private boolean strongTaskCompletionBlocked(RunEntity run) {
        if(taskAcceptanceModes == null || run.getParentRunId() != null
                || !(run.getRole() == null || "MAIN".equals(run.getRole())) || !taskAcceptanceModes.isStrong(run.getId()))return false;
        return !strongReceipts.rootReady(run.getId())||!strongIntegrationCurrent(run.getId());
    }
    @org.springframework.beans.factory.annotation.Autowired private com.dsh.orchestration.acceptance.TaskCheckReceiptReader strongReceipts;
    @org.springframework.beans.factory.annotation.Autowired private com.dsh.storage.IntegrationRecordRepository strongIntegrations;
    private boolean strongIntegrationCurrent(String root){
        var latest=strongIntegrations.findFirstByRootRunIdOrderByCreatedAtDesc(root);
        var lease=workspaceManager.find(root);
        return latest.isPresent()&&"APPLIED".equals(latest.get().getState())&&lease!=null
                &&java.util.Objects.equals(latest.get().getResultDigest(),workspaceManager.currentDigest(lease));
    }

    /** C11：复核继续复用RUNNING和既有预算，不能按PASSED跳过模型覆盖检查。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reopenForCompletionReview(String runId) {
        RunEntity run = runRepository.findByIdForUpdate(runId).orElseThrow();
        if (!ST_VERIFYING.equals(run.getState()) || !completionReview.active(runId)) return false;
        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElseThrow();
        ThreadEntity thread = threadRepository.findByIdForUpdate(turn.getThreadId()).orElseThrow();
        run.setState(ST_RUNNING); run.setUpdatedAt(Instant.now());
        run = runRepository.saveAndFlush(run);
        turn.setState(ST_RUNNING); turn.setVerificationStatus("PASSED"); turn.setUpdatedAt(Instant.now());
        turnRepository.save(turn);
        eventStore.append(meta(thread, run), EventType.RUN_STATE_CHANGED,
                new EventPayloads.StateChanged(ST_VERIFYING, ST_RUNNING, run.getVersion() + 1));
        return true;
    }

    public boolean completionReviewSatisfied(String runId) { return completionReview.satisfied(runId); }

    /**
     * A4：自动验证未通过后，把失败事实作为一条 user 消息回流，并让 Run 回到 RUNNING，
     * 使模型能在剩余预算内继续修正，而不是「一次写错即判死」。
     *
     * <p>仅在 {@code retryLimit > 0} 且本 Run **已完成的验证次数**未超过上限时生效；
     * 返回 {@code null} 表示不满足条件，调用方必须按既有行为落 VERIFICATION_FAILED 终态。
     * 默认 {@code dsh.loop.verification-retry-limit=0} ⇒ 产品默认行为逐字不变。
     *
     * <p>不变量：只消费验证记录里已有的客观字段，不臆造失败原因；Run/turn 状态成对回到
     * RUNNING，并各写一条 state_changed / input_applied 事件，保证历史可重建。
     *
     * @return 已落库的反馈文本；{@code null} 表示不回流
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String reopenForVerificationFix(String runId, int retryLimit) {
        if (retryLimit <= 0) {
            return null;
        }
        RunEntity run = runRepository.findById(runId).orElse(null);
        if (run == null || !ST_VERIFYING.equals(run.getState())) {
            return null;
        }
        var page = verificationRepository.findByRunIdOrderByStartedAtDesc(
                runId, org.springframework.data.domain.PageRequest.of(0, retryLimit + 2));
        long attempts = page.getTotalElements();
        if (page.getContent().isEmpty() || attempts > retryLimit) {
            return null;
        }
        var latest = page.getContent().get(0);
        if (!"FAILED".equals(latest.getStatus())) {
            return null;
        }
        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElse(null);
        if (turn == null) {
            return null;
        }
        ThreadEntity thread = threadRepository.findByIdForUpdate(turn.getThreadId()).orElse(null);
        if (thread == null) {
            return null;
        }
        String feedbackKey = "[verification-fix:" + latest.getId() + "]";
        if (verificationFeedbackDelivered(feedbackKey, turn)) {
            return null; // 同一证据只回流一次；没有工作区修改时不能循环消耗模型预算。
        }
        Instant now = Instant.now();
        String text = feedbackKey + "\n" + verificationFixFeedback(latest.getCommandSummary(), latest.getExitCode(),
                latest.getTestsDiscovered(), latest.getTestsPassed(), latest.getTestsSkipped(),
                (int) attempts, retryLimit)
                + VerificationFailureDiagnostics.feedback(latest, artifactStore);
        MessageEntity message = new MessageEntity(IdGen.next("msg_"), turn.getThreadId(), turn.getId(),
                "user", text, nextMessageSequence(turn.getThreadId()), now);
        messageRepository.save(message);
        evidenceContext.markInternalFeedback(run, message, thread.getProjectId());
        EventMeta meta = meta(thread, run);

        run.setState(ST_RUNNING);
        run.setVerificationStatus("PENDING");
        run.setStopReason(null);
        run.setUpdatedAt(now);
        run = runRepository.saveAndFlush(run);
        eventStore.append(meta, EventType.RUN_STATE_CHANGED,
                new EventPayloads.StateChanged(ST_VERIFYING, ST_RUNNING, run.getVersion() + 1));
        eventStore.append(meta, EventType.RUN_INPUT_APPLIED,
                new EventPayloads.InputApplied(message.getId(), run.getAttempt()));

        turn.setState(ST_RUNNING);
        turn.setVerificationStatus("PENDING");
        turn.setStopReason(null);
        turn.setUpdatedAt(now);
        turnRepository.save(turn);
        return text;
    }

    /** A4 修正回到原始基线也必须重验，不能跳过尚未通过的失败证据。 */
    private boolean verificationRepairPending(RunEntity run, TurnEntity turn) {
        var latest = verificationRepository.findByRunIdOrderByStartedAtDesc(
                run.getId(), org.springframework.data.domain.PageRequest.of(0, 1));
        return !latest.isEmpty() && "FAILED".equals(latest.getContent().get(0).getStatus())
                && verificationFeedbackDelivered("[verification-fix:" + latest.getContent().get(0).getId() + "]", turn);
    }

    private boolean verificationFeedbackDelivered(String key, TurnEntity turn) {
        return messageRepository.listByThread(turn.getThreadId()).stream()
                .anyMatch(m -> turn.getId().equals(m.getTurnId()) && "user".equals(m.getRole())
                        && m.getContent() != null && m.getContent().startsWith(key));
    }

    /**
     * A4 反馈文本（纯函数，便于单测）：只陈述验证记录里的客观事实。
     * discovered=0 只说明没有解析到测试，不据此断言环境、代码或命令的根因。
     */
    static String verificationFixFeedback(String commandSummary, int exitCode,
                                          int discovered, int passed, int skipped,
                                          int attempt, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("工作区自动验证未通过，请把修复做完后再结束回合")
                .append("（第 ").append(attempt).append('/').append(limit).append(" 次修正机会）。\n")
                .append("验证命令：").append(commandSummary == null ? "(未记录)" : commandSummary).append('\n')
                .append("退出码：").append(exitCode).append('\n')
                .append("测试计数：discovered=").append(discovered)
                .append(" passed=").append(passed)
                .append(" skipped=").append(skipped).append('\n');
        if (discovered == 0) {
            sb.append("注意：discovered=0 表示验证命令没有解析出任何测试——先确认这条命令在这份工作区里")
                    .append("能否执行、输出是否为预期的测试报告格式，不要把「没跑出测试」当成「改动正确」。\n");
        }
        sb.append("做法：优先阅读随附失败日志片段；信息不足时用 run_command 针对性复现。")
                .append("定位失败原因后修改代码，改完再次结束回合，由系统重新验证。");
        return sb.toString();
    }

    /** 验证后的任何外部修改都会使证据过期，不能再提交成功终态。 */
    private boolean hasFreshVerificationEvidence(RunEntity run) {
        var latest = verificationRepository.findByRunIdOrderByStartedAtDesc(
                run.getId(), org.springframework.data.domain.PageRequest.of(0, 1));
        if (latest.isEmpty() || !"PASSED".equals(latest.getContent().get(0).getStatus())
                || run.getWorkspaceRef() == null) {
            return false;
        }
        String expected = latest.getContent().get(0).getWorkspaceDigest();
        try {
            return expected != null && evidenceContext.isFresh(run, latest.getContent().get(0));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * D-13：判断本轮次是否调用过任何 WORKSPACE_WRITE 工具（apply_patch / write_file 等）。
     * 用于区分「合法无需改动」与「声称改代码但无 Diff」：有写动作却无净改动时，
     * 完成依据记为 NO_NET_CHANGE_AFTER_WRITE，由模型最终文本说明实际结果。
     */
    private boolean workspaceWriteAttempted(String threadId, String turnId) {
        for (MessageEntity m : messageRepository.listByThread(threadId)) {
            if (!ROLE_ASSISTANT_TOOL.equals(m.getRole()) || !turnId.equals(m.getTurnId())) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(m.getContent());
                for (JsonNode call : root.path("calls")) {
                    if ("WORKSPACE_WRITE".equals(toolDispatcher.sideEffectOf(call.path("name").asText()))) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
                // 损坏消息跳过，不影响完成判定
            }
        }
        return false;
    }

    private RoundResult fail(RunEntity run, TurnEntity turn, ThreadEntity thread, EventMeta meta,
                             String stopReason, String message) {
        Instant now = Instant.now();
        String from = run.getState();
        if (ST_VERIFYING.equals(from) && "PENDING".equals(run.getVerificationStatus())) {
            run.setVerificationStatus("ENV_ERROR");
        }
        run.setState(ST_FAILED);
        run.setStopReason(stopReason);
        run.setBudgetUsageStatus("SETTLED");
        run.setUpdatedAt(now);
        run = runRepository.saveAndFlush(run);
        int publicVersion = run.getVersion() + 1;

        eventStore.append(meta, EventType.RUN_STATE_CHANGED, new EventPayloads.StateChanged(
                from, ST_FAILED, publicVersion));
        eventStore.append(meta, EventType.RUN_FAILED, new EventPayloads.RunFailed(
                ST_FAILED, stopReason, abbreviate(message, 2000), publicVersion));

        turn.setState(ST_FAILED);
        turn.setVerificationStatus(run.getVerificationStatus());
        turn.setStopReason(stopReason);
        turn.setActiveKey(null);
        turn.setUpdatedAt(now);
        turnRepository.save(turn);

        thread.setActiveTurnId(null);
        thread.setUpdatedAt(now);
        threadRepository.save(thread);
        runToolMountStore.release(run.getId());
        return RoundResult.TERMINAL;
    }

    private RunEntity transition(RunEntity run, String to, EventMeta meta) {
        String from = run.getState();
        run.setState(to);
        run.setUpdatedAt(Instant.now());
        RunEntity saved = runRepository.saveAndFlush(run);
        eventStore.append(meta, EventType.RUN_STATE_CHANGED, new EventPayloads.StateChanged(
                from, to, saved.getVersion() + 1));
        return saved;
    }

    /** 异常、取消或响应落库回滚：仅结算已发生的本次请求，不保存其回答/工具副作用。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleAbortedModelCall(String runId, int reservationTokens, Usage usage) {
        RunEntity run = runRepository.findByIdForUpdate(runId).orElseThrow();
        settleModelUsage(run, usage, reservationTokens);
        if (!ST_RUNNING.equals(run.getState())) run.setBudgetUsageStatus("SETTLED");
        run.setUpdatedAt(Instant.now());
        runRepository.saveAndFlush(run);
    }

    private void settleModelUsage(RunEntity run, Usage usage, int reservationTokens) {
        boolean incompletePriorUsage = "UNKNOWN".equals(run.getUsageStatus())
                && nz(run.getBudgetSettledTokens()) > 0;
        if (reservationTokens == 0) run.setBudgetModelRequestsUsed(run.getBudgetModelRequestsUsed() + 1);
        if (reservationTokens > 0) {
            if (run.getBudgetReservedTokens() < reservationTokens) {
                throw new IllegalStateException("本次模型预约丢失，拒绝从其他预算中结算");
            }
            run.setBudgetReservedTokens(run.getBudgetReservedTokens() - reservationTokens);
        }
        if (usage == null
                || (!"KNOWN".equals(usage.status()) && !"ESTIMATED".equals(usage.status()))
                || usage.inputTokens() == null || usage.outputTokens() == null
                || usage.inputTokens() < 0 || usage.outputTokens() < 0) {
            if (reservationTokens > 0) {
                run.setBudgetSettledTokens((int) Math.min(Integer.MAX_VALUE,
                        (long) nz(run.getBudgetSettledTokens()) + reservationTokens));
            }
            clearUnknownUsage(run);
            return;
        }
        int in = nz(usage.inputTokens());
        int out = nz(usage.outputTokens());
        int hit = nz(usage.cacheHitTokens());
        int miss = nz(usage.cacheMissTokens());
        run.setUsageStatus(usage.status());
        run.setUsageInputTokens(nz(run.getUsageInputTokens()) + in);
        run.setUsageOutputTokens(nz(run.getUsageOutputTokens()) + out);
        run.setUsageCacheHitTokens(plusNullable(run.getUsageCacheHitTokens(), hit));
        run.setUsageCacheMissTokens(plusNullable(run.getUsageCacheMissTokens(), miss));
        int settled = (int) Math.min(Integer.MAX_VALUE, (long) nz(run.getBudgetSettledTokens()) + in + out);
        run.setBudgetSettledTokens(settled);
        run.setBudgetUsageStatus("ACTIVE");
        // 不把部分轮次的已知计数冒充全 Run 实际总量。保守预算扣账仍保留。
        if (incompletePriorUsage) clearUnknownUsage(run);
    }

    private static void clearUnknownUsage(RunEntity run) {
        run.setUsageStatus("UNKNOWN");
        run.setUsageInputTokens(null);
        run.setUsageOutputTokens(null);
        run.setUsageCacheHitTokens(null);
        run.setUsageCacheMissTokens(null);
        run.setUsageCost(null);
        run.setUsageCurrency(null);
        run.setUsagePriceVersion(null);
    }

    private EventMeta meta(ThreadEntity thread, RunEntity run) {
        return new EventMeta(thread.getProjectId(), thread.getId(), run.getTurnId(), run.getId(),
                run.getParentRunId(), run.getTraceId(), traceContext.spanId());
    }

    private long nextMessageSequence(String threadId) {
        return messageRepository.findFirstByThreadIdOrderBySequenceDesc(threadId)
                .map(m -> m.getSequence() + 1)
                .orElse(1L);
    }

    /** 读取内部 assistant_tool 消息：工具调用列表 + reasoningContent（thinking 续接）。 */
    private ModelMessage readAssistantToolCalls(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<ModelToolCall> calls = new ArrayList<>();
            String reasoningContent = root.path("reasoningContent").asText(null);
            for (JsonNode node : root.path("calls")) {
                calls.add(new ModelToolCall(
                        node.path("callId").asText(), node.path("name").asText(),
                        node.path("arguments").asText("{}")));
            }
            return ModelMessage.assistantToolCalls(calls, reasoningContent);
        } catch (Exception e) {
            throw new IllegalStateException("内部工具调用消息损坏", e);
        }
    }

    /**
     * 重建模型侧工具消息：必须包含工具返回的正文 content（文件内容/命令输出等），
     * 否则模型只能看到 summary，无法基于真实内容继续（曾导致 read_file 后模型声称"看不到文件"）。
     * D-08：compactLargeOutputs=true 且消息带有 fullOutputArtifactId 时，折叠为摘要+产物引用，
     * 保留 callId 与 name 以维持 DeepSeek 工具续接配对，完整证据留在 ArtifactStore 可重读。
     */
    private ModelMessage readToolResult(String json, boolean compactLargeOutputs) {
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(json);
            StringBuilder sb = new StringBuilder();
            String status = node.path("status").asText("");
            String summary = node.path("summary").asText("");
            String callId = node.path("callId").asText("");
            String name = node.path("name").asText("");
            if (!status.isEmpty()) {
                sb.append('[').append(status).append(']');
            }
            if (node.path("isError").asBoolean(false)) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append("[工具调用错误]");
            }
            if (!summary.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(summary);
            }
            String errorCode = node.path("errorCode").asText(null);
            if (errorCode != null && !errorCode.isEmpty()) {
                sb.append("\nerrorCode: ").append(errorCode);
            }
            JsonNode exit = node.get("exitCode");
            if (exit != null && !exit.isNull()) {
                sb.append("\nexitCode: ").append(exit.asInt());
            }
            String artifactId = node.path("fullOutputArtifactId").asText(null);
            String structuredArtifactId = node.path("structuredArtifactId").asText(null);
            if (compactLargeOutputs && artifactId != null && !artifactId.isEmpty()) {
                // C05: retain bounded observations even when stage-one folding alone is sufficient.
                StringBuilder observedText = new StringBuilder();
                for (JsonNode block : node.path("content")) {
                    if ("text".equals(block.path("type").asText())) {
                        observedText.append(HistoryCheckpoint.diagnosticExcerpt(block.path("text").asText())).append('\n');
                        if (observedText.length()>600) break;
                    }
                }
                sb.append("\n[观察到的诊断片段，不等于根因]\n")
                        .append(HistoryCheckpoint.bounded(observedText.toString(),600));
                ObjectNode observations = objectMapper.createObjectNode();
                JsonNode originalStructured = node.path("structuredContent");
                for (String key : List.of("workspaceDigest","sha256","path","changed","termination","commandDigest")) {
                    JsonNode value = originalStructured.get(key);
                    if (value != null && value.isValueNode()) observations.set(key,
                            value.isTextual() ? objectMapper.getNodeFactory().textNode(HistoryCheckpoint.bounded(value.asText(),160)) : value);
                }
                if (!observations.isEmpty()) sb.append("\n[observedMetadata]\n").append(observations);
                if (node.path("captureTruncated").asBoolean(false))
                    sb.append("\n[采集阶段已丢失 ").append(node.path("outputDroppedBytes").asLong())
                            .append(" 字节；该产物不等于完整输出，缺失部分无法回读]");
                // 压缩视图保留 callId 供工具续接

                sb.append("\n\n[工具输出已压缩；完整内容 ").append(node.path("fullOutputBytes").asInt(0))
                        .append(" 字节，产物 ").append(artifactId).append(" 可用 read_artifact 按需分页回读]");
                if (structuredArtifactId != null && !structuredArtifactId.isEmpty()) {
                    sb.append("\n[完整结构化内容产物 ").append(structuredArtifactId).append("]");
                }
                return ModelMessage.toolResult(callId, name, sb.toString());
            }
            JsonNode content = node.get("content");
            if (content != null && content.isArray()) {
                for (JsonNode block : content) {
                    String type = block.path("type").asText("");
                    if ("text".equals(type)) {
                        String t = block.path("text").asText("");
                        if (!t.isEmpty()) {
                            sb.append("\n\n").append(t);
                        }
                    } else if ("artifact".equals(type)) {
                        sb.append("\n\n[artifact ").append(block.path("artifactId").asText("?"))
                                .append(' ').append(block.path("mimeType").asText(""))
                                .append(' ').append(block.path("description").asText("")).append(']');
                    }
                }
            }
            JsonNode structured = node.get("structuredContent");
            if (structured != null && structured.isObject() && structured.size() > 0) {
                sb.append("\n\n[structured]\n").append(objectMapper.writeValueAsString(structured));
            }
            if (node.path("truncated").asBoolean(false)) {
                sb.append("\n\n[注意：工具输出已被截断，以上为有界视图]");
            }
            if (artifactId != null && !artifactId.isEmpty()) {
                if (node.path("captureTruncated").asBoolean(false)) {
                    // A3：采集阶段就丢了字节，产物并不完整，不能笼统说「完整输出」。
                    sb.append("\n\n[工具输出产物：").append(artifactId)
                            .append("（注意：命令输出在采集阶段即被截断——原始输出共 ")
                            .append(node.path("outputTotalBytes").asLong(0L))
                            .append(" 字节，其中 ")
                            .append(node.path("outputDroppedBytes").asLong(0L))
                            .append(" 字节未被采集、已永久丢失；该产物不等于完整输出，"
                                    + "缺失部分需缩小输出范围后重跑命令获取）]");
                } else {
                    sb.append("\n\n[完整工具输出已另存产物：").append(artifactId).append("；可用 read_artifact 分页回读]");
                }
            }
            if (structuredArtifactId != null && !structuredArtifactId.isEmpty()) {
                sb.append("\n\n[完整结构化内容已另存产物：")
                        .append(structuredArtifactId).append("]");
            }
            String rendered = sb.toString();
            if (rendered.isBlank()) {
                rendered = summary; // 旧数据兜底
            }
            return ModelMessage.toolResult(callId, name, rendered);
        } catch (Exception e) {
            throw new IllegalStateException("内部工具结果消息损坏", e);
        }
    }

    /**
     * 保存工具结果内部消息（role=tool）：除摘要外持久化正文与结构化字段，供后续轮次重建上下文。
     * D-08：完整正文超过 LARGE_TOOL_OUTPUT_CHARS 时，另存 ArtifactStore（失败证据可重读），
     * 消息内只存截断视图与 fullOutputArtifactId 引用；压缩时可折叠为摘要+产物引用。
     */
    private void saveToolResult(String callId, String name, ToolOutcomeDto outcome,
                               ThreadEntity thread, TurnEntity turn) {
        saveToolResult(callId,name,outcome,thread,turn,false);
    }

    private void saveToolResult(String callId, String name, ToolOutcomeDto outcome,
                               ThreadEntity thread, TurnEntity turn, boolean notExecuted) {
        ObjectNode toolResult = objectMapper.createObjectNode();
        // 只有后端在未调用Dispatcher的分支设置；不能复制工具返回内容中的同名主张。
        if (notExecuted) toolResult.put("notExecutedByHost",true);
        toolResult.put("callId", callId);
        toolResult.put("name", name);
        toolResult.put("status", outcome.status());
        toolResult.put("errorCode", outcome.errorCode());
        toolResult.put("summary", outcome.summary());
        toolResult.put("isError", outcome.isError());
        toolResult.put("source", outcome.source());
        if (outcome.exitCode() != null) {
            toolResult.put("exitCode", outcome.exitCode());
        }

        // D-08：先序列化完整正文，超长则落 ArtifactStore 并记录引用
        List<ContentBlock> blocks = outcome.content() == null ? List.of() : outcome.content();
        String fullText = serializeFullTextContent(blocks);
        String fullOutputArtifactId = null;
        if (fullText.length() > LARGE_TOOL_OUTPUT_CHARS) {
            String artifactName = "tool-output-" + callId + ".txt";
            try {
                var artifact = artifactStore.save(turn.getRootRunId(), thread.getProjectId(),
                        artifactName, "text/plain",
                        fullText.getBytes(StandardCharsets.UTF_8), true);
                fullOutputArtifactId = artifact.getId();
                toolResult.put("fullOutputArtifactId", fullOutputArtifactId);
                toolResult.put("fullOutputBytes", fullText.length());
            } catch (RuntimeException e) {
                // 完整证据无法保存时不能继续返回看似成功的截断结果。
                throw new IllegalStateException("工具完整输出产物保存失败", e);
            }
        }

        // 当前轮次也给模型有界视图；完整正文已另存 Artifact，可按权限重读。
        // 消息列上限 100000 字符，普通小结果仍保留完整正文。
        int budget = fullOutputArtifactId == null ? 90000 : LARGE_TOOL_OUTPUT_CHARS;
        boolean budgetHit = false;
        // A3：视图被裁时不能笼统说「完整输出见产物」——采集阶段就截断过的输出，
        // 产物同样是有界视图，如实说明缺失口径。
        boolean captureTruncated = outcome.truncated();
        String viewCutNote = captureTruncated
                ? "\n[……中间内容已截断；产物仅为有界视图（原始输出在采集阶段已被截断，缺失部分不可从产物恢复）……]\n"
                : fullOutputArtifactId == null
                        ? "\n[……中间内容已截断……]\n"
                        : "\n[……中间内容已截断，完整输出见产物……]\n";
        ArrayNode contentArray = objectMapper.createArrayNode();
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.TextBlock tb) {
                String text = tb.text() == null ? "" : tb.text();
                if (text.length() > budget) {
                    int head = Math.max(0, budget * 2 / 3);
                    int tail = Math.max(0, budget - head);
                    text = text.substring(0, head) + viewCutNote
                            + text.substring(text.length() - tail);
                    budgetHit = true;
                }
                budget -= text.length();
                ObjectNode b = objectMapper.createObjectNode();
                b.put("type", "text");
                b.put("text", text);
                contentArray.add(b);
            } else if (block instanceof ContentBlock.ArtifactBlock ab) {
                ObjectNode b = objectMapper.createObjectNode();
                b.put("type", "artifact");
                b.put("artifactId", ab.artifactId());
                b.put("mimeType", ab.mimeType());
                b.put("description", ab.description());
                b.put("unsupported", ab.unsupported());
                contentArray.add(b);
            }
        }
        if (!contentArray.isEmpty()) {
            toolResult.set("content", contentArray);
        }
        if (outcome.structuredContent() != null
                && outcome.structuredContent().isObject() && outcome.structuredContent().size() > 0) {
            String structuredJson = outcome.structuredContent().toString();
            if (structuredJson.length() <= Math.max(0, budget)) {
                toolResult.set("structuredContent", outcome.structuredContent());
            } else {
                try {
                    var artifact = artifactStore.save(turn.getRootRunId(), thread.getProjectId(),
                            "tool-structured-" + callId + ".json", "application/json",
                            structuredJson.getBytes(StandardCharsets.UTF_8), true);
                    toolResult.put("structuredArtifactId", artifact.getId());
                    toolResult.put("structuredBytes", structuredJson.getBytes(StandardCharsets.UTF_8).length);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("工具结构化内容产物保存失败", e);
                }
                budgetHit = true;
            }
        }
        if (outcome.truncated() || budgetHit) {
            toolResult.put("truncated", true);
        }
        // A3：区分两种截断。捕获截断＝命令输出在**采集阶段**就已丢弃字节，产物同样不完整；
        // 视图截断（budgetHit）＝原始输出完整、只是本轮给模型的有界视图被裁。
        if (captureTruncated) {
            toolResult.put("captureTruncated", true);
            JsonNode structuredNode = outcome.structuredContent();
            if (structuredNode != null && structuredNode.isObject()) {
                if (structuredNode.hasNonNull("outputTotalBytes")) {
                    toolResult.put("outputTotalBytes",
                            structuredNode.path("outputTotalBytes").asLong());
                }
                if (structuredNode.hasNonNull("outputDroppedBytes")) {
                    toolResult.put("outputDroppedBytes",
                            structuredNode.path("outputDroppedBytes").asLong());
                }
            }
        }
        messageRepository.save(new MessageEntity(IdGen.next("msg_"), thread.getId(), turn.getId(),
                ROLE_TOOL, toolResult.toString(), nextMessageSequence(thread.getId()), Instant.now()));
    }

    /** 拼接所有 text 内容块的完整正文（用于判断是否需要落 ArtifactStore）。 */
    private String serializeFullTextContent(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.TextBlock tb && tb.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tb.text());
            }
        }
        return sb.toString();
    }

    private boolean isTerminal(String state) {
        return ST_COMPLETED.equals(state) || ST_FAILED.equals(state) || ST_CANCELLED.equals(state);
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    /** 增量为 0 时保留 null（未知），否则非空累计。 */
    private Integer plusNullable(Integer current, int delta) {
        if (delta == 0) {
            return current;
        }
        return Integer.valueOf(nz(current) + delta);
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
