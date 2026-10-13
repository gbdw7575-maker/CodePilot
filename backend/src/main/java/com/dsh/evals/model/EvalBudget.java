package com.dsh.evals.model;

/**
 * 评测预算（覆盖默认根 Run 预算，用于 EVAL-01 改预算对照实验）。
 * 字段语义对齐 com.dsh.application.DshProperties.Budget。
 */
public record EvalBudget(
        int maxModelRequests,
        int maxToolCalls,
        int maxCumulativeTokens,
        int deadlineMinutes
) {
    public EvalBudget {
        if (maxModelRequests < 1) maxModelRequests = 1;
        if (maxToolCalls < 0) maxToolCalls = 0;
        if (maxCumulativeTokens < 1) maxCumulativeTokens = 1;
        if (deadlineMinutes < 1) deadlineMinutes = 1;
    }
}
