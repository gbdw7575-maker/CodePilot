package com.dsh.orchestration;

import com.dsh.application.DshProperties;
import com.dsh.common.IdGen;
import com.dsh.storage.ResourceLeaseEntity;
import com.dsh.storage.ResourceLeaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-04 / AT-05：worker 三级并发配额（全局/根 Run/提供方）与持久化资源租约。
 * 内存公平信号量裁决并发，HELD/RELEASED 行落库；满额有界 QuotaFullException，
 * release 幂等且不超额归还许可。
 */
@SpringBootTest
class Ma04WorkerConcurrencyTest {

    @Autowired
    private ResourceLeaseRepository leaseRepository;

    private WorkerConcurrencyManager manager(int global, int root, int provider) {
        DshProperties.Orchestration.Quota quota =
                new DshProperties.Orchestration.Quota(global, root, provider, 0, 0, 0);
        DshProperties properties = new DshProperties(null, null, null, null, null, null,
                null, null, null,
                new DshProperties.Orchestration(true, 2, 8, 30, 60_000, 600, false, 4,
                        DshProperties.Orchestration.Plan.defaults(), quota, 3, 500, null),
                null, null, null, null, null);
        return new WorkerConcurrencyManager(properties, leaseRepository);
    }

    private Instant deadline() {
        return Instant.now().plus(5, ChronoUnit.MINUTES);
    }

    @Test
    void globalQuotaBoundedAndLeasePersistedThenReleased() {
        WorkerConcurrencyManager manager = manager(1, 2, 2);
        String root = "root-ma04-global-" + IdGen.next("x_");

        WorkerConcurrencyManager.Lease lease = manager.acquire(root, "holder-1", "fake", deadline());
        ResourceLeaseEntity row = leaseRepository.findById(lease.leaseId()).orElseThrow();
        assertEquals(ResourceLeaseEntity.HELD, row.getState());
        assertEquals(root, row.getRootRunId());
        assertEquals("holder-1", row.getHolderRunId());
        assertEquals("fake", row.getProviderKey());
        assertNotNull(row.getAcquiredAt());
        assertTrue(row.getLeaseUntil().isAfter(Instant.now()));
        assertEquals(1, leaseRepository.countByRootRunIdAndState(root, ResourceLeaseEntity.HELD));

        // 全局已满（另一根/另一提供方也不行）：有界异常且不落新租约行
        WorkerConcurrencyManager.QuotaFullException full = assertThrows(
                WorkerConcurrencyManager.QuotaFullException.class,
                () -> manager.acquire("root-other", "holder-2", "other", deadline()));
        assertEquals("GLOBAL", full.dimension());
        assertEquals(1, leaseRepository.countByRootRunIdAndState(root, ResourceLeaseEntity.HELD));
        assertEquals(0, leaseRepository.countByRootRunIdAndState("root-other",
                ResourceLeaseEntity.HELD));

        // 释放后行 RELEASED，许可归还可再获取
        manager.release(lease);
        ResourceLeaseEntity released = leaseRepository.findById(lease.leaseId()).orElseThrow();
        assertEquals(ResourceLeaseEntity.RELEASED, released.getState());
        assertNotNull(released.getReleasedAt());
        WorkerConcurrencyManager.Lease again = manager.acquire(root, "holder-3", "fake", deadline());
        manager.release(again);

        // 幂等：重复释放不得二次归还许可（否则信号量超发）
        manager.release(lease);
        manager.release(again);
        WorkerConcurrencyManager.Lease a = manager.acquire(root, "holder-a", "fake", deadline());
        assertThrows(WorkerConcurrencyManager.QuotaFullException.class,
                () -> manager.acquire(root, "holder-b", "fake", deadline()));
        manager.release(a);
    }

    @Test
    void rootQuotaIsPerRootRunWhileOthersProceed() {
        WorkerConcurrencyManager manager = manager(2, 1, 2);
        String rootA = "root-ma04-a-" + IdGen.next("x_");
        String rootB = "root-ma04-b-" + IdGen.next("x_");

        WorkerConcurrencyManager.Lease a1 = manager.acquire(rootA, "h-a1", "fake", deadline());
        WorkerConcurrencyManager.QuotaFullException rootFull = assertThrows(
                WorkerConcurrencyManager.QuotaFullException.class,
                () -> manager.acquire(rootA, "h-a2", "fake", deadline()));
        assertEquals("ROOT", rootFull.dimension());

        // 同一时刻另一根 Run 不受影响（全局仍有空位）
        WorkerConcurrencyManager.Lease b1 = manager.acquire(rootB, "h-b1", "fake", deadline());
        assertEquals(1, leaseRepository.countByRootRunIdAndState(rootA, ResourceLeaseEntity.HELD));
        assertEquals(1, leaseRepository.countByRootRunIdAndState(rootB, ResourceLeaseEntity.HELD));

        manager.release(a1);
        manager.release(b1);
        assertEquals(0, leaseRepository.countByRootRunIdAndState(rootA, ResourceLeaseEntity.HELD));
    }

    @Test
    void providerQuotaIsPerProviderKey() {
        WorkerConcurrencyManager manager = manager(3, 3, 1);

        WorkerConcurrencyManager.Lease p1 = manager.acquire(
                "root-prov-a", "h-p1", "provider-x", deadline());
        // 不同根 Run 但同一提供方：提供方维度已满
        WorkerConcurrencyManager.QuotaFullException providerFull = assertThrows(
                WorkerConcurrencyManager.QuotaFullException.class,
                () -> manager.acquire("root-prov-b", "h-p2", "provider-x", deadline()));
        assertEquals("PROVIDER", providerFull.dimension());

        // 另一提供方仍可获取
        WorkerConcurrencyManager.Lease p2 = manager.acquire(
                "root-prov-c", "h-p3", "provider-y", deadline());
        manager.release(p1);
        manager.release(p2);
        List<String> states = leaseRepository.findAllById(
                java.util.List.of(p1.leaseId(), p2.leaseId())).stream()
                .map(ResourceLeaseEntity::getState).toList();
        assertEquals(List.of(ResourceLeaseEntity.RELEASED, ResourceLeaseEntity.RELEASED), states);
    }
}
