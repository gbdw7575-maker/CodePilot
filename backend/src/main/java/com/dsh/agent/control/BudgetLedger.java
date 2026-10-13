package com.dsh.agent.control;

/**
 * 预算账本：原子预约/结算/释放。
 * DSH-005 仅定义接口与 RunRepository 薄封装；子运行并发预约属于 DSH-010。
 */
public interface BudgetLedger {

    /** 原子预约模型请求额度；成功返回 true，透支返回 false。 */
    boolean reserveModelRequests(String runId, int n);

    /** B03: 仅 RUNNING 且截止时间内，原子占用一次请求及其保守 Token 成本。 */
    boolean reserveModelCall(String runId, int tokens);

    /** 原子预约工具调用额度；成功返回 true，透支返回 false。 */
    boolean reserveToolCalls(String runId, int n);

    /** 结算后释放保留 Token（reserved 归零）。 */
    void releaseReservedTokens(String runId);

    /**
     * DSH-010：父 Run 为子 Run 原子预约三维预算切片；任一维度不足整笔失败。
     * 兄弟子 Run 不能同时花同一份余额（根 Run 总账、子 Run 分账）。
     * MA-04 起委托零主任务保留版本，保留 MA-03 前语义；新调用应使用 7 参重载。
     */
    boolean reserveChildBudget(String parentRunId, int modelRequests, int toolCalls, int tokens);

    /**
     * MA-04：带主任务保留的原子预约（规范 §10.1）。
     * 三维切片 + 主任务保留必须同时落在根上限内，否则整笔失败（已结算/进行中预约口径
     * 见 RunRepository.reserveChildBudgetWithReserve 条件 UPDATE）。
     */
    boolean reserveChildBudget(String parentRunId, int modelRequests, int toolCalls, int tokens,
                               int reserveModelRequests, int reserveToolCalls, int reserveTokens);

    /** DSH-010：子 Run 结束后向父 Run 实结（退订占用、入账实际用量）。 */
    void settleChildBudget(String parentRunId, int reservedModelRequests, int usedModelRequests,
                           int reservedToolCalls, int usedToolCalls,
                           int reservedTokens, int usedTokens);
}
