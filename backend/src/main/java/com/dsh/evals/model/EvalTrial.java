package com.dsh.evals.model;

/**
 * 单次试验记录（doc/内部接口契约.md §8：证据与原始 trial 引用）。
 * 一个样本的一次运行 = 一个 trial。attempt 从 1 起。
 *
 * <p>MA-11 追加对照可观测字段（仅只读统计，不改调度语义）：
 * strategy 为本次运行策略；category 为样本类别；
 * childRunCount=根 Run 直属子 Run 数；conflictCount=汇合 CONFLICTS 记录数；
 * reworkAttempts=任务 attemptNo 超过首 attempt 的累计返工次数。</p>
 */
public record EvalTrial(
        String trialId,
        String sampleId,
        String split,
        int attempt,
        String runId,
        String terminalState,
        String stopReason,
        String patchArtifactId,
        String graderStatus,
        Integer graderExitCode,
        int testsDiscovered,
        int testsPassed,
        int testsSkipped,
        String graderLogArtifactId,
        EvalFailureType failureType,
        long latencyMs,
        int modelRequests,
        int toolCalls,
        Integer settledTokens,
        String cost,
        String currency,
        Integer inputTokens,
        Integer outputTokens,
        Integer cacheHitTokens,
        Integer cacheMissTokens,
        String priceVersion,
        EvalStrategy strategy,
        EvalCategory category,
        int childRunCount,
        int conflictCount,
        int reworkAttempts
) {
    /** MA-10 前全参口径（无 token 细分、无 MA-11 对照字段），供既有契约测试构造。 */
    public EvalTrial(String trialId, String sampleId, String split, int attempt, String runId,
                     String terminalState, String stopReason, String patchArtifactId,
                     String graderStatus, Integer graderExitCode, int testsDiscovered,
                     int testsPassed, int testsSkipped, String graderLogArtifactId,
                     EvalFailureType failureType, long latencyMs, int modelRequests,
                     int toolCalls, Integer settledTokens, String cost, String currency) {
        this(trialId, sampleId, split, attempt, runId, terminalState, stopReason,
                patchArtifactId, graderStatus, graderExitCode, testsDiscovered,
                testsPassed, testsSkipped, graderLogArtifactId, failureType, latencyMs,
                modelRequests, toolCalls, settledTokens, cost, currency,
                null, null, null, null, null,
                EvalStrategy.SINGLE, null, 0, 0, 0);
    }

    /** 评分器是否真正执行（非 SKIPPED）。 */
    public boolean graderRan() {
        return graderStatus != null && !"SKIPPED".equals(graderStatus);
    }
}
