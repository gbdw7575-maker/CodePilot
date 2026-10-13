package com.dsh.agent.control;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ApprovalDecision;
import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.event.EventPayloads;
import com.dsh.api.dto.event.EventType;
import com.dsh.application.DshProperties;
import com.dsh.application.event.EventMeta;
import com.dsh.application.event.EventStore;
import com.dsh.application.trace.TraceContext;
import com.dsh.common.IdGen;
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
import java.util.List;
import java.util.Map;

/**
 * 审批生命周期：request（PENDING）→ decide（APPROVED/DENIED）→ consume（执行前复核）。
 * 审批绑定 parametersDigest + workspaceDigest；摘要变化即失效（INVALIDATED）。
 * 状态一次性转换：PENDING → 终态后不可再改。
 */
@Service
public class ApprovalService {

    private final ApprovalRepository approvalRepository;
    private final RunRepository runRepository;
    private final TurnRepository turnRepository;
    private final ThreadRepository threadRepository;
    private final EventStore eventStore;
    private final DshProperties properties;
    private final TraceContext traceContext;

    public ApprovalService(ApprovalRepository approvalRepository, RunRepository runRepository,
                           TurnRepository turnRepository, ThreadRepository threadRepository,
                           EventStore eventStore, DshProperties properties,
                           TraceContext traceContext) {
        this.approvalRepository = approvalRepository;
        this.runRepository = runRepository;
        this.turnRepository = turnRepository;
        this.threadRepository = threadRepository;
        this.eventStore = eventStore;
        this.properties = properties;
        this.traceContext = traceContext;
    }

    /**
     * 创建 PENDING 审批并发出 approval.requested 事件。
     * 同一 toolCallId 重复请求返回已有审批（幂等）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public ApprovalEntity request(String turnId, String runId, String toolCallId, String toolName,
                                  String argumentsSummary, String argumentsJson,
                                  String parametersDigest, String workspaceDigest, String risk) {
        RunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "运行不存在"));
        TurnEntity turn = turnRepository.findById(turnId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "轮次不存在"));
        ThreadEntity thread = threadRepository.findById(turn.getThreadId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在"));

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.policy().approvalTimeoutMinutes() * 60L);
        ApprovalEntity approval = new ApprovalEntity(
                IdGen.next("apv_"), turnId, runId, toolCallId, toolName,
                argumentsSummary, argumentsJson, parametersDigest, workspaceDigest, risk,
                "工具副作用需人工审批", expiresAt, now, now);
        approvalRepository.save(approval);

        try (TraceContext.SpanScope ignored = traceContext.spanId() == null
                ? traceContext.enterRun(run.getTraceId()) : traceContext.startChildSpan()) {
            EventMeta meta = new EventMeta(thread.getProjectId(), thread.getId(), turnId, runId,
                    run.getParentRunId(), run.getTraceId(), traceContext.spanId());
            eventStore.append(meta, EventType.APPROVAL_REQUESTED,
                    new EventPayloads.ApprovalRequested(approval.getId(), toolCallId, expiresAt));
        }
        return approval;
    }

    /**
     * 审批决策：PENDING → APPROVED/DENIED。
     * 摘要不匹配 → APPROVAL_STALE；已决策 → APPROVAL_ALREADY_DECIDED；过期 → EXPIRED。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApprovalEntity decide(String approvalId, ApprovalDecision.Decision decision,
                                 String parametersDigest, String workspaceDigest) {
        ApprovalEntity approval = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "审批不存在"));

        if (!"PENDING".equals(approval.getStatus())) {
            throw new ApiException(ErrorCode.APPROVAL_ALREADY_DECIDED,
                    "审批已决策，状态: " + approval.getStatus());
        }
        if (Instant.now().isAfter(approval.getExpiresAt())) {
            approval.setStatus("EXPIRED");
            approval.setUpdatedAt(Instant.now());
            approvalRepository.save(approval);
            appendResolvedEvent(approval, "EXPIRED");
            throw new ApiException(ErrorCode.APPROVAL_ALREADY_DECIDED, "审批已过期");
        }
        if (!approval.getParametersDigest().equals(parametersDigest)
                || !approval.getWorkspaceDigest().equals(workspaceDigest)) {
            throw new ApiException(ErrorCode.APPROVAL_STALE,
                    "审批绑定的参数或工作区摘要已变化，请重新发起审批",
                    Map.<String, Object>of("expectedParametersDigest", approval.getParametersDigest(),
                            "expectedWorkspaceDigest", approval.getWorkspaceDigest()));
        }

        String status = decision == ApprovalDecision.Decision.APPROVE ? "APPROVED" : "DENIED";
        approval.setStatus(status);
        approval.setUpdatedAt(Instant.now());
        approvalRepository.save(approval);
        appendResolvedEvent(approval, status);
        return approval;
    }

    /**
     * 执行前复核：APPROVED 且摘要匹配才允许执行，并在独立事务中持久化 CONSUMED。
     * 进程在副作用与工具结果提交之间中断时，恢复器据此拒绝自动重放。
     * 摘要不匹配 → 置 INVALIDATED 并返回 false（工具应返回 DENIED 结果）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(String approvalId, String parametersDigest, String workspaceDigest) {
        ApprovalEntity approval = approvalRepository.findById(approvalId).orElse(null);
        if (approval == null) {
            return false;
        }
        if (!"APPROVED".equals(approval.getStatus())) {
            return false;
        }
        if (!approval.getParametersDigest().equals(parametersDigest)
                || !approval.getWorkspaceDigest().equals(workspaceDigest)) {
            approval.setStatus("INVALIDATED");
            approval.setUpdatedAt(Instant.now());
            approvalRepository.save(approval);
            appendResolvedEvent(approval, "INVALIDATED");
            return false;
        }
        approval.setStatus("CONSUMED");
        approval.setUpdatedAt(Instant.now());
        approvalRepository.saveAndFlush(approval);
        return true;
    }

    private void appendResolvedEvent(ApprovalEntity approval, String status) {
        RunEntity run = runRepository.findById(approval.getRunId()).orElse(null);
        if (run == null) {
            return;
        }
        TurnEntity turn = turnRepository.findById(approval.getTurnId()).orElse(null);
        if (turn == null) {
            return;
        }
        ThreadEntity thread = threadRepository.findById(turn.getThreadId()).orElse(null);
        if (thread == null) {
            return;
        }
        try (TraceContext.SpanScope ignored = traceContext.spanId() == null
                ? traceContext.enterRun(run.getTraceId()) : traceContext.startChildSpan()) {
            EventMeta meta = new EventMeta(thread.getProjectId(), thread.getId(),
                    approval.getTurnId(), approval.getRunId(), run.getParentRunId(),
                    run.getTraceId(), traceContext.spanId());
            eventStore.append(meta, EventType.APPROVAL_RESOLVED,
                    new EventPayloads.ApprovalResolved(approval.getId(), status));
        }
    }

    /** 查找运行最近一个 PENDING 审批（resume 前定位待执行工具调用）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public java.util.Optional<ApprovalEntity> findPending(String runId) {
        List<ApprovalEntity> pending = approvalRepository.findByRunIdAndStatus(runId, "PENDING");
        return pending.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(pending.get(0));
    }

    /** 查找运行最近一个 APPROVED 审批（decide 后 resume 前定位待执行工具调用）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public java.util.Optional<ApprovalEntity> findApproved(String runId) {
        List<ApprovalEntity> approved = approvalRepository.findByRunIdAndStatus(runId, "APPROVED");
        return approved.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(approved.get(0));
    }

    /** 列出 Turn 的审批记录。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<ApprovalEntity> listByTurn(String turnId) {
        return approvalRepository.listByTurn(turnId);
    }

    /** ApprovalEntity → Approval DTO。 */
    public static com.dsh.api.dto.Approval toDto(ApprovalEntity e) {
        return new com.dsh.api.dto.Approval(
                e.getId(), e.getTurnId(), e.getRunId(), e.getToolCallId(), e.getToolName(),
                e.getArgumentsSummary(), e.getParametersDigest(), e.getWorkspaceDigest(),
                com.dsh.api.dto.Risk.valueOf(e.getRisk()), e.getReason(), e.getScope(),
                com.dsh.api.dto.ApprovalStatus.valueOf(e.getStatus()), e.getExpiresAt(),
                e.getVersion() + 1, e.getCreatedAt(), e.getUpdatedAt());
    }
}
