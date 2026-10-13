package com.dsh.orchestration.task;

import java.util.List;

/**
 * 任务调度域对外只读视图（dispatch/await/read/cancel 四个工具结构化输出的统一来源）。
 * 字段只暴露服务端权威状态，模型不能指定 attemptId/childRunId 之外的执行参数。
 */
public final class TaskViews {

    private TaskViews() {
    }

    /**
     * 任务当前权威视图；result 非空表示最新 attempt 已终态。
     * MA-06 增 evidenceValid：标记 SUCCEEDED 结果的封存证据是否仍与当前上游一致（AT-11）。
     */
    public record TaskView(String planId, String taskId, int taskVersion, String state,
                           String attemptId, String childRunId, String reasonCode,
                           boolean evidenceValid, ResultView result) {
    }

    /**
     * attempt 终态结果（MA-06：子 Run COMPLETED 经 VERIFYING 验收闸门后才是 SUCCEEDED；
     * 验收通过封存 verificationRef + inputDigest + outputDigest 为不可变证据）。
     */
    public record ResultView(String attemptId, String state, String errorCode, String errorMessage,
                             List<String> artifactRefs, String patchArtifactId, String outputDigest,
                             String inputDigest, String verificationRef,
                             int modelRequestsUsed, int toolCallsUsed, Integer settledTokens,
                             long durationMs) {
    }
}
