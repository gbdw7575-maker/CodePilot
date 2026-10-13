package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 统一事件流：sequence 为 Thread 内提交顺序（与消息序号是两套计数器），允许 BigInt 间隙。
 * envelopeJson 保存完整 EventEnvelope JSON（events.schema.json），重放只做忠实重发。
 */
@Entity
@Table(name = "events", uniqueConstraints = @UniqueConstraint(columnNames = {"thread_id", "sequence"}))
public class EventEntity {

    @Id
    private String id;

    @Column(name = "project_id", nullable = false, length = 128)
    private String projectId;

    @Column(name = "thread_id", nullable = false, length = 128)
    private String threadId;

    @Column(name = "turn_id", length = 128)
    private String turnId;

    @Column(name = "run_id", length = 128)
    private String runId;

    @Column(name = "parent_run_id", length = 128)
    private String parentRunId;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    @Column(name = "span_id", length = 128)
    private String spanId;

    @Column(nullable = false)
    private long sequence;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(name = "envelope_json", nullable = false, columnDefinition = "text")
    private String envelopeJson;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(name = "schema_version", nullable = false, length = 8)
    private String schemaVersion;

    protected EventEntity() {
    }

    public EventEntity(String id, String projectId, String threadId, String turnId, String runId,
                       String parentRunId, String traceId, String spanId, long sequence, String type,
                       String envelopeJson, Instant timestamp, String schemaVersion) {
        this.id = id;
        this.projectId = projectId;
        this.threadId = threadId;
        this.turnId = turnId;
        this.runId = runId;
        this.parentRunId = parentRunId;
        this.traceId = traceId;
        this.spanId = spanId;
        this.sequence = sequence;
        this.type = type;
        this.envelopeJson = envelopeJson;
        this.timestamp = timestamp;
        this.schemaVersion = schemaVersion;
    }

    public String getId() {
        return id;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getTurnId() {
        return turnId;
    }

    public String getRunId() {
        return runId;
    }

    public String getParentRunId() {
        return parentRunId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public long getSequence() {
        return sequence;
    }

    public String getType() {
        return type;
    }

    public String getEnvelopeJson() {
        return envelopeJson;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }
}
