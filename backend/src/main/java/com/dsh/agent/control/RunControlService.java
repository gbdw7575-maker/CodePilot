package com.dsh.agent.control;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.event.EventPayloads;
import com.dsh.api.dto.event.EventType;
import com.dsh.application.event.EventMeta;
import com.dsh.application.event.EventStore;
import com.dsh.application.trace.TraceContext;
import com.dsh.storage.ApprovalEntity;
import com.dsh.storage.ApprovalRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

/**
 * 运行控制：cancel 状态机 + resume from BLOCKED。
 * cancel: RUNNING/WAITING_* → CANCELLING → CANCELLED（清理确认）或 BLOCKED（清理未知）。
 * resume: 仅 BLOCKED → attempt+1 → RECOVERING → RUNNING。
 */
@Service
public class RunControlService {
    @org.springframework.beans.factory.annotation.Autowired private com.dsh.orchestration.acceptance.TaskCheckRootRecovery checkRecovery;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private RunControlService controlSelf;
    /** Trusted recovery performs Docker control outside the final source-locked transaction. */
    public RunEntity recoverChecks(String runId){
        try{checkRecovery.recover(runId);
            return controlSelf.consumeRecoveredChecks(checkRecovery.observeRelease(runId));
        }catch(RuntimeException unknown){throw new ApiException(ErrorCode.INVALID_STATE,"RECOVERY_REQUIRED: 检查清理或围栏无法确认，保留阻塞运行");}
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public RunEntity consumeRecoveredChecks(com.dsh.orchestration.acceptance.TaskCheckRootRecovery.Release proof){
        String runId=proof.rootRunId();var initial=runRepository.findById(runId).orElseThrow();
        var source=lockMainSource(initial);if(source==null)throw new ApiException(ErrorCode.INVALID_STATE,"不是根运行");
        checkRecovery.requireCurrentJoined(proof);var run=source.root();
        if(!Set.of(ST_BLOCKED,ST_CANCELLING).contains(run.getState()))throw new ApiException(ErrorCode.INVALID_STATE,"运行不是待清理状态");
        // Recovery of a cancelled/uncertain generation finishes that generation; no checks are replayed.
        var turn=source.turn();var thread=source.thread();String from=run.getState();Instant now=Instant.now();
        entityManager.createNativeQuery("UPDATE agent_task_attempts SET state='CANCELLED',lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE plan_id IN(SELECT id FROM agent_plans WHERE root_run_id=:root) AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED')").setParameter("root",runId).executeUpdate();
        entityManager.createNativeQuery("UPDATE agent_tasks SET state='CANCELLED',updated_at=now() WHERE plan_id IN(SELECT id FROM agent_plans WHERE root_run_id=:root) AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED')").setParameter("root",runId).executeUpdate();
        run.setState(ST_CANCELLED);run.setStopReason("CHECK_RECOVERY_FENCED");run.setCleanupConfirmed(true);run.setBudgetUsageStatus("SETTLED");run.setUpdatedAt(now);
        run=runRepository.saveAndFlush(run);
        EventMeta meta=new EventMeta(thread.getProjectId(),thread.getId(),run.getTurnId(),runId,null,run.getTraceId(),traceContext.spanId());
        eventStore.append(meta,EventType.RUN_STATE_CHANGED,new EventPayloads.StateChanged(from,ST_CANCELLED,run.getVersion()+1));
        eventStore.append(meta,EventType.RUN_CANCELLED,new EventPayloads.RunCancelled(ST_CANCELLED,"CHECK_RECOVERY_FENCED",run.getVersion()+1));
        turn.setState(ST_CANCELLED);turn.setStopReason("CHECK_RECOVERY_FENCED");turn.setActiveKey(null);turn.setUpdatedAt(now);turnRepository.save(turn);
        thread.setActiveTurnId(null);thread.setUpdatedAt(now);threadRepository.save(thread);tokenRegistry.release(runId);return run;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void blockCheckRecovery(String runId){
        var initial=runRepository.findById(runId).orElseThrow();var source=lockMainSource(initial);if(source==null)return;
        var run=source.root();if(TERMINAL.contains(run.getState()))return;
        checkLifecycle.cancelRootChecks(runId);tokenRegistry.requestCancel(runId);String from=run.getState();Instant now=Instant.now();
        run.setState(ST_BLOCKED);run.setStopReason("RECOVERY_REQUIRED");run.setCleanupConfirmed(false);run.setCancelRequestedAt(now);run.setUpdatedAt(now);runRepository.saveAndFlush(run);
        source.turn().setState(ST_BLOCKED);source.turn().setStopReason("RECOVERY_REQUIRED");source.turn().setUpdatedAt(now);turnRepository.save(source.turn());
        EventMeta meta=new EventMeta(source.thread().getProjectId(),source.thread().getId(),run.getTurnId(),runId,null,run.getTraceId(),traceContext.spanId());
        eventStore.append(meta,EventType.RUN_STATE_CHANGED,new EventPayloads.StateChanged(from,ST_BLOCKED,run.getVersion()+1));
    }
    @org.springframework.beans.factory.annotation.Autowired
    private com.dsh.orchestration.acceptance.TaskCheckLifecycleGuard checkLifecycle;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public static final String ST_WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String ST_WAITING_INPUT = "WAITING_INPUT";
    public static final String ST_CANCELLING = "CANCELLING";
    public static final String ST_CANCELLED = "CANCELLED";
    public static final String ST_BLOCKED = "BLOCKED";
    public static final String ST_RECOVERING = "RECOVERING";

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED");
    private static final Set<String> CANCELLABLE = Set.of(
            "RUNNING", "WAITING_INPUT", "WAITING_APPROVAL", "PREPARING", "VERIFYING");

    private final RunRepository runRepository;
    private final TurnRepository turnRepository;
    private final ThreadRepository threadRepository;
    private final ApprovalRepository approvalRepository;
    private final EventStore eventStore;
    private final CancellationTokenRegistry tokenRegistry;
    private final TraceContext traceContext;

    public RunControlService(RunRepository runRepository, TurnRepository turnRepository,
                             ThreadRepository threadRepository, ApprovalRepository approvalRepository,
                             EventStore eventStore,
                             CancellationTokenRegistry tokenRegistry,
                             TraceContext traceContext) {
        this.runRepository = runRepository;
        this.turnRepository = turnRepository;
        this.threadRepository = threadRepository;
        this.approvalRepository = approvalRepository;
        this.eventStore = eventStore;
        this.tokenRegistry = tokenRegistry;
        this.traceContext = traceContext;
    }

    /**
     * 请求取消：终态→原样返回（200）；活动态→CANCELLING（202）。
     * 信号内存取消令牌；无活动令牌时清理状态待 Loop 确认→BLOCKED。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunEntity cancel(String runId, String reason) {
        RunEntity initial = runRepository.findById(runId).orElse(null);
        var source = lockMainSource(initial);
        RunEntity run = source == null ? runRepository.findByIdForUpdate(runId).orElse(null) : source.root();
        if (run == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "运行不存在");
        }
        if (isMain(run)) checkLifecycle.cancelRootChecks(runId);
        if (TERMINAL.contains(run.getState())) {
            return run;
        }
        if (ST_CANCELLING.equals(run.getState()) || ST_BLOCKED.equals(run.getState())) {
            return run;
        }
        if (!CANCELLABLE.contains(run.getState())) {
            return run;
        }

        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElseThrow();
        ThreadEntity thread = threadRepository.findByIdForUpdate(turn.getThreadId()).orElseThrow();
        EventMeta meta = new EventMeta(thread.getProjectId(), thread.getId(),
                run.getTurnId(), runId, run.getParentRunId(), run.getTraceId(), traceContext.spanId());

        String from = run.getState();
        Instant now = Instant.now();
        run.setState(ST_CANCELLING);
        run.setCancelRequestedAt(now);
        run.setCancelReason(reason);
        run.setUpdatedAt(now);
        run = runRepository.saveAndFlush(run);
        int publicVersion = run.getVersion() + 1;
        eventStore.append(meta, EventType.RUN_STATE_CHANGED,
                new EventPayloads.StateChanged(from, ST_CANCELLING, publicVersion));

        turn.setState(ST_CANCELLING);
        turn.setUpdatedAt(now);
        turnRepository.save(turn);

        // 暂停态（等待审批/追问）时 Loop 线程已挂起，但令牌仍注册到 Run 结束，
        // requestCancel 恒为 true 却不会有安全点消费信号：因此暂停态不依赖信号，
        // 直接作废未决审批并落 CANCELLED（令牌也一并置位，供可能在途的 resume 探测）。
        if (ST_WAITING_APPROVAL.equals(from) || ST_WAITING_INPUT.equals(from)) {
            tokenRegistry.requestCancel(runId);
            voidPendingApprovals(runId, meta);
            finalizeCancellation(runId);
            return run;
        }

        // 活动态：信号内存令牌，Loop 在安全点检查后完成最终化
        boolean signalled = tokenRegistry.requestCancel(runId);
        if (!signalled) {
            // 活动态 Loop 失联（可能崩溃）：清理不能确认→BLOCKED
            run.setState(ST_BLOCKED);
            run.setUpdatedAt(Instant.now());
            run = runRepository.saveAndFlush(run);
            eventStore.append(meta, EventType.RUN_STATE_CHANGED,
                    new EventPayloads.StateChanged(ST_CANCELLING, ST_BLOCKED, run.getVersion() + 1));
        }
        return run;
    }

    /** 将 Run 下所有 PENDING 审批置为 EXPIRED 并发 resolved 事件（取消/删除时作废未决审批）。 */
    private void voidPendingApprovals(String runId, EventMeta meta) {
        Instant now = Instant.now();
        for (ApprovalEntity approval : approvalRepository.findByRunIdAndStatus(runId, "PENDING")) {
            approval.setStatus("EXPIRED");
            approval.setUpdatedAt(now);
            approvalRepository.save(approval);
            eventStore.append(meta, EventType.APPROVAL_RESOLVED,
                    new EventPayloads.ApprovalResolved(approval.getId(), "EXPIRED"));
        }
    }

    /**
     * Loop 在安全点检测到取消后调用：CANCELLING → CANCELLED（清理确认）。
     * 已是终态或 BLOCKED 则 no-op。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunEntity finalizeCancellation(String runId) {
        RunEntity initial = runRepository.findById(runId).orElse(null);
        var source = lockMainSource(initial);
        RunEntity run = source == null ? runRepository.findByIdForUpdate(runId).orElse(null) : source.root();
        if (run == null || !ST_CANCELLING.equals(run.getState())) {
            return run;
        }
        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElseThrow();
        ThreadEntity thread = threadRepository.findByIdForUpdate(turn.getThreadId()).orElseThrow();
        EventMeta meta = new EventMeta(thread.getProjectId(), thread.getId(),
                run.getTurnId(), runId, run.getParentRunId(), run.getTraceId(), traceContext.spanId());

        Instant now = Instant.now();
        if (isMain(run) && checkLifecycle.hasRootChecks(runId)) {
            run.setState(ST_BLOCKED);
            run.setStopReason("RECOVERY_REQUIRED");
            run.setCleanupConfirmed(false);
            run.setUpdatedAt(now);
            run = runRepository.saveAndFlush(run);
            eventStore.append(meta, EventType.RUN_STATE_CHANGED,
                    new EventPayloads.StateChanged(ST_CANCELLING, ST_BLOCKED, run.getVersion() + 1));
            turn.setState(ST_BLOCKED);
            turn.setStopReason("RECOVERY_REQUIRED");
            turn.setUpdatedAt(now);
            turnRepository.save(turn);
            return run; // Keep active pointers and budget unsettled until real cleanup can be established.
        }
        run.setState(ST_CANCELLED);
        run.setStopReason("USER_CANCELLED");
        run.setCleanupConfirmed(true);
        run.setBudgetUsageStatus("SETTLED");
        run.setUpdatedAt(now);
        run = runRepository.saveAndFlush(run);
        int publicVersion = run.getVersion() + 1;
        eventStore.append(meta, EventType.RUN_STATE_CHANGED,
                new EventPayloads.StateChanged(ST_CANCELLING, ST_CANCELLED, publicVersion));
        eventStore.append(meta, EventType.RUN_CANCELLED,
                new EventPayloads.RunCancelled(ST_CANCELLED, "USER_CANCELLED", publicVersion));

        turn.setState(ST_CANCELLED);
        turn.setStopReason("USER_CANCELLED");
        turn.setActiveKey(null);
        turn.setUpdatedAt(now);
        turnRepository.save(turn);

        thread.setActiveTurnId(null);
        thread.setUpdatedAt(now);
        threadRepository.save(thread);

        tokenRegistry.release(runId);
        return run;
    }
    private com.dsh.orchestration.acceptance.RootTaskAcceptanceModes.RootSource lockMainSource(RunEntity initial) {
        if (!isMain(initial)) return null;
        checkLifecycle.lockRoot(initial.getId());
        // Refresh only after the shared SQL source locks; budget writes may have advanced the initial JPA version.
        entityManager.refresh(initial);
        var turn = turnRepository.findById(initial.getTurnId()).orElseThrow();
        entityManager.refresh(turn);
        var thread = threadRepository.findById(turn.getThreadId()).orElseThrow();
        entityManager.refresh(thread);
        return new com.dsh.orchestration.acceptance.RootTaskAcceptanceModes.RootSource(initial,turn,thread);
    }
    private static boolean isMain(RunEntity run) {
        return run != null && run.getParentRunId() == null && "MAIN".equals(run.getRole());
    }

    /**
     * DSH-010：父 Run 取消经令牌链传导到子 Run：子 Run 持久态不是 CANCELLING
     * （取消请求针对父 Run），在安全点统一落 CANCELLED/PARENT_CANCELLED。
     * 终态 no-op；活动态/暂停态都级联取消并清理 turn/thread 活跃指针。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cascadeCancelFromParent(String runId) {
        RunEntity run = runRepository.findByIdForUpdate(runId).orElse(null);
        if (run == null || TERMINAL.contains(run.getState())) {
            return;
        }
        if (ST_CANCELLING.equals(run.getState())) {
            finalizeCancellation(runId);
            return;
        }
        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElseThrow();
        ThreadEntity thread = threadRepository.findByIdForUpdate(turn.getThreadId()).orElseThrow();
        EventMeta meta = new EventMeta(thread.getProjectId(), thread.getId(),
                run.getTurnId(), runId, run.getParentRunId(), run.getTraceId(), traceContext.spanId());

        voidPendingApprovals(runId, meta);
        String from = run.getState();
        Instant now = Instant.now();
        run.setState(ST_CANCELLED);
        run.setStopReason("PARENT_CANCELLED");
        run.setCleanupConfirmed(true);
        run.setBudgetUsageStatus("SETTLED");
        run.setUpdatedAt(now);
        run = runRepository.saveAndFlush(run);
        eventStore.append(meta, EventType.RUN_STATE_CHANGED,
                new EventPayloads.StateChanged(from, ST_CANCELLED, run.getVersion() + 1));
        eventStore.append(meta, EventType.RUN_CANCELLED,
                new EventPayloads.RunCancelled(ST_CANCELLED, "PARENT_CANCELLED",
                        run.getVersion() + 1));

        turn.setState(ST_CANCELLED);
        turn.setStopReason("PARENT_CANCELLED");
        turn.setActiveKey(null);
        turn.setUpdatedAt(now);
        turnRepository.save(turn);

        thread.setActiveTurnId(null);
        thread.setUpdatedAt(now);
        threadRepository.save(thread);

        tokenRegistry.release(runId);
    }

    /**
     * 恢复 BLOCKED 运行：attempt+1，保留预算与工作区。
     * 非 BLOCKED 状态拒绝恢复。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunEntity resume(String runId) {
        var initial = runRepository.findById(runId).orElse(null);
        var source = lockMainSource(initial);
        RunEntity run = source == null ? runRepository.findByIdForUpdate(runId).orElse(null) : source.root();
        if (run == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "运行不存在");
        }
        if (TERMINAL.contains(run.getState())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "终态运行不能恢复");
        }
        if (!ST_BLOCKED.equals(run.getState())) {
            throw new ApiException(ErrorCode.INVALID_STATE,
                    "仅 BLOCKED 运行可以恢复，当前状态: " + run.getState());
        }
        if (isMain(run) && checkLifecycle.hasRootChecks(runId)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "检查清理尚未确认，不能恢复根运行");
        }

        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElseThrow();
        ThreadEntity thread = threadRepository.findByIdForUpdate(turn.getThreadId()).orElseThrow();
        EventMeta meta = new EventMeta(thread.getProjectId(), thread.getId(),
                run.getTurnId(), runId, run.getParentRunId(), run.getTraceId(), traceContext.spanId());

        int newAttempt = run.getAttempt() + 1;
        Instant now = Instant.now();
        run.setState(ST_RECOVERING);
        run.setAttempt(newAttempt);
        run.setUpdatedAt(now);
        run = runRepository.saveAndFlush(run);
        eventStore.append(meta, EventType.RUN_STATE_CHANGED,
                new EventPayloads.StateChanged(ST_BLOCKED, ST_RECOVERING, run.getVersion() + 1));

        run.setState("RUNNING");
        run.setUpdatedAt(Instant.now());
        run = runRepository.saveAndFlush(run);
        eventStore.append(meta, EventType.RUN_STATE_CHANGED,
                new EventPayloads.StateChanged(ST_RECOVERING, "RUNNING", run.getVersion() + 1));

        turn.setState("RUNNING");
        turn.setUpdatedAt(now);
        turnRepository.save(turn);
        return run;
    }
}
