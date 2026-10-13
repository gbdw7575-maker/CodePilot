package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** 本地项目登记：只记录绝对路径与信任状态，不复制或执行项目代码。 */
@Entity
@Table(name = "projects")
public class ProjectEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private String rootPath;

    @Column(nullable = false)
    private boolean trusted;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ProjectEntity() {
    }

    public ProjectEntity(String id, String name, String rootPath, boolean trusted, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.rootPath = rootPath;
        this.trusted = trusted;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRootPath() {
        return rootPath;
    }

    public boolean isTrusted() {
        return trusted;
    }

    public void setTrusted(boolean trusted) {
        this.trusted = trusted;
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
