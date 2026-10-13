package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 任务尝试结果（MA-05，规范 §5.1 read_task_result 的权威数据来源）。
 *
 * <p>每个 attempt 终态一行；重试新建 attempt（MA-09），旧结果不覆盖。
 * outputDigest/verificationRef 的验收门在 MA-06/MA-07：本切片 outputDigest
 * 仅为子副本封存摘要，子 COMPLETED 即被调度器映射为 SUCCEEDED（显式基线，
 * MA-06 会在 RUNNING 与 SUCCEEDED 之间插入 VERIFYING 闸门）。</p>
 */
@Entity
@Table(name = "agent_task_results")
public class TaskResultEntity {

    @Id
    @Column(name = "attempt_id", length = 128)
    private String attemptId;

    @Column(name = "plan_id", nullable = false, length = 128)
    private String planId;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "task_version", nullable = false)
    private int taskVersion;

    @Column(name = "child_run_id", length = 128)
    private String childRunId;

    /** 尝试结果态：SUCCEEDED / FAILED / CANCELLED。 */
    @Column(nullable = false, length = 32)
    private String state;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    /** JSON 数组：可消费产物 ID（封存通过的 patch 等）；无产物为空数组。 */
    @Column(name = "artifact_refs_json", nullable = false, columnDefinition = "text")
    private String artifactRefsJson;

    @Column(name = "patch_artifact_id", length = 128)
    private String patchArtifactId;

    @Column(name = "output_digest", length = 128)
    private String outputDigest;

    /** MA-06：上游依赖产物的冻结摘要（sha256），用于 AT-11 证据有效性校验。 */
    @Column(name = "input_digest", length = 128)
    private String inputDigest;

    /** MA-06：验收记录引用（VerificationEntity.id），验收通过才非空。 */
    @Column(name = "verification_ref", length = 128)
    private String verificationRef;

    @Column(name = "model_requests_used", nullable = false)
    private int modelRequestsUsed;

    @Column(name = "tool_calls_used", nullable = false)
    private int toolCallsUsed;

    /** 保守实结口径：未知 usage 非空（等于切片额）；无 Run 的纯失败为 null。 */
    @Column(name = "settled_tokens")
    private Integer settledTokens;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TaskResultEntity() {
    }

    public TaskResultEntity(String attemptId, String planId, String taskId, int taskVersion,
                            String childRunId, String state, String errorCode, String errorMessage,
                            String artifactRefsJson, String patchArtifactId, String outputDigest,
                            String inputDigest, String verificationRef,
                            int modelRequestsUsed, int toolCallsUsed, Integer settledTokens,
                            long durationMs, Instant createdAt) {
        this.attemptId = attemptId;
        this.planId = planId;
        this.taskId = taskId;
        this.taskVersion = taskVersion;
        this.childRunId = childRunId;
        this.state = state;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.artifactRefsJson = artifactRefsJson;
        this.patchArtifactId = patchArtifactId;
        this.outputDigest = outputDigest;
        this.inputDigest = inputDigest;
        this.verificationRef = verificationRef;
        this.modelRequestsUsed = modelRequestsUsed;
        this.toolCallsUsed = toolCallsUsed;
        this.settledTokens = settledTokens;
        this.durationMs = durationMs;
        this.createdAt = createdAt;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public String getPlanId() {
        return planId;
    }

    public String getTaskId() {
        return taskId;
    }

    public int getTaskVersion() {
        return taskVersion;
    }

    public String getChildRunId() {
        return childRunId;
    }

    public String getState() {
        return state;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getArtifactRefsJson() {
        return artifactRefsJson;
    }

    public String getPatchArtifactId() {
        return patchArtifactId;
    }

    public String getOutputDigest() {
        return outputDigest;
    }

    public String getInputDigest() {
        return inputDigest;
    }

    public String getVerificationRef() {
        return verificationRef;
    }

    public int getModelRequestsUsed() {
        return modelRequestsUsed;
    }

    public int getToolCallsUsed() {
        return toolCallsUsed;
    }

    public Integer getSettledTokens() {
        return settledTokens;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
