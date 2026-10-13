package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Thread 内统一事件高水位；append 在同一事务内对该行加行锁递增，保证 sequence 提交顺序。
 */
@Entity
@Table(name = "event_cursors")
public class EventCursorEntity {

    @Id
    @Column(name = "thread_id", length = 128)
    private String threadId;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    protected EventCursorEntity() {
    }

    public EventCursorEntity(String threadId, long lastSequence) {
        this.threadId = threadId;
        this.lastSequence = lastSequence;
    }

    public String getThreadId() {
        return threadId;
    }

    public long getLastSequence() {
        return lastSequence;
    }

    public void setLastSequence(long lastSequence) {
        this.lastSequence = lastSequence;
    }
}
