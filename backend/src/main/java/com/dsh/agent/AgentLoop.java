package com.dsh.agent;

import com.dsh.agent.control.CancellationToken;
import com.dsh.agent.control.CancellationTokenRegistry;
import com.dsh.agent.control.BudgetLedger;
import com.dsh.agent.control.RunControlService;
import com.dsh.agent.model.GatewayRouter;
import com.dsh.agent.model.ModelGatewayException;
import com.dsh.agent.model.ModelRequest;
import com.dsh.agent.model.ModelResponse;
import com.dsh.api.dto.Usage;
import com.dsh.application.VerificationService;
import com.dsh.application.trace.TraceContext;
import com.dsh.memory.EpisodicMemoryService;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnRepository;
import com.dsh.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 单 Loop 编排（内部接口契约 §3）：
 * 读持久化状态 → 预算检查 → 事务外调模型 → 事务内提交完整响应/事件 →
 * 有工具调用串行执行后再调模型，无工具调用按追问/解释完成/截断分流。
 *
 * <p>DSH-005 起：安全点检查取消令牌；写工具前 capture checkpoint；
 * 审批暂停于 WAITING_APPROVAL，WAITING_INPUT 暂停期间保留租约。</p>
 */
@Service
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final LoopSteps steps;
    private final GatewayRouter gatewayRouter;
    private final WorkspaceManager workspaceManager;
    private final CancellationTokenRegistry tokenRegistry;
    private final RunControlService runControlService;
    private final VerificationService verificationService;
    private final RunRepository runRepository;
    private final TraceContext traceContext;
    private final EpisodicMemoryService episodicMemoryService;
    private final TurnRepository turnRepository;
    private final ThreadRepository threadRepository;
    private final BudgetLedger budgetLedger;
    /**
     * A4：自动验证未通过后允许回流修正的**额外**验证次数上限。
     * 0（默认）＝既有行为（一次失败即落 VERIFICATION_FAILED）；&gt;0 时把失败事实回流给模型，
     * 在剩余预算内继续修正并重新验证，最多再验证该次数。
     */
    private final int verificationRetryLimit;
    private final CompletionReviewService completionReview;
    private final ModelContextObservation contextObservation;

    public AgentLoop(LoopSteps steps, GatewayRouter gatewayRouter, WorkspaceManager workspaceManager,
                     CancellationTokenRegistry tokenRegistry, RunControlService runControlService,
                     VerificationService verificationService, RunRepository runRepository,
                     TraceContext traceContext, EpisodicMemoryService episodicMemoryService,
                     TurnRepository turnRepository, ThreadRepository threadRepository, BudgetLedger budgetLedger,
                     @Value("${dsh.loop.verification-retry-limit:0}") int verificationRetryLimit,
                     CompletionReviewService completionReview, ModelContextObservation contextObservation) {
        this.steps = steps;
        this.gatewayRouter = gatewayRouter;
        this.workspaceManager = workspaceManager;
        this.tokenRegistry = tokenRegistry;
        this.runControlService = runControlService;
        this.verificationService = verificationService;
        this.runRepository = runRepository;
        this.traceContext = traceContext;
        this.episodicMemoryService = episodicMemoryService;
        this.turnRepository = turnRepository;
        this.threadRepository = threadRepository;
        this.budgetLedger = budgetLedger;
        this.verificationRetryLimit = Math.max(0, verificationRetryLimit);
        this.completionReview = completionReview;
        this.contextObservation = contextObservation;
    }

    /** 从 QUEUED 启动根 Run（幂等：非 QUEUED 直接返回）。 */
    public int executionVerificationRetryLimit() { return verificationRetryLimit; }

    public void startRootRun(String runId) {
        boolean began = false;
        try (TraceContext.SpanScope ignored = traceContext.enterRun(runTraceId(runId))) {
            began = steps.begin(runId);
            if (!began) {
                return;
            }
            tokenRegistry.register(runId);
            runLoop(runId);
        } catch (ModelGatewayException e) {
            failWithTrace(runId, "PROVIDER_ERROR", safeMessage(e.getMessage()));
        } catch (ContextLimitException e) {
            failWithTrace(runId, "CONTEXT_LIMIT", safeMessage(e.getMessage()));
        } catch (Exception e) {
            log.error("根 Run 执行异常: {}", runId, e);
            failWithTrace(runId, "ENVIRONMENT_ERROR", "运行环境异常: " + safeMessage(e.getMessage()));
        } finally {
            if (began) {
                releaseWorkspaceIfTerminal(runId);
            }
        }
    }

    /** WAITING_INPUT 补充输入或 WAITING_APPROVAL 审批通过后的恢复。 */
    public void resumeRootRun(String runId) {
        try (TraceContext.SpanScope ignored = traceContext.enterRun(runTraceId(runId))) {
            runLoop(runId);
        } catch (ModelGatewayException e) {
            failWithTrace(runId, "PROVIDER_ERROR", safeMessage(e.getMessage()));
        } catch (ContextLimitException e) {
            failWithTrace(runId, "CONTEXT_LIMIT", safeMessage(e.getMessage()));
        } catch (Exception e) {
            log.error("根 Run 恢复执行异常: {}", runId, e);
            failWithTrace(runId, "ENVIRONMENT_ERROR", "运行环境异常: " + safeMessage(e.getMessage()));
        } finally {
            releaseWorkspaceIfTerminal(runId);
        }
    }

    /** 审批决策后恢复：先执行待审批工具，再继续模型循环。 */
    public void resumeFromApproval(String turnId, String runId) {
        try (TraceContext.SpanScope ignored = traceContext.enterRun(runTraceId(runId))) {
            if (steps.resumeFromApproval(turnId)) {
                runLoop(runId);
            }
        } catch (ModelGatewayException e) {
            failWithTrace(runId, "PROVIDER_ERROR", safeMessage(e.getMessage()));
        } catch (ContextLimitException e) {
            failWithTrace(runId, "CONTEXT_LIMIT", safeMessage(e.getMessage()));
        } catch (Exception e) {
            log.error("根 Run 审批恢复执行异常: {}", runId, e);
            failWithTrace(runId, "ENVIRONMENT_ERROR", "运行环境异常: " + safeMessage(e.getMessage()));
        } finally {
            releaseWorkspaceIfTerminal(runId);
        }
    }

    /** 有改动的主 Run 先同步验证，再决定终态并释放租约。 */
    private void releaseWorkspaceIfTerminal(String runId) {
        try (TraceContext.SpanScope ignored = traceContext.enterRun(runTraceId(runId))) {
            String state = verifyWithFixLoop(runId);
            if (LoopSteps.ST_COMPLETED.equals(state) || LoopSteps.ST_FAILED.equals(state)
                    || LoopSteps.ST_CANCELLED.equals(state)) {
                workspaceManager.release(runId);
                tokenRegistry.release(runId);
                ingestEpisodicIfFinished(runId, state);
            }
        } catch (Exception e) {
            log.warn("终态工作区租约检查失败 run={}: {}", runId, e.getMessage());
        }
    }

    /**
     * 验证 →（未通过且允许回流）→ 把失败事实注入历史并继续修正 → 重新验证，直到通过或机会用尽。
     *
     * <p>A4：{@code dsh.loop.verification-retry-limit=0}（默认）时本方法与既有行为逐字一致
     * ——验证未通过即 {@code steps.fail(VERIFICATION_FAILED)}。&gt;0 时最多再验证该次数，
     * 次数由 {@code LoopSteps.reopenForVerificationFix} 按已完成的验证记录条数封顶，故循环必然终止。
     *
     * @return 收尾后的 Run 状态（终态或仍在进行中的状态）
     */
    private String verifyWithFixLoop(String runId) {
        String state = steps.snapshot(runId).state();
        while (LoopSteps.ST_VERIFYING.equals(state) && isMainRun(runId)) {
            String verificationId;
            try {
                verificationId = verificationRetryLimit > 0
                        ? verificationService.runForCurrentWorkspace(runId)
                        : verificationService.run(runId);
            } catch (Exception e) {
                log.warn("自动验证失败 run={}: {}", runId, e.getMessage());
                steps.fail(runId, "VERIFICATION_UNAVAILABLE", "验证环境异常: " + safeMessage(e.getMessage()));
                return steps.snapshot(runId).state();
            }
            if (cancelIfRequested(runId)) {
                return steps.snapshot(runId).state();
            }
            if (verificationId == null) {
                steps.fail(runId, "VERIFICATION_UNAVAILABLE", "验证工作区不可用");
                return LoopSteps.ST_FAILED;
            }
            if (steps.verificationPassed(runId)) {
                if (completionReview.exhausted(runId)) {
                    steps.fail(runId, "COMPLETION_REVIEW_LIMIT", "收尾复核次数耗尽，不能仅凭测试通过完成");
                    return LoopSteps.ST_FAILED;
                }
                if (completionReview.open(runId, verificationId, null) && steps.reopenForCompletionReview(runId)) {
                    runLoop(runId);
                    state = steps.snapshot(runId).state();
                    continue;
                }
                steps.completeAfterVerification(runId);
                return LoopSteps.ST_COMPLETED;
            }
            String feedback = steps.reopenForVerificationFix(runId, verificationRetryLimit);
            if (feedback == null) {
                steps.fail(runId, "VERIFICATION_FAILED", "工作区验证未通过");
                return LoopSteps.ST_FAILED;
            }
            log.info("A4 验证未通过已回流修正 run={} 反馈字符数={}", runId, feedback.length());
            try {
                runLoop(runId);
            } catch (ModelGatewayException e) {
                failWithTrace(runId, "PROVIDER_ERROR", safeMessage(e.getMessage()));
            } catch (ContextLimitException e) {
                failWithTrace(runId, "CONTEXT_LIMIT", safeMessage(e.getMessage()));
            } catch (Exception e) {
                log.error("A4 回流修正执行异常 run={}: {}", runId, e.getMessage());
                failWithTrace(runId, "ENVIRONMENT_ERROR", safeMessage(e.getMessage()));
            }
            state = steps.snapshot(runId).state();
        }
        return state;
    }

    private boolean isMainRun(String runId) {
        return runRepository.findById(runId)
                .map(run -> run.getRole() == null || "MAIN".equals(run.getRole()))
                .orElse(true);
    }

    /**
     * L5 情景记忆入库钩子（S2 接入点）：Run 落 COMPLETED/FAILED 后异步摘要入库。
     * CANCELLED 不入库（用户主动打断噪音大）；ingest 内部按 sourceRunId 幂等去重，
     * 多入口 finally 重复触发安全；失败仅 log 不影响主流程。
     */
    private void ingestEpisodicIfFinished(String runId, String state) {
        if (!LoopSteps.ST_COMPLETED.equals(state) && !LoopSteps.ST_FAILED.equals(state)) {
            return;
        }
        try {
            RunEntity run = runRepository.findById(runId).orElse(null);
            if (run == null) {
                return;
            }
            String projectId = turnRepository.findById(run.getTurnId())
                    .flatMap(turn -> threadRepository.findById(turn.getThreadId()))
                    .map(com.dsh.storage.ThreadEntity::getProjectId)
                    .orElse(null);
            episodicMemoryService.ingest(projectId, runId, run.getTraceId(), state,
                    run.getVerificationStatus());
        } catch (Exception e) {
            log.warn("L5 情景记忆入库触发失败 run={}: {}", runId, e.getMessage());
        }
    }

    /** 读取 Run 的 traceId；不存在时返回 null（enterRun 会新建）。 */
    private String runTraceId(String runId) {
        return runRepository.findById(runId).map(RunEntity::getTraceId).orElse(null);
    }

    private void failWithTrace(String runId, String reason, String message) {
        try (TraceContext.SpanScope ignored = traceContext.enterRun(runTraceId(runId))) {
            steps.fail(runId, reason, message);
        }
    }

    private void runLoop(String runId) {
        while (true) {
            // 安全点：检查取消令牌
            if (cancelIfRequested(runId)) {
                return;
            }

            LoopSteps.RoundSnapshot snapshot = steps.snapshot(runId);
            if (!LoopSteps.ST_RUNNING.equals(snapshot.state())) {
                // PAUSED（WAITING_INPUT/WAITING_APPROVAL）或已终态：等待外部事件
                return;
            }
            if (steps.exceededBudget(snapshot).isPresent()) {
                steps.fail(runId, "BUDGET_LIMIT", steps.exceededBudget(snapshot).get());
                return;
            }

            completionReview.invalidateAtBoundary(runId);
            if (completionReview.exhausted(runId)) {
                steps.fail(runId, "COMPLETION_REVIEW_LIMIT", "收尾复核响应次数耗尽，未取得有效完成结论");
                return;
            }
            String candidate = completionReview.candidate(runId);
            if (candidate != null && completionReview.consumeCandidate(runId,candidate)) {
                // 退出0不作为完成证据；独立验证沿用现有SandboxRunner和C10新鲜度门。
                String verificationId = verificationService.runForCurrentWorkspace(runId);
                if (cancelIfRequested(runId)) return;
                if (verificationId != null) completionReview.open(runId, verificationId, candidate);
            }

            ModelRequest request;
            LoopSteps.PreparedRequest prepared;
            try {
                prepared = steps.prepare(runId, snapshot);
                request = prepared.request();
            } catch (BudgetAdmissionException e) {
                steps.fail(runId, "BUDGET_LIMIT", e.getMessage());
                return;
            }
            var gateway = gatewayRouter.gatewayFor(snapshot.provider());
            int reservation = CumulativeBudgetAdmission.reservation(
                    request.estimatedInputTokens(), request.maxOutputTokens());
            if (!budgetLedger.reserveModelCall(runId, reservation)) {
                if (!cancelIfRequested(runId) && LoopSteps.ST_RUNNING.equals(steps.snapshot(runId).state())) {
                    steps.fail(runId, "BUDGET_LIMIT", "下一次模型调用预算预约失败（余额/次数/截止时间已变化）");
                }
                return;
            }
            long startedNanos = System.nanoTime();
            // 供应商调用在事务外：只允许完整响应进入写事务
            CancellationToken loopToken = tokenRegistry.get(runId);
            ModelResponse response = null;
            boolean committed = false;
            try (TraceContext.SpanScope ignored = traceContext.startChildSpan()) {
                var observation = contextObservation.started(runId, prepared.contextMetrics());
                try {
                    response = gateway.generate(request, loopToken != null ? loopToken : new CancellationToken());
                } finally {
                    contextObservation.finished(observation, response == null ? null : response.usage(), response != null);
                }
                // 完整响应与 model.completed 在同一个模型 span 内，事件可定位该次请求。
                // 网关返回后仍先检查取消；取消后的响应不得落盘收尾。
                if (cancelIfRequested(runId)) {
                    return;
                }
                recordUsageError(runId, request, response);
                recordCacheUsage(runId, request, response);
                int durationMs = (int) Math.min(Integer.MAX_VALUE,
                        (System.nanoTime() - startedNanos) / 1_000_000L);
                LoopSteps.RoundResult result = steps.apply(runId, response, Math.max(durationMs, 0), reservation,
                        prepared.entryContext());
                committed = true;
                if (result != LoopSteps.RoundResult.CONTINUE) {
                    return;
                }
            } finally {
                if (!committed) {
                    steps.settleAbortedModelCall(runId, reservation, response == null ? null : response.usage());
                }
            }
        }
    }

    /**
     * DSH-010：取消安全点统一入口。返回 true 表示本 Run 已落取消终态，调用方应退出 Loop。
     * CANCELLING→用户取消收尾；其余活动/暂停态→父 Run 级联取消（PARENT_CANCELLED）。
     */
    private boolean cancelIfRequested(String runId) {
        com.dsh.agent.control.CancellationToken token = tokenRegistry.get(runId);
        if (token == null || !token.isCancelled()) {
            return false;
        }
        String cancelledState = steps.snapshot(runId).state();
        if (LoopSteps.ST_CANCELLING.equals(cancelledState)) {
            runControlService.finalizeCancellation(runId);
        } else {
            runControlService.cascadeCancelFromParent(runId);
        }
        return true;
    }

    private String safeMessage(String message) {
        if (message == null) {
            return "未知错误";
        }
        return message.length() <= 1900 ? message : message.substring(0, 1900);
    }

    /**
     * D-07：对比本次请求的预估输入 token 与供应商返回的真实 usage，记录误差供校准。
     * 仅当 usage 状态为 KNOWN 且 inputTokens 非空时记录；未知用量不臆测为 0。
     */
    private void recordUsageError(String runId, ModelRequest request, ModelResponse response) {
        Usage usage = response.usage();
        int estimated = request.estimatedInputTokens();
        if (estimated <= 0 || usage == null || !"KNOWN".equals(usage.status())
                || usage.inputTokens() == null) {
            return;
        }
        int actual = usage.inputTokens();
        int error = estimated - actual;
        int pct = actual > 0 ? (error * 100 / actual) : 0;
        log.info("context-budget usage-error run={} estimated={} actual={} error={} pct={}%",
                runId, estimated, actual, error, pct);
    }

    /**
     * D-09：记录稳定前缀哈希与 DeepSeek 前缀缓存命中情况，评估缓存布局收益。
     * 仅当 usage 状态为 KNOWN 且 cacheHit/cacheMiss 非空时记录；未知不臆测为 0。
     * prefixHash 相同表示同任务前缀稳定，可命中缓存；hitRate 越高缓存收益越大。
     */
    private void recordCacheUsage(String runId, ModelRequest request, ModelResponse response) {
        Usage usage = response.usage();
        if (usage == null || !"KNOWN".equals(usage.status())) {
            return;
        }
        Integer hit = usage.cacheHitTokens();
        Integer miss = usage.cacheMissTokens();
        if (hit == null || miss == null) {
            return;
        }
        int h = hit;
        int m = miss;
        int total = h + m;
        int hitPct = total > 0 ? (h * 100 / total) : 0;
        log.info("cache-prefix run={} prefixHash={} hit={} miss={} hitRate={}%",
                runId, request.prefixHash(), h, m, hitPct);
    }
}
