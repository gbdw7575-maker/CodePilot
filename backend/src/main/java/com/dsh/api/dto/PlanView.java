package com.dsh.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * MA-10 计划/任务只读视图（GET /runs/{runId}/plans）。
 *
 * <p>把 MA-02 计划定义（目标/依赖/验收/范围/必需性）与 MA-05～MA-09 运行态
 * （权威状态、最新 attempt、终态结果、证据有效性）组装成前端可直接渲染的快照。
 * 字段全部来自持久化行实时组装，不含隐藏推理与凭据。</p>
 */
public record PlanView(String planId, String rootRunId, int revision, String status,
                       String goal, Instant createdAt, Instant updatedAt,
                       List<TaskRow> tasks) {

    /** 任务输入引用（受控产物）。 */
    public record InputRef(String taskId, String output) {
    }

    /** 任务资源范围。 */
    public record ResourceScope(List<String> read, List<String> write) {
    }

    /** 单任务行：冻结定义 + 当前权威运行态。 */
    public record TaskRow(String taskId, int taskVersion, String goal, List<String> dependsOn,
                          List<InputRef> inputRefs, String deliverable, List<String> acceptance,
                          ResourceScope resourceScope, String executionMode, boolean required,
                          String state, String attemptId, String childRunId, String reasonCode,
                          boolean evidenceValid, TaskResult result) {
    }

    /** 最新 attempt 的终态结果（证据展示；CANCELLING 与 CANCELLED 的区分在 state 字段）。 */
    public record TaskResult(String attemptId, String state, String errorCode,
                             String errorMessage, List<String> artifactRefs,
                             String patchArtifactId, String outputDigest, String inputDigest,
                             String verificationRef, int modelRequestsUsed, int toolCallsUsed,
                             Integer settledTokens, long durationMs) {
    }
}
