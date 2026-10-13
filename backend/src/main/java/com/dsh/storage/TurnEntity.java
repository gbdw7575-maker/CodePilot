package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 轮次：保存原始输入原文，不做 intent/taskType 拆分。
 * activeKey = threadId（轮次活动期间）；终态置 null，配合唯一约束兜底"同会话只有一个活动 Turn"。
 */
@Entity
@Table(name = "turns")
public class TurnEntity {

    public static final String STATE_QUEUED = "QUEUED";

    @Id
    private String id;

    @Column(nullable = false, length = 128)
    private String threadId;

    @Column(nullable = false, length = 128)
    private String rootRunId;

    @Column(nullable = false, length = 100000)
    private String input;

    @Column(nullable = false, length = 32)
    private String state;

    @Column(nullable = false, length = 32)
    private String verificationStatus;

    @Column(length = 48)
    private String stopReason;

    @Column(name = "active_key", length = 140, unique = true)
    private String activeKey;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected TurnEntity() {
    }

    public TurnEntity(String id, String threadId, String rootRunId, String input, String state,
                      String verificationStatus, String activeKey, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.threadId = threadId;
        this.rootRunId = rootRunId;
        this.input = input;
        this.state = state;
        this.verificationStatus = verificationStatus;
        this.activeKey = activeKey;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getRootRunId() {
        return rootRunId;
    }

    public String getInput() {
        return input;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(String verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public String getActiveKey() {
        return activeKey;
    }

    public void setActiveKey(String activeKey) {
        this.activeKey = activeKey;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
