package com.dsh.orchestration;

import com.dsh.agent.AgentLoop;
import com.dsh.agent.control.CancellationTokenRegistry;
import com.dsh.agent.control.RunControlService;
import com.dsh.agent.control.ApprovalService;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.workspace.WorkspaceManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 子 Run 的唯一执行者。暂停不是返回点；外部只提交审批决策，原 worker 恢复执行。 */
@Service
public class ChildRunLifecycle {
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED");
    private final Set<String> managed = ConcurrentHashMap.newKeySet();
    private final AgentLoop loop;
    private final RunRepository runs;
    private final ApprovalService approvals;
    private final RunControlService control;
    private final CancellationTokenRegistry tokens;
    private final WorkspaceManager workspaces;
    private final long pauseTimeoutNanos;

    public ChildRunLifecycle(AgentLoop loop, RunRepository runs, ApprovalService approvals,
                             RunControlService control, CancellationTokenRegistry tokens,
                             WorkspaceManager workspaces,
                             @Value("${dsh.orchestration.child-approval-timeout-seconds:60}") long timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) {
            throw new IllegalArgumentException("child-approval-timeout-seconds 必须在 1..3600 内");
        }
        this.loop = loop;
        this.runs = runs;
        this.approvals = approvals;
        this.control = control;
        this.tokens = tokens;
        this.workspaces = workspaces;
        this.pauseTimeoutNanos = Duration.ofSeconds(timeoutSeconds).toNanos();
    }

    public long executionApprovalTimeoutSeconds() { return java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(pauseTimeoutNanos); }

    public boolean isManaged(String runId) { return managed.contains(runId); }

    /** 调用者持有预算和并发租约；本方法仅在终态返回。没有长事务或第二条执行线程。 */
    public void execute(String parentId, String childId) {
        if (!managed.add(childId)) throw new IllegalStateException("子 Run 已有执行者: " + childId);
        boolean interrupted = false;
        try {
            loop.startRootRun(childId);
            long pausedAt = 0;
            while (true) {
                RunEntity child = runs.findById(childId).orElseThrow();
                if (TERMINAL.contains(child.getState())) return;
                RunEntity parent = runs.findById(parentId).orElse(null);
                var token = tokens.get(childId);
                if (parent == null || TERMINAL.contains(parent.getState())
                        || "CANCELLING".equals(parent.getState())
                        || (token != null && token.isCancelled())) {
                    control.cascadeCancelFromParent(childId);
                    continue;
                }
                Instant now = Instant.now();
                if ((child.getBudgetDeadlineAt() != null && !now.isBefore(child.getBudgetDeadlineAt()))
                        || (parent.getBudgetDeadlineAt() != null && !now.isBefore(parent.getBudgetDeadlineAt()))) {
                    cancelIdle(childId, "CHILD_DEADLINE_EXCEEDED");
                    continue;
                }
                if ("CANCELLING".equals(child.getState())) {
                    control.finalizeCancellation(childId);
                    continue;
                }
                if ("RUNNING".equals(child.getState())) {
                    pausedAt = 0;
                    loop.resumeRootRun(childId);
                    continue;
                }
                if (!"WAITING_APPROVAL".equals(child.getState())
                        && !"WAITING_INPUT".equals(child.getState())) {
                    throw new IllegalStateException("子 Run 未收敛: " + childId + " state=" + child.getState());
                }
                if (pausedAt == 0) pausedAt = System.nanoTime();
                if (System.nanoTime() - pausedAt >= pauseTimeoutNanos) {
                    cancelIdle(childId, "CHILD_APPROVAL_TIMEOUT");
                    continue;
                }
                if ("WAITING_APPROVAL".equals(child.getState())) {
                    // REQUIRES_NEW 短事务取得新快照，不能在外层事务同步作用域复用旧实体。
                    var snapshot = approvals.listByTurn(child.getTurnId()).stream()
                            .filter(a -> childId.equals(a.getRunId())).toList();
                    var pending = snapshot.stream().filter(a -> "PENDING".equals(a.getStatus())).toList();
                    if (pending.stream().anyMatch(a -> !now.isBefore(a.getExpiresAt()))) {
                        cancelIdle(childId, "CHILD_APPROVAL_EXPIRED");
                        continue;
                    }
                    var approved = snapshot.stream().filter(a -> "APPROVED".equals(a.getStatus())).toList();
                    if (approved.stream().anyMatch(a -> !now.isBefore(a.getExpiresAt()))) {
                        cancelIdle(childId, "CHILD_APPROVAL_EXPIRED");
                        continue;
                    }
                    if (!approved.isEmpty()) {
                        // 仍由 LoopSteps/ApprovalService.consume 复核参数和工作区摘要。
                        loop.resumeFromApproval(child.getTurnId(), childId);
                        pausedAt = 0;
                        continue;
                    }
                    if (pending.isEmpty()
                            && snapshot.stream().anyMatch(a -> "DENIED".equals(a.getStatus()))) {
                        cancelIdle(childId, "CHILD_APPROVAL_DENIED");
                        continue;
                    }
                }
                Thread.sleep(50);
            }
        } catch (RuntimeException e) {
            // JDBC 连接池也可能把中断包装为运行时异常；先清标志完成取消与清理。
            boolean interruptedDuringIo = Thread.interrupted();
            interrupted |= interruptedDuringIo;
            try {
                cancelIdle(childId, interruptedDuringIo ? "CHILD_INTERRUPTED" : "CHILD_EXECUTION_ERROR");
                if (interruptedDuringIo && runs.findById(childId)
                        .map(r -> TERMINAL.contains(r.getState())).orElse(false)) return;
            } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        } catch (InterruptedException e) {
            interrupted = true;
            cancelIdle(childId, "CHILD_INTERRUPTED");
            if (!runs.findById(childId).map(r -> TERMINAL.contains(r.getState())).orElse(false)) {
                throw new IllegalStateException("子 Run 中断后取消未确认: " + childId, e);
            }
        } finally {
            interrupted |= Thread.interrupted();
            try {
                if (runs.findById(childId).map(r -> TERMINAL.contains(r.getState())).orElse(false)) {
                    workspaces.release(childId);
                    tokens.release(childId);
                }
            } finally {
                managed.remove(childId);
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private void cancelIdle(String childId, String reason) {
        control.cancel(childId, reason);
        // 唯一 worker 当前未在模型/工具调用内，可确认安全点。
        control.finalizeCancellation(childId);
    }
}
