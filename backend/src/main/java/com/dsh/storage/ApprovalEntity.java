package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 工具调用审批：绑定 parametersDigest + workspaceDigest，一次性 PENDING → APPROVED/DENIED。
 * 执行前 consume 复核摘要匹配；摘要变化 → INVALIDATED。
 */
@Entity
@Table(name = "approvals")
public class ApprovalEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 128)
    private String turnId;

    @Column(nullable = false, length = 128)
    private String runId;

    @Column(nullable = false, length = 128)
    private String toolCallId;

    @Column(nullable = false, length = 64)
    private String toolName;

    @Column(nullable = false, length = 2000)
    private String argumentsSummary;

    /** 完整工具参数 JSON，resume 时按此执行。 */
    @Column(nullable = false, columnDefinition = "text")
    private String argumentsJson;

    @Column(nullable = false, length = 64)
    private String parametersDigest;

    @Column(nullable = false, length = 64)
    private String workspaceDigest;

    @Column(nullable = false, length = 16)
    private String risk;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false, length = 8)
    private String scope;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private Instant expiresAt;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ApprovalEntity() {
    }

    public ApprovalEntity(String id, String turnId, String runId, String toolCallId, String toolName,
                          String argumentsSummary, String argumentsJson, String parametersDigest,
                          String workspaceDigest, String risk, String reason,
                          Instant expiresAt, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.turnId = turnId;
        this.runId = runId;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.argumentsSummary = argumentsSummary;
        this.argumentsJson = argumentsJson;
        this.parametersDigest = parametersDigest;
        this.workspaceDigest = workspaceDigest;
        this.risk = risk;
        this.reason = reason;
        this.scope = "ONCE";
        this.status = "PENDING";
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getTurnId() {
        return turnId;
    }

    public String getRunId() {
        return runId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getArgumentsSummary() {
        return argumentsSummary;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public String getParametersDigest() {
        return parametersDigest;
    }

    public String getWorkspaceDigest() {
        return workspaceDigest;
    }

    public String getRisk() {
        return risk;
    }

    public String getReason() {
        return reason;
    }

    public String getScope() {
        return scope;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
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
