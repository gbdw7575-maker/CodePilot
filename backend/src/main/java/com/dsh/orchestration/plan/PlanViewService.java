package com.dsh.orchestration.plan;

import com.dsh.api.dto.PlanView;
import com.dsh.orchestration.task.TaskQueryService;
import com.dsh.orchestration.task.TaskViews.ResultView;
import com.dsh.orchestration.task.TaskViews.TaskView;
import com.dsh.storage.PlanEntity;
import com.dsh.storage.PlanRepository;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.TaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * MA-10 计划只读视图组装（无条件装配）。
 *
 * <p>规范 §12 要求编排开关关闭时已有计划/任务/结果仍可读取，因此本服务与底层
 * Repository/JPA 实体一样始终存在；不依赖任何 {@code dsh.orchestration.enabled}
 * 条件 bean。回答实时来自持久化行，不用内存缓存。</p>
 */
@Service
public class PlanViewService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<PlanView.InputRef>> INPUT_REFS = new TypeReference<>() {
    };

    private final PlanRepository planRepository;
    private final TaskRepository taskRepository;
    private final TaskQueryService queryService;
    private final ObjectMapper objectMapper;

    public PlanViewService(PlanRepository planRepository, TaskRepository taskRepository,
                           TaskQueryService queryService, ObjectMapper objectMapper) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.queryService = queryService;
        this.objectMapper = objectMapper;
    }

    /** 根 Run 全部计划（最新修订在前），每个计划内嵌任务快照。 */
    @Transactional(readOnly = true)
    public List<PlanView> listPlans(String rootRunId) {
        List<PlanView> out = new ArrayList<>();
        for (PlanEntity plan : planRepository.findByRootRunIdOrderByCreatedAtDesc(rootRunId)) {
            List<TaskEntity> tasks =
                    taskRepository.findByPlanIdOrderByTaskIdAsc(plan.getId());
            List<PlanView.TaskRow> rows = new ArrayList<>(tasks.size());
            for (TaskEntity task : tasks) {
                rows.add(toRow(task, queryService.view(task)));
            }
            out.add(new PlanView(plan.getId(), plan.getRootRunId(), plan.getRevision(),
                    plan.getStatus(), plan.getGoal(), plan.getCreatedAt(), plan.getUpdatedAt(),
                    rows));
        }
        return out;
    }

    private PlanView.TaskRow toRow(TaskEntity t, TaskView v) {
        return new PlanView.TaskRow(
                t.getTaskId(), t.getTaskVersion(), t.getGoal(),
                parseStringList(t.getDependsOn()),
                parseInputRefs(t.getInputRefs()),
                t.getDeliverable(), parseStringList(t.getAcceptance()),
                parseResourceScope(t.getResourceScope()),
                t.getExecutionMode(), t.isRequired(),
                v.state(), v.attemptId(), v.childRunId(), v.reasonCode(),
                v.evidenceValid(),
                v.result() == null ? null : toResult(v.result()));
    }

    private PlanView.TaskResult toResult(ResultView r) {
        return new PlanView.TaskResult(r.attemptId(), r.state(), r.errorCode(),
                r.errorMessage(), r.artifactRefs(), r.patchArtifactId(),
                r.outputDigest(), r.inputDigest(), r.verificationRef(),
                r.modelRequestsUsed(), r.toolCallsUsed(), r.settledTokens(), r.durationMs());
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

    private List<PlanView.InputRef> parseInputRefs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<PlanView.InputRef> value = objectMapper.readValue(json, INPUT_REFS);
            return value == null ? List.of() : value;
        } catch (Exception e) {
            return List.of();
        }
    }

    private PlanView.ResourceScope parseResourceScope(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PlanView.ResourceScope.class);
        } catch (Exception e) {
            return null;
        }
    }
}
