package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 子任务输入快照（MA-04，规范 §7 上下文顺序、§8.1 冻结输入快照）。
 *
 * <p>子 Run 启动前落库：种子工作区引用与其基线摘要、引用产物的内容 sha256 清单、
 * 经服务端归一化的写范围 JSON。执行与封存只能对照该冻结版本；MA-07 将补依赖产物
 * 实际物化进副本，MA-06 验收时核对输入版本，本切片只冻结不物化。</p>
 *
 * <p>MA-04 同步路径下一个子 Run 恰好一份快照（重试新建子 Run），run_id 唯一。</p>
 */
@Entity
@Table(name = "agent_input_snapshots", uniqueConstraints =
        @UniqueConstraint(name = "uk_agent_input_snapshots_run", columnNames = {"run_id"}))
public class InputSnapshotEntity {

    @Id
    @Column(length = 128)
    private String snapshotId;

    @Column(name = "run_id", nullable = false, length = 128)
    private String runId;

    @Column(name = "root_run_id", nullable = false, length = 128)
    private String rootRunId;

    @Column(name = "project_id", nullable = false, length = 128)
    private String projectId;

    @Column(name = "seed_workspace_ref", length = 128)
    private String seedWorkspaceRef;

    /** 种子副本冻结时的基线摘要（无种子为空串）。 */
    @Column(name = "base_digest", nullable = false, length = 128)
    private String baseDigest;

    /** JSON 数组：[{artifactId, mediaType, sha256}]，按请求顺序冻结。 */
    @Column(name = "input_artifacts", nullable = false, columnDefinition = "text")
    private String inputArtifactsJson;

    /** 归一化后的写范围 JSON {read:[],write:[]}；null=整副本可写（MA-04 前行为）。 */
    @Column(name = "write_scope", columnDefinition = "text")
    private String writeScopeJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InputSnapshotEntity() {
    }

    public InputSnapshotEntity(String snapshotId, String runId, String rootRunId, String projectId,
                               String seedWorkspaceRef, String baseDigest,
                               String inputArtifactsJson, String writeScopeJson, Instant createdAt) {
        this.snapshotId = snapshotId;
        this.runId = runId;
        this.rootRunId = rootRunId;
        this.projectId = projectId;
        this.seedWorkspaceRef = seedWorkspaceRef;
        this.baseDigest = baseDigest;
        this.inputArtifactsJson = inputArtifactsJson;
        this.writeScopeJson = writeScopeJson;
        this.createdAt = createdAt;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public String getRunId() {
        return runId;
    }

    public String getRootRunId() {
        return rootRunId;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getSeedWorkspaceRef() {
        return seedWorkspaceRef;
    }

    public String getBaseDigest() {
        return baseDigest;
    }

    public String getInputArtifactsJson() {
        return inputArtifactsJson;
    }

    public String getWriteScopeJson() {
        return writeScopeJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
