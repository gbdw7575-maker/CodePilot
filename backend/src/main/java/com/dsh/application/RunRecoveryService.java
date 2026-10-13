package com.dsh.application;

import com.dsh.agent.LoopSteps;
import com.dsh.agent.LoopScheduler;
import com.dsh.application.trace.TraceContext;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.VerificationEntity;
import com.dsh.storage.VerificationRepository;
import com.dsh.storage.ApprovalRepository;
import com.dsh.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * D-14 进程中断恢复：启动时收敛进程所有的活动态孤儿 Run。
 *
 * <p>Loop 由进程内单线程 executor 驱动；进程重启后，仍停留在
 * QUEUED/PREPARING/RUNNING/VERIFYING 的 Run 已无人执行，必须收敛：</p>
 * <ul>
 *   <li>QUEUED/PREPARING/RUNNING → FAILED/RECOVERY_REQUIRED。
 *       不自动续跑模型循环：工具写副作用可能已提交但结果未落盘，重发调用会造成重复外部动作
 *       （apply_patch/write_file 的哈希预检会拦截重复写，但会产生误导性工具错误），
 *       且真实供应商调用有成本；由用户重新提交 Turn 更安全。</li>
 *   <li>VERIFYING 且已有验证记录 → 按已提交证据收敛（PASSED→COMPLETED，FAILED→FAILED），
 *       验证命令不重复执行（{@link VerificationService#run} 幂等边界同口径）。</li>
 *   <li>VERIFYING 且无验证记录 → FAILED/RECOVERY_REQUIRED：命令是否执行过无法确认，
 *       不重复执行，要求重新提交。</li>
 *   <li>WAITING_INPUT 不动；WAITING_APPROVAL 若仍 PENDING 则继续等待，若 APPROVED 且未消费则恢复，
 *       若已 CONSUMED 而未落工具结果则失败收敛，不自动重放未知副作用。</li>
 * </ul>
 *
 * <p>recoverOne 幂等：终态 Run 直接 no-op（{@code LoopSteps.fail} 对终态 no-op），
 * 重复恢复不产生重复事件。</p>
 */
@Service
public class RunRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RunRecoveryService.class);

    /** 进程所有的活动态（进程死亡后即为孤儿）；暂停态不在其列。 */
    private static final List<String> PROCESS_OWNED_STATES = List.of(
            LoopSteps.ST_QUEUED, LoopSteps.ST_PREPARING, LoopSteps.ST_RUNNING, LoopSteps.ST_VERIFYING);

    private final RunRepository runRepository;
    private final VerificationRepository verificationRepository;
    private final LoopSteps steps;
    private final WorkspaceManager workspaceManager;
    private final TraceContext traceContext;
    private final ApprovalRepository approvalRepository;
    private final LoopScheduler loopScheduler;

    public RunRecoveryService(RunRepository runRepository,
                              VerificationRepository verificationRepository,
                              LoopSteps steps,
                              WorkspaceManager workspaceManager,
                              TraceContext traceContext,
                              ApprovalRepository approvalRepository,
                              LoopScheduler loopScheduler) {
        this.runRepository = runRepository;
        this.verificationRepository = verificationRepository;
        this.steps = steps;
        this.workspaceManager = workspaceManager;
        this.traceContext = traceContext;
        this.approvalRepository = approvalRepository;
        this.loopScheduler = loopScheduler;
    }

    /** 启动恢复入口：应用就绪后扫描并收敛全部孤儿 Run。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedRuns() {
        List<RunEntity> orphans = runRepository.findByStateIn(
                java.util.List.of(LoopSteps.ST_QUEUED, LoopSteps.ST_PREPARING,
                        LoopSteps.ST_RUNNING, LoopSteps.ST_VERIFYING, LoopSteps.ST_WAITING_APPROVAL));
        for (RunEntity orphan : orphans) {
            try {
                recoverOne(orphan.getId());
            } catch (Exception e) {
                log.error("孤儿 Run 恢复失败 run={}: {}", orphan.getId(), e.getMessage(), e);
            }
        }
        if (!orphans.isEmpty()) {
            log.info("进程中断恢复完成：收敛 {} 个孤儿 Run", orphans.size());
        }
    }

    /**
     * 恢复单个 Run（幂等）。非进程所有状态（暂停态/终态）直接 no-op。
     * 供启动扫描与故障注入测试调用。
     */
    public void recoverOne(String runId) {
        RunEntity run = runRepository.findById(runId).orElse(null);
        if (run == null || (!PROCESS_OWNED_STATES.contains(run.getState())
                && !LoopSteps.ST_WAITING_APPROVAL.equals(run.getState()))) {
            return;
        }

        try (TraceContext.SpanScope ignored = traceContext.enterRun(run.getTraceId())) {
            if (LoopSteps.ST_WAITING_APPROVAL.equals(run.getState())) {
                recoverWaitingApproval(run);
            } else if (LoopSteps.ST_VERIFYING.equals(run.getState())) {
                recoverVerifying(run);
            } else {
                log.warn("孤儿 Run 收敛为失败终态 run={} state={} reason=RECOVERY_REQUIRED", runId, run.getState());
                steps.fail(runId, "RECOVERY_REQUIRED",
                        "进程中断于 " + run.getState() + "，已提交动作未重复执行，请重新提交");
            }
        }
        releaseIfTerminal(runId);
    }

    private void recoverWaitingApproval(RunEntity run) {
        if (!approvalRepository.findByRunIdAndStatus(run.getId(), "CONSUMED").isEmpty()) {
            steps.fail(run.getId(), "RECOVERY_REQUIRED",
                    "审批工具已开始执行但结果未提交，无法确认副作用，不自动重放");
            return;
        }
        var approved = approvalRepository.findByRunIdAndStatus(run.getId(), "APPROVED");
        if (!approved.isEmpty()) {
            loopScheduler.scheduleResumeFromApproval(approved.get(0).getTurnId(), run.getId());
        }
    }

    /** VERIFYING 孤儿：按已提交验证证据收敛；无证据则失败收敛，不重复执行验证命令。 */
    private void recoverVerifying(RunEntity run) {
        var latest = verificationRepository.findByRunIdOrderByStartedAtDesc(
                run.getId(), PageRequest.of(0, 1));
        if (latest.isEmpty()) {
            log.warn("验证中断且无已提交证据 run={}，不重跑验证命令", run.getId());
            steps.fail(run.getId(), "RECOVERY_REQUIRED",
                    "进程中断于验证执行期间，证据未提交且未重复执行验证命令，请重新提交");
            return;
        }
        VerificationEntity evidence = latest.getContent().get(0);
        if ("PASSED".equals(evidence.getStatus()) && steps.verificationPassed(run.getId())
                && !steps.completionReviewSatisfied(run.getId())) {
            steps.fail(run.getId(), "COMPLETION_REVIEW_REQUIRED",
                    "验证通过但有界完成复核未提交，不重放模型或验证命令，请重新提交");
            return;
        }
        if ("PASSED".equals(evidence.getStatus()) && steps.verificationPassed(run.getId())) {
            log.info("验证中断但证据已提交 run={} verification={} status=PASSED → 按证据完成",
                    run.getId(), evidence.getId());
            steps.completeAfterVerification(run.getId());
        } else {
            log.info("验证中断但证据已提交 run={} verification={} status=FAILED → 按证据失败",
                    run.getId(), evidence.getId());
            String reason = "PASSED".equals(evidence.getStatus()) ? "VERIFICATION_STALE" : "VERIFICATION_FAILED";
            steps.fail(run.getId(), reason, "工作区验证未通过或证据已过期（进程恢复后按已提交证据收敛）");
        }
    }

    private void releaseIfTerminal(String runId) {
        try {
            workspaceManager.release(runId);
        } catch (Exception e) {
            log.warn("恢复后释放工作区租约失败 run={}: {}", runId, e.getMessage());
        }
    }
}
