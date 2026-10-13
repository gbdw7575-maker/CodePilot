package com.dsh.orchestration;

import java.time.Instant;
import java.util.List;

/**
 * 顺序编排产物报告（DSH-010，开发文档 §12 coding-review v1）。
 * 作为 application/json artifact 落父 Run：逐角色 runId/状态/用量、总用量、汇合结果/冲突，
 * 供对照报告核算多 Agent 分工的收益与成本（总预算不超父 Run 上限）。
 */
public record OrchestrationReport(String template, String templateVersion, String parentRunId,
                                  String task, Instant startedAt, Instant finishedAt,
                                  String overallStatus, String failureReason,
                                  List<Stage> stages, Merge merge, Totals totals,
                                  String reportArtifactId) {

    /** 单个角色阶段的执行视图。 */
    public record Stage(String role, String runId, boolean executed, String state,
                        String stopReason, String summary, String workspaceRef,
                        String patchArtifactId, int modelRequests, int toolCalls,
                        Integer tokens, long durationMs) {
    }

    /** 汇合结果（未尝试时 attempted=false）。 */
    public record Merge(boolean attempted, String status, String writerWorkspaceRef,
                        List<String> appliedPaths, List<WorkspaceMerge.Conflict> conflicts) {

        static Merge notAttempted() {
            return new Merge(false, "SKIPPED", "", List.of(), List.of());
        }
    }

    /** 全部子阶段用量合计（对照成本口径）。 */
    public record Totals(int modelRequests, int toolCalls, int tokens, long durationMs) {
    }
}
