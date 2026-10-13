package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** 会话；activeTurnId 指向当前活动轮次，同会话同时只允许一个活动 Turn。 */
@Entity
@Table(name = "threads")
public class ThreadEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 128)
    private String projectId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 128)
    private String modelProfileId;

    @Column(length = 128)
    private String activeTurnId;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ThreadEntity() {
    }

    public ThreadEntity(String id, String projectId, String title, String modelProfileId,
                        Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.title = title;
        this.modelProfileId = modelProfileId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getModelProfileId() {
        return modelProfileId;
    }

    public void setModelProfileId(String modelProfileId) {
        this.modelProfileId = modelProfileId;
    }

    public String getActiveTurnId() {
        return activeTurnId;
    }

    public void setActiveTurnId(String activeTurnId) {
        this.activeTurnId = activeTurnId;
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
