package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 编排计划（MA-02，Agent 编排升级规范 §4.1）。
 *
 * <p>一个根 Run 可有多个计划草案；当前版本只支持 DRAFT（update_plan 创建/更新，不启动执行）。
 * revision 为业务乐观并发号：更新必须携带 expectedRevision，由 PlanService 显式比对
 * （不使用 JPA @Version，避免与「返回当前修订号」的业务语义耦合）。计划不绑定 threadId，
 * 归属根 Run 与项目；rootRunId、projectId 由服务端上下文写入，模型不可指定。</p>
 */
@Entity
@Table(name = "agent_plans")
public class PlanEntity {

    @Id
    @Column(length = 128)
    private String id;

    @Column(name = "root_run_id", nullable = false, length = 128)
    private String rootRunId;

    @Column(name = "project_id", nullable = false, length = 128)
    private String projectId;

    @Column(nullable = false)
    private int revision;

    /** Nullable for legacy rows. Retained across task removal to prevent generation reuse. */
    @Column(name = "task_version_high_water")
    private Integer taskVersionHighWater;

    /** DRAFT：唯一已发布状态；后续切片扩状态机时保持字符串值域，不塞 RunState。 */
    @Column(nullable = false, length = 32)
    private String status;

    @Column(columnDefinition = "text")
    private String goal;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlanEntity() {
    }

    public PlanEntity(String id, String rootRunId, String projectId, int revision, String status,
                      String goal, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.rootRunId = rootRunId;
        this.projectId = projectId;
        this.revision = revision;
        this.status = status;
        this.goal = goal;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getRootRunId() {
        return rootRunId;
    }

    public String getProjectId() {
        return projectId;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public Integer getTaskVersionHighWater() { return taskVersionHighWater; }
    public void setTaskVersionHighWater(Integer value) { this.taskVersionHighWater = value; }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
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
