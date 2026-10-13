package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 任务派发幂等登记（MA-05，规范 §5.1）。
 *
 * <p>{@code (rootRunId, requestKey)} 构成幂等范围：重复请求返回既有登记；
 * 相同 key 不同参数（paramsHash 不同）返回 REQUEST_KEY_CONFLICT。
 * paramsHash 覆盖 planId/expectedRevision/规范化后的 taskIds 列表。</p>
 */
@Entity
@Table(name = "agent_task_dispatches", uniqueConstraints =
        @UniqueConstraint(name = "uk_agent_task_dispatches_root_key",
                columnNames = {"root_run_id", "request_key"}))
public class TaskDispatchEntity {

    @Id
    @Column(length = 128)
    private String id;

    @Column(name = "root_run_id", nullable = false, length = 128)
    private String rootRunId;

    @Column(name = "request_key", nullable = false, length = 128)
    private String requestKey;

    @Column(name = "plan_id", nullable = false, length = 128)
    private String planId;

    @Column(name = "params_hash", nullable = false, length = 64)
    private String paramsHash;

    /** JSON 数组：本次登记提交的 taskId 列表（保序）。 */
    @Column(name = "task_ids_json", nullable = false, columnDefinition = "text")
    private String taskIdsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TaskDispatchEntity() {
    }

    public TaskDispatchEntity(String id, String rootRunId, String requestKey, String planId,
                              String paramsHash, String taskIdsJson, Instant createdAt) {
        this.id = id;
        this.rootRunId = rootRunId;
        this.requestKey = requestKey;
        this.planId = planId;
        this.paramsHash = paramsHash;
        this.taskIdsJson = taskIdsJson;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getRootRunId() {
        return rootRunId;
    }

    public String getRequestKey() {
        return requestKey;
    }

    public String getPlanId() {
        return planId;
    }

    public String getParamsHash() {
        return paramsHash;
    }

    public String getTaskIdsJson() {
        return taskIdsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
