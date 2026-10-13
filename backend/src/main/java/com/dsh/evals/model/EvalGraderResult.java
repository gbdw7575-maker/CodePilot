package com.dsh.evals.model;

/**
 * 评分器执行结果（EvalGrader 内部产出，供 EvalRunner 记入 trial）。
 * status：PASSED / FAILED / NO_TESTS / ERROR / UNSCORED（仅运行观察）/ SKIPPED（不执行）。
 * 成本未知为 null，不补零。
 */
public record EvalGraderResult(
        String status,
        Integer exitCode,
        int testsDiscovered,
        int testsPassed,
        int testsSkipped,
        String logArtifactId
) {
}
