package com.dsh.application.trace;

import com.dsh.common.IdGen;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 本地链路上下文（DSH-003）：通过 ThreadLocal + MDC 管理 traceId/spanId 的生成与传播。
 *
 * <p>模型：每个 Run 持有一个 traceId（根 Run 新建，子 Run 继承父 Run 的 traceId）；
 * Run 根 span 为该 trace 的起点；模型请求、工具调用、审批、验证等边界在根 span 下
 * 创建子 span，形成父子链路。重试/恢复复用同一 traceId，新建 span。</p>
 *
 * <p>并发安全：单线程内通过栈式 SpanScope 保证嵌套正确；不同 Run 运行在不同线程，
 * 互不串扰。close 时恢复进入前的上下文，避免线程复用导致的链路泄漏。</p>
 */
@Component
public class TraceContext {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";

    private static final ThreadLocal<SpanState> CURRENT = new ThreadLocal<>();

    /** 当前 traceId；无上下文返回 null。 */
    public String traceId() {
        SpanState s = CURRENT.get();
        return s == null ? null : s.traceId();
    }

    /** 当前 spanId；无上下文返回 null。 */
    public String spanId() {
        SpanState s = CURRENT.get();
        return s == null ? null : s.spanId();
    }

    /**
     * 进入一个 Run 的根 span。若 traceId 为 null 则新建。
     * 返回的 SpanScope 在 close 时恢复进入前的上下文。
     */
    public SpanScope enterRun(String traceId) {
        String actualTraceId = traceId == null ? IdGen.next("trace_") : traceId;
        String spanId = IdGen.next("span_");
        return push(new SpanState(actualTraceId, spanId));
    }

    /**
     * 在当前 span 下创建子 span（traceId 不变，spanId 新建）。
     * 若当前无上下文，则退化为新建 trace 的根 span。
     */
    public SpanScope startChildSpan() {
        SpanState current = CURRENT.get();
        if (current == null) {
            return enterRun(null);
        }
        String spanId = IdGen.next("span_");
        return push(new SpanState(current.traceId(), spanId));
    }

    /** 清理当前线程的 trace 上下文（兜底）。 */
    public void clear() {
        CURRENT.remove();
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_SPAN_ID);
    }

    private SpanScope push(SpanState next) {
        SpanState previous = CURRENT.get();
        CURRENT.set(next);
        MDC.put(MDC_TRACE_ID, next.traceId());
        MDC.put(MDC_SPAN_ID, next.spanId());
        return new SpanScope(previous);
    }

    private record SpanState(String traceId, String spanId) {
    }

    /** 栈式作用域：close 时恢复进入前的 span 状态。 */
    public final class SpanScope implements AutoCloseable {
        private final SpanState previous;
        private boolean closed;

        private SpanScope(SpanState previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
                MDC.remove(MDC_TRACE_ID);
                MDC.remove(MDC_SPAN_ID);
            } else {
                CURRENT.set(previous);
                MDC.put(MDC_TRACE_ID, previous.traceId());
                MDC.put(MDC_SPAN_ID, previous.spanId());
            }
        }
    }
}
