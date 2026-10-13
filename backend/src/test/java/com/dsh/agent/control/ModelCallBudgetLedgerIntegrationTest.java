package com.dsh.agent.control;

import com.dsh.common.IdGen;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ModelCallBudgetLedgerIntegrationTest {
    @Autowired BudgetLedger ledger;
    @Autowired RunRepository runs;
    private String create(int requests, int tokens) {
        return create(requests, tokens, Instant.now().plusSeconds(600));
    }
    private String create(int requests, int tokens, Instant deadline) {
        Instant now = Instant.now();
        RunEntity r = new RunEntity(IdGen.next("run_b03_"), "turn-x", null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest", requests, 20, tokens, deadline, now, now);
        return runs.save(r).getId();
    }
    private Object[] counters(String id) { return runs.readBudgetCounters(id).getFirst(); }
    @Test void conditionalReservationIsAtomicAndRejectsInvalidOrExpiredRuns() {
        String id = create(1, 1000);
        assertThrows(IllegalArgumentException.class, () -> ledger.reserveModelCall(id, 0));
        assertFalse(ledger.reserveModelCall(id, 1001));
        assertArrayEquals(new Object[]{0,0,null,0}, counters(id));
        assertTrue(ledger.reserveModelCall(id, 1000));
        assertFalse(ledger.reserveModelCall(id, 1));
        assertArrayEquals(new Object[]{1,0,null,1000}, counters(id));
        String expired = create(10,1000,Instant.now().minusSeconds(1));
        assertFalse(ledger.reserveModelCall(expired, 10));
        String paused = create(10,1000); RunEntity r = runs.findById(paused).orElseThrow();
        r.setState("WAITING_INPUT"); runs.save(r);
        assertFalse(ledger.reserveModelCall(paused, 10));
    }
    @Test void excludesSettledAndExistingChildReservations() {
        String id = create(10,1000);
        RunEntity r = runs.findById(id).orElseThrow(); r.setBudgetSettledTokens(200); runs.save(r);
        assertTrue(ledger.reserveChildBudget(id, 2, 2, 500));
        assertFalse(ledger.reserveModelCall(id,301));
        assertTrue(ledger.reserveModelCall(id,300));
        assertArrayEquals(new Object[]{3,2,200,800}, counters(id));
    }
    @Test void parallelModelReservationsCannotSpendTheSameBalance() throws Exception {
        String id = create(10,1000); ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> { ready.countDown(); start.await(); return ledger.reserveModelCall(id,700); };
        try {
            Future<Boolean> a=pool.submit(attempt), b=pool.submit(attempt);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            assertNotEquals(a.get(10,TimeUnit.SECONDS), b.get(10,TimeUnit.SECONDS));
            assertArrayEquals(new Object[]{1,0,null,700}, counters(id));
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    @Test void modelAndChildDispatchRaceCannotSpendTheSameBalance() throws Exception {
        String id = create(10,1000); ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> a=pool.submit(() -> { ready.countDown();start.await();return ledger.reserveModelCall(id,700); });
            Future<Boolean> b=pool.submit(() -> { ready.countDown();start.await();return ledger.reserveChildBudget(id,2,2,700); });
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            boolean model=a.get(10,TimeUnit.SECONDS), child=b.get(10,TimeUnit.SECONDS);
            assertNotEquals(model,child);
            assertArrayEquals(model?new Object[]{1,0,null,700}:new Object[]{2,2,null,700}, counters(id));
        } finally { start.countDown();pool.shutdownNow(); }
    }
}
