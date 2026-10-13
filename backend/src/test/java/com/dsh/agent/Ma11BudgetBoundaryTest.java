package com.dsh.agent;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-11 严格预算边界（验收记录 §11）：maxCumulativeTokens 是累计支出上限，
 * 由「请求准入」与「终态判定」两处共同保证。此前只做准入判定，最后一次响应
 * 可以越过上限仍以成功终态收尾，预算实际只是请求前阈值。
 *
 * <p>本类只覆盖纯判定函数，不启动 Spring 上下文、不调用真实 Provider。</p>
 */
class Ma11BudgetBoundaryTest {

    @Test
    void settledExactlyAtCapIsNotABreach() {
        // 恰好用尽上限：不允许再发起请求，但已完成的响应不是越界
        assertTrue(LoopSteps.budgetBreachAfterSettlement(200000L, 200000L).isEmpty());
    }

    @Test
    void oneTokenOverCapIsABreach() {
        Optional<String> breach = LoopSteps.budgetBreachAfterSettlement(200001L, 200000L);
        assertTrue(breach.isPresent());
        assertTrue(breach.get().contains("200001"));
        assertTrue(breach.get().contains("200000"));
    }

    @Test
    void realOverspendFromAcceptanceRecordIsABreach() {
        // 验收记录 §10：MULTI c04/attempt2 结算 206233 后仍判 PASS（上限 200000）
        Optional<String> breach = LoopSteps.budgetBreachAfterSettlement(206233L, 200000L);
        assertTrue(breach.isPresent());
    }

    @Test
    void noCapMeansNoTerminalCheck() {
        assertTrue(LoopSteps.budgetBreachAfterSettlement(5000L, 0L).isEmpty());
        assertTrue(LoopSteps.budgetBreachAfterSettlement(5000L, -1L).isEmpty());
    }

    @Test
    void zeroSettledNeverBreaches() {
        assertFalse(LoopSteps.budgetBreachAfterSettlement(0L, 200000L).isPresent());
    }

    @Test
    void unknownUsageDoesNotFabricateOverspend() {
        // usage 未知时结算值不增长：不把未知用量当作 0，也不臆测为超额
        assertTrue(LoopSteps.budgetBreachAfterSettlement(0L, 1L).isEmpty());
    }
}
