package com.dsh.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dsh.agent.model.*;
import com.dsh.api.dto.Usage;
import com.dsh.application.trace.TraceContext;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelContextObservationTest {
    ObjectMapper mapper = new ObjectMapper();
    TraceContext trace = new TraceContext();
    ModelContextObservation observer = new ModelContextObservation(trace, mapper);
    Logger logger = (Logger) LoggerFactory.getLogger(ModelContextObservation.class);
    ListAppender<ILoggingEvent> appender;
    @BeforeEach void capture() { appender = new ListAppender<>(); appender.start(); logger.addAppender(appender); }
    @AfterEach void close() { logger.detachAppender(appender); appender.stop(); trace.clear(); }
    JsonNode row(int i) throws Exception { return mapper.readTree(appender.list.get(i).getFormattedMessage().substring("model-context ".length())); }

    @Test void stageAccountingIncludesCallsAndOnlyFinalSelectedView() {
        var call = ModelMessage.assistantToolCalls(List.of(new ModelToolCall("call","tool","{\"x\":\"long argument\"}")), "PRIVATE_REASONING");
        var original = List.of(ModelMessage.user("USER_SECRET"),call,ModelMessage.toolResult("call","tool","x".repeat(8000)));
        var projected = List.of(original.get(0),call,ModelMessage.toolResult("call","tool","FAILED artifact reference"));
        var replay = ContextProjectionMetrics.ReplayStage.capture(original,projected);
        var selected = List.of(ModelMessage.system("SYSTEM_SECRET"),original.get(0),call,projected.get(2));
        int raw = TokenEstimator.estimateMessages(selected) + 11;
        var metrics = ContextProjectionMetrics.finish(replay,5,"WINDOW_LASTUSER",selected,11,TokenEstimator.calibrateInput(raw),64,false,false,true);
        assertEquals(1,metrics.replayFoldedResults());
        assertTrue(metrics.historyRawTokensAfterReplay() < metrics.historyRawTokensBeforeReplay());
        assertEquals(metrics.historyRawTokensFinal(),metrics.toolCallsRawTokensFinal()+metrics.toolResultsRawTokensFinal()+metrics.otherHistoryRawTokensFinal());
        assertEquals(TokenEstimator.calibrateInput(metrics.systemRawTokensFinal()+metrics.toolsRawTokensFinal()+metrics.historyRawTokensFinal()),metrics.estimatedInputTokens());
        String json = assertDoesNotThrow(() -> mapper.writeValueAsString(metrics));
        for (String secret : List.of("PRIVATE_REASONING","USER_SECRET","SYSTEM_SECRET","long argument","FAILED artifact reference")) assertFalse(json.contains(secret));
    }
    @Test void offAndMissingDiagnosticsAreNotInventedAsSavings() throws Exception {
        var before = List.of(ModelMessage.user("unchanged"));
        assertEquals(0,ContextProjectionMetrics.ReplayStage.capture(before,before).foldedResults());
        var invocation=observer.started("run",null); observer.finished(invocation,null,true);
        assertTrue(row(0).path("context").isNull()); assertEquals("RESPONSE_RECEIVED",row(1).path("phase").asText());
        assertTrue(row(1).path("usage").path("inputTokens").isNull());
    }
    @Test void sameSpanKnownUsageOnlyLogsWhitelistedNumbers() throws Exception {
        try (var run = trace.enterRun(null); var model = trace.startChildSpan()) {
            var invocation=observer.started("run",null);
            observer.finished(invocation,new Usage("KNOWN",101,7,80,21,"COST_SECRET","CURRENCY_SECRET","PRICE_SECRET"),true);
            assertEquals(trace.spanId(),row(0).path("spanId").asText());
            assertEquals(row(0).path("spanId"),row(1).path("spanId"));
            assertEquals(101,row(1).path("usage").path("inputTokens").asInt());
            assertEquals(80,row(1).path("usage").path("cacheHitTokens").asInt());
            assertTrue(row(1).path("durationMs").asInt()>=0);
            assertTrue(row(0).path("usage").isNull());
            assertFalse(appender.list.toString().contains("COST_SECRET"));
            assertFalse(row(1).path("usage").has("cost"));
        }
    }
    @Test void unknownCountsStayNullEvenIfUntrustedObjectHasNumbers() throws Exception {
        var invocation=observer.started("run",null);
        observer.finished(invocation,new Usage("UNKNOWN",1,2,3,4,null,null,null),true);
        JsonNode usage=row(1).path("usage");
        assertEquals("UNKNOWN",usage.path("status").asText());
        for(String key:List.of("inputTokens","outputTokens","cacheHitTokens","cacheMissTokens")) assertTrue(usage.path(key).isNull());
    }
    @Test void failedCallRecordsUnknownUsageWithoutExceptionText() throws Exception {
        var invocation=observer.started("run",null);observer.finished(invocation,null,false);
        assertEquals("CALL_FAILED",row(1).path("phase").asText());
        assertEquals("UNKNOWN",row(1).path("usage").path("status").asText());
    }
    @Test void nestedAndSeparateInvocationsKeepCapturedSpan() throws Exception {
        String first;
        try(var run=trace.enterRun(null);var model=trace.startChildSpan()){
            var invocation=observer.started("run1",null);first=trace.spanId();
            try(var tool=trace.startChildSpan()){observer.finished(invocation,Usage.unknown(),true);}
        }
        try(var run=trace.enterRun(null);var model=trace.startChildSpan()){
            var invocation=observer.started("run2",null);observer.finished(invocation,Usage.unknown(),true);
        }
        assertEquals(first,row(1).path("spanId").asText());assertNotEquals(row(1).path("spanId"),row(3).path("spanId"));
        assertNotEquals(row(1).path("traceId"),row(3).path("traceId"));
    }
    @Test void serializationFailureDoesNotEscapeIntoProviderFlow() throws Exception {
        var broken=mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new IllegalStateException("SECRET_ERROR"));
        var safe=new ModelContextObservation(trace,broken);
        assertDoesNotThrow(()->{var invocation=safe.started("run",null);safe.finished(invocation,null,false);});
        assertFalse(appender.list.stream().anyMatch(e->e.getFormattedMessage().contains("SECRET_ERROR")));
    }
}
