package com.dsh.agent;

import com.dsh.agent.control.BudgetLedger;
import com.dsh.agent.control.CancellationTokenRegistry;
import com.dsh.agent.model.*;
import com.dsh.api.dto.Usage;
import com.dsh.common.IdGen;
import com.dsh.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class ModelBudgetAdmissionIntegrationTest {
    @Autowired AgentLoop loop;
    @Autowired LoopSteps steps;
    @Autowired BudgetLedger ledger;
    @Autowired CancellationTokenRegistry tokens;
    @Autowired RunRepository runs;
    @Autowired TurnRepository turns;
    @Autowired ThreadRepository threads;
    @Autowired ProjectRepository projects;
    @Autowired ModelProfileRepository profiles;
    @Autowired MessageRepository messages;
    @MockitoSpyBean FakeModelGateway gateway;
    @TempDir Path root;

    record Chain(String runId, String turnId, String threadId) {}

    @Autowired EventRepository events;
    private final com.fasterxml.jackson.databind.ObjectMapper observationMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> observationLog;
    @org.junit.jupiter.api.BeforeEach void captureObservations() {
        observationLog = new ch.qos.logback.core.read.ListAppender<>(); observationLog.start();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ModelContextObservation.class)).addAppender(observationLog);
    }
    @org.junit.jupiter.api.AfterEach void detachObservations() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ModelContextObservation.class)).detachAppender(observationLog);
        observationLog.stop();
    }
    private java.util.List<com.fasterxml.jackson.databind.JsonNode> observations(Chain c) {
        return observationLog.list.stream().map(e -> {
            try { return observationMapper.readTree(e.getFormattedMessage().substring("model-context ".length())); }
            catch (Exception badLog) { throw new AssertionError(badLog); }
        }).filter(n -> c.runId().equals(n.path("runId").asText())).toList();
    }
    private com.fasterxml.jackson.databind.JsonNode completedObservation(Chain c, String phase) {
        var rows = observations(c); assertEquals(2,rows.size());
        assertEquals("STARTED",rows.getFirst().path("phase").asText());
        var done = rows.getLast(); assertEquals(phase,done.path("phase").asText());
        assertEquals(rows.getFirst().path("spanId"),done.path("spanId"));
        assertFalse(done.path("spanId").asText().isEmpty());
        assertEquals(rows.getFirst().path("context"),done.path("context"));
        return done;
    }
    private void assertMetrics(LoopSteps.PreparedRequest prepared) {
        var m = prepared.contextMetrics(); assertNotNull(m);
        var request = prepared.request();
        assertEquals(request.estimatedInputTokens(),m.estimatedInputTokens());
        assertEquals(request.maxOutputTokens(),m.reservedOutputTokens());
        assertEquals(request.messages().size()-1,m.historyMessagesFinal());
        assertEquals(TokenEstimator.estimateMessages(request.messages().subList(1,request.messages().size())),m.historyRawTokensFinal());
        assertEquals(m.historyRawTokensFinal(),m.toolCallsRawTokensFinal()+m.toolResultsRawTokensFinal()+m.otherHistoryRawTokensFinal());
        assertEquals(TokenEstimator.calibrateInput(m.systemRawTokensFinal()+m.toolsRawTokensFinal()+m.historyRawTokensFinal()),m.estimatedInputTokens());
    }

    private Chain create(int maxTokens) {
        Instant now = Instant.now();
        String project = IdGen.next("proj_"); String profile = IdGen.next("prof_");
        projects.save(new ProjectEntity(project, "B03", root.toString(), true, now, now));
        profiles.save(new ModelProfileEntity(profile, "fake-b03", "fake", "fake-b03",
                "http://127.0.0.1:9", null, "disabled", null, 4096, false, "ACTIVE", now, now));
        String thread = IdGen.next("thr_"); String turn = IdGen.next("turn_"); String run = IdGen.next("run_");
        threads.save(new ThreadEntity(thread, project, "B03", profile, now, now));
        turns.save(new TurnEntity(turn, thread, run, "说明代码", "QUEUED", "PENDING", thread, now, now));
        RunEntity created = new RunEntity(run, turn, null, "MAIN", "QUEUED", "PENDING", 1,
                IdGen.next("ws_"), "b03", 12, 20, maxTokens, now.plusSeconds(60), now, now);
        created.setTraceId(IdGen.next("trace_")); runs.save(created);
        messages.save(new MessageEntity(IdGen.next("msg_"), thread, turn, "user", "说明代码", 1L, now));
        return new Chain(run, turn, thread);
    }
    private Usage known(int input, int output) {
        return new Usage("KNOWN", input, output, null, null, null, null, null);
    }
    private ModelResponse answer(Usage usage) {
        return new ModelResponse("完成", List.of(), "stop", usage, null);
    }
    private RunEntity run(Chain c) { return runs.findById(c.runId()).orElseThrow(); }
    private ModelRequest assemble(Chain c) { return steps.assemble(c.runId(), steps.snapshot(c.runId())); }
    private int cost(ModelRequest r) {
        return CumulativeBudgetAdmission.reservation(r.estimatedInputTokens(), r.maxOutputTokens());
    }
    private void add(Chain c, String role, String text, long sequence) {
        messages.save(new MessageEntity(IdGen.next("msg_"), c.threadId(), c.turnId(), role, text, sequence, Instant.now()));
    }

    @Test void artifactReplayKeepsExistingDefaultDisabled() {
        assertEquals(0,steps.executionSettings().get("loop.replayProtectedTailUnits"));
    }

    @Test void tinyBalanceNeverCallsProviderOrCreatesToolMessages() {
        Chain c = create(1); AtomicInteger calls = new AtomicInteger();
        doAnswer(i -> { calls.incrementAndGet(); return answer(known(10, 2)); }).when(gateway).generate(any(), any());
        loop.startRootRun(c.runId());
        assertEquals(0, calls.get()); assertEquals("BUDGET_LIMIT", run(c).getStopReason());
        assertTrue(observations(c).isEmpty(), "预约拒绝不得冒充已调用");
        assertEquals(0, run(c).getBudgetModelRequestsUsed());
        assertEquals(0, run(c).getBudgetReservedTokens());
        assertEquals(1, messages.listByThread(c.threadId()).size());
    }
    @Test void limitedOutputIsSentAndKnownUsageReleasesOnlyOwnReservation() {
        Chain c = create(200000); steps.begin(c.runId());
        add(c, "assistant_tool", """
                {"calls":[{"callId":"call_b03","name":"echo","arguments":"{}"}],"reasoningContent":"private_thinking"}
                """, 2);
        add(c, "tool", """
                {"callId":"call_b03","name":"echo","status":"SUCCEEDED","summary":"done","content":[]}
                """, 3);
        // 保持同一软提醒档位，先组装后冻结一个不足以容纳全部输出的余额。
        RunEntity r = run(c); r.setBudgetSettledTokens(180000); runs.save(r);
        ModelRequest full = assemble(c);
        int available = full.estimatedInputTokens() + (int) CumulativeBudgetAdmission.headroom(full.estimatedInputTokens()) + 500;
        r = run(c); r.setBudgetReservedTokens(20000 - available); runs.save(r);
        int childReservation = r.getBudgetReservedTokens();
        AtomicReference<ModelRequest> sent = new AtomicReference<>();
        doAnswer(i -> {
            sent.set(i.getArgument(0));
            assertEquals(childReservation + cost(sent.get()), run(c).getBudgetReservedTokens());
            assertEquals(1, run(c).getBudgetModelRequestsUsed());
            return answer(known(10, 2));
        }).when(gateway).generate(any(), any());
        loop.resumeRootRun(c.runId());
        assertNotNull(sent.get()); assertEquals(500, sent.get().maxOutputTokens());
        var observed=completedObservation(c,"RESPONSE_RECEIVED");
        assertEquals(500,observed.path("context").path("reservedOutputTokens").asInt());
        assertEquals(sent.get().estimatedInputTokens(),observed.path("context").path("estimatedInputTokens").asInt());
        assertFalse(observed.toString().contains("private_thinking"));
        assertEquals(full.messages(), sent.get().messages());
        assertTrue(sent.get().messages().stream().anyMatch(m -> "private_thinking".equals(m.reasoningContent())));
        assertEquals(1, sent.get().messages().stream().filter(m -> "tool".equals(m.role())).count());
        assertEquals(full.prefixHash(), sent.get().prefixHash());
        assertEquals(full.baseUrl(), sent.get().baseUrl());
        assertEquals(full.streaming(), sent.get().streaming());
        assertEquals(180012, run(c).getBudgetSettledTokens());
        assertEquals(childReservation, run(c).getBudgetReservedTokens());
        assertEquals(1, run(c).getBudgetModelRequestsUsed());
    }
    @Test void cumulativePressureCompactsOldHistoryEvenBelowContextWindow() {
        Chain c = create(200000); steps.begin(c.runId());
        add(c, "assistant", "先前日志" + "x".repeat(40000), 2);
        add(c, "user", "当前目标逐字保留", 3);
        RunEntity r = run(c); r.setBudgetSettledTokens(170000); runs.save(r);
        ModelRequest before = assemble(c);
        r = run(c); r.setBudgetReservedTokens(20000); runs.save(r);
        var prepared = steps.prepare(c.runId(),steps.snapshot(c.runId()));
        assertMetrics(prepared); assertTrue(prepared.contextMetrics().cumulativePressure());
        ModelRequest compacted = prepared.request();
        assertTrue(compacted.estimatedInputTokens() < before.estimatedInputTokens());
        assertTrue(compacted.messages().stream().anyMatch(m -> "当前目标逐字保留".equals(m.content())));
        assertTrue(cost(compacted) <= 10000);
        assertEquals(3, messages.listByThread(c.threadId()).size(), "原始转录不能被准入压缩改写");
    }
    @Test void protectedCurrentUserCannotBeDroppedToMakeBudgetFit() {
        Chain c = create(200000); steps.begin(c.runId());
        add(c, "user", "不可丢弃".repeat(10000), 2);
        RunEntity r = run(c); r.setBudgetSettledTokens(190000); runs.save(r);
        assertThrows(BudgetAdmissionException.class, () -> assemble(c));
        assertEquals("不可丢弃".repeat(10000), messages.listByThread(c.threadId()).getLast().getContent());
    }
    @Test void unknownUsageIsChargedConservativelyWithoutInventingActualUsage() {
        Chain c = create(200000); AtomicReference<ModelRequest> sent = new AtomicReference<>();
        doAnswer(i -> { sent.set(i.getArgument(0)); return answer(Usage.unknown()); }).when(gateway).generate(any(), any());
        loop.startRootRun(c.runId());
        assertEquals(cost(sent.get()), run(c).getBudgetSettledTokens());
        assertEquals(0, run(c).getBudgetReservedTokens()); assertEquals(1, run(c).getBudgetModelRequestsUsed());
        assertEquals("UNKNOWN", run(c).getUsageStatus()); assertNull(run(c).getUsageInputTokens());
        var observation=completedObservation(c,"RESPONSE_RECEIVED");
        assertEquals("UNKNOWN",observation.path("usage").path("status").asText());
        assertTrue(observation.path("usage").path("inputTokens").isNull());
    }
    @Test void providerFailureKeepsRequestChargeAndDoesNotLeakReservation() {
        Chain c = create(200000); AtomicReference<ModelRequest> sent = new AtomicReference<>();
        doAnswer(i -> { sent.set(i.getArgument(0)); throw new ModelGatewayException("fake failure"); }).when(gateway).generate(any(), any());
        loop.startRootRun(c.runId());
        assertEquals("PROVIDER_ERROR", run(c).getStopReason());
        assertTrue(completedObservation(c,"CALL_FAILED").path("usage").path("inputTokens").isNull());
        assertEquals(cost(sent.get()), run(c).getBudgetSettledTokens());
        assertEquals(0, run(c).getBudgetReservedTokens()); assertEquals(1, run(c).getBudgetModelRequestsUsed());
    }
    @Test void cancellationAfterResponseSettlesKnownUsageWithoutSavingAnswer() {
        Chain c = create(200000);
        doAnswer(i -> { tokens.get(c.runId()).cancel(); return answer(known(10, 2)); }).when(gateway).generate(any(), any());
        loop.startRootRun(c.runId());
        assertEquals("CANCELLED", run(c).getState());
        assertEquals(10,completedObservation(c,"RESPONSE_RECEIVED").path("usage").path("inputTokens").asInt());
        assertTrue(events.findByRunId(c.runId()).stream().noneMatch(e->"model.completed".equals(e.getType())));
        assertEquals(12, run(c).getBudgetSettledTokens()); assertEquals(0, run(c).getBudgetReservedTokens());
        assertEquals(1, messages.listByThread(c.threadId()).size());
    }
    @Test void estimatorUnderstatesActualInputStillCannotFinishSuccessfullyOverCap() {
        Chain c = create(200000);
        doAnswer(i -> answer(known(200001, 1))).when(gateway).generate(any(), any());
        loop.startRootRun(c.runId());
        assertEquals("FAILED", run(c).getState()); assertEquals("BUDGET_LIMIT", run(c).getStopReason());
        assertEquals(200002, run(c).getBudgetSettledTokens()); assertEquals(0, run(c).getBudgetReservedTokens());
    }
    @Test void mixedKnownAndUnknownCallsKeepWholeRunUsageUnknownInBothOrders() {
        for (boolean unknownFirst : new boolean[]{true, false}) {
            Chain c = create(200000); steps.begin(c.runId());
            assertTrue(ledger.reserveModelCall(c.runId(), 1000));
            steps.settleAbortedModelCall(c.runId(), 1000, unknownFirst ? Usage.unknown() : known(10, 2));
            assertTrue(ledger.reserveModelCall(c.runId(), 1000));
            steps.settleAbortedModelCall(c.runId(), 1000, unknownFirst ? known(10, 2) : Usage.unknown());
            assertEquals(1012, run(c).getBudgetSettledTokens());
            assertEquals(0, run(c).getBudgetReservedTokens());
            assertEquals(2, run(c).getBudgetModelRequestsUsed());
            assertEquals(Usage.unknown(), com.dsh.application.TurnService.toRunDto(run(c)).usage());
        }
    }

    @Test void windowPressureIsMeasuredBeforeFinalCompaction() {
        Chain c = create(200000); steps.begin(c.runId());
        for (int i=0;i<6;i++) add(c,"assistant","OLD_LOG"+"x".repeat(90000),2+i);
        add(c,"user","当前目标",8);
        var prepared=steps.prepare(c.runId(),steps.snapshot(c.runId())); assertMetrics(prepared);
        assertTrue(prepared.contextMetrics().windowPressure());
        assertFalse(prepared.contextMetrics().watermarkPressure());
        assertTrue(prepared.contextMetrics().historyRawTokensFinal()<prepared.contextMetrics().historyRawTokensAfterReplay());
        assertEquals(8,messages.listByThread(c.threadId()).size());
    }
    @Test void watermarkFlagDoesNotPretendCompressionSucceeded() {
        Chain c = create(200000); steps.begin(c.runId());
        RunEntity run=run(c);run.setBudgetSettledTokens(145000);runs.save(run);
        org.springframework.test.util.ReflectionTestUtils.setField(steps,"compactionMode",LoopSteps.CompactionMode.BUDGET_WATERMARK);
        try {
            var prepared=steps.prepare(c.runId(),steps.snapshot(c.runId()));assertMetrics(prepared);
            assertTrue(prepared.contextMetrics().watermarkPressure());
            assertEquals(prepared.contextMetrics().historyRawTokensAfterReplay(),prepared.contextMetrics().historyRawTokensFinal());
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(steps,"compactionMode",LoopSteps.CompactionMode.WINDOW_LASTUSER);}
    }
    @Test void multipleModelRoundsHaveDistinctSpansAndMatchCommittedUsage() throws Exception {
        Chain c=create(200000);AtomicInteger index=new AtomicInteger();
        doAnswer(i->index.getAndIncrement()==0
                ?new ModelResponse("",List.of(new ModelToolCall("bad","not_a_tool","{}")),"tool_calls",known(10,2),null)
                :answer(known(20,3))).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId());var rows=observations(c);assertEquals(4,rows.size());
        var completed=rows.stream().filter(n->"RESPONSE_RECEIVED".equals(n.path("phase").asText())).toList();assertEquals(2,completed.size());
        assertNotEquals(completed.get(0).path("spanId"),completed.get(1).path("spanId"));
        for(var e:events.findByRunId(c.runId()).stream().filter(e->"model.completed".equals(e.getType())).toList()){
            var row=completed.stream().filter(n->e.getSpanId().equals(n.path("spanId").asText())).findFirst().orElseThrow();
            var payload=observationMapper.readTree(e.getEnvelopeJson()).path("payload");
            assertEquals(payload.path("usage").path("inputTokens"),row.path("usage").path("inputTokens"));
            assertEquals(e.getTraceId(),row.path("traceId").asText());
        }
        assertEquals(2,run(c).getBudgetModelRequestsUsed());assertEquals(35,run(c).getBudgetSettledTokens());
    }
}
