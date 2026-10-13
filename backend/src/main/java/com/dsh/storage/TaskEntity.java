package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 计划任务定义（MA-02，规范 §4.1 Task）。
 *
 * <p>taskId 是计划内逻辑标识（模型提供，受 ^[A-Za-z0-9_-]+$ 与长度约束）；代理键 id 仅为存储。
 * 任务的可变结构（goal/dependsOn/inputRefs/deliverable/acceptance/resourceScope/executionMode/
 * required）属于冻结的任务版本：未变行保留，可编辑定义变化仅替换对应行并分配新generation。
 * 一旦派发或进入活跃/成功状态
 * （PENDING/READY/RUNNING/VERIFYING/WAITING_APPROVAL/CANCELLING/SUCCEEDED），PlanService 拒绝静默修改，
 * 必须先取消再新建版本/尝试（规范 §4.3，TASK_LOCKED）。</p>
 *
 * <p>dependsOn/inputRefs/acceptance/resourceScope 以规范化 JSON 文本存储（PostgreSQL text）。
 * state 当前只会写入 DRAFT；值域与 MA-01 fixtures 的 taskStates 一致，禁止写入 RunState 枚举。</p>
 */
@Entity
@Table(name = "agent_tasks", uniqueConstraints =
        @UniqueConstraint(name = "uk_agent_tasks_plan_task", columnNames = {"plan_id", "task_id"}))
public class TaskEntity {

    @Id
    @Column(length = 128)
    private String id;

    @Column(name = "plan_id", nullable = false, length = 128)
    private String planId;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "task_version", nullable = false)
    private int taskVersion;

    @Column(nullable = false, columnDefinition = "text")
    private String goal;

    /** JSON 数组：直接依赖的 taskId 列表。 */
    @Column(name = "depends_on", nullable = false, columnDefinition = "text")
    private String dependsOn;

    /** JSON 数组：[{taskId, output}]，只允许引用本计划内任务。 */
    @Column(name = "input_refs", nullable = false, columnDefinition = "text")
    private String inputRefs;

    @Column(nullable = false, length = 500)
    private String deliverable;

    /** JSON 数组：执行前冻结的验收条目字符串。 */
    @Column(nullable = false, columnDefinition = "text")
    private String acceptance;

    /** JSON 对象 {read:[], write:[]}，可空（模型申请的写范围，服务端后续取交集）。 */
    @Column(name = "resource_scope", columnDefinition = "text")
    private String resourceScope;

    /** DELEGATED（委派 worker）或 INLINE（主 Agent 自行执行）。 */
    @Column(name = "execution_mode", nullable = false, length = 16)
    private String executionMode;

    @Column(nullable = false)
    private boolean required;

    @Column(nullable = false, length = 32)
    private String state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TaskEntity() {
    }

    public TaskEntity(String id, String planId, String taskId, int taskVersion, String goal,
                      String dependsOn, String inputRefs, String deliverable, String acceptance,
                      String resourceScope, String executionMode, boolean required, String state,
                      Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.planId = planId;
        this.taskId = taskId;
        this.taskVersion = taskVersion;
        this.goal = goal;
        this.dependsOn = dependsOn;
        this.inputRefs = inputRefs;
        this.deliverable = deliverable;
        this.acceptance = acceptance;
        this.resourceScope = resourceScope;
        this.executionMode = executionMode;
        this.required = required;
        this.state = state;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
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

    public String getGoal() {
        return goal;
    }

    public String getDependsOn() {
        return dependsOn;
    }

    public String getInputRefs() {
        return inputRefs;
    }

    public String getDeliverable() {
        return deliverable;
    }

    public String getAcceptance() {
        return acceptance;
    }

    public String getResourceScope() {
        return resourceScope;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public boolean isRequired() {
        return required;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    /** MA-05 调度状态推进时同步更新时间戳。 */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
