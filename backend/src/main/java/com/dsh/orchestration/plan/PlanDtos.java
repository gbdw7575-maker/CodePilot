package com.dsh.orchestration.plan;

import java.util.List;

/**
 * MA-02 计划草案服务的入参/出参 DTO（Agent 编排升级规范 §4/§5）。
 * 字段与 MA-01 冻结 fixtures 的 UpdatePlanRequest/TaskDef 一一对应；
 * 服务只接受服务端上下文（rootRunId/projectId），计划 ID 与修订由服务端管理。
 */
public final class PlanDtos {

    private PlanDtos() {
    }

    /** update_plan v2 入参：创建时 planId/expectedRevision 均为 null；更新时二者必填。 */
    public record PlanDraft(String planId, Integer expectedRevision, String goal,
                            List<TaskDraft> tasks) {
    }

    public record InputRef(String taskId, String output) {
    }

    public record ResourceScope(List<String> read, List<String> write) {
    }

    public record TaskDraft(String taskId, String goal, List<String> dependsOn,
                            List<InputRef> inputRefs, String deliverable, List<String> acceptance,
                            ResourceScope resourceScope, String executionMode, boolean required) {
    }

    /** 落库后的权威视图：revision 为当前修订号，taskStates 为服务端权威状态。 */
    public record SavedPlan(String planId, int revision, String status, String goal,
                            List<SavedTask> tasks) {
    }

    public record SavedTask(String taskId, int taskVersion, String state, String goal,
                            List<String> dependsOn, String deliverable, List<String> acceptance,
                            String executionMode, boolean required) {
    }
}
