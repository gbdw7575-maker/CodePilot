package com.dsh.evals.model;

/** 评测样本：自然语言任务输入 + 任务类别 + 留出评分器规格。 */
public record EvalSample(
        String id,
        String input,
        EvalCategory category,
        EvalGraderSpec grader
) {
    public EvalSample {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("sample.id 必填");
        }
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("sample.input 必填");
        }
        if (grader == null) {
            throw new IllegalArgumentException("sample.grader 必填");
        }
    }

    /** 兼容旧数据集（MA-11 前无 category 字段）：未分类样本。 */
    public EvalSample(String id, String input, EvalGraderSpec grader) {
        this(id, input, null, grader);
    }
}
