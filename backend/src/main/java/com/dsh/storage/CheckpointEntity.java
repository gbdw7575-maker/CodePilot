package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 检查点：capture 时工作区内容摘要 + zip 产物引用（metaDir/checkpoints/）。
 * restore 时比较 expectedDigest 与当前摘要，不匹配→409 WORKSPACE_CONFLICT。
 */
@Entity
@Table(name = "checkpoints")
public class CheckpointEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 128)
    private String runId;

    @Column(nullable = false, length = 64)
    private String workspaceDigest;

    /** zip 产物在 metaDir 下的相对路径；MVP 单个 zip。 */
    @Column(length = 500)
    private String artifactRef;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private Instant createdAt;

    protected CheckpointEntity() {
    }

    public CheckpointEntity(String id, String runId, String workspaceDigest, String artifactRef,
                            Instant createdAt) {
        this.id = id;
        this.runId = runId;
        this.workspaceDigest = workspaceDigest;
        this.artifactRef = artifactRef;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getWorkspaceDigest() {
        return workspaceDigest;
    }

    public String getArtifactRef() {
        return artifactRef;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
