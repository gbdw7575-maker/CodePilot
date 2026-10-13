package com.dsh.agent;

/**
 * Run 调度：submit/append 事务提交后触发。
 * 同步实现用于离线确定性验收；异步实现用于真实后台执行。
 */
public interface LoopScheduler {

    void scheduleStart(String runId);

    void scheduleResume(String runId);

    /** 审批决策后恢复：先 resumeFromApproval 执行工具，再 scheduleResume 继续模型循环。 */
    void scheduleResumeFromApproval(String turnId, String runId);
}
