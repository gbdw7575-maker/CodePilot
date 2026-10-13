package com.dsh.orchestration;

import com.dsh.agent.control.BudgetLedger;
import com.dsh.common.IdGen;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-04 / AT-05：原子预算预约在“主任务保留”口径下的三维条件更新。
 * 七参预约=旧四维切片+三维保留，可分配=上限−已结算−进行中预约−保留；
 * 任一维度含保留后不足整笔失败且零副作用；末份并发争抢恰一方成功；
 * 保留显式配 0 与旧四参行为一致。
 */
@SpringBootTest
class Ma04BudgetReservationIntegrationTest {

    @Autowired
    private BudgetLedger ledger;
    @Autowired
    private RunRepository runRepository;

    /** [modelRequestsUsed, toolCallsUsed, settledTokens, reservedTokens] */
    private Object[] counters(String id) {
        return runRepository.readBudgetCounters(id).get(0);
    }

    private RunEntity persistRun(int maxMr, int maxTc, int maxTok) {
        String id = IdGen.next("run_ma04_budget_");
        RunEntity run = new RunEntity(id, "turn-x", null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest", maxMr, maxTc, maxTok,
                Instant.now().plusSeconds(600), Instant.now(), Instant.now());
        return runRepository.save(run);
    }

    @Test
    @Transactional
    void reserveHonoursMainReserveAcrossAllThreeDimensions() {
        // 父账 12/20/1000；主保留 4/8/400 → 可分配 8/12/600
        RunEntity run = persistRun(12, 20, 1000);
        String id = run.getId();

        assertTrue(ledger.reserveChildBudget(id, 8, 8, 500, 4, 8, 400));
        assertArrayEquals(new Object[]{8, 8, null, 500}, counters(id));

        // 保留口径下 mr 可分配已为 0：哪怕只申请 1 个模型请求也必须拒绝
        assertFalse(ledger.reserveChildBudget(id, 1, 1, 1, 4, 8, 400));
        assertArrayEquals(new Object[]{8, 8, null, 500}, counters(id),
                "保留导致的预约失败必须零副作用");

        // tc 维度：可用 12，申请 13（保留 8 已计入）整笔失败
        assertFalse(ledger.reserveChildBudget(id, 0, 13, 1, 4, 8, 400));
        assertArrayEquals(new Object[]{8, 8, null, 500}, counters(id));

        // token 维度：可用 1000-500(reserved)-400(保留)=100，申请 101 失败
        assertFalse(ledger.reserveChildBudget(id, 0, 0, 101, 4, 8, 400));
        assertArrayEquals(new Object[]{8, 8, null, 500}, counters(id));
    }

    @Test
    @Transactional
    void zeroReserveIsEquivalentToLegacyFourArgReservation() {
        RunEntity run = persistRun(10, 10, 1000);
        String id = run.getId();

        assertTrue(ledger.reserveChildBudget(id, 10, 10, 1000, 0, 0, 0));
        assertArrayEquals(new Object[]{10, 10, null, 1000}, counters(id));
        assertFalse(ledger.reserveChildBudget(id, 1, 1, 1, 0, 0, 0));
    }

    @Test
    @Transactional
    void settleWithUnknownTokensKeepsConservativeLiabilityThenReserveUsesReserve() {
        // 父账 10/10/1000，主保留 2/2/100
        RunEntity run = persistRun(10, 10, 1000);
        String id = run.getId();

        assertTrue(ledger.reserveChildBudget(id, 5, 5, 600, 2, 2, 100));
        // usage 未知：按切片 token 全额保守实结（负债不释放）
        ledger.settleChildBudget(id, 5, 5, 5, 5, 600, 600);
        assertArrayEquals(new Object[]{5, 5, 600, 0}, counters(id));

        // 实结后再预约：可分配 mr=10-5-2=3、tc=3、tok=1000-600-100=300，恰满成功
        assertTrue(ledger.reserveChildBudget(id, 3, 3, 300, 2, 2, 100));
        assertArrayEquals(new Object[]{8, 8, 600, 300}, counters(id));
        // 再多 1 个请求维度都不允许（保留不可侵占）
        assertFalse(ledger.reserveChildBudget(id, 1, 0, 0, 2, 2, 100));
    }

    @Test
    void exactlyOneWinsTheLastSliceUnderContention() throws Exception {
        // 不放在单一事务里：条件 UPDATE 的行锁裁决必须在真实提交边界上发生
        RunEntity run = persistRun(20, 20, 10_000);
        String id = run.getId();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        try {
            java.util.List<Future<Boolean>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    // 每个线程都试图吃完全部可分配余额；保留为 0
                    boolean ok = ledger.reserveChildBudget(id, 20, 20, 10_000, 0, 0, 0);
                    if (ok) {
                        wins.incrementAndGet();
                    }
                    return ok;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Boolean> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, wins.get(), "末份预算并发争抢必须恰一方成功");
        assertArrayEquals(new Object[]{20, 20, null, 10_000}, counters(id),
                "赢者三维全额入账，败者零副作用");
    }
}
