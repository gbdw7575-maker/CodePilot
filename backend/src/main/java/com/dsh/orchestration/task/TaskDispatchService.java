package com.dsh.orchestration.task;

import com.dsh.application.DshProperties;
import com.dsh.common.Digest;
import com.dsh.common.IdGen;
import com.dsh.orchestration.task.TaskViews.TaskView;
import com.dsh.storage.PlanEntity;
import com.dsh.storage.PlanRepository;
import com.dsh.storage.TaskDispatchEntity;
import com.dsh.storage.TaskDispatchRepository;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.TaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.LockModeType;

/**
 * dispatch_tasks 登记服务（MA-05，规范 §5.1 幂等与四种事实、§6.3 事务后启动）。
 *
 * <p>数据库原子登记：{@code (rootRunId,requestKey)} 唯一；同 key 同参数返回既有登记
 * （幂等重放），同 key 不同参数 REQUEST_KEY_CONFLICT。计划归属/版本/任务存在/依赖已派发/
 * 执行模式/任务当前状态整批校验，任一不满足整批拒绝，没有局部启动。登记成功只把
 * DRAFT→PENDING 并写流水，立即返回；就绪推进与 worker 启动在事务提交后发生。</p>
 */
@Service
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class TaskDispatchService {
    @org.springframework.beans.factory.annotation.Value("${dsh.orchestration.strong-runtime-enabled:false}") private boolean strongRuntime;

    private static final Logger log = LoggerFactory.getLogger(TaskDispatchService.class);

    private final PlanRepository planRepository;
    private final TaskRepository taskRepository;
    private final TaskDispatchRepository dispatchRepository;
    private final TaskQueryService queryService;
    private final TaskEventJournal journal;
    private final TaskSchedulingService schedulingService;
    private final ObjectMapper objectMapper;
    private final DshProperties properties;
    @PersistenceContext private EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Autowired
    private com.dsh.orchestration.acceptance.RootTaskAcceptanceModes acceptanceModes;
    @org.springframework.beans.factory.annotation.Autowired
    private com.dsh.orchestration.acceptance.TaskCheckDispatchGate acceptanceGate;

    public TaskDispatchService(PlanRepository planRepository, TaskRepository taskRepository,
                               TaskDispatchRepository dispatchRepository,
                               TaskQueryService queryService, TaskEventJournal journal,
                               @Lazy TaskSchedulingService schedulingService,
                               ObjectMapper objectMapper, DshProperties properties) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.dispatchRepository = dispatchRepository;
        this.queryService = queryService;
        this.journal = journal;
        this.schedulingService = schedulingService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 登记结果；accepted=false 时 errorCode 为 MA-01 冻结 reasonCodes 之一。 */
    public record DispatchResult(boolean accepted, String errorCode, String errorMessage,
                                 String dispatchId, boolean replay, List<TaskView> tasks) {
        static DispatchResult rejected(String code, String message) {
            return new DispatchResult(false, code, message, null, false, List.of());
        }
    }

    @Transactional
    public DispatchResult register(String rootRunId, String planId, int expectedRevision,
                                   List<String> rawTaskIds, String requestKey) {
        List<String> taskIds = rawTaskIds == null ? List.of()
                : rawTaskIds.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        int maxBatch = properties.orchestration().plan().dispatchMaxBatch();
        if (taskIds.isEmpty() || taskIds.size() > maxBatch) {
            return DispatchResult.rejected("VALIDATION_FAILED",
                    "taskIds 数量必须在 1～" + maxBatch + " 之间");
        }
        if (new HashSet<>(taskIds).size() != taskIds.size()) {
            return DispatchResult.rejected("VALIDATION_FAILED", "taskIds 含重复任务标识，整批拒绝");
        }

        // Same source lock order as human confirmation; never take plan before root/thread.
        var source = acceptanceModes.lockSource(rootRunId);
        PlanEntity plan = planRepository.findForUpdate(planId, rootRunId).orElse(null);
        if (plan == null) {
            return DispatchResult.rejected("PLAN_NOT_FOUND",
                    "计划不存在或不属于当前根 Run: " + planId);
        }
        entityManager.refresh(plan, LockModeType.PESSIMISTIC_WRITE);
        if (plan.getRevision() != expectedRevision) {
            return DispatchResult.rejected("REVISION_MISMATCH",
                    "计划修订已变化：expected=" + expectedRevision + "，current=" + plan.getRevision()
                            + "，请重读计划后用新版本派发");
        }

        String paramsHash = paramsHash(planId, expectedRevision, taskIds);
        Optional<TaskDispatchEntity> existing =
                dispatchRepository.findByRootRunIdAndRequestKey(rootRunId, requestKey);
        if (existing.isPresent()) {
            TaskDispatchEntity prior = existing.get();
            if (!prior.getParamsHash().equals(paramsHash)) {
                return DispatchResult.rejected("REQUEST_KEY_CONFLICT",
                        "requestKey 已用于不同的派发参数（planId/expectedRevision/taskIds 不一致），"
                                + "请为新派发使用新的 requestKey");
            }
            if (acceptanceModes.isStrong(rootRunId)&&!strongRuntime) {
                return DispatchResult.rejected("EVIDENCE_MISSING", "EXECUTION_NOT_AVAILABLE: 强模式不能重放旧派发为已接受任务");
            }
            // 幂等重放：返回同一 dispatchId 与当前权威状态，绝不重复启动子 Run。
            Map<String, TaskEntity> index = queryService.indexRootTasks(rootRunId);
            List<TaskView> views = taskIds.stream()
                    .map(id -> queryService.view(index.get(id))).toList();
            return new DispatchResult(true, null, null, prior.getId(), true, views);
        }

        boolean strongMode = acceptanceModes.isStrong(rootRunId);
        List<TaskEntity> tasks = new ArrayList<>();
        Map<String, TaskEntity> planIndex = new java.util.LinkedHashMap<>();
        // Strong dispatch needs a stable full definition. Legacy workers can already be running:
        // locking their rows here would invert worker event-cursor -> task-flush lock order.
        // Keep the legacy read path; mode selection is still serialized by the source lock.
        for (TaskEntity t : strongMode ? taskRepository.findForPlanUpdate(planId)
                : taskRepository.findByPlanIdOrderByTaskIdAsc(planId)) {
            if (strongMode) entityManager.refresh(t, LockModeType.PESSIMISTIC_WRITE);
            planIndex.put(t.getTaskId(), t);
        }
        for (String taskId : taskIds) {
            TaskEntity task = planIndex.get(taskId);
            if (task == null) {
                return DispatchResult.rejected("TASK_NOT_FOUND",
                        "计划 " + planId + " 中不存在任务: " + taskId + "（整批拒绝，未启动任何任务）");
            }
            tasks.add(task);
        }
        for (TaskEntity task : tasks) {
            if (!"DELEGATED".equals(task.getExecutionMode())) {
                return DispatchResult.rejected("INVALID_STATE",
                        "任务 " + task.getTaskId() + " 是 INLINE 任务，不由任务调度器派发"
                                + "（主 Agent 自办语义不在本切片范围），整批拒绝");
            }
            if (!TaskStates.DRAFT.equals(task.getState())) {
                return DispatchResult.rejected("INVALID_STATE",
                        "任务 " + task.getTaskId() + " 当前状态 " + task.getState()
                                + "，只有 DRAFT 任务可登记派发，整批拒绝");
            }
            for (String dep : queryService.parseDependencies(task)) {
                TaskEntity dependency = planIndex.get(dep);
                if (dependency == null) {
                    return DispatchResult.rejected("DEPENDENCY_NOT_DISPATCHED",
                            "任务 " + task.getTaskId() + " 依赖的 " + dep + " 不在计划内，整批拒绝");
                }
                if (TaskStates.DRAFT.equals(dependency.getState())) {
                    return DispatchResult.rejected("DEPENDENCY_NOT_DISPATCHED",
                            "任务 " + task.getTaskId() + " 的依赖 " + dep
                                    + " 尚未派发（必须先派发并处于非 DRAFT 状态），整批拒绝");
                }
                if (TaskStates.FAILED.equals(dependency.getState())
                        || TaskStates.CANCELLED.equals(dependency.getState())) {
                    return DispatchResult.rejected("INVALID_STATE",
                            "任务 " + task.getTaskId() + " 的依赖 " + dep + " 已"
                                    + dependency.getState() + "，不能派发，整批拒绝");
                }
            }
        }

        if (strongMode) {
            if (!Set.of("RUNNING", "WAITING_INPUT", "WAITING_APPROVAL").contains(source.root().getState())
                    || Set.of("COMPLETED", "FAILED", "CANCELLED").contains(source.turn().getState())) {
                return DispatchResult.rejected("INVALID_STATE", "强派发仅接受活动根任务，整批拒绝");
            }
            acceptanceModes.refreshRequirements(source.thread().getId());
            var refusal = acceptanceGate.inspect(rootRunId, planId, tasks);
            if(refusal!=null)return DispatchResult.rejected("EVIDENCE_MISSING", refusal.diagnostic() + ": " + refusal.detail());
        }
        if(!strongMode)acceptanceModes.freezeLegacy(rootRunId);
        Instant now = Instant.now();
        String dispatchId = IdGen.next("disp_");
        String taskIdsJson;
        try {
            taskIdsJson = objectMapper.writeValueAsString(taskIds);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("taskIds 序列化失败", e);
        }
        dispatchRepository.save(new TaskDispatchEntity(dispatchId, rootRunId, requestKey, planId,
                paramsHash, taskIdsJson, now));

        for (TaskEntity task : tasks) {
            String from = task.getState();
            task.setState(TaskStates.PENDING);
            task.setUpdatedAt(now);
            taskRepository.save(task);
            journal.append(rootRunId, planId, task.getTaskId(), task.getTaskVersion(),
                    null, null, from, TaskStates.PENDING, "DISPATCH_REQUESTED");
        }

        registerAfterCommitPromotion(rootRunId);
        log.info("任务派发登记 root={} plan={} dispatch={} tasks={}",
                rootRunId, planId, dispatchId, taskIds);
        return new DispatchResult(true, null, null, dispatchId, false,
                queryService.views(tasks));
    }

    /** 事务提交后才允许推进就绪队列/启动 worker（规范 §6.3：不能只靠 afterCommit，
     *  但 afterCommit 是实时路径；持久化 QUEUED 行 + 补偿扫描是崩溃恢复路径）。 */
    private void registerAfterCommitPromotion(String rootRunId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedulingService.promoteAndPump(rootRunId);
                }
            });
        } else {
            schedulingService.promoteAndPump(rootRunId);
        }
    }

    private String paramsHash(String planId, int expectedRevision, List<String> taskIds) {
        String canonical = planId + '|' + expectedRevision + '|' + String.join(",", taskIds);
        return Digest.sha256Hex(canonical);
    }
}
