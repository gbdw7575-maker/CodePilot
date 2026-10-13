package com.dsh.evals.model;

/** 评测报告：每样本汇总。passedAttempt = 首次通过轮次（1 起），null 表示从未通过。 */
public record EvalSampleResult(
        String sampleId,
        int attempts,
        Integer passedAttempt,
        EvalFailureType failureType
) {
}
