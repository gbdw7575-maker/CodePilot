package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 任务状态流水的根 Run 内单调游标分配器（MA-05）。
 *
 * <p>每个根 Run 一行；状态推进在同一短事务内 {@code lastSeq+1} 条件更新后取值，
 * 并发推进靠行更新串行化。await_tasks 的 afterCursor/新游标即该 seq 的十进制字符串。</p>
 */
@Entity
@Table(name = "agent_task_event_cursor")
public class TaskEventCursorEntity {

    @Id
    @Column(name = "root_run_id", length = 128)
    private String rootRunId;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TaskEventCursorEntity() {
    }

    public TaskEventCursorEntity(String rootRunId, long lastSeq, Instant updatedAt) {
        this.rootRunId = rootRunId;
        this.lastSeq = lastSeq;
        this.updatedAt = updatedAt;
    }

    public String getRootRunId() {
        return rootRunId;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public void setLastSeq(long lastSeq) {
        this.lastSeq = lastSeq;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
