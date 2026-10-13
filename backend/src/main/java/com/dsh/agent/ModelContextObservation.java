package com.dsh.agent;

import com.dsh.api.dto.Usage;
import com.dsh.application.trace.TraceContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Logs bounded numeric observations, never ModelRequest/response text or exception messages. */
@Component
public class ModelContextObservation {
    private static final Logger log = LoggerFactory.getLogger(ModelContextObservation.class);
    private final TraceContext trace;
    private final ObjectMapper mapper;

    public ModelContextObservation(TraceContext trace, ObjectMapper mapper) {
        this.trace = trace; this.mapper = mapper;
    }

    record Invocation(String runId, String traceId, String spanId, ContextProjectionMetrics context, long startedNanos) {}
    record SupplierUsage(String status, Integer inputTokens, Integer outputTokens, Integer cacheHitTokens, Integer cacheMissTokens) {
        static SupplierUsage from(Usage usage) {
            if (usage == null || !"KNOWN".equals(usage.status()))
                return new SupplierUsage("UNKNOWN", null, null, null, null);
            return new SupplierUsage("KNOWN", usage.inputTokens(), usage.outputTokens(), usage.cacheHitTokens(), usage.cacheMissTokens());
        }
    }
    record Observation(int version, String phase, String runId, String traceId, String spanId,
                       ContextProjectionMetrics context, Integer durationMs, SupplierUsage usage) {}

    Invocation started(String runId, ContextProjectionMetrics context) {
        var invocation = new Invocation(runId, trace.traceId(), trace.spanId(), context, System.nanoTime());
        write(new Observation(1, "STARTED", runId, invocation.traceId(), invocation.spanId(), context, null, null));
        return invocation;
    }

    void finished(Invocation invocation, Usage usage, boolean responseReceived) {
        int duration = (int) Math.min(Integer.MAX_VALUE, Math.max(0,
                (System.nanoTime() - invocation.startedNanos()) / 1_000_000L));
        write(new Observation(1, responseReceived ? "RESPONSE_RECEIVED" : "CALL_FAILED",
                invocation.runId(), invocation.traceId(), invocation.spanId(), invocation.context(),
                duration, SupplierUsage.from(usage)));
    }

    private void write(Observation observation) {
        try {
            log.info("model-context {}", mapper.writeValueAsString(observation));
        } catch (JsonProcessingException | RuntimeException unavailable) {
            // Diagnostics must not change provider calls or accounting. No exception text is logged.
            log.debug("model-context observation unavailable");
        }
    }
}
