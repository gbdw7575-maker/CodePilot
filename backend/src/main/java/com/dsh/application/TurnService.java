package com.dsh.application;

import com.dsh.api.ApiException;
import com.dsh.api.dto.BudgetView;
import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.InputAccepted;
import com.dsh.api.dto.Run;
import com.dsh.api.dto.RunRole;
import com.dsh.api.dto.RunState;
import com.dsh.api.dto.SubmitTurn;
import com.dsh.api.dto.Turn;
import com.dsh.api.dto.TurnAccepted;
import com.dsh.api.dto.Usage;
import com.dsh.agent.LoopScheduler;
import com.dsh.agent.LoopSteps;
import com.dsh.api.dto.VerificationStatus;
import com.dsh.api.dto.event.EventPayloads;
import com.dsh.api.dto.event.EventType;
import com.dsh.application.event.EventMeta;
import com.dsh.application.event.EventStore;
import com.dsh.common.IdGen;
import com.dsh.storage.MessageEntity;
import com.dsh.storage.MessageRepository;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Turn 接受事务（接口文档 §4）：
 * 消息、Turn、根 Run、配置摘要与线程活动指针在同一事务中提交；
 * 校验失败不创建任何资源；同会话同时只允许一个活动 Turn。
 */
@Service
public class TurnService {

    private static final Set<String> TERMINAL_STATES = Set.of("COMPLETED", "FAILED", "CANCELLED");

    private final ThreadRepository threadRepository;
    private final ProjectRepository projectRepository;
    private final ModelProfileRepository profileRepository;
    private final TurnRepository turnRepository;
    private final RunRepository runRepository;
    private final MessageRepository messageRepository;
    private final DshProperties properties;
    private final EventStore eventStore;
    private final LoopScheduler loopScheduler;
    private final LoopSteps loopSteps;
    private final org.springframework.beans.factory.ObjectProvider<com.dsh.orchestration.ChildRunLifecycle> childLifecycle;

    public TurnService(ThreadRepository threadRepository, ProjectRepository projectRepository,
                       ModelProfileRepository profileRepository, TurnRepository turnRepository,
                       RunRepository runRepository, MessageRepository messageRepository,
                       DshProperties properties, EventStore eventStore,
                       LoopScheduler loopScheduler, LoopSteps loopSteps,
                       org.springframework.beans.factory.ObjectProvider<com.dsh.orchestration.ChildRunLifecycle> childLifecycle) {
        this.threadRepository = threadRepository;
        this.projectRepository = projectRepository;
        this.profileRepository = profileRepository;
        this.turnRepository = turnRepository;
        this.runRepository = runRepository;
        this.messageRepository = messageRepository;
        this.properties = properties;
        this.eventStore = eventStore;
        this.loopScheduler = loopScheduler;
        this.loopSteps = loopSteps;
        this.childLifecycle = childLifecycle;
    }

    public TurnAccepted submit(String threadId, SubmitTurn request) {
        // 行锁串行化同会话提交，活动轮次与消息序号判断在锁内完成
        ThreadEntity thread = threadRepository.findByIdForUpdate(threadId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在"));
        requireTrustedProject(thread.getProjectId());
        String input = requireValidInput(request);
        String registration;
        try {
            registration = request.verification() == null ? null
                    : RunVerificationRequirement.parse(request.verification()).encode();
            if (request.projectPlanDigest() != null) {
                var project = projectRepository.findById(thread.getProjectId()).orElseThrow();
                String expected = request.projectPlanDigest();
                if (!expected.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("projectPlanDigest 格式无效");
                if (!expected.equals(VerificationPlan.load(java.nio.file.Path.of(project.getRootPath())).displayDigest()))
                    throw new ApiException(ErrorCode.VERSION_CONFLICT, "项目验收配置已改变，请重新查看后提交");
            }
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "验收登记无效，请检查 verification 和 projectPlanDigest");
        }

        if (thread.getActiveTurnId() != null) {
            throw new ApiException(ErrorCode.ACTIVE_TURN_EXISTS, "当前会话已有活动轮次",
                    Map.<String, Object>of("activeTurnId", thread.getActiveTurnId()));
        }

        Instant now = Instant.now();
        String turnId = IdGen.next("turn_");
        String runId = IdGen.next("run_");

        MessageEntity message = new MessageEntity(IdGen.next("msg_"), threadId, turnId, "user", input,
                nextSequence(threadId), now);
        messageRepository.save(message);

        ModelProfileEntity profile = profileRepository.findById(thread.getModelProfileId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "会话引用的模型配置不存在"));

        TurnEntity turn = new TurnEntity(turnId, threadId, runId, input, "QUEUED", "PENDING", threadId, now, now);
        turnRepository.save(turn);

        String traceId = IdGen.next("trace_");
        createRun(runId, turnId, now, thread,
                properties.budget().maxModelRequests(), properties.budget().maxToolCalls(),
                properties.budget().maxCumulativeTokens(), properties.budget().deadlineMinutes(),
                ModelProfileService.configDigest(profile), traceId, registration);

        // 起始事件与输入/Run 同事务提交；eventsAfterSequence 是本 Turn 首事件之前的 thread 游标
        long highWatermarkBefore = eventStore.highWatermark(threadId);
        EventMeta meta = new EventMeta(thread.getProjectId(), threadId, turnId, runId, null, traceId, null);
        eventStore.append(meta, EventType.RUN_STATE_CHANGED,
                new EventPayloads.StateChanged(null, "QUEUED", 1));

        // 提交成功后才允许执行，避免回滚后产生孤儿运行
        scheduleAfterCommit(() -> loopScheduler.scheduleStart(runId));
        return new TurnAccepted(turnId, runId, RunState.QUEUED, Long.toString(highWatermarkBefore));
    }

    /**
     * 评测专用提交（DSH-008 EvalRunner）：与 {@link #submit} 走相同的真实 Agent 路径
     * （消息/Turn/根 Run/事件同事务提交 + 同步调度），但预算与配置摘要由调用方提供，
     * 用于 EVAL-01 改 prompt/工具/预算对照实验。不读 SubmitTurn DTO，避免依赖 evals 包。
     * 调用方须已确保项目受信任并完成模型配置。
     */
    @Transactional
    public TurnAccepted submitEval(String threadId, String input, String idempotencyKey,
                                   int maxModelRequests, int maxToolCalls, int maxCumulativeTokens,
                                   int deadlineMinutes, String configDigest) {
        ThreadEntity thread = threadRepository.findByIdForUpdate(threadId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在"));
        requireTrustedProject(thread.getProjectId());
        String validated = validateInput(input);
        if (thread.getActiveTurnId() != null) {
            throw new ApiException(ErrorCode.ACTIVE_TURN_EXISTS, "当前会话已有活动轮次",
                    Map.<String, Object>of("activeTurnId", thread.getActiveTurnId()));
        }

        Instant now = Instant.now();
        String turnId = IdGen.next("turn_");
        String runId = IdGen.next("run_");

        MessageEntity message = new MessageEntity(IdGen.next("msg_"), threadId, turnId, "user", validated,
                nextSequence(threadId), now);
        messageRepository.save(message);

        TurnEntity turn = new TurnEntity(turnId, threadId, runId, validated, "QUEUED", "PENDING", threadId, now, now);
        turnRepository.save(turn);

        String traceId = IdGen.next("trace_");
        createRun(runId, turnId, now, thread,
                maxModelRequests, maxToolCalls, maxCumulativeTokens, deadlineMinutes, configDigest, traceId, null);

        long highWatermarkBefore = eventStore.highWatermark(threadId);
        EventMeta meta = new EventMeta(thread.getProjectId(), threadId, turnId, runId, null, traceId, null);
        eventStore.append(meta, EventType.RUN_STATE_CHANGED,
                new EventPayloads.StateChanged(null, "QUEUED", 1));

        scheduleAfterCommit(() -> loopScheduler.scheduleStart(runId));
        return new TurnAccepted(turnId, runId, RunState.QUEUED, Long.toString(highWatermarkBefore));
    }

    /** 创建根 Run 并持久化（submit 与 submitEval 共用，避免重复）。 */
    private void createRun(String runId, String turnId, Instant now, ThreadEntity thread,
                          int maxModelRequests, int maxToolCalls, int maxCumulativeTokens,
                          int deadlineMinutes, String configDigest, String traceId, String registration) {
        RunEntity run = new RunEntity(runId, turnId, null, RunRole.MAIN.name(), "QUEUED", "PENDING", 1,
                IdGen.next("ws_"), configDigest,
                maxModelRequests, maxToolCalls, maxCumulativeTokens,
                now.plusSeconds(deadlineMinutes * 60L), now, now);
        run.setTraceId(traceId);
        if (registration != null) run.registerVerificationRequirement(registration);
        runRepository.save(run);

        thread.setActiveTurnId(turnId);
        thread.setUpdatedAt(now);
        threadRepository.save(thread);
    }

    public Turn get(String turnId) {
        return toDto(load(turnId));
    }

    public InputAccepted appendInput(String turnId, SubmitTurn request) {
        if (request != null && (request.verification() != null || request.projectPlanDigest() != null))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "追加输入不能更改本次 Run 的验收登记");
        TurnEntity turn = load(turnId);
        boolean childOwned = requireChildOwner(turn.getRootRunId());
        ThreadEntity thread = threadRepository.findByIdForUpdate(turn.getThreadId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在"));
        requireTrustedProject(thread.getProjectId());
        String input = requireValidInput(request);

        if (TERMINAL_STATES.contains(turn.getState())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "轮次已终态，不能补充输入");
        }

        Instant now = Instant.now();
        MessageEntity message = new MessageEntity(IdGen.next("msg_"), thread.getId(), turn.getId(), "user",
                input, nextSequence(thread.getId()), now);
        messageRepository.save(message);

        // Run 正停在追问：同事务转回 RUNNING（input_applied 事件），提交后恢复 Loop；
        // RUNNING 中补充的输入仅排队，由 Loop 下一轮自然读入
        if (loopSteps.resumeFromWaiting(turn.getId(), message.getId())) {
            String runId = turn.getRootRunId();
            if (!childOwned) scheduleAfterCommit(() -> loopScheduler.scheduleResume(runId));
        }
        return new InputAccepted(message.getId(), turn.getId(), "QUEUED_FOR_MODEL", now);
    }

    private void scheduleAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    public Run getRun(String runId) {
        RunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "运行不存在"));
        return toRunDto(run);
    }

    /**
     * 审批决策后恢复：执行待审批工具并继续模型循环。
     * 调用方（ApprovalController）在 decide 成功后调用此方法。
     * 统一由 agentLoop.resumeFromApproval 负责执行工具 + 继续循环。
     */
    public void resumeFromApproval(String turnId) {
        TurnEntity turn = load(turnId);
        String runId = turn.getRootRunId();
        if (!requireChildOwner(runId)) loopScheduler.scheduleResumeFromApproval(turnId, runId);
    }

    /** 子 Run 只由保有预算/并发租约的 worker 恢复，避免 API 与 worker 双重执行。 */
    private boolean requireChildOwner(String runId) {
        RunEntity run = runRepository.findById(runId).orElseThrow();
        if (run.getParentRunId() == null) return false;
        if (TERMINAL_STATES.contains(run.getState())) return true;
        var owner = childLifecycle.getIfAvailable();
        if (owner == null || !owner.isManaged(runId)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "子 Run 执行者已退出，不能直接恢复；请走恢复核验");
        }
        return true;
    }

    public TurnEntity load(String turnId) {
        return turnRepository.findById(turnId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "轮次不存在"));
    }

    private void requireTrustedProject(String projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在"));
        if (!project.isTrusted()) {
            throw new ApiException(ErrorCode.PROJECT_NOT_TRUSTED, "项目未信任，不能提交执行类动作",
                    Map.<String, Object>of("projectId", project.getId()));
        }
    }

    private String requireValidInput(SubmitTurn request) {
        return validateInput(request == null ? null : request.input());
    }

    /** 输入校验：非空、不超 100,000 字符、含非空白字符。submit 与 submitEval 共用。 */
    private String validateInput(String input) {
        if (input == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "input 必填");
        }
        if (input.length() > 100_000) {
            throw new ApiException(ErrorCode.INPUT_TOO_LARGE, "input 不能超过 100,000 字符");
        }
        if (input.chars().noneMatch(c -> !Character.isWhitespace(c))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "input 必须包含至少一个非空白字符");
        }
        return input;
    }

    private long nextSequence(String threadId) {
        return messageRepository.findFirstByThreadIdOrderBySequenceDesc(threadId)
                .map(m -> m.getSequence() + 1)
                .orElse(1L);
    }

    public static Turn toDto(TurnEntity entity) {
        return new Turn(entity.getId(), entity.getThreadId(), entity.getRootRunId(), entity.getInput(),
                RunState.valueOf(entity.getState()), VerificationStatus.valueOf(entity.getVerificationStatus()),
                entity.getStopReason() == null ? null : com.dsh.api.dto.StopReason.valueOf(entity.getStopReason()),
                entity.getVersion() + 1, entity.getCreatedAt(), entity.getUpdatedAt());
    }

    public static Run toRunDto(RunEntity entity) {
        return new Run(entity.getId(), entity.getTurnId(), entity.getParentRunId(), entity.getTraceId(),
                RunRole.valueOf(entity.getRole()), RunState.valueOf(entity.getState()),
                VerificationStatus.valueOf(entity.getVerificationStatus()),
                entity.getStopReason() == null ? null : com.dsh.api.dto.StopReason.valueOf(entity.getStopReason()),
                entity.getAttempt(), entity.getWorkspaceRef(), entity.getConfigDigest(),
                new BudgetView(entity.getBudgetMaxModelRequests(), entity.getBudgetModelRequestsUsed(),
                        entity.getBudgetMaxToolCalls(), entity.getBudgetToolCallsUsed(),
                        entity.getBudgetMaxCumulativeTokens(), entity.getBudgetSettledTokens(),
                        entity.getBudgetReservedTokens(), entity.getBudgetDeadlineAt(),
                        entity.getBudgetUsageStatus()),
                new Usage(entity.getUsageStatus(), entity.getUsageInputTokens(), entity.getUsageOutputTokens(),
                        entity.getUsageCacheHitTokens(), entity.getUsageCacheMissTokens(), entity.getUsageCost(),
                        entity.getUsageCurrency(), entity.getUsagePriceVersion()),
                entity.getSummary(), entity.getCompletionBasis(),
                entity.getVersion() + 1, entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
