package com.dsh.orchestration;

import java.util.List;

/**
 * 子 Agent 执行结果（开发文档 §12：状态、简短总结、产物引用、验证引用、用量和失败原因）。
 * state 复用 Run 状态机：COMPLETED/FAILED/CANCELLED/WAITING_APPROVAL/WAITING_INPUT；
 * 子工具收尾不等于整体目标通过 —— 是否合并/重新验证由父运行决定。
 */
public record SubagentResult(String runId, String role, String state, String stopReason,
                             String summary, List<String> artifactRefs, String patchArtifactId,
                             String workspaceRef, String workspaceDigest,
                             int modelRequestsUsed, int toolCallsUsed, Integer settledTokens,
                             long durationMs) {

    public boolean succeeded() {
        return "COMPLETED".equals(state);
    }

    /** 子任务终态（含审批/追问暂停：对编排而言是未完成，交回父运行处理）。 */
    public boolean unfinished() {
        return "WAITING_APPROVAL".equals(state) || "WAITING_INPUT".equals(state);
    }
}
