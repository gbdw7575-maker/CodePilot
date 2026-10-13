package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 任务尝试（MA-02 持久化占位，规范 §4.1 TaskAttempt）。
 *
 * <p>重试生成新 attemptId、递增 attemptNo，不覆盖旧尝试证据；执行租约（leaseOwner/leaseUntil/
 * fencingToken）与预算预约（budgetReservationId）在 MA-04/MA-05/MA-08 接线。
 * <strong>MA-02 只交付表结构与仓储，尚无任何代码写入本实体</strong>——调度领取在 MA-05 落地，
 * 提前建表是为了冻结状态/字段映射，避免后续切片改表迁移。</p>
 */
@Entity
@Table(name = "agent_task_attempts")
public class TaskAttemptEntity {

    @Id
    @Column(length = 128)
    private String attemptId;

    @Column(name = "plan_id", nullable = false, length = 128)
    private String planId;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "task_version", nullable = false)
    private int taskVersion;

    @Column(name = "child_run_id", length = 128)
    private String childRunId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "fencing_token")
    private Long fencingToken;

    @Column(name = "budget_reservation_id", length = 128)
    private String budgetReservationId;

    @Column(nullable = false, length = 32)
    private String state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TaskAttemptEntity() {
    }

    public TaskAttemptEntity(String attemptId, String planId, String taskId, int taskVersion,
                             String childRunId, int attemptNo, String leaseOwner, Instant leaseUntil,
                             Long fencingToken, String budgetReservationId, String state,
                             Instant createdAt, Instant updatedAt) {
        this.attemptId = attemptId;
        this.planId = planId;
        this.taskId = taskId;
        this.taskVersion = taskVersion;
        this.childRunId = childRunId;
        this.attemptNo = attemptNo;
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
        this.fencingToken = fencingToken;
        this.budgetReservationId = budgetReservationId;
        this.state = state;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public String getPlanId() {
        return planId;
    }

    public String getTaskId() {
        return taskId;
    }

    public int getTaskVersion() {
        return taskVersion;
    }

    public String getChildRunId() {
        return childRunId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    // MA-08：requeue 重排同一 attempt 时递增 attemptNo 作为重试计数（不创建新 attempt）
    public void setAttemptNo(int attemptNo) {
        this.attemptNo = attemptNo;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public Long getFencingToken() {
        return fencingToken;
    }

    public String getBudgetReservationId() {
        return budgetReservationId;
    }

    public String getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // ---------- MA-05 调度领取/终态收敛写入点 ----------

    public void setState(String state) {
        this.state = state;
    }

    public void setChildRunId(String childRunId) {
        this.childRunId = childRunId;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public void setLeaseUntil(Instant leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public void setFencingToken(Long fencingToken) {
        this.fencingToken = fencingToken;
    }

    public void setBudgetReservationId(String budgetReservationId) {
        this.budgetReservationId = budgetReservationId;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
