package com.dsh.evals.model;

import java.util.List;

/**
 * 单/多 Agent 对照报告（MA-11，AT-18）：
 * 仅在同任务集/同模型配置/同预算/同镜像/同 seed/同 repeat、仅 strategy 不同的两次运行间生成。
 *
 * <p>overall 为总体口径；categories 按 simple/independent/dependent/conflict 分别独立记账。
 * 成功率为样本首轮口径（attempt 1 PASS 样本占比），与 EvalReport.passAt1 一致；
 * 成本未知保留 null；warnings 记录未知成本等不阻断但必须如实披露的事项。
 * 小样本结果只描述本样本，不据此给普遍结论。</p>
 */
public record EvalComparison(
        String datasetName,
        String datasetVersion,
        String split,
        int repeatCount,
        String factorKey,
        ComparisonSide overallSingle,
        ComparisonSide overallMulti,
        List<CategoryComparison> categories,
        List<String> warnings
) {
}
