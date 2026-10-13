package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * worker 资源租约（MA-04，规范 §6.1 就绪条件 3/4、§6.2 有界执行）。
 *
 * <p>每个实际启动的子 Run 对应一行 HELD 租约：全局/根 Run/模型提供方三级信号量
 * 同时获取成功后落库，终态幂等置 RELEASED。行记录是 MA-08 崩溃恢复（fencing、
 * 过期租约回收）的持久化依据；MA-04 只在单实例内存信号量之上做落库与释放，
 * 不做过期回收，也不跨实例裁决。</p>
 */
@Entity
@Table(name = "agent_resource_leases")
public class ResourceLeaseEntity {

    public static final String HELD = "HELD";
    public static final String RELEASED = "RELEASED";

    @Id
    @Column(length = 128)
    private String leaseId;

    @Column(name = "root_run_id", nullable = false, length = 128)
    private String rootRunId;

    @Column(name = "holder_run_id", nullable = false, length = 128)
    private String holderRunId;

    @Column(name = "provider_key", nullable = false, length = 64)
    private String providerKey;

    @Column(nullable = false, length = 16)
    private String state;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    @Column(name = "lease_until", nullable = false)
    private Instant leaseUntil;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected ResourceLeaseEntity() {
    }

    public ResourceLeaseEntity(String leaseId, String rootRunId, String holderRunId,
                               String providerKey, String state,
                               Instant acquiredAt, Instant leaseUntil, Instant releasedAt) {
        this.leaseId = leaseId;
        this.rootRunId = rootRunId;
        this.holderRunId = holderRunId;
        this.providerKey = providerKey;
        this.state = state;
        this.acquiredAt = acquiredAt;
        this.leaseUntil = leaseUntil;
        this.releasedAt = releasedAt;
    }

    public String getLeaseId() {
        return leaseId;
    }

    public String getRootRunId() {
        return rootRunId;
    }

    public String getHolderRunId() {
        return holderRunId;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Instant getAcquiredAt() {
        return acquiredAt;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }
}
