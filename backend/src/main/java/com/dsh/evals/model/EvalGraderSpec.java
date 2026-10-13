package com.dsh.evals.model;

/**
 * 独立评分器规格（doc/内部接口契约.md §8）：
 * 仅由 EvalRunner 从数据集文件加载，不进入 Agent 工作区——
 * 被测 Agent 不可改评分器或读取留出答案。
 *
 * <p>命令在「干净起点 + Agent 最终补丁」的工作区副本内执行（workdir 缺省 "."）。
 * expectTests=true 时发现数为 0 视为失败（对齐 VER-02：零测试不算通过）。</p>
 */
public record EvalGraderSpec(
        String command,
        String workdir,
        int timeoutSeconds,
        boolean expectTests,
        int minTests,
        String supportFile,
        EvalGradingMode gradingMode
) {
    public EvalGraderSpec(String command, String workdir, int timeoutSeconds,
                          boolean expectTests, int minTests) {
        this(command, workdir, timeoutSeconds, expectTests, minTests, null, null);
    }

    public EvalGraderSpec(String command, String workdir, int timeoutSeconds,
                         boolean expectTests, int minTests, String supportFile) {
        this(command, workdir, timeoutSeconds, expectTests, minTests, supportFile, null);
    }

    public EvalGraderSpec {
        if (gradingMode == null) gradingMode = expectTests ? EvalGradingMode.TESTS : EvalGradingMode.SMOKE;
        if (gradingMode == EvalGradingMode.TESTS && !expectTests
                || gradingMode == EvalGradingMode.ASSERTIONS && expectTests) {
            throw new IllegalArgumentException("grader.gradingMode 与 expectTests 不一致");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("grader.command 必填");
        }
        if (workdir == null || workdir.isBlank()) {
            workdir = ".";
        }
        if (timeoutSeconds < 1) {
            timeoutSeconds = 120;
        }
        if (minTests < 0) {
            minTests = 0;
        }
    }
}
