package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 受控产物元数据：Diff patch、验证日志等。
 * 内容存储在工作区副本的 .meta/artifacts/{artifactId} 下，元数据入库。
 * downloadable=false 的产物（如内部中间文件）不允许下载。
 */
@Entity
@Table(name = "artifacts")
public class ArtifactEntity {

    @Id
    private String id;

    @Column(length = 128)
    private String runId;

    @Column(nullable = false, length = 128)
    private String projectId;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(nullable = false, length = 200)
    private String mediaType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private boolean downloadable;

    protected ArtifactEntity() {
    }

    public ArtifactEntity(String id, String runId, String projectId, String name, String mediaType,
                          long sizeBytes, String sha256, Instant createdAt, boolean downloadable) {
        this.id = id;
        this.runId = runId;
        this.projectId = projectId;
        this.name = name;
        this.mediaType = mediaType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.createdAt = createdAt;
        this.downloadable = downloadable;
    }

    public String getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public String getMediaType() {
        return mediaType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isDownloadable() {
        return downloadable;
    }
}
