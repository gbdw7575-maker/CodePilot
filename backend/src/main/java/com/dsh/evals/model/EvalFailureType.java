package com.dsh.evals.model;

/**
 * 评测失败分型（doc/Agent项目开发文档.md §17：报告解决率、失败分类）。
 * 顺序即判定优先级：终态异常先于补丁缺失先于评分器结果。
 */
public enum EvalFailureType {
    /** Agent 完成、补丁非空、独立评分器通过。 */
    PASS,
    /** Agent 完成，但未产出任何补丁（无文件变更）。 */
    AGENT_NO_CHANGES,
    /** Agent 完成、补丁非空，但独立评分器退出非 0 或发现数低于门槛。 */
    GRADER_FAILED,
    /** Agent 完成、补丁非空，评分器要求测试但发现数为 0。 */
    GRADER_NO_TESTS,
    /** 评分器自身异常（超时/无法启动/退出码未知）。 */
    GRADER_ERROR,
    /** 只有运行观察，没有独立业务评分；不是缺陷已解决或确定未解决的证据。 */
    GRADER_UNSCORED,
    /** 根 Run 终态 FAILED。 */
    AGENT_FAILED,
    /** 根 Run 被取消。 */
    AGENT_CANCELLED,
    /** 根 Run 因预算上限失败（stopReason=BUDGET_LIMIT）。 */
    BUDGET_EXCEEDED;

    public boolean isPass() {
        return this == PASS;
    }
}
