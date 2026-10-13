package com.dsh.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * L5 情景记忆条目（S2 新增）：单个 Run 终态后摘要入库，按 projectId 隔离。
 * keywords 为分词后去停用词的关键词列表（以空格分隔），用于 LIKE 召回。
 * decayWeight 由 createdAt 与当前时间差按 {@code decayPerDay} 衰减得出，近期事件权重高。
 * <p>
 * 不存原始事件全文（事件本身在 L1 events 表已 append-only 持久化），仅存摘要与召回所需元数据。
 */
@Entity
@Table(name = "episodic_memory", indexes = {
        @Index(name = "idx_episodic_project", columnList = "project_id"),
        @Index(name = "idx_episodic_run", columnList = "source_run_id")
})
public class EpisodicMemoryEntry {

    @Id
    private String id;

    @Column(name = "project_id", nullable = false, length = 128)
    private String projectId;

    @Column(name = "source_run_id", nullable = false, length = 128)
    private String sourceRunId;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    @Column(name = "summary_text", nullable = false, length = 4096)
    private String summaryText;

    /** 空格分隔的关键词，便于 LIKE %word% 召回；CJK 按二元组分词。 */
    @Column(name = "keywords", nullable = false, length = 2048)
    private String keywords;

    @Column(name = "final_state", nullable = false, length = 32)
    private String finalState;

    @Column(name = "verification_status", length = 32)
    private String verificationStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 入库时计算好的衰减权重，召回时重算以反映真实老化。 */
    @Column(name = "decay_weight", nullable = false)
    private double decayWeight;

    protected EpisodicMemoryEntry() {
    }

    public EpisodicMemoryEntry(String id, String projectId, String sourceRunId, String traceId,
                               String summaryText, String keywords, String finalState,
                               String verificationStatus, Instant createdAt, double decayWeight) {
        this.id = id;
        this.projectId = projectId;
        this.sourceRunId = sourceRunId;
        this.traceId = traceId;
        this.summaryText = summaryText;
        this.keywords = keywords;
        this.finalState = finalState;
        this.verificationStatus = verificationStatus;
        this.createdAt = createdAt;
        this.decayWeight = decayWeight;
    }

    public String getId() {
        return id;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getSourceRunId() {
        return sourceRunId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public String getKeywords() {
        return keywords;
    }

    public String getFinalState() {
        return finalState;
    }

    public String getVerificationStatus() {
        return verificationStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public double getDecayWeight() {
        return decayWeight;
    }
}
