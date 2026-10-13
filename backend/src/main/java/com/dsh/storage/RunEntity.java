package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** 根/子运行；DSH-002 只创建 QUEUED 根 Run，执行语义属于后续任务。 */
@Entity
@Table(name = "runs")
public class RunEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 128)
    private String turnId;

    @Column(length = 128)
    private String parentRunId;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, length = 32)
    private String state;

    @Column(nullable = false, length = 32)
    private String verificationStatus;

    @Column(length = 48)
    private String stopReason;

    /** 完成依据：VERIFICATION_PASSED / NO_CHANGE / NO_NET_CHANGE_AFTER_WRITE；进行中为 null。 */
    @Column(name = "completion_basis", length = 32)
    private String completionBasis;

    /** C29 immutable intake; null keeps legacy project-level requirements. */
    @Column(name = "verification_requirement_v1", columnDefinition = "text", updatable = false)
    private String verificationRequirement;

    public String getVerificationRequirement() { return verificationRequirement; }
    public void registerVerificationRequirement(String encoded) {
        if (verificationRequirement != null) throw new IllegalStateException("Run 验收登记不可修改");
        verificationRequirement = com.dsh.application.RunVerificationRequirement.decode(encoded).encode();
    }

    @Column(nullable = false)
    private int attempt;

    @Column(nullable = false, length = 128)
    private String workspaceRef;

    @Column(nullable = false, length = 64)
    private String configDigest;

    @Column(nullable = false)
    private int budgetMaxModelRequests;

    @Column(nullable = false)
    private int budgetModelRequestsUsed;

    @Column(nullable = false)
    private int budgetMaxToolCalls;

    @Column(nullable = false)
    private int budgetToolCallsUsed;

    @Column(nullable = false)
    private int budgetMaxCumulativeTokens;

    private Integer budgetSettledTokens;

    @Column(nullable = false)
    private int budgetReservedTokens;

    @Column(nullable = false)
    private Instant budgetDeadlineAt;

    @Column(nullable = false, length = 16)
    private String budgetUsageStatus;

    @Column(nullable = false, length = 16)
    private String usageStatus;

    private Integer usageInputTokens;

    private Integer usageOutputTokens;

    private Integer usageCacheHitTokens;

    private Integer usageCacheMissTokens;

    @Column(length = 64)
    private String usageCost;

    @Column(length = 3)
    private String usageCurrency;

    @Column(length = 64)
    private String usagePriceVersion;

    @Column(length = 2000)
    private String summary;

    /** 取消请求时间戳；null 表示未请求取消。 */
    private Instant cancelRequestedAt;

    /** 取消清理是否已确认（已知副作用已收尾）。 */
    @Column(nullable = false)
    private boolean cleanupConfirmed;

    /** 取消原因（USER_CANCELLED / ENVIRONMENT_ERROR 等）。 */
    @Column(length = 48)
    private String cancelReason;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RunEntity() {
    }

    @SuppressWarnings("PMD.ExcessiveParameterList")
    public RunEntity(String id, String turnId, String parentRunId, String role, String state,
                     String verificationStatus, int attempt, String workspaceRef, String configDigest,
                     int budgetMaxModelRequests, int budgetMaxToolCalls, int budgetMaxCumulativeTokens,
                     Instant budgetDeadlineAt, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.turnId = turnId;
        this.parentRunId = parentRunId;
        this.role = role;
        this.state = state;
        this.verificationStatus = verificationStatus;
        this.attempt = attempt;
        this.workspaceRef = workspaceRef;
        this.configDigest = configDigest;
        this.budgetMaxModelRequests = budgetMaxModelRequests;
        this.budgetMaxToolCalls = budgetMaxToolCalls;
        this.budgetMaxCumulativeTokens = budgetMaxCumulativeTokens;
        this.budgetDeadlineAt = budgetDeadlineAt;
        this.budgetUsageStatus = "UNKNOWN";
        this.usageStatus = "UNKNOWN";
        this.cleanupConfirmed = false;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getTurnId() {
        return turnId;
    }

    public String getParentRunId() {
        return parentRunId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getRole() {
        return role;
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

    public String getCompletionBasis() {
        return completionBasis;
    }

    public void setCompletionBasis(String completionBasis) {
        this.completionBasis = completionBasis;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public String getWorkspaceRef() {
        return workspaceRef;
    }

    public void setWorkspaceRef(String workspaceRef) {
        this.workspaceRef = workspaceRef;
    }

    public String getConfigDigest() {
        return configDigest;
    }

    public int getBudgetMaxModelRequests() {
        return budgetMaxModelRequests;
    }

    public int getBudgetModelRequestsUsed() {
        return budgetModelRequestsUsed;
    }

    public void setBudgetModelRequestsUsed(int budgetModelRequestsUsed) {
        this.budgetModelRequestsUsed = budgetModelRequestsUsed;
    }

    public void setBudgetToolCallsUsed(int budgetToolCallsUsed) {
        this.budgetToolCallsUsed = budgetToolCallsUsed;
    }

    public void setBudgetSettledTokens(Integer budgetSettledTokens) {
        this.budgetSettledTokens = budgetSettledTokens;
    }

    public void setBudgetReservedTokens(int budgetReservedTokens) {
        this.budgetReservedTokens = budgetReservedTokens;
    }

    public void setBudgetUsageStatus(String budgetUsageStatus) {
        this.budgetUsageStatus = budgetUsageStatus;
    }

    public void setUsageStatus(String usageStatus) {
        this.usageStatus = usageStatus;
    }

    public void setUsageInputTokens(Integer usageInputTokens) {
        this.usageInputTokens = usageInputTokens;
    }

    public void setUsageOutputTokens(Integer usageOutputTokens) {
        this.usageOutputTokens = usageOutputTokens;
    }

    public void setUsageCacheHitTokens(Integer usageCacheHitTokens) {
        this.usageCacheHitTokens = usageCacheHitTokens;
    }

    public void setUsageCacheMissTokens(Integer usageCacheMissTokens) {
        this.usageCacheMissTokens = usageCacheMissTokens;
    }

    public void setUsageCost(String usageCost) {
        this.usageCost = usageCost;
    }

    public void setUsageCurrency(String usageCurrency) {
        this.usageCurrency = usageCurrency;
    }

    public void setUsagePriceVersion(String usagePriceVersion) {
        this.usagePriceVersion = usagePriceVersion;
    }

    public int getBudgetMaxToolCalls() {
        return budgetMaxToolCalls;
    }

    public int getBudgetToolCallsUsed() {
        return budgetToolCallsUsed;
    }

    public int getBudgetMaxCumulativeTokens() {
        return budgetMaxCumulativeTokens;
    }

    public Integer getBudgetSettledTokens() {
        return budgetSettledTokens;
    }

    public int getBudgetReservedTokens() {
        return budgetReservedTokens;
    }

    public Instant getBudgetDeadlineAt() {
        return budgetDeadlineAt;
    }

    public String getBudgetUsageStatus() {
        return budgetUsageStatus;
    }

    public String getUsageStatus() {
        return usageStatus;
    }

    public Integer getUsageInputTokens() {
        return usageInputTokens;
    }

    public Integer getUsageOutputTokens() {
        return usageOutputTokens;
    }

    public Integer getUsageCacheHitTokens() {
        return usageCacheHitTokens;
    }

    public Integer getUsageCacheMissTokens() {
        return usageCacheMissTokens;
    }

    public String getUsageCost() {
        return usageCost;
    }

    public String getUsageCurrency() {
        return usageCurrency;
    }

    public String getUsagePriceVersion() {
        return usagePriceVersion;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
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

    public Instant getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public void setCancelRequestedAt(Instant cancelRequestedAt) {
        this.cancelRequestedAt = cancelRequestedAt;
    }

    public boolean isCleanupConfirmed() {
        return cleanupConfirmed;
    }

    public void setCleanupConfirmed(boolean cleanupConfirmed) {
        this.cleanupConfirmed = cleanupConfirmed;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }
}
