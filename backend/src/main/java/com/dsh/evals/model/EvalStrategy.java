package com.dsh.evals.model;

/**
 * 对照策略因子（MA-11，AT-18）：
 * <ul>
 *   <li>SINGLE：单 Agent ReAct 路径（默认开关 dsh.orchestration.enabled=false）；</li>
 *   <li>MULTI：多 Agent 编排路径（同模型/预算/镜像/任务，仅进程开关与策略不同）。</li>
 * </ul>
 * 同任务/模型/总预算/镜像/seed/repeat 完全一致、仅策略不同的两次运行才允许进入对照聚合。
 */
public enum EvalStrategy {
    SINGLE,
    MULTI
}
