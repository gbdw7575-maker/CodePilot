package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 主 Run await_tasks 停车记录（MA-05，规范 §9 串行邮箱的持久化锚点）。
 *
 * <p>主循环决定停车时在短事务内落一行 WAITING（Run 本身保持 RUNNING，不新增
 * RunState 枚举值），随后唯一主循环线程在事务外等待信号/超时；恢复短事务回填
 * 唯一 tool result 后把本行置 DONE/TIMED_OUT。进程崩溃后由启动补偿扫描收敛
 * （到点或条件已满足即回填并恢复；崩溃窗口的完整裁决在 MA-08）。</p>
 */
@Entity
@Table(name = "agent_task_waits", uniqueConstraints =
        @UniqueConstraint(name = "uk_agent_task_waits_call", columnNames = {"tool_call_id"}))
public class TaskWaitEntity {

    public static final String WAITING = "WAITING";
    public static final String DONE = "DONE";
    public static final String TIMED_OUT = "TIMED_OUT";

    @Id
    @Column(length = 128)
    private String id;

    @Column(name = "root_run_id", nullable = false, length = 128)
    private String rootRunId;

    @Column(name = "turn_id", nullable = false, length = 128)
    private String turnId;

    @Column(name = "tool_call_id", nullable = false, length = 128)
    private String toolCallId;

    @Column(name = "task_ids_json", nullable = false, columnDefinition = "text")
    private String taskIdsJson;

    /** ANY / ALL。 */
    @Column(name = "wait_mode", nullable = false, length = 8)
    private String waitMode;

    @Column(name = "after_cursor", nullable = false)
    private long afterCursor;

    /** 原始工具参数 JSON（恢复时原样重放一次 await 求值，保证结果与请求配对）。 */
    @Column(name = "arguments_json", nullable = false, columnDefinition = "text")
    private String argumentsJson;

    @Column(name = "wait_until", nullable = false)
    private Instant waitUntil;

    @Column(nullable = false, length = 16)
    private String state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected TaskWaitEntity() {
    }

    public TaskWaitEntity(String id, String rootRunId, String turnId, String toolCallId,
                          String taskIdsJson, String waitMode, long afterCursor,
                          String argumentsJson, Instant waitUntil, String state,
                          Instant createdAt, Instant completedAt) {
        this.id = id;
        this.rootRunId = rootRunId;
        this.turnId = turnId;
        this.toolCallId = toolCallId;
        this.taskIdsJson = taskIdsJson;
        this.waitMode = waitMode;
        this.afterCursor = afterCursor;
        this.argumentsJson = argumentsJson;
        this.waitUntil = waitUntil;
        this.state = state;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    public String getId() {
        return id;
    }

    public String getRootRunId() {
        return rootRunId;
    }

    public String getTurnId() {
        return turnId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getTaskIdsJson() {
        return taskIdsJson;
    }

    public String getWaitMode() {
        return waitMode;
    }

    public long getAfterCursor() {
        return afterCursor;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public Instant getWaitUntil() {
        return waitUntil;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
