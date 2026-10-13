package com.dsh.orchestration;

import com.dsh.common.IdGen;
import com.dsh.storage.IntegrationRecordEntity;
import com.dsh.storage.IntegrationRecordRepository;
import com.dsh.storage.PlanEntity;
import com.dsh.storage.PlanRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.TaskAttemptEntity;
import com.dsh.storage.TaskAttemptRepository;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.TaskRepository;
import com.dsh.storage.TaskResultEntity;
import com.dsh.storage.TaskResultRepository;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 父工作区集成服务（MA-07，规范 §8.3 合并协议 + §9 根 Run 完成要求）。
 *
 * <p>所有当前版本必需任务 SUCCEEDED 后，把各子任务已验收产物合并到父工作区：
 * <ul>
 *   <li>记录 IntegrationRecord（baseDigest/输入产物/fencingToken/state/appliedPaths journal）；</li>
 *   <li>复用 {@link WorkspaceMerge#mergeAll} 做多写者三方合并，冲突整批不应用（AT-13）；</li>
 *   <li>迟到写拒绝：旧 fencingToken / 已取消的 attempt 产物不写入当前工作区（§8.3）；</li>
 *   <li>中断恢复：IN_PROGRESS record 重启后，工作区未被改（摘要==baseDigest 且 appliedPaths 空）
 *       则安全重做，否则 RECOVERED_BLOCKED 阻塞报告，不静默留半份补丁（AT-13）。</li>
 * </ul>
 *
 * <p>MA-07 不做取消级联/fencing 写校验（MA-08）、重规划（MA-09）；fencingToken 落库为后续
 * 切片基础，本切片校验父工作区摘要一致性防中途篡改。</p>
 */
@Service
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class IntegrationService {
    @org.springframework.beans.factory.annotation.Autowired private com.dsh.orchestration.acceptance.RootTaskAcceptanceModes strongModes;
    @org.springframework.beans.factory.annotation.Autowired private com.dsh.orchestration.acceptance.TaskCheckReceiptReader strongReceipts;

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);

    public static final String STATE_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATE_APPLIED = "APPLIED";
    public static final String STATE_CONFLICTS = "CONFLICTS";
    public static final String STATE_RECOVERED_BLOCKED = "RECOVERED_BLOCKED";

    private final IntegrationRecordRepository recordRepository;
    private final WorkspaceMerge workspaceMerge;
    private final WorkspaceManager workspaceManager;
    private final PlanRepository planRepository;
    private final TaskRepository taskRepository;
    private final TaskResultRepository resultRepository;
    private final RunRepository runRepository;
    private final TaskAttemptRepository attemptRepository;
    private final ObjectMapper objectMapper;

    public IntegrationService(IntegrationRecordRepository recordRepository,
                               WorkspaceMerge workspaceMerge, WorkspaceManager workspaceManager,
                               PlanRepository planRepository, TaskRepository taskRepository,
                               TaskResultRepository resultRepository,
                               RunRepository runRepository,
                               TaskAttemptRepository attemptRepository,
                               ObjectMapper objectMapper) {
        this.recordRepository = recordRepository;
        this.workspaceMerge = workspaceMerge;
        this.workspaceManager = workspaceManager;
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.resultRepository = resultRepository;
        this.runRepository = runRepository;
        this.attemptRepository = attemptRepository;
        this.objectMapper = objectMapper;
    }

    /** 集成结果。state ∈ APPLIED/CONFLICTS/RECOVERED_BLOCKED/SKIPPED/FAILED。 */
    public record IntegrationOutcome(String integrationId, String state, String resultDigest,
                                     List<String> appliedPaths, List<String> conflicts,
                                     String message) {
    }

    /** collectSucceededWriters 结果：写者列表 + 旧 fencingToken 被拒绝的 attempt。 */
    private record SucceededWriters(List<WorkspaceMerge.Writer> writers,
                                    List<String> staleAttemptIds) {
        static SucceededWriters empty() {
            return new SucceededWriters(List.of(), List.of());
        }
    }

    /**
     * 触发根 Run 的父工作区集成。
     *
     * <p>由 TaskSchedulingService.registerPostCompletion 在所有必需任务 SUCCEEDED 后调用。
     * 重复调用幂等：已有 APPLIED/CONFLICTS/RECOVERED_BLOCKED 终态 record 直接返回；
     * IN_PROGRESS record 走中断恢复判定。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IntegrationOutcome integrate(String rootRunId) {
        if(strongModes!=null&&strongModes.isStrong(rootRunId)){
            strongModes.lockSource(rootRunId);
            if(!strongReceipts.rootReady(rootRunId))return new IntegrationOutcome(null,"FAILED",null,List.of(),List.of(),"EVIDENCE_MISSING: 强任务凭据缺失或过期");
            var prior=recordRepository.findFirstByRootRunIdOrderByCreatedAtDesc(rootRunId);
            if(prior.isPresent()&&STATE_APPLIED.equals(prior.get().getState())){
                var parent=workspaceManager.find(rootRunId);
                if(parent==null||!java.util.Objects.equals(prior.get().getResultDigest(),workspaceManager.currentDigest(parent)))
                    return new IntegrationOutcome(prior.get().getIntegrationId(),STATE_RECOVERED_BLOCKED,null,List.of(),List.of(),"父交付已变化，旧 APPLIED 不可复用");
            }
        }
        // 1. 幂等检查：已有终态 record 直接返回
        Optional<IntegrationRecordEntity> latestOpt =
                recordRepository.findFirstByRootRunIdOrderByCreatedAtDesc(rootRunId);
        if (latestOpt.isPresent()) {
            IntegrationRecordEntity latest = latestOpt.get();
            if (STATE_APPLIED.equals(latest.getState())
                    || STATE_CONFLICTS.equals(latest.getState())) {
                return new IntegrationOutcome(latest.getIntegrationId(), latest.getState(),
                        latest.getResultDigest(), parseList(latest.getAppliedPathsJson()),
                        List.of(), "已有终态集成记录: " + latest.getState());
            }
            if (STATE_RECOVERED_BLOCKED.equals(latest.getState())) {
                return new IntegrationOutcome(latest.getIntegrationId(),
                        STATE_RECOVERED_BLOCKED, latest.getResultDigest(),
                        parseList(latest.getAppliedPathsJson()), List.of(),
                        "上次集成中断且不可安全恢复，已阻塞: " + latest.getIntegrationId());
            }
            if (STATE_IN_PROGRESS.equals(latest.getState())) {
                // 2. 中断恢复：IN_PROGRESS record 说明上次崩溃在本切片窗口
                IntegrationOutcome recovery = handleRecovery(rootRunId, latest);
                if (recovery != null) {
                    return recovery;
                }
            }
        }

        // 3. 组装 SUCCEEDED 子任务写者（childRunId + workspaceRef）+ fencingToken 写校验（§8.3）
        SucceededWriters collected = collectSucceededWriters(rootRunId);
        // MA-08 fencingToken 写校验（AT-15 窗口3）：旧 fencingToken 的迟到产物拒绝写入当前工作区
        if (!collected.staleAttemptIds().isEmpty()) {
            String integrationId = IdGen.next("intg_");
            Instant now = Instant.now();
            String fencingToken = rootRunId + ":" + now.toEpochMilli();
            IntegrationRecordEntity record = new IntegrationRecordEntity(integrationId, rootRunId,
                    resolveProjectId(rootRunId), "", "[]", null, STATE_RECOVERED_BLOCKED,
                    "[]", null, fencingToken, now, now);
            recordRepository.save(record);
            log.warn("集成拒绝旧 fencingToken 产物 root={} stale={}", rootRunId,
                    collected.staleAttemptIds());
            return new IntegrationOutcome(integrationId, STATE_RECOVERED_BLOCKED, null, List.of(),
                    List.of("旧 fencingToken 的迟到产物拒绝写入: " + collected.staleAttemptIds()),
                    "旧 fencingToken 的迟到产物拒绝写入当前工作区");
        }
        List<WorkspaceMerge.Writer> writers = collected.writers();
        if (writers.isEmpty()) {
            // 无 SUCCEEDED 子任务产物 → 无集成需求（根 Run 无写任务或全是空产出）
            String integrationId = IdGen.next("intg_");
            Instant now = Instant.now();
            String fencingToken = rootRunId + ":" + now.toEpochMilli();
            IntegrationRecordEntity record = new IntegrationRecordEntity(integrationId, rootRunId,
                    resolveProjectId(rootRunId), "", "[]", null, STATE_APPLIED,
                    "[]", null, fencingToken, now, now);
            recordRepository.save(record);
            return new IntegrationOutcome(integrationId, STATE_APPLIED, null, List.of(),
                    List.of(), "无 SUCCEEDED 子任务产物，跳过集成");
        }

        // 4. 取父工作区当前摘要作为 baseDigest；lease 不存在则失败
        WorkspaceLease parentLease = workspaceManager.find(rootRunId);
        if (parentLease == null) {
            return new IntegrationOutcome(null, "FAILED", null, List.of(), List.of(),
                    "父工作区租约不存在 root=" + rootRunId + "（MA-07 不自动重建）");
        }
        String baseDigest = workspaceManager.currentDigest(parentLease);

        // 5. 创建 IN_PROGRESS record（fencingToken 落库，供 MA-08 扩展）
        String integrationId = IdGen.next("intg_");
        Instant now = Instant.now();
        String fencingToken = rootRunId + ":" + now.toEpochMilli();
        String inputRefsJson;
        try {
            inputRefsJson = objectMapper.writeValueAsString(
                    writers.stream().map(WorkspaceMerge.Writer::runId).toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("写者列表序列化失败", e);
        }
        IntegrationRecordEntity record = new IntegrationRecordEntity(integrationId, rootRunId,
                parentLease.projectId(), baseDigest, inputRefsJson, null, STATE_IN_PROGRESS,
                "[]", null, fencingToken, now, now);
        recordRepository.save(record);
        log.info("集成开始 integration={} root={} writers={} base={}",
                integrationId, rootRunId, writers.size(), baseDigest);

        // 6. 迟到写拒绝（§8.3）：应用前再校验父工作区摘要==baseDigest，
        //    若中途被改（外部写入）→ RECOVERED_BLOCKED，不应用。
        String currentDigestBeforeApply = workspaceManager.currentDigest(parentLease);
        if (!baseDigest.equals(currentDigestBeforeApply)) {
            record.setState(STATE_RECOVERED_BLOCKED);
            record.setUpdatedAt(Instant.now());
            recordRepository.save(record);
            return new IntegrationOutcome(integrationId, STATE_RECOVERED_BLOCKED, null,
                    List.of(), List.of(),
                    "集成期间父工作区被外部篡改 base=" + baseDigest + " cur=" + currentDigestBeforeApply);
        }

        // 7. 多写者三方合并（冲突整批不应用 AT-13）
        WorkspaceMerge.MultiResult mergeResult = workspaceMerge.mergeAll(writers, rootRunId,
                workspaceManager);

        if (mergeResult.applied()) {
            String resultDigest = workspaceManager.currentDigest(parentLease);
            record.setResultDigest(resultDigest);
            record.setState(STATE_APPLIED);
            try {
                record.setAppliedPathsJson(objectMapper.writeValueAsString(
                        mergeResult.appliedPaths()));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("appliedPaths 序列化失败", e);
            }
            record.setUpdatedAt(Instant.now());
            recordRepository.save(record);
            log.info("集成完成 APPLIED integration={} root={} applied={} result={}",
                    integrationId, rootRunId, mergeResult.appliedPaths().size(), resultDigest);
            return new IntegrationOutcome(integrationId, STATE_APPLIED, resultDigest,
                    mergeResult.appliedPaths(), List.of(), "集成成功");
        } else {
            record.setState(STATE_CONFLICTS);
            // 冲突整批不应用：appliedPaths 保持空，resultDigest 不更新
            record.setUpdatedAt(Instant.now());
            recordRepository.save(record);
            List<String> conflictDescs = mergeResult.conflicts().stream()
                    .map(c -> c.path() + " (写者:" + c.writerRunIds() + " 变化:" + c.changes() + ")")
                    .toList();
            log.warn("集成冲突 CONFLICTS integration={} root={} conflicts={}",
                    integrationId, rootRunId, conflictDescs);
            return new IntegrationOutcome(integrationId, STATE_CONFLICTS, null, List.of(),
                    conflictDescs, "集成冲突，整批不应用");
        }
    }

    /**
     * 中断恢复判定（§8.3/§10.3 "合并中断"窗口）：
     * - appliedPaths 空 且 父工作区摘要==baseDigest（未实际写入）→ 安全重做，旧 record 标记
     *   RECOVERED_BLOCKED 由新 record 接续（本切片简化为直接返回 RECOVERED_BLOCKED 让外层重试）；
     * - appliedPaths 非空 或 摘要!=baseDigest（已部分写入）→ RECOVERED_BLOCKED 阻塞报告。
     *
     * <p>返回 null 表示无 IN_PROGRESS record 或已处理由外层继续；非 null 直接返回调用方。</p>
     */
    private IntegrationOutcome handleRecovery(String rootRunId, IntegrationRecordEntity inProgress) {
        List<String> appliedPaths = parseList(inProgress.getAppliedPathsJson());
        WorkspaceLease parentLease = workspaceManager.find(rootRunId);
        boolean workspaceClean = parentLease != null
                && workspaceManager.currentDigest(parentLease).equals(inProgress.getBaseDigest());
        if (appliedPaths.isEmpty() && workspaceClean) {
            // 未实际写入且工作区未被改 → 安全重做：标记旧 record 完成由新 record 接续
            inProgress.setState(STATE_RECOVERED_BLOCKED);
            inProgress.setUpdatedAt(Instant.now());
            recordRepository.save(inProgress);
            log.info("集成中断恢复：旧 IN_PROGRESS 已安全归档 {}，外层将重新 integrate root={}",
                    inProgress.getIntegrationId(), rootRunId);
            return null; // 让外层走新建 record 重做
        }
        // 已部分写入或工作区被改 → 阻塞，不静默留半份补丁
        inProgress.setState(STATE_RECOVERED_BLOCKED);
        inProgress.setUpdatedAt(Instant.now());
        recordRepository.save(inProgress);
        log.error("集成中断不可恢复 integration={} root={} applied={} baseDigest={} → RECOVERED_BLOCKED",
                inProgress.getIntegrationId(), rootRunId, appliedPaths.size(),
                inProgress.getBaseDigest());
        return new IntegrationOutcome(inProgress.getIntegrationId(), STATE_RECOVERED_BLOCKED,
                null, appliedPaths, List.of(),
                "集成中断且已部分写入或工作区被改，不可安全恢复，已阻塞");
    }

    /**
     * 收集 rootRunId 下所有 SUCCEEDED 子任务的 (childRunId, workspaceRef) 写者，
     * 并校验 fencingToken（MA-08 AT-15 窗口3，§8.3 迟到写拒绝）。
     *
     * <p>仅取最新 plan 的 SUCCEEDED 结果，且 childRunId/workspaceRef 均非空。
     * fencingToken 低于同 plan/task 的 maxTaskFencingToken 的 attempt 说明已被后续 claim 超越
     * （重试/取消重投），其产物不可信，加入 staleAttemptIds 拒绝写入。</p>
     */
    private SucceededWriters collectSucceededWriters(String rootRunId) {
        List<PlanEntity> plans = planRepository.findByRootRunIdOrderByCreatedAtDesc(rootRunId);
        if (plans.isEmpty()) {
            return SucceededWriters.empty();
        }
        // Root-scoped token allocation is monotonic; only a later claim of the same task replaces its writer.
        // 最新 plan 优先；跨计划重名取首个（与 TaskQueryService.indexRootTasks 一致）
        PlanEntity plan = plans.get(0);
        List<TaskEntity> tasks = taskRepository.findByPlanIdOrderByTaskIdAsc(plan.getId());
        List<WorkspaceMerge.Writer> writers = new ArrayList<>();
        List<String> staleAttemptIds = new ArrayList<>();
        for (TaskEntity task : tasks) {
            Optional<TaskResultEntity> resultOpt = resultRepository
                    .findTopByPlanIdAndTaskIdOrderByCreatedAtDesc(plan.getId(), task.getTaskId());
            if (resultOpt.isEmpty()) {
                continue;
            }
            TaskResultEntity result = resultOpt.get();
            if (!"SUCCEEDED".equals(result.getState())) {
                continue;
            }
            TaskAttemptEntity attempt = attemptRepository.findById(result.getAttemptId())
                    .orElse(null);
            if (attempt == null) {
                continue;
            }
            Long token = attempt.getFencingToken();
            long maxFencing = attemptRepository.maxTaskFencingToken(plan.getId(), task.getTaskId());
            if (token == null || token < maxFencing) {
                staleAttemptIds.add(attempt.getAttemptId());
                continue;
            }
            String childRunId = result.getChildRunId();
            if (childRunId == null || childRunId.isBlank()) {
                continue;
            }
            RunEntity childRun = runRepository.findById(childRunId).orElse(null);
            if (childRun == null) {
                continue;
            }
            String workspaceRef = childRun.getWorkspaceRef();
            if (workspaceRef == null || workspaceRef.isBlank()) {
                continue;
            }
            writers.add(new WorkspaceMerge.Writer(childRunId, workspaceRef));
        }
        return new SucceededWriters(writers, staleAttemptIds);
    }

    private String resolveProjectId(String rootRunId) {
        RunEntity root = runRepository.findById(rootRunId).orElse(null);
        if (root == null) {
            return "";
        }
        return root.getWorkspaceRef() == null ? "" : root.getId();
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }
}
