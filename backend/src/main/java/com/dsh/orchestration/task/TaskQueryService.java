package com.dsh.orchestration.task;

import com.dsh.orchestration.task.TaskViews.ResultView;
import com.dsh.orchestration.task.TaskViews.TaskView;
import com.dsh.storage.PlanEntity;
import com.dsh.storage.PlanRepository;
import com.dsh.storage.TaskAttemptEntity;
import com.dsh.storage.TaskAttemptRepository;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.TaskRepository;
import com.dsh.storage.TaskResultEntity;
import com.dsh.storage.TaskResultRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务权威读模型（MA-05）：所有工具回答的任务状态都经这里从持久化行实时组装，
 * 不使用任何内存缓存（模型不能在过期状态上决策）。
 */
@Service
public class TaskQueryService {
    @org.springframework.beans.factory.annotation.Autowired private com.dsh.orchestration.acceptance.TaskCheckReceiptReader strongReceipts;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final PlanRepository planRepository;
    private final TaskRepository taskRepository;
    private final TaskAttemptRepository attemptRepository;
    private final TaskResultRepository resultRepository;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.dsh.orchestration.acceptance.RootTaskAcceptanceModes acceptanceModes;

    public TaskQueryService(PlanRepository planRepository, TaskRepository taskRepository,
                            TaskAttemptRepository attemptRepository,
                            TaskResultRepository resultRepository, ObjectMapper objectMapper) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.attemptRepository = attemptRepository;
        this.resultRepository = resultRepository;
        this.objectMapper = objectMapper;
    }

    /** 根 Run 全部任务索引（taskId→任务）；跨计划重名时最新计划优先，保证视图确定。 */
    @Transactional(readOnly = true)
    public Map<String, TaskEntity> indexRootTasks(String rootRunId) {
        List<PlanEntity> plans = planRepository.findByRootRunIdOrderByCreatedAtDesc(rootRunId);
        Map<String, TaskEntity> index = new LinkedHashMap<>();
        if (plans.isEmpty()) {
            return index;
        }
        List<String> planIds = plans.stream().map(PlanEntity::getId).toList();
        for (TaskEntity task : taskRepository.findByPlanIdInOrderByPlanIdAscTaskIdAsc(planIds)) {
            index.putIfAbsent(task.getTaskId(), task);
        }
        return index;
    }

    /** 根 Run 的全部 planId（最新在前）。 */
    @Transactional(readOnly = true)
    public List<String> rootPlanIds(String rootRunId) {
        return planRepository.findByRootRunIdOrderByCreatedAtDesc(rootRunId).stream()
                .map(PlanEntity::getId).toList();
    }

    /** 组装任务权威视图（最新 attempt + 其终态结果，若有）。 */
    @Transactional(readOnly = true)
    public TaskView view(TaskEntity task) {
        List<TaskAttemptEntity> attempts =
                attemptRepository.findByPlanIdAndTaskIdOrderByAttemptNoDesc(
                        task.getPlanId(), task.getTaskId());
        TaskAttemptEntity latest = attempts.isEmpty() ? null : attempts.get(0);
        TaskResultEntity result = latest == null ? null
                : resultRepository.findById(latest.getAttemptId()).orElse(null);
        boolean evidenceValid = isEvidenceValid(task, result);
        return new TaskView(task.getPlanId(), task.getTaskId(), task.getTaskVersion(),
                task.getState(),
                latest == null ? null : latest.getAttemptId(),
                latest == null ? null : latest.getChildRunId(),
                null,
                evidenceValid,
                result == null ? null : toResultView(result));
    }

    @Transactional(readOnly = true)
    public List<TaskView> views(List<TaskEntity> tasks) {
        List<TaskView> out = new ArrayList<>(tasks.size());
        for (TaskEntity task : tasks) {
            out.add(view(task));
        }
        return out;
    }

    /** 解析依赖 taskId 列表（dependsOn JSON 文本）。 */
    public List<String> parseDependencies(TaskEntity task) {
        return parseStringList(task.getDependsOn());
    }

    /** 解析 inputRefs JSON：[{taskId, output}]，取 taskId 去重保序。 */
    public List<String> parseInputRefTaskIds(TaskEntity task) {
        if (task.getInputRefs() == null || task.getInputRefs().isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> refs = objectMapper.readValue(task.getInputRefs(),
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            List<String> ids = new ArrayList<>();
            for (Map<String, Object> ref : refs) {
                Object id = ref.get("taskId");
                if (id != null && !ids.contains(id.toString())) {
                    ids.add(id.toString());
                }
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> value = objectMapper.readValue(json, STRING_LIST);
            return value == null ? List.of() : value;
        } catch (Exception e) {
            return List.of();
        }
    }

    private ResultView toResultView(TaskResultEntity r) {
        List<String> artifactRefs = parseStringList(r.getArtifactRefsJson());
        return new ResultView(r.getAttemptId(), r.getState(), r.getErrorCode(),
                r.getErrorMessage(), artifactRefs, r.getPatchArtifactId(), r.getOutputDigest(),
                r.getInputDigest(), r.getVerificationRef(),
                r.getModelRequestsUsed(), r.getToolCallsUsed(), r.getSettledTokens(),
                r.getDurationMs());
    }

    /** 按 attemptId 取终态结果（恢复/对账用）。 */
    @Transactional(readOnly = true)
    public Optional<TaskResultEntity> findResult(String attemptId) {
        return resultRepository.findById(attemptId);
    }

    /**
     * 检查任务结果是否仍为有效证据（MA-06 AT-11）。
     * 上游产物变化或任务版本变化后，旧结果可追溯但不再有效。
     */
    @Transactional(readOnly = true)
    public boolean isEvidenceValid(TaskEntity task, TaskResultEntity result) {
        if (result == null || !TaskStates.SUCCEEDED.equals(result.getState())) {
            return false;
        }
        if (acceptanceModes != null && !acceptanceModes.legacyAllowed(task.getPlanId())) return strongReceipts!=null&&strongReceipts.isValid(task,result);
        if (result.getTaskVersion() != task.getTaskVersion()) {
            return false;
        }
        if (result.getInputDigest() == null) {
            return true;
        }
        PlanEntity plan = planRepository.findById(task.getPlanId()).orElse(null);
        if (plan == null) {
            return false;
        }
        String currentInputDigest = computeInputDigest(plan, task);
        return result.getInputDigest().equals(currentInputDigest);
    }

    /**
     * 计算上游依赖产物的冻结摘要（MA-06 AT-11）。
     * 取任务全部依赖的最新结果 outputDigest 拼接后 sha256；无依赖返回 null。
     */
    @Transactional(readOnly = true)
    String computeInputDigest(PlanEntity plan, TaskEntity task) {
        List<String> deps = parseDependencies(task);
        if (deps.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String depId : deps) {
            resultRepository
                    .findTopByPlanIdAndTaskIdOrderByCreatedAtDesc(plan.getId(), depId)
                    .ifPresent(r -> {
                        if (r.getOutputDigest() != null) {
                            sb.append(depId).append(':').append(r.getOutputDigest()).append('\n');
                        }
                    });
        }
        String material = sb.toString();
        return material.isEmpty() ? null
                : com.dsh.common.Digest.sha256Hex(material);
    }

    /**
     * MA-07 AT-12：收集任务全部 inputRefs 上游 SUCCEEDED 结果的可消费产物 ID。
     *
     * <p>仅取 SUCCEEDED 上游（FAILED/CANCELLED 不物化，由 promote 闸门保证不达；这里再校验
     * 防御竞态：上游在 promote 后、本任务 buildRequest 前状态变化）。非 SUCCEEDED 或无产物
     * 的上游贡献空列表；多个上游产物按 inputRefs 顺序汇总保序去重。</p>
     */
    @Transactional(readOnly = true)
    public List<String> collectUpstreamArtifactRefs(String planId, TaskEntity task) {
        List<String> depTaskIds = parseInputRefTaskIds(task);
        if (depTaskIds.isEmpty()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (String depId : depTaskIds) {
            if(acceptanceModes!=null&&!acceptanceModes.legacyAllowed(planId)){
                var dep=taskRepository.findByPlanIdAndTaskId(planId,depId).orElseThrow();
                var result=resultRepository.findTopByPlanIdAndTaskIdOrderByCreatedAtDesc(planId,depId).orElseThrow();
                if(!isEvidenceValid(dep,result))throw new com.dsh.api.ApiException(com.dsh.api.dto.ErrorCode.INVALID_STATE,"EVIDENCE_MISSING: 上游强验收凭据缺失或过期");
            }
            resultRepository
                    .findTopByPlanIdAndTaskIdOrderByCreatedAtDesc(planId, depId)
                    .ifPresent(r -> {
                        if (TaskStates.SUCCEEDED.equals(r.getState())) {
                            refs.addAll(parseStringList(r.getArtifactRefsJson()));
                        }
                    });
        }
        return refs;
    }
}
