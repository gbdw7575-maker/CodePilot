package com.dsh.application.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TraceContext 单元测试（DSH-003）：验证 traceId/spanId 的生成、父子传播、
 * 栈式恢复与并发隔离。
 */
class TraceContextTest {

    private final TraceContext ctx = new TraceContext();

    @AfterEach
    void tearDown() {
        ctx.clear();
    }

    @Test
    void enterRunGeneratesTraceAndSpanWhenTraceIdNull() {
        assertNull(ctx.traceId());
        assertNull(ctx.spanId());
        try (TraceContext.SpanScope scope = ctx.enterRun(null)) {
            assertNotNull(ctx.traceId());
            assertNotNull(ctx.spanId());
            assertTrue(ctx.traceId().startsWith("trace_"));
            assertTrue(ctx.spanId().startsWith("span_"));
        }
        assertNull(ctx.traceId());
        assertNull(ctx.spanId());
    }

    @Test
    void enterRunUsesProvidedTraceId() {
        String traceId = "trace_fixed";
        try (TraceContext.SpanScope scope = ctx.enterRun(traceId)) {
            assertEquals(traceId, ctx.traceId());
            assertNotNull(ctx.spanId());
        }
        assertNull(ctx.traceId());
    }

    @Test
    void childSpanInheritsTraceIdAndGetsNewSpanId() {
        try (TraceContext.SpanScope root = ctx.enterRun("trace_root")) {
            String rootSpan = ctx.spanId();
            try (TraceContext.SpanScope child = ctx.startChildSpan()) {
                assertEquals("trace_root", ctx.traceId());
                assertNotEquals(rootSpan, ctx.spanId());
            }
            // 子 span 关闭后恢复根 span
            assertEquals(rootSpan, ctx.spanId());
            assertEquals("trace_root", ctx.traceId());
        }
    }

    @Test
    void nestedChildSpansMaintainStack() {
        try (TraceContext.SpanScope root = ctx.enterRun("trace_nested")) {
            String rootSpan = ctx.spanId();
            try (TraceContext.SpanScope l1 = ctx.startChildSpan()) {
                String l1Span = ctx.spanId();
                assertNotEquals(rootSpan, l1Span);
                try (TraceContext.SpanScope l2 = ctx.startChildSpan()) {
                    String l2Span = ctx.spanId();
                    assertNotEquals(l1Span, l2Span);
                    assertEquals("trace_nested", ctx.traceId());
                }
                assertEquals(l1Span, ctx.spanId());
            }
            assertEquals(rootSpan, ctx.spanId());
        }
    }

    @Test
    void startChildSpanWithoutContextCreatesNewTrace() {
        try (TraceContext.SpanScope scope = ctx.startChildSpan()) {
            assertNotNull(ctx.traceId());
            assertNotNull(ctx.spanId());
        }
        assertNull(ctx.traceId());
    }

    @Test
    void concurrentThreadsHaveIsolatedContexts() throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<String> t1Trace = new AtomicReference<>();
        AtomicReference<String> t2Trace = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();

        Runnable worker1 = () -> {
            try {
                start.await();
                try (TraceContext.SpanScope scope = ctx.enterRun(null)) {
                    t1Trace.set(ctx.traceId());
                    Thread.sleep(20);
                }
            } catch (Exception e) {
                error.set(e.getMessage());
            } finally {
                done.countDown();
            }
        };
        Runnable worker2 = () -> {
            try {
                start.await();
                try (TraceContext.SpanScope scope = ctx.enterRun(null)) {
                    t2Trace.set(ctx.traceId());
                    Thread.sleep(20);
                }
            } catch (Exception e) {
                error.set(e.getMessage());
            } finally {
                done.countDown();
            }
        };

        Thread th1 = new Thread(worker1);
        Thread th2 = new Thread(worker2);
        th1.start();
        th2.start();
        start.countDown();
        done.await();

        assertNull(error.get());
        assertNotNull(t1Trace.get());
        assertNotNull(t2Trace.get());
        assertNotEquals(t1Trace.get(), t2Trace.get());
        // 主线程上下文不受子线程影响
        assertNull(ctx.traceId());
    }
}
