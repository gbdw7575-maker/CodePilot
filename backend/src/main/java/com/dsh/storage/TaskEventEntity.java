package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 任务状态变更流水（MA-05，规范 §6.3/§9）。
 *
 * <p>内部持久化事件，先落盘后发内存信号（{@code agent_task_waits} 的唤醒与
 * 就绪依赖释放都消费它的 seq）。正式 Envelope 事件（task.state.changed 等）
 * 在 MA-10 发布，本实体不直接进 WebSocket。</p>
 *
 * <p>状态值域为 MA-01 冻结 taskStates；attempt 级 QUEUED 不产生任务流水
 * （任务停留在 READY，等待态由 read_task_result 的 attempt 视图表达）。</p>
 */
@Entity
@Table(name = "agent_task_events", uniqueConstraints =
        @UniqueConstraint(name = "uk_agent_task_events_root_seq",
                columnNames = {"root_run_id", "seq"}))
public class TaskEventEntity {

    @Id
    @Column(length = 128)
    private String id;

    @Column(name = "root_run_id", nullable = false, length = 128)
    private String rootRunId;

    @Column(nullable = false)
    private long seq;

    @Column(name = "plan_id", nullable = false, length = 128)
    private String planId;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "task_version", nullable = false)
    private int taskVersion;

    @Column(name = "attempt_id", length = 128)
    private String attemptId;

    @Column(name = "child_run_id", length = 128)
    private String childRunId;

    @Column(nullable = false, length = 32)
    private String state;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected TaskEventEntity() {
    }

    public TaskEventEntity(String id, String rootRunId, long seq, String planId, String taskId,
                           int taskVersion, String attemptId, String childRunId, String state,
                           String reasonCode, Instant occurredAt) {
        this.id = id;
        this.rootRunId = rootRunId;
        this.seq = seq;
        this.planId = planId;
        this.taskId = taskId;
        this.taskVersion = taskVersion;
        this.attemptId = attemptId;
        this.childRunId = childRunId;
        this.state = state;
        this.reasonCode = reasonCode;
        this.occurredAt = occurredAt;
    }

    public String getId() {
        return id;
    }

    public String getRootRunId() {
        return rootRunId;
    }

    public long getSeq() {
        return seq;
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

    public String getAttemptId() {
        return attemptId;
    }

    public String getChildRunId() {
        return childRunId;
    }

    public String getState() {
        return state;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
