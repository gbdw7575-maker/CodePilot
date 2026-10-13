package com.dsh.orchestration;

import com.dsh.common.IdGen;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.orchestration.plan.PlanService;
import com.dsh.orchestration.task.TaskAwaitService;
import com.dsh.orchestration.task.TaskDispatchService;
import com.dsh.orchestration.task.TaskSchedulingService;
import com.dsh.orchestration.task.TaskStates;
import com.dsh.storage.EventEntity;
import com.dsh.storage.EventRepository;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunRepository;
import com.dsh.storage.TaskAttemptEntity;
import com.dsh.storage.TaskAttemptRepository;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.TaskRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * MA-10 任务域 → 统一事件流桥接测试（规范 §11/§10.3，AT-17）。
 *
 * <p>验证：plan.updated / task.dispatched / task.state.changed / task.result.ready /
 * run.waiting_tasks / integration.completed 与领域状态同事务写入 EventStore；事件挂在根
 * Run（runId=root，thread 归属可解析）；重复 complete 幂等不重复发 result.ready；
 * 事件可经 REST /threads/{id}/events 补拉且按 runId 天然隔离。</p>
 *
 * <p>全部 service 层构造状态 + Fake 基础设施，不产生真实模型费用。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "dsh.policy.require-for-side-effects=NONE")
class Ma10TaskEventBridgeTest {

    @Autowired private PlanService planService;
    @Autowired private TaskDispatchService dispatchService;
    @Autowired private TaskSchedulingService schedulingService;
    @Autowired private TaskAwaitService awaitService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ModelProfileRepository profileRepository;
    @Autowired private ThreadRepository threadRepository;
    @Autowired private TurnRepository turnRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MockMvc mockMvc;

    @TempDir Path projectRoot;

    @Test
    void planSaveAndRevisionEmitsPlanUpdated() {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(task("A", "任务A", List.of())));

        List<JsonNode> first = payloads(chain.runId(), "plan.updated");
        assertEquals(1, first.size(), "首次落草案发 plan.updated");
        assertEquals(planId, first.get(0).path("planId").asText());
        assertEquals(1, first.get(0).path("revision").asInt());

        PlanDraft update = new PlanDraft(planId, 1, "修订目标",
                List.of(task("A", "任务A", List.of()), task("B", "任务B", List.of())));
        planService.saveDraft(chain.runId(), update);

        List<JsonNode> all = payloads(chain.runId(), "plan.updated");
        assertEquals(2, all.size(), "修订再发一条 plan.updated");
        assertEquals(2, all.get(1).path("revision").asInt());
        assertTrue(all.get(1).path("planId").asText().startsWith("plan_"));
    }

    @Test
    void dispatchEmitsTaskDispatched() {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(task("A", "任务A", List.of())));

        TaskDispatchService.DispatchResult result = dispatchService.register(
                chain.runId(), planId, 1, List.of("A"),
                "rk-ma10-dispatch-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        assertTrue(result.accepted(), "派发登记应被接受");

        List<JsonNode> dispatched = payloads(chain.runId(), "task.dispatched");
        assertEquals(1, dispatched.size(), "DRAFT→PENDING 发 task.dispatched");
        JsonNode p = dispatched.get(0);
        assertEquals(planId, p.path("planId").asText());
        assertEquals("A", p.path("taskId").asText());
        assertEquals(1, p.path("taskVersion").asInt());
        assertEquals(TaskStates.PENDING, p.path("state").asText());
        assertTrue(p.path("attemptId").isNull(), "登记时尚无 attempt");
        assertEnvelopeMeta(chain, dispatched);
    }

    @Test
    void completeEmitsStateChangedResultReadyAndIntegrationEvent() throws Exception {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(task("A", "任务A", List.of())));
        Instant now = Instant.now();
        TaskAttemptEntity attempt = completeSucceeded(planId, "A", "run_ma10_child_a", now);

        // 成功路径：RUNNING→VERIFYING（验收闸门）→SUCCEEDED 两条迁移
        List<JsonNode> changed = payloads(chain.runId(), "task.state.changed");
        assertEquals(2, changed.size(), "验收中间态与终态各一条 task.state.changed");
        assertEquals(TaskStates.VERIFYING, changed.get(0).path("to").asText());
        assertEquals(TaskStates.RUNNING, changed.get(0).path("from").asText());
        JsonNode stateChanged = changed.get(1);
        assertEquals("A", stateChanged.path("taskId").asText());
        assertEquals(TaskStates.SUCCEEDED, stateChanged.path("to").asText());
        assertEquals(TaskStates.VERIFYING, stateChanged.path("from").asText());
        assertEquals(attempt.getAttemptId(), stateChanged.path("attemptId").asText());
        assertEquals("run_ma10_child_a", stateChanged.path("childRunId").asText());
        assertNotNull(stateChanged.path("reasonCode").asText(null));

        JsonNode resultReady = sole(chain.runId(), "task.result.ready");
        assertEquals(TaskStates.SUCCEEDED, resultReady.path("state").asText());
        assertEquals(attempt.getAttemptId(), resultReady.path("attemptId").asText());
        assertEquals("run_ma10_child_a", resultReady.path("childRunId").asText());
        assertTrue(resultReady.path("artifactRefs").isArray());
        assertEquals("digest-A", resultReady.path("outputDigest").asText(),
                "payload 只带产物 digest 引用（测试 SubagentResult 原样落库；生产为 sha256）");
        assertEnvelopeMeta(chain, List.of(resultReady));

        // 无真实子 Run 写者 → 集成走"空产物 APPLIED"路径；maybeIntegrate 在独立线程执行
        JsonNode integrated = awaitEvent(chain.runId(), "integration.completed", 3000);
        assertEquals("APPLIED", integrated.path("state").asText());
        assertTrue(integrated.path("integrationId").asText().startsWith("intg_"));
    }

    @Test
    void duplicateCompleteDoesNotDuplicateResultReady() {
        Chain chain = buildChain();
        String planId = savePlan(chain, List.of(task("A", "任务A", List.of())));
        Instant now = Instant.now();
        TaskAttemptEntity attempt = completeSucceeded(planId, "A", "run_ma10_child_dup", now);

        // 崩溃补偿/迟到回调重投：attempt 已终态 → 幂等忽略，不重复落 result/事件
        SubagentResult replay = new SubagentResult(
                "run_ma10_child_dup", "CODER", "COMPLETED", null,
                "replay", List.of(), "patch-dup", "", "digest-dup", 0, 0, null, 0);
        schedulingService.complete(attempt.getAttemptId(), replay);

        assertEquals(1, payloads(chain.runId(), "task.result.ready").size(),
                "重复 complete 不得重复发 task.result.ready");
    }

    @Test
    void parkEmitsWaitingTasks() {
        Chain chain = buildChain();
        savePlan(chain, List.of(task("A", "任务A", List.of()), task("B", "任务B", List.of())));
        Instant waitUntil = Instant.now().plusSeconds(30);

        awaitService.park(chain.runId(), chain.turnId(), "call_ma10_wait",
                List.of("A", "B"), "ALL", 7L, "{}", waitUntil);

        JsonNode p = sole(chain.runId(), "run.waiting_tasks");
        assertEquals("ALL", p.path("waitMode").asText());
        assertEquals("7", p.path("cursor").asText());
        assertEquals(List.of("A", "B"), objectMapper.convertValue(
                p.path("taskIds"), objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, String.class)));
        assertNotNull(p.path("waitUntil").asText(null));
    }

    @Test
    void eventsIsolatedByRootRunAndRestBackfillComplete() throws Exception {
        Chain root1 = buildChain();
        savePlan(root1, List.of(task("A", "root1 任务", List.of())));
        Chain root2 = buildChain();
        savePlan(root2, List.of(task("X", "root2 任务", List.of())));

        // 统一事件流按 runId 隔离：历史根 Run 的任务事件不污染当前根 Run
        for (JsonNode p : payloads(root1.runId(), "plan.updated")) {
            assertEquals(root1.runId(), envelopeFor(root1.runId(), p).getRunId());
        }
        assertTrue(payloads(root2.runId(), "plan.updated").stream()
                .noneMatch(p -> root1.runId().equals(envelopeFor(root2.runId(), p).getRunId())));

        // REST 补拉：分页接口能取回桥接事件，sequence 升序、eventId 去重、归属正确
        MvcResult result = mockMvc.perform(get(
                        "/api/v1/threads/" + root1.threadId() + "/events")
                        .param("afterSequence", "0").param("limit", "200")
                        .header("X-DSH-Client", "local-ui"))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus());
        JsonNode page = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        List<JsonNode> items = objectMapper.convertValue(page.path("items"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, JsonNode.class));
        assertTrue(items.size() >= 1, "补拉至少包含 plan.updated");
        long lastSeq = 0L;
        java.util.Set<String> eventIds = new java.util.HashSet<>();
        boolean sawPlanUpdated = false;
        for (JsonNode item : items) {
            long seq = item.path("sequence").asLong();
            assertTrue(seq > lastSeq, "补拉事件按 sequence 升序");
            lastSeq = seq;
            assertTrue(eventIds.add(item.path("eventId").asText()), "eventId 唯一去重");
            assertEquals(root1.threadId(), item.path("threadId").asText());
            assertEquals(root1.runId(), item.path("runId").asText(),
                    "任务域事件归属在根 Run");
            if ("plan.updated".equals(item.path("type").asText())) {
                sawPlanUpdated = true;
            }
        }
        assertTrue(sawPlanUpdated, "REST 补拉包含桥接的 plan.updated");
    }

    // ------------------------------------------------------- helpers

    private JsonNode sole(String rootRunId, String type) {
        List<JsonNode> list = payloads(rootRunId, type);
        assertEquals(1, list.size(), "期望恰好一条 " + type + "，实际 " + list.size());
        return list.get(0);
    }

    private JsonNode awaitEvent(String rootRunId, String type, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<JsonNode> list = payloads(rootRunId, type);
            if (!list.isEmpty()) {
                return list.get(0);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待事件超时: " + type);
    }

    private List<JsonNode> payloads(String rootRunId, String type) {
        return eventRepository.findByRunId(rootRunId).stream()
                .filter(e -> type.equals(e.getType()))
                .map(this::envelopePayload)
                .toList();
    }

    /** payload 无 runId 字段；为断言归属，按 payload 内容反查 envelope（测试辅助）。 */
    private EventEntity envelopeFor(String rootRunId, JsonNode payload) {
        String needle = payload.toString();
        return eventRepository.findByRunId(rootRunId).stream()
                .filter(e -> envelopePayload(e).toString().equals(needle))
                .findFirst().orElseThrow();
    }

    private void assertEnvelopeMeta(Chain chain, List<JsonNode> payloads) {
        for (JsonNode payload : payloads) {
            EventEntity entity = envelopeFor(chain.runId(), payload);
            assertEquals(chain.runId(), entity.getRunId(), "事件挂在根 Run");
            assertEquals(chain.threadId(), entity.getThreadId(), "thread 归属可解析");
            assertEquals(chain.projectId(), entity.getProjectId());
            assertTrue(entity.getSequence() > 0);
        }
    }

    private JsonNode envelopePayload(EventEntity entity) {
        try {
            return objectMapper.readTree(entity.getEnvelopeJson()).path("payload");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private TaskDraft task(String id, String goal, List<String> deps) {
        return new TaskDraft(id, goal, deps, List.of(), "deliver-" + id,
                List.of("self-check-" + id), null, "DELEGATED", true);
    }

    private String savePlan(Chain chain, List<TaskDraft> tasks) {
        return planService.saveDraft(chain.runId(),
                new PlanDraft(null, null, "MA-10 桥接测试计划", tasks)).planId();
    }

    private TaskAttemptEntity completeSucceeded(String planId, String taskId, String childRunId,
                                                Instant baseTime) {
        TaskEntity task = taskRepository.findByPlanIdAndTaskId(planId, taskId).orElseThrow();
        TaskAttemptEntity attempt = new TaskAttemptEntity(
                IdGen.next("att_"), planId, taskId, task.getTaskVersion(),
                childRunId, 1, "test-worker", baseTime.plusSeconds(600), 1L, null,
                "RUNNING", baseTime, baseTime);
        attemptRepository.save(attempt);
        task.setState(TaskStates.RUNNING);
        task.setUpdatedAt(baseTime);
        taskRepository.save(task);
        SubagentResult result = new SubagentResult(
                childRunId, "CODER", "COMPLETED", null,
                "self-check-" + taskId + " done", List.of(), "patch-" + taskId,
                "", "digest-" + taskId, 0, 0, null, 0);
        schedulingService.complete(attempt.getAttemptId(), result);
        return attempt;
    }

    private Chain buildChain() {
        Instant now = Instant.now();
        String projectId = "proj-ma10ev-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA10EV", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-ma10ev-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, "fake-fake-ma10",
                "fake", "fake-ma10", "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));
        String threadId = IdGen.next("thr_ma10ev_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA10EV 会话",
                profileId, now, now));
        String runId = IdGen.next("run_ma10ev_");
        String turnId = IdGen.next("turn_ma10ev_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new com.dsh.storage.RunEntity(runId, turnId, null, "MAIN",
                "RUNNING", "PENDING", 1, IdGen.next("ws_"), "digest-ma10ev",
                30, 120, 320_000, now.plusSeconds(600), now, now));
        return new Chain(projectId, threadId, turnId, runId);
    }

    private record Chain(String projectId, String threadId, String turnId, String runId) {
    }
}
