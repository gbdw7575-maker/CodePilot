package com.dsh.api;

import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.SubmitTurn;
import com.dsh.application.IdempotencyService;
import com.dsh.application.TurnService;
import com.dsh.common.Digest;
import com.dsh.storage.IdempotencyRecordEntity;
import com.dsh.storage.IdempotencyRecordRepository;
import com.dsh.storage.RunRepository;
import com.dsh.storage.TurnRepository;
import com.dsh.web.CachedBodyRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等并发事务（接口文档 §2.1）：相同键并发提交只产生一个 Run；
 * 不同键并发提交受活动轮次约束；占位未决时返回 409 + Retry-After。
 */
class TurnConcurrencyTest extends ApiTestBase {

    @Autowired
    private IdempotencyService idempotency;

    @Autowired
    private TurnService turnService;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private TurnRepository turnRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    /** 单次提交结果：要么是响应，要么是未经控制器包装的业务异常。 */
    private record Outcome(ResponseEntity<?> response, ErrorCode error) {
    }

    /** 响应体可能是重放的 byte[]，也可能是首请求的 DTO。 */
    private java.util.Map<?, ?> asMap(Object body) throws java.io.IOException {
        if (body instanceof byte[] bytes) {
            return mapper.readValue(bytes, java.util.Map.class);
        }
        return mapper.convertValue(body, java.util.Map.class);
    }

    /** 绕过 MockMvc 直接调用幂等执行器，保证并发时每个线程拥有独立的请求上下文。 */
    private Outcome callSubmit(String threadId, String idempotencyKey, String input) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/threads/" + threadId + "/turns");
        byte[] body = ("{\"input\":\"" + input + "\"}").getBytes(StandardCharsets.UTF_8);
        request.setContent(body);
        request.setContentType("application/json");
        try {
            CachedBodyRequestWrapper wrapper;
            try {
                wrapper = new CachedBodyRequestWrapper(request, 1024L * 1024);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("构造请求包装失败", e);
            }
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(wrapper));
            ResponseEntity<?> response = idempotency.execute(idempotencyKey, () ->
                    ResponseEntity.status(HttpStatus.ACCEPTED)
                            .body(turnService.submit(threadId, new SubmitTurn(input))));
            return new Outcome(response, null);
        } catch (com.dsh.api.ApiException e) {
            return new Outcome(null, e.code());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void concurrentSameKeyCreatesExactlyOneRun() throws Exception {
        String threadId = bootstrapThread();
        long runsBefore = runRepository.count();
        long turnsBefore = turnRepository.count();
        String key = uniqueKey("conc");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> callSubmit(threadId, key, "并发提交")));
            }
            Set<String> turnIds = new HashSet<>();
            for (Future<Outcome> future : futures) {
                Outcome outcome = future.get(30, TimeUnit.SECONDS);
                assertNotNull(outcome.response(), "相同键并发提交不允许出现业务异常: " + outcome.error());
                assertEquals(HttpStatus.ACCEPTED, outcome.response().getStatusCode(), "重放必须返回首次的 202");
                String turnId = String.valueOf(asMap(outcome.response().getBody()).get("turnId"));
                turnIds.add(turnId);
            }
            assertEquals(1, turnIds.size(), "相同键并发提交必须收敛到同一个 Turn");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(turnsBefore + 1, turnRepository.count(), "相同键并发提交只能创建一个 Turn");
        assertEquals(runsBefore + 1, runRepository.count(), "相同键并发提交只能创建一个 Run");
    }

    @Test
    void concurrentDifferentKeysCreateOnlyOneActiveTurn() throws Exception {
        String threadId = bootstrapThread();
        long runsBefore = runRepository.count();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Outcome> first = () -> callSubmit(threadId, uniqueKey("r1"), "先到");
            Callable<Outcome> second = () -> callSubmit(threadId, uniqueKey("r2"), "后到");
            Future<Outcome> f1 = pool.submit(first);
            Future<Outcome> f2 = pool.submit(second);
            Outcome o1 = f1.get(30, TimeUnit.SECONDS);
            Outcome o2 = f2.get(30, TimeUnit.SECONDS);

            boolean firstAccepted = o1.response() != null;
            boolean secondAccepted = o2.response() != null;
            assertTrue(firstAccepted ^ secondAccepted, "恰好一个请求应被接受");
            Outcome rejected = firstAccepted ? o2 : o1;
            assertEquals(ErrorCode.ACTIVE_TURN_EXISTS, rejected.error(),
                    "被拒请求必须是 ACTIVE_TURN_EXISTS");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(runsBefore + 1, runRepository.count(), "同会话并发只能创建一个 Run");
    }

    @Test
    void unresolvedPlaceholderReturns409WithRetryAfter() throws Exception {
        String threadId = bootstrapThread();
        String key = uniqueKey("progress");
        String body = "{\"input\":\"处理中\"}";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        // 直接落一条 IN_PROGRESS 占位，模拟首请求尚未确定响应
        String scopeKey = Digest.sha256Hex("local|POST|/api/v1/threads/" + threadId + "/turns");
        String digest = Digest.sha256Hex(Base64.getEncoder().encodeToString(bodyBytes) + "|");
        idempotencyRecordRepository.saveAndFlush(new IdempotencyRecordEntity(
                "idem_test_" + key, scopeKey, key, digest, Instant.now(), Instant.now().plusSeconds(3600)));

        Outcome outcome = callSubmit(threadId, key, "处理中");
        assertNotNull(outcome.response(), "占位未决应返回 409 响应而非异常");
        assertEquals(HttpStatus.CONFLICT, outcome.response().getStatusCode());
        assertEquals("1", outcome.response().getHeaders().getFirst("Retry-After"),
                "IN_PROGRESS 必须携带 Retry-After");
        assertNotNull(outcome.response().getBody());
        assertEquals(ErrorCode.IDEMPOTENCY_IN_PROGRESS.name(),
                mapper.convertValue(outcome.response().getBody(), java.util.Map.class).get("code"));
    }
}
