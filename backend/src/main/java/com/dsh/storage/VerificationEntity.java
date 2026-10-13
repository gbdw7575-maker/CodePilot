package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 验证记录：在工作区副本中执行验证命令的结果。
 * DSH-007：独立验证子系统核心。
 */
@Entity
@Table(name = "verifications")
public class VerificationEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 128)
    private String runId;

    @Column(nullable = false, length = 64)
    private String planVersion;

    @Column(nullable = false, length = 64)
    private String workspaceDigest;

    /** 验证命令执行前的工作区摘要；与 workspaceDigest（执行后）不等表示命令产生了产物。 */
    @Column(name = "pre_verification_digest", length = 64)
    private String preVerificationDigest;

    @Column(nullable = false, length = 64)
    private String imageDigest;

    /** Actual shell text is identity-bearing; never truncate it to a display-sized VARCHAR. */
    @Column(nullable = false, length = org.hibernate.Length.LONG32)
    private String commandSummary;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private int exitCode;

    @Column(nullable = false)
    private int testsDiscovered;

    @Column(nullable = false)
    private int testsPassed;

    @Column(nullable = false)
    private int testsSkipped;

    @Column(nullable = false)
    private boolean stale;

    @Column(nullable = false, length = 1024)
    private String logArtifactIds;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant finishedAt;

    public VerificationEntity() {
    }

    public VerificationEntity(String id, String runId, String planVersion, String workspaceDigest,
                              String preVerificationDigest, String imageDigest, String commandSummary,
                              String status, int exitCode, int testsDiscovered, int testsPassed,
                              int testsSkipped, boolean stale, String logArtifactIds,
                              Instant startedAt, Instant finishedAt) {
        this.id = id;
        this.runId = runId;
        this.planVersion = planVersion;
        this.workspaceDigest = workspaceDigest;
        this.preVerificationDigest = preVerificationDigest;
        this.imageDigest = imageDigest;
        this.commandSummary = commandSummary;
        this.status = status;
        this.exitCode = exitCode;
        this.testsDiscovered = testsDiscovered;
        this.testsPassed = testsPassed;
        this.testsSkipped = testsSkipped;
        this.stale = stale;
        this.logArtifactIds = logArtifactIds;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    public String getId() { return id; }
    public String getRunId() { return runId; }
    public String getPlanVersion() { return planVersion; }
    public String getWorkspaceDigest() { return workspaceDigest; }
    public String getPreVerificationDigest() { return preVerificationDigest; }
    public String getImageDigest() { return imageDigest; }
    public String getCommandSummary() { return commandSummary; }
    public String getStatus() { return status; }
    public int getExitCode() { return exitCode; }
    public int getTestsDiscovered() { return testsDiscovered; }
    public int getTestsPassed() { return testsPassed; }
    public int getTestsSkipped() { return testsSkipped; }
    public boolean isStale() { return stale; }
    public String getLogArtifactIds() { return logArtifactIds; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }

    public void setStale(boolean stale) { this.stale = stale; }
    public void setStatus(String status) { this.status = status; }
}
