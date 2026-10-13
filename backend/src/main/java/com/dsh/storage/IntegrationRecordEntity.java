package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 父工作区集成记录（MA-07，规范 §8.3 合并协议）。
 *
 * <p>每次集成落一行：记录 baseDigest（父工作区集成前摘要）、输入产物引用、当前 resultDigest、
 * state、appliedPaths（journal：已应用文件相对路径）、evidenceRef 与 fencingToken。
 * §8.3 要求"每次集成记录 baseDigest、输入产物、当前父工作区摘要和 integrationId"，
 * 中断恢复时 IN_PROGRESS 行阻塞或重做，不能静默留半份补丁（AT-13）。</p>
 *
 * <p>state 取值：IN_PROGRESS（集成进行中，崩溃窗口）、APPLIED（集成成功）、
 * CONFLICTS（冲突整批不应用）、RECOVERED_BLOCKED（中断恢复发现已部分写入，阻塞报告）。</p>
 */
@Entity
@Table(name = "agent_integration_records")
public class IntegrationRecordEntity {

    @Id
    @Column(name = "integration_id", length = 128)
    private String integrationId;

    @Column(name = "root_run_id", nullable = false, length = 128)
    private String rootRunId;

    @Column(name = "project_id", nullable = false, length = 128)
    private String projectId;

    /** 父工作区集成前摘要（§8.3：重新检查父状态与实际工作区版本）。 */
    @Column(name = "base_digest", nullable = false, length = 128)
    private String baseDigest;

    /** JSON 数组：本次集成输入的全部 SUCCEEDED 子任务产物 ID。 */
    @Column(name = "input_artifact_refs", nullable = false, columnDefinition = "text")
    private String inputArtifactRefsJson;

    /** 集成后父工作区新摘要；未完成（IN_PROGRESS/CONFLICTS/RECOVERED_BLOCKED）为空。 */
    @Column(name = "result_digest", length = 128)
    private String resultDigest;

    @Column(nullable = false, length = 32)
    private String state;

    /** journal：已应用文件相对路径 JSON 数组；中断恢复据此判断是否部分写入。 */
    @Column(name = "applied_paths", nullable = false, columnDefinition = "text")
    private String appliedPathsJson;

    /** 验收证据引用（集成后父工作区验证记录）。 */
    @Column(name = "evidence_ref", length = 128)
    private String evidenceRef;

    /** §8.3/§10.3：防迟到写——旧 fencingToken 的结果不得写入当前工作区。 */
    @Column(name = "fencing_token", nullable = false, length = 128)
    private String fencingToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IntegrationRecordEntity() {
    }

    public IntegrationRecordEntity(String integrationId, String rootRunId, String projectId,
                                    String baseDigest, String inputArtifactRefsJson,
                                    String resultDigest, String state, String appliedPathsJson,
                                    String evidenceRef, String fencingToken,
                                    Instant createdAt, Instant updatedAt) {
        this.integrationId = integrationId;
        this.rootRunId = rootRunId;
        this.projectId = projectId;
        this.baseDigest = baseDigest;
        this.inputArtifactRefsJson = inputArtifactRefsJson;
        this.resultDigest = resultDigest;
        this.state = state;
        this.appliedPathsJson = appliedPathsJson;
        this.evidenceRef = evidenceRef;
        this.fencingToken = fencingToken;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getIntegrationId() {
        return integrationId;
    }

    public String getRootRunId() {
        return rootRunId;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getBaseDigest() {
        return baseDigest;
    }

    public String getInputArtifactRefsJson() {
        return inputArtifactRefsJson;
    }

    public String getResultDigest() {
        return resultDigest;
    }

    public void setResultDigest(String resultDigest) {
        this.resultDigest = resultDigest;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getAppliedPathsJson() {
        return appliedPathsJson;
    }

    public void setAppliedPathsJson(String appliedPathsJson) {
        this.appliedPathsJson = appliedPathsJson;
    }

    public String getEvidenceRef() {
        return evidenceRef;
    }

    public void setEvidenceRef(String evidenceRef) {
        this.evidenceRef = evidenceRef;
    }

    public String getFencingToken() {
        return fencingToken;
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
