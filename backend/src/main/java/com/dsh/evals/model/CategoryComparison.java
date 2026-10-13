package com.dsh.evals.model;

/** 单个任务类别下的单/多 Agent 对照统计（MA-11，AT-18 四类任务分别记账）。 */
public record CategoryComparison(
        String category,
        ComparisonSide single,
        ComparisonSide multi
) {
}
