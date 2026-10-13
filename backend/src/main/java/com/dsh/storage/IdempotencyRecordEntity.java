package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 幂等记录：作用域 = 本地调用主体 + HTTP 方法 + 规范化路径（scopeKey 为其哈希）。
 * 保存期至少 24 小时；COMPLETED 后可重放首次持久化的状态与响应体。
 */
@Entity
@Table(name = "idempotency_records",
        uniqueConstraints = @UniqueConstraint(columnNames = {"scope_key", "idempotency_key"}))
public class IdempotencyRecordEntity {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";

    @Id
    private String id;

    @Column(nullable = false, length = 64)
    private String scopeKey;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String requestDigest;

    @Column(nullable = false, length = 16)
    private String status;

    private Integer responseStatus;

    @Column(length = 256)
    private String responseEtag;

    @Column(length = 200000)
    private String responseBody;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecordEntity() {
    }

    public IdempotencyRecordEntity(String id, String scopeKey, String idempotencyKey, String requestDigest,
                                   Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.scopeKey = scopeKey;
        this.idempotencyKey = idempotencyKey;
        this.requestDigest = requestDigest;
        this.status = STATUS_IN_PROGRESS;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getId() {
        return id;
    }

    public String getScopeKey() {
        return scopeKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestDigest() {
        return requestDigest;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getResponseEtag() {
        return responseEtag;
    }

    public void setResponseEtag(String responseEtag) {
        this.responseEtag = responseEtag;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
