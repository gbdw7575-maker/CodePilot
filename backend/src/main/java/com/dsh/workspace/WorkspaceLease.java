package com.dsh.workspace;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务副本写租约（内部接口契约 §1 WorkspaceManager）：
 * 绑定根 Run 与授权项目，持有隔离副本目录、基线摘要与排他写权。
 * 租约之外不得写副本；同一 Run 复用同一租约，恢复/续跑不重新拷贝。
 */
public class WorkspaceLease {

    private final String workspaceId;
    private final String runId;
    private final String projectId;
    private final Path sourceRoot;
    private final Path dir;
    private final Path metaDir;
    private final String baselineDigest;
    private final Instant createdAt;
    /** 租约内可写的计划产物版本（update_plan），不写入用户源码。 */
    private final AtomicReference<String> planRef = new AtomicReference<>();

    public WorkspaceLease(String workspaceId, String runId, String projectId, Path sourceRoot,
                          Path dir, Path metaDir, String baselineDigest, Instant createdAt) {
        this.workspaceId = workspaceId;
        this.runId = runId;
        this.projectId = projectId;
        this.sourceRoot = sourceRoot;
        this.dir = dir;
        this.metaDir = metaDir;
        this.baselineDigest = baselineDigest;
        this.createdAt = createdAt;
    }

    public String workspaceId() {
        return workspaceId;
    }

    public String runId() {
        return runId;
    }

    public String projectId() {
        return projectId;
    }

    public Path sourceRoot() {
        return sourceRoot;
    }

    /** 任务副本根目录（容器内挂载为 /workspace）。 */
    public Path dir() {
        return dir;
    }

    /** 副本之外的元数据目录：计划、完整日志产物，不进 git_status/diff 与基线摘要。 */
    public Path metaDir() {
        return metaDir;
    }

    public String baselineDigest() {
        return baselineDigest;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String planRef() {
        return planRef.get();
    }

    public void setPlanRef(String value) {
        planRef.set(value);
    }
}
