package com.dsh.orchestration;

import com.dsh.application.DshProperties;
import com.dsh.common.IdGen;
import com.dsh.storage.ResourceLeaseEntity;
import com.dsh.storage.ResourceLeaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * worker 并发与资源租约管理（MA-04，规范 §6.2）。
 *
 * <p>放一个 worker 必须同时持有三级公平许可：应用全局、根 Run、模型提供方。
 * 获取非阻塞——并发已满由调用方转有界失败（CONCURRENCY_LIMIT），不无限排队；
 * 获取成功落一行 HELD {@link ResourceLeaseEntity}，终态 {@link #release} 幂等置 RELEASED
 * 并归还许可。单实例内存信号量是并发裁决真值，落库行供 MA-08 fencing/崩溃恢复，
 * 本切片不做跨实例裁决与过期回收。</p>
 */
@Component
public class WorkerConcurrencyManager {

    private static final Logger log = LoggerFactory.getLogger(WorkerConcurrencyManager.class);

    private final DshProperties properties;
    private final ResourceLeaseRepository leaseRepository;

    private final Semaphore globalPermits;
    private final ConcurrentHashMap<String, Semaphore> rootPools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> providerPools = new ConcurrentHashMap<>();

    public WorkerConcurrencyManager(DshProperties properties,
                                    ResourceLeaseRepository leaseRepository) {
        this.properties = properties;
        this.leaseRepository = leaseRepository;
        this.globalPermits = new Semaphore(
                properties.orchestration().quota().globalMaxWorkers(), true);
    }

    /** 已获得的资源租约句柄；release 幂等，可安全放入 finally。 */
    public record Lease(String leaseId, String rootRunId, String holderRunId, String providerKey,
                        AtomicBoolean released) {
    }

    /** 并发已满：dimension 为 GLOBAL/ROOT/PROVIDER。 */
    public static class QuotaFullException extends RuntimeException {
        private final String dimension;

        public QuotaFullException(String dimension, String key, int limit) {
            super("worker 并发配额已满: " + dimension + (key == null ? "" : "(" + key + ")")
                    + " 上限=" + limit);
            this.dimension = dimension;
        }

        public String dimension() {
            return dimension;
        }
    }

    public Lease acquire(String rootRunId, String holderRunId, String providerKey,
                         Instant leaseUntil) {
        DshProperties.Orchestration.Quota quota = properties.orchestration().quota();
        Semaphore rootPool = pool(rootPools, rootRunId, quota.rootMaxWorkers());
        Semaphore providerPool = pool(providerPools, providerKey, quota.providerMaxWorkers());

        boolean global = false;
        boolean root = false;
        boolean provider = false;
        String dimension;
        String key = null;
        int limit;
        try {
            if (globalPermits.tryAcquire()) {
                global = true;
            } else {
                dimension = "GLOBAL";
                limit = quota.globalMaxWorkers();
                throw full(dimension, key, limit);
            }
            if (rootPool.tryAcquire()) {
                root = true;
            } else {
                dimension = "ROOT";
                key = rootRunId;
                limit = quota.rootMaxWorkers();
                throw full(dimension, key, limit);
            }
            if (providerPool.tryAcquire()) {
                provider = true;
            } else {
                dimension = "PROVIDER";
                key = providerKey;
                limit = quota.providerMaxWorkers();
                throw full(dimension, key, limit);
            }
        } catch (QuotaFullException e) {
            if (provider) {
                providerPool.release();
            }
            if (root) {
                rootPool.release();
            }
            if (global) {
                globalPermits.release();
            }
            throw e;
        }

        Instant now = Instant.now();
        ResourceLeaseEntity entity = new ResourceLeaseEntity(IdGen.next("lease_"),
                rootRunId, holderRunId, providerKey == null ? "" : providerKey,
                ResourceLeaseEntity.HELD, now, leaseUntil, null);
        leaseRepository.save(entity);
        return new Lease(entity.getLeaseId(), rootRunId, holderRunId,
                entity.getProviderKey(), new AtomicBoolean(false));
    }

    /** 幂等释放：HELD→RELEASED 成功落库才归还三级许可；重复调用无副作用。 */
    public void release(Lease lease) {
        if (lease == null || !lease.released().compareAndSet(false, true)) {
            return;
        }
        int updated;
        try {
            updated = leaseRepository.releaseIfHeld(lease.leaseId(), Instant.now());
        } catch (RuntimeException e) {
            // 落库失败不阻断调用方终态流程：泄漏行由 MA-08 过期回收处理；
            // 内存许可仍归还，避免同实例信号量被永久占满。
            log.warn("资源租约落库释放失败 lease={}（MA-08 回收兜底）: {}",
                    lease.leaseId(), e.getMessage());
            updated = 1;
        }
        if (updated > 0) {
            pool(rootPools, lease.rootRunId(), properties.orchestration().quota().rootMaxWorkers())
                    .release();
            pool(providerPools, lease.providerKey(),
                    properties.orchestration().quota().providerMaxWorkers()).release();
            globalPermits.release();
        }
    }

    private static QuotaFullException full(String dimension, String key, int limit) {
        return new QuotaFullException(dimension, key, limit);
    }

    private Semaphore pool(ConcurrentHashMap<String, Semaphore> pools, String key, int permits) {
        return pools.computeIfAbsent(key == null ? "" : key,
                k -> new Semaphore(permits, true));
    }
}
