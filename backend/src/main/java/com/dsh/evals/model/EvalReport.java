package com.dsh.evals.model;

import java.util.List;
import java.util.Map;

/**
 * 评测报告（doc/内部接口契约.md §8 EvalReport）：
 * 通过率（pass@1 与 pass@K）、失败分型、延迟（均值/p95）、
 * 请求/token/成本累计、证据与原始 trial 引用。
 *
 * <p>passAt1 = 首轮（attempt 1）通过样本占比——不把重试后的最好结果当默认 pass@1；
 * passAtK = K 轮内至少一次通过的样本占比，单独标注，不冒充 pass@1。</p>
 * <p>C20：含 UNSCORED 时两项解决率为 null，保留运行和资源统计，不能用 0 替代未知。</p>
 */
public record EvalReport(
        String configDigest,
        String datasetName,
        String datasetVersion,
        String split,
        int totalTrials,
        int passedTrials,
        Double passAt1,
        Double passAtK,
        Map<String, Integer> failureBreakdown,
        long latencyMeanMs,
        long latencyP95Ms,
        int totalModelRequests,
        long totalTokens,
        String totalCost,
        String currency,
        List<EvalSampleResult> sampleResults,
        List<EvalTrial> trials,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        EvalExecutionManifest executionManifest
) {
    /** Old in-memory aggregate callers and old JSON retain the legacy report shape. */
    public EvalReport(String configDigest, String datasetName, String datasetVersion, String split,
                      int totalTrials, int passedTrials, double passAt1, double passAtK,
                      Map<String,Integer> failureBreakdown, long latencyMeanMs, long latencyP95Ms,
                      int totalModelRequests, long totalTokens, String totalCost, String currency,
                      List<EvalSampleResult> sampleResults, List<EvalTrial> trials) {
        this(configDigest,datasetName,datasetVersion,split,totalTrials,passedTrials,passAt1,passAtK,
                failureBreakdown,latencyMeanMs,latencyP95Ms,totalModelRequests,totalTokens,totalCost,
                currency,sampleResults,trials,null);
    }
    public EvalReport withExecutionManifest(EvalExecutionManifest manifest) {
        return new EvalReport(configDigest,datasetName,datasetVersion,split,totalTrials,passedTrials,passAt1,
                passAtK,failureBreakdown,latencyMeanMs,latencyP95Ms,totalModelRequests,totalTokens,totalCost,
                currency,sampleResults,trials,manifest);
    }
}
