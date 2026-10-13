package com.dsh.evals;

import com.dsh.agent.control.ApprovalService;
import com.dsh.agent.control.RunControlService;
import com.dsh.api.dto.ApprovalDecision;
import com.dsh.api.dto.TurnAccepted;
import com.dsh.application.TurnService;
import com.dsh.orchestration.task.TaskCancelService;
import com.dsh.orchestration.task.TaskQueryService;
import com.dsh.orchestration.task.TaskStates;
import com.dsh.storage.ApprovalRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/** 等待真实执行收敛；数据库暂停态不等于 trial 已结束。无长事务，不持锁等待。 */
@Service
public class EvalRunAwaiter {
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED");
    private final RunRepository runs;
    private final ApprovalRepository approvals;
    private final ApprovalService decisions;
    private final TurnService turns;
    private final RunControlService control;
    private final ObjectProvider<TaskQueryService> tasks;
    private final ObjectProvider<TaskCancelService> cancellations;

    public EvalRunAwaiter(RunRepository runs, ApprovalRepository approvals,
                          ApprovalService decisions, TurnService turns, RunControlService control,
                          ObjectProvider<TaskQueryService> tasks,
                          ObjectProvider<TaskCancelService> cancellations) {
        this.runs = runs;
        this.approvals = approvals;
        this.decisions = decisions;
        this.turns = turns;
        this.control = control;
        this.tasks = tasks;
        this.cancellations = cancellations;
    }

    public RunEntity await(TurnAccepted accepted, long startedNanos, Duration budget) {
        return await(accepted, startedNanos, budget, Duration.ofSeconds(30), 100);
    }

    // 短轮询用于数据库跨线程可见性；测试可缩短时间而不依赖真实模型。
    RunEntity await(TurnAccepted accepted, long startedNanos, Duration budget,
                    Duration cleanupGrace, long pollMillis) {
        String rootId = accepted.rootRunId();
        Set<String> handledApprovals = new HashSet<>();
        String abortReason = null;
        long abortStarted = 0;
        try {
            while (true) {
                RunEntity root = runs.findById(rootId).orElseThrow();
                var children = runs.listChildren(rootId);
                var taskQuery = tasks.getIfAvailable();
                var taskRows = taskQuery == null ? java.util.List.<com.dsh.storage.TaskEntity>of()
                        : taskQuery.indexRootTasks(rootId).values().stream().toList();
                boolean tasksActive = taskRows.stream().anyMatch(t -> !TaskStates.isTerminal(t.getState())
                        && !TaskStates.DRAFT.equals(t.getState()));
                boolean settled = TERMINAL.contains(root.getState())
                        && children.stream().allMatch(c -> TERMINAL.contains(c.getState())) && !tasksActive;
                if (settled) {
                    if (abortReason != null) throw new IllegalStateException(abortReason + ": " + rootId);
                    return root;
                }
                if (abortReason == null) {
                    if ("WAITING_INPUT".equals(root.getState()) || "BLOCKED".equals(root.getState())) {
                        abortReason = "EVAL_REQUIRES_INTERVENTION";
                    } else if (System.nanoTime() - startedNanos >= budget.toNanos()) {
                        abortReason = "EVAL_DEADLINE_EXCEEDED";
                    } else {
                        var waiting = new java.util.ArrayList<RunEntity>();
                        waiting.add(root);
                        waiting.addAll(children);
                        for (var candidate : waiting) {
                            if (!"WAITING_APPROVAL".equals(candidate.getState())) continue;
                            for (var approval : approvals.findByRunIdAndStatus(candidate.getId(), "PENDING")) {
                                if (!handledApprovals.add(approval.getId())) continue;
                                if (handledApprovals.size() > 100) {
                                    abortReason = "EVAL_APPROVAL_LIMIT";
                                    break;
                                }
                                decisions.decide(approval.getId(), ApprovalDecision.Decision.APPROVE,
                                        approval.getParametersDigest(), approval.getWorkspaceDigest());
                                // 根 Run 调度恢复；子 Run 由仍持有租约的原 worker 消费决策。
                                turns.resumeFromApproval(candidate.getTurnId());
                                break;
                            }
                            if (abortReason != null) break;
                        }
                    }
                    if (abortReason != null) {
                        abortStarted = System.nanoTime();
                        cancel(rootId, abortReason);
                    }
                } else if (System.nanoTime() - abortStarted >= cleanupGrace.toNanos()) {
                    throw new IllegalStateException(abortReason + ": cleanup 未确认，停止评测并保留运行 " + rootId);
                }
                Thread.sleep(pollMillis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try { cancel(rootId, "EVAL_INTERRUPTED"); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw new IllegalStateException("评测被中断，未确认执行结束: " + rootId, e);
        }
    }

    private void cancel(String rootId, String reason) {
        control.cancel(rootId, reason);
        var query = tasks.getIfAvailable();
        var service = cancellations.getIfAvailable();
        if (query != null && service != null) {
            service.cancel(rootId, query.indexRootTasks(rootId).keySet().stream().toList(), reason);
        }
        for (var child : runs.listChildren(rootId)) {
            if (!TERMINAL.contains(child.getState())) control.cancel(child.getId(), reason);
        }
    }
}
