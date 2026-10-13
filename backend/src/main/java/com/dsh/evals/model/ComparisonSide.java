package com.dsh.evals.model;

/**
 * 对照报告中单方（一个策略侧）的统计（MA-11，AT-18/§12）。
 * 成功率与总成本独立记录；任一 trial 成本未知则 totalCost=null，不补零。
 */
public record ComparisonSide(
        String configDigest,
        String strategy,
        int trials,
        int samples,
        double passAt1,
        long latencyMeanMs,
        long latencyP95Ms,
        int totalModelRequests,
        Long totalTokens,
        String totalCost,
        String currency,
        int childRunCount,
        int conflictCount,
        int reworkAttempts
) {
}
