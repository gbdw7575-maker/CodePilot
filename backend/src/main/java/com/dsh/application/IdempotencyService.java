package com.dsh.application;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ApiError;
import com.dsh.api.dto.ErrorCode;
import com.dsh.common.Digest;
import com.dsh.common.IdGen;
import com.dsh.storage.IdempotencyRecordEntity;
import com.dsh.storage.IdempotencyRecordRepository;
import com.dsh.web.CachedBodyRequestWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等执行器（接口文档 §2.1）：
 * - 所有 POST 必须携带 Idempotency-Key（8~128），作用域 = 本地调用主体 + 方法 + 规范化路径；
 * - 规范请求摘要含请求体与 If-Match，不含 requestId 等诊断头；
 * - 相同键相同摘要重放首次持久化的状态与响应体；不同摘要 409 IDEMPOTENCY_CONFLICT；
 *   首请求未确定响应 409 IDEMPOTENCY_IN_PROGRESS + Retry-After；
 * - 幂等占位与资源写入、响应记录在同一事务中协调：首请求失败则整体回滚，重试重新执行。
 * 来源/头部检查已在 LocalAccessGuardFilter 先行完成。
 */
@Service
public class IdempotencyService {

    /** 本地单用户主体；远端访问不在本版本范围内。 */
    private static final String PRINCIPAL = "local";
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final int MAX_ATTEMPTS = 3;

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public IdempotencyService(IdempotencyRecordRepository repository, ObjectMapper mapper,
                              TransactionTemplate tx) {
        this.repository = repository;
        this.mapper = mapper;
        this.tx = tx;
    }

    /** 控制流内部标记：幂等占位唯一键竞争失败，外层换事务重试。 */
    private static final class Race extends RuntimeException {
    }

    public ResponseEntity<?> execute(String idempotencyKey, Supplier<ResponseEntity<?>> action) {
        if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key 必填且长度在 8~128 之间");
        }
        HttpServletRequest request = currentRequest();
        String scopeKey = Digest.sha256Hex(PRINCIPAL + "|" + request.getMethod() + "|" + request.getRequestURI());
        String ifMatch = request.getHeader("If-Match");
        String digest = Digest.sha256Hex(
                Base64.getEncoder().encodeToString(CachedBodyRequestWrapper.bodyOf(request))
                        + "|" + (ifMatch == null ? "" : ifMatch));

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> doExecute(scopeKey, idempotencyKey, digest, action));
            } catch (Race | DataAccessException | TransactionException e) {
                // 占位唯一键竞争或锁等待： winner 提交前无法判定，换事务重查重放
            }
        }
        return resolveAfterRace(scopeKey, idempotencyKey, digest);
    }

    private ResponseEntity<?> doExecute(String scopeKey, String key, String digest,
                                        Supplier<ResponseEntity<?>> action) {
        Instant now = Instant.now();
        Optional<IdempotencyRecordEntity> found = repository.findByScopeKeyAndIdempotencyKey(scopeKey, key);
        if (found.isPresent()) {
            IdempotencyRecordEntity record = found.get();
            if (record.getExpiresAt().isAfter(now)) {
                if (IdempotencyRecordEntity.STATUS_COMPLETED.equals(record.getStatus())) {
                    if (!record.getRequestDigest().equals(digest)) {
                        throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                                "相同 Idempotency-Key 的请求内容不一致");
                    }
                    return replay(record);
                }
                return inProgress();
            }
            repository.delete(record);
            repository.flush();
        }

        // saveAndFlush 走 merge 返回托管副本；必须改写返回值才能被脏检查 flush（String 主键 isNew 恒为 false）
        IdempotencyRecordEntity managed;
        try {
            managed = repository.saveAndFlush(new IdempotencyRecordEntity(
                    IdGen.next("idem_"), scopeKey, key, digest, now, now.plus(RETENTION)));
        } catch (DataAccessException e) {
            throw new Race();
        }

        ResponseEntity<?> result = action.get();
        managed.setStatus(IdempotencyRecordEntity.STATUS_COMPLETED);
        managed.setResponseStatus(result.getStatusCode().value());
        managed.setResponseEtag(result.getHeaders().getETag());
        managed.setResponseBody(serialize(result.getBody()));
        return result;
    }

    /** 竞争收敛：占位已存在则重放/报冲突，否则视为首请求结果未知。 */
    private ResponseEntity<?> resolveAfterRace(String scopeKey, String key, String digest) {
        return tx.execute(status -> {
            Optional<IdempotencyRecordEntity> found = repository.findByScopeKeyAndIdempotencyKey(scopeKey, key);
            if (found.isEmpty() || found.get().getExpiresAt().isBefore(Instant.now())) {
                return inProgress();
            }
            IdempotencyRecordEntity record = found.get();
            if (!IdempotencyRecordEntity.STATUS_COMPLETED.equals(record.getStatus())) {
                return inProgress();
            }
            if (!record.getRequestDigest().equals(digest)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "相同 Idempotency-Key 的请求内容不一致");
            }
            return replay(record);
        });
    }

    private ResponseEntity<?> replay(IdempotencyRecordEntity record) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(record.getResponseStatus())
                .contentType(MediaType.APPLICATION_JSON);
        if (record.getResponseEtag() != null) {
            builder.eTag(record.getResponseEtag());
        }
        return builder.body(record.getResponseBody().getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<?> inProgress() {
        ApiError body = ApiError.of(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                "请求仍在处理中，请使用相同 Idempotency-Key 重试",
                com.dsh.web.RequestIdFilter.currentRequestId(), true);
        return ResponseEntity.status(409)
                .header("Retry-After", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private String serialize(Object body) {
        if (body == null) {
            return "null";
        }
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化幂等响应", e);
        }
    }

    private static HttpServletRequest currentRequest() {
        return ((ServletRequestAttributes) (RequestContextHolder.currentRequestAttributes())).getRequest();
    }
}
