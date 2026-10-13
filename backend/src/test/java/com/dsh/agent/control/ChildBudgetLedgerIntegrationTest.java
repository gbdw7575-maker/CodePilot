package com.dsh.agent.control;

import com.dsh.common.IdGen;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-010：父账三维预算分账。原子预约任一维度不足整笔失败（兄弟子 Run 不能透同一余额）；
 * 实结退还预约、实际用量入账（token 未知保守全额）。
 */
@SpringBootTest
class ChildBudgetLedgerIntegrationTest {

    @Autowired
    private BudgetLedger ledger;
    @Autowired
    private RunRepository runRepository;

    private RunEntity persistRun(int maxMr, int maxTc, int maxTok) {
        String id = IdGen.next("run_budget_");
        RunEntity run = new RunEntity(id, "turn-x", null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest", maxMr, maxTc, maxTok,
                Instant.now().plusSeconds(600), Instant.now(), Instant.now());
        return runRepository.save(run);
    }

    /** [modelRequestsUsed, toolCallsUsed, settledTokens, reservedTokens] */
    private Object[] counters(String id) {
        return runRepository.readBudgetCounters(id).get(0);
    }

    @Test
    @Transactional
    void reserveIsAtomicAcrossThreeDimensions() {
        RunEntity run = persistRun(10, 10, 1000);
        String id = run.getId();

        assertTrue(ledger.reserveChildBudget(id, 4, 4, 300));
        // 新 Run settledTokens 初始为 null，reserve 只动 reserved 维度
        assertArrayEquals(new Object[]{4, 4, null, 300}, counters(id));

        // 模型请求维度余额不足（剩 6，申请 7）→ 整笔失败，其他维度不得被占用
        assertFalse(ledger.reserveChildBudget(id, 7, 1, 100));
        assertArrayEquals(new Object[]{4, 4, null, 300}, counters(id),
                "条件 UPDATE 失败必须零副作用");

        // token 维度不足（剩 700，申请 701）→ 失败
        assertFalse(ledger.reserveChildBudget(id, 1, 1, 701));
        assertArrayEquals(new Object[]{4, 4, null, 300}, counters(id));

        // 恰好用尽余额 → 成功
        assertTrue(ledger.reserveChildBudget(id, 6, 6, 700));
        assertArrayEquals(new Object[]{10, 10, null, 1000}, counters(id));
        // 已满，任何正切片都失败
        assertFalse(ledger.reserveChildBudget(id, 1, 0, 0));
    }

    @Test
    @Transactional
    void settleReturnsReservationAndBooksActualUsage() {
        RunEntity run = persistRun(20, 20, 2000);
        String id = run.getId();

        ledger.reserveChildBudget(id, 8, 8, 800);
        // 子 Run 实际消耗 mr=3 tc=5 tok=400
        ledger.settleChildBudget(id, 8, 3, 8, 5, 800, 400);

        Object[] c = counters(id);
        // mr: 8-8+3=3；tc: 8-8+5=5；settledTokens=400；reservedTokens=800-800=0
        assertArrayEquals(new Object[]{3, 5, 400, 0}, c);

        // 再次预约必须基于实结后的真实余额：剩余 mr=17/tc=15/tok=1600
        assertTrue(ledger.reserveChildBudget(id, 17, 15, 1600));
        assertArrayEquals(new Object[]{20, 20, 400, 1600}, counters(id));
    }

    @Test
    @Transactional
    void settleWithUnknownTokensChargesFullReservationConservatively() {
        RunEntity run = persistRun(10, 10, 1000);
        String id = run.getId();

        ledger.reserveChildBudget(id, 5, 5, 600);
        // token 未知：调用方按预约全额保守结算
        ledger.settleChildBudget(id, 5, 5, 5, 5, 600, 600);

        assertArrayEquals(new Object[]{5, 5, 600, 0}, counters(id));
    }
}
