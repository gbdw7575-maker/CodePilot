package com.dsh.orchestration.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dsh.orchestration.SubagentResult;
import com.dsh.orchestration.plan.TaskAcceptance;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.VerificationEntity;
import com.dsh.storage.VerificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 子任务验收闸门（MA-06，规范 §9：VERIFYING 状态、AT-10/AT-11）。
 *
 * <p>子 Run COMPLETED 不自动等同任务 SUCCEEDED。本服务在子 Run 终态后执行验收：
 * 检查子 Run 的验证记录（VerificationEntity.status=PASSED）、产物完整性与验收条目。
 * 验收通过才允许任务进入 SUCCEEDED 并封存不可变产物；证据缺失/测试失败则 FAILED，
 * 不释放下游依赖（AT-10）。封存后上游输入或任务版本变化时旧结果可追溯但不再有效（AT-11）。</p>
 *
 * <p>MA-05 基线口径（子 COMPLETED 即 SUCCEEDED）由 {@link TaskSchedulingService} 收紧为
 * 子 COMPLETED → VERIFYING → 验收 → SUCCEEDED/FAILED。验收仅针对 COMPLETED 的子 Run；
 * CANCELLED/FAILED/WAITING_APPROVAL 子 Run 终态不经过验收闸门，沿用 MA-05 路径。</p>
 */
@Service
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class TaskVerificationService {

    private static final Logger log = LoggerFactory.getLogger(TaskVerificationService.class);

    /** 验收失败原因码：子 Run 自述成功但验证记录缺失或测试未通过。 */
    static final String VERIFICATION_FAILED = "VERIFICATION_FAILED";
    /** 验收失败原因码：子 Run 无验证记录（证据缺失）。 */
    static final String EVIDENCE_MISSING = "EVIDENCE_MISSING";

    private final VerificationRepository verificationRepository;
    private final ObjectMapper objectMapper;
    /** 验收条目覆盖判定口径（MA-11 §17）。默认 KEYWORD＝保持 MA-06 既有行为。 */
    private final AcceptanceCoverage.Mode coverageMode;
    @org.springframework.beans.factory.annotation.Autowired
    private com.dsh.orchestration.acceptance.RootTaskAcceptanceModes acceptanceModes;

    public TaskVerificationService(VerificationRepository verificationRepository,
                                   ObjectMapper objectMapper,
                                   @Value("${dsh.orchestration.acceptance-coverage-mode:KEYWORD}")
                                   String acceptanceCoverageMode) {
        this.verificationRepository = verificationRepository;
        this.objectMapper = objectMapper;
        this.coverageMode = AcceptanceCoverage.Mode.parse(acceptanceCoverageMode);
    }

    public String executionCoverageMode() { return coverageMode.name(); }
    public static String normalizedCoverageMode(String raw) { return AcceptanceCoverage.Mode.parse(raw).name(); }

    /**
     * 对子 Run 终态结果执行验收。
     *
     * <p>验收维度（全部通过才 PASSED）：
     * <ol>
     *   <li>若子 Run 有验证记录（VerificationEntity）→ status 须为 PASSED 且非 stale</li>
     *   <li>若子 Run 无验证记录（MA-06 子 Run 侧不跑 VerificationService 的场景）→
     *       须有实际产物变更（patchArtifactId 非空）作为证据，否则 EVIDENCE_MISSING（AT-10）</li>
     *   <li>冻结的验收条目必须完整合法，子 Run 摘要须体现条目覆盖（既有存在性口径）</li>
     * </ol>
     * 验收不通过时返回 FAILED + reasonCode + 详情；通过时返回 PASSED + verificationRef。</p>
     *
     * @param task    任务定义（含冻结的 acceptance 条目）
     * @param result  子 Run 终态结果
     * @return 验收结论
     */
    @Transactional(readOnly = true)
    public Outcome verify(TaskEntity task, SubagentResult result) {
        if (acceptanceModes != null && !acceptanceModes.legacyAllowed(task.getPlanId())) {
            return new Outcome(false, EVIDENCE_MISSING,
                    "EXECUTION_NOT_AVAILABLE: 强验收尚无独立执行证据，禁止PASSED、摘要或patch兜底", null);
        }
        if (result.runId() == null || result.runId().isBlank()) {
            return new Outcome(false, EVIDENCE_MISSING, "子 Run ID 缺失，无法查验证记录", null);
        }
        final List<String> acceptance;
        try {
            acceptance = TaskAcceptance.readFrozen(objectMapper, task.getAcceptance());
        } catch (Exception e) {
            // A patch or a PASSED row cannot replace missing/corrupted frozen requirements.
            // Do not echo the stored payload or parser exception into public task error details.
            return new Outcome(false, EVIDENCE_MISSING,
                    "冻结验收条目缺失或损坏（须为1～8条非空字符串，每条最多200字符），不能跳过验收", null);
        }
        List<VerificationEntity> verifications = verificationRepository
                .findByRunIdOrderByStartedAtDesc(result.runId(),
                        org.springframework.data.domain.PageRequest.of(0, 1))
                .getContent();
        if (!verifications.isEmpty()) {
            VerificationEntity verification = verifications.get(0);
            if (!"PASSED".equals(verification.getStatus())) {
                return new Outcome(false, VERIFICATION_FAILED,
                        "子 Run 验证未通过 status=" + verification.getStatus()
                                + " exitCode=" + verification.getExitCode()
                                + " tests=" + verification.getTestsPassed() + "/"
                                + verification.getTestsDiscovered(),
                        verification.getId());
            }
            if (verification.isStale()) {
                return new Outcome(false, VERIFICATION_FAILED,
                        "验证记录已过期（stale=true），证据不再有效", verification.getId());
            }
            String acceptanceMismatch = checkAcceptanceCoverage(acceptance, result, verification);
            if (acceptanceMismatch != null) {
                return new Outcome(false, VERIFICATION_FAILED, acceptanceMismatch,
                        verification.getId());
            }
            log.info("任务验收通过（验证记录）task={} child={} verification={}",
                    task.getTaskId(), result.runId(), verification.getId());
            return new Outcome(true, null, null, verification.getId());
        }
        // MA-06：子 Run 无验证记录（AgentLoop 仅对 MAIN Run 跑 VerificationService）。
        // 以实际产物变更（patchArtifactId）作为证据兜底；无产物则 EVIDENCE_MISSING（AT-10）。
        boolean hasArtifact = result.patchArtifactId() != null
                && !result.patchArtifactId().isBlank();
        if (!hasArtifact) {
            return new Outcome(false, EVIDENCE_MISSING,
                    "子 Run 无验证记录且无产物变更（证据缺失），worker 自述成功不等于验收通过", null);
        }
        String acceptanceMismatch = checkAcceptanceCoverage(acceptance, result, null);
        if (acceptanceMismatch != null) {
            return new Outcome(false, VERIFICATION_FAILED, acceptanceMismatch, null);
        }
        log.info("任务验收通过（产物兜底）task={} child={} patch={}",
                task.getTaskId(), result.runId(), result.patchArtifactId());
        return new Outcome(true, null, null, null);
    }

    /**
     * 宽松验收条目覆盖校验：委托 {@link AcceptanceCoverage}，口径由
     * {@code dsh.orchestration.acceptance-coverage-mode} 决定
     * （默认 {@link AcceptanceCoverage.Mode#KEYWORD}，与 MA-06 行为逐字一致）。
     *
     * <p>本切片不做语义级判定，仅做存在性兜底——防止 worker 完全跳过验收条目。
     * MA-11 §17 查明 KEYWORD 口径会误杀「产物正确但摘要未逐字复述条目」的子任务，
     * 故提供 IDENTIFIER 口径作为修复开关（默认不启用，保持默认行为不变）。</p>
     *
     * @param verification 可空（无验证记录时传 null，跳过验证命令摘要部分）
     */
    private String checkAcceptanceCoverage(List<String> items, SubagentResult result,
                                           VerificationEntity verification) {
        String haystack = safeLower(result.summary()) + " "
                + (verification == null ? "" : safeLower(verification.getCommandSummary()) + " "
                        + safeLower(verification.getStatus()));
        return AcceptanceCoverage.evaluate(items, haystack, coverageMode);
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    /** 验收结论。 */
    public record Outcome(boolean passed, String reasonCode, String detail,
                          String verificationRef) {
    }
}
