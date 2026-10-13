package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** 用户可见消息；sequence 为 thread 内消息序号（与事件序号是两套计数器），原文保存。 */
@Entity
@Table(name = "messages", uniqueConstraints = @UniqueConstraint(columnNames = {"thread_id", "sequence"}))
public class MessageEntity {

    @Id
    private String id;

    @Column(name = "thread_id", nullable = false, length = 128)
    private String threadId;

    @Column(name = "turn_id", length = 128)
    private String turnId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, length = 100000)
    private String content;

    @Column(nullable = false)
    private long sequence;

    @Column(nullable = false)
    private Instant createdAt;

    protected MessageEntity() {
    }

    public MessageEntity(String id, String threadId, String turnId, String role, String content,
                         long sequence, Instant createdAt) {
        this.id = id;
        this.threadId = threadId;
        this.turnId = turnId;
        this.role = role;
        this.content = content;
        this.sequence = sequence;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getTurnId() {
        return turnId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public long getSequence() {
        return sequence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
