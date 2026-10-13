package com.dsh.api;

import com.dsh.common.IdGen;
import com.dsh.orchestration.SubagentResult;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.orchestration.plan.PlanService;
import com.dsh.orchestration.task.TaskSchedulingService;
import com.dsh.orchestration.task.TaskStates;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * MA-10 GET /runs/{runId}/plans 计划任务快照接口测试（规范 §11，AT-17）。
 *
 * <p>覆盖：计划定义 + 权威运行态 + 终态证据快照；CANCELLING（已请求取消）与
 * CANCELLED（已停止）在视图中明确区分；run 不存在 404；run 存在无计划返回空列表 200。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "dsh.policy.require-for-side-effects=NONE")
class Ma10PlanViewApiTest {

    @Autowired private PlanService planService;
    @Autowired private TaskSchedulingService schedulingService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ModelProfileRepository profileRepository;
    @Autowired private ThreadRepository threadRepository;
    @Autowired private TurnRepository turnRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private MockMvc mockMvc;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;

    @TempDir Path projectRoot;

    @Test
    void planSnapshotContainsDefinitionRuntimeAndEvidence() throws Exception {
        Chain chain = buildChain();
        String planId = planService.saveDraft(chain.runId(),
                new PlanDraft(null, null, "MA-10 快照测试", List.of(
                        task("A", "实现修复", List.of()),
                        task("B", "运行中任务", List.of()),
                        task("C", "已停止任务", List.of())))).planId();
        Instant now = Instant.now();
        completeSucceeded(planId, "A", "run_ma10pv_child_a", now);
        // B：已请求取消（安全点终止前）；C：已停止——视图必须区分
        TaskEntity b = taskRepository.findByPlanIdAndTaskId(planId, "B").orElseThrow();
        b.setState(TaskStates.CANCELLING);
        TaskEntity c = taskRepository.findByPlanIdAndTaskId(planId, "C").orElseThrow();
        c.setState(TaskStates.CANCELLED);
        c.setUpdatedAt(now);
        taskRepository.saveAll(List.of(b, c));

        JsonNode root = getPlans(chain.runId(), 200);
        assertEquals(1, root.path("plans").size());
        JsonNode plan = root.path("plans").get(0);
        assertEquals(planId, plan.path("planId").asText());
        assertEquals(chain.runId(), plan.path("rootRunId").asText());
        assertEquals(1, plan.path("revision").asInt());
        assertEquals("DRAFT", plan.path("status").asText());
        assertEquals("MA-10 快照测试", plan.path("goal").asText());
        assertEquals(3, plan.path("tasks").size());

        JsonNode a = plan.path("tasks").get(0);
        assertEquals("A", a.path("taskId").asText());
        assertEquals("实现修复", a.path("goal").asText());
        assertEquals("DELEGATED", a.path("executionMode").asText());
        assertTrue(a.path("required").asBoolean());
        assertEquals("deliver-A", a.path("deliverable").asText());
        assertTrue(a.path("acceptance").isArray());
        assertTrue(a.path("resourceScope").isNull());
        assertEquals(TaskStates.SUCCEEDED, a.path("state").asText());
        assertTrue(a.path("evidenceValid").asBoolean(), "SUCCEEDED 同版本证据有效");
        assertTrue(a.path("attemptId").asText().startsWith("att_"));
        assertEquals("run_ma10pv_child_a", a.path("childRunId").asText());
        JsonNode result = a.path("result");
        assertFalse(result.isNull(), "终态结果内嵌展示");
        assertEquals(TaskStates.SUCCEEDED, result.path("state").asText());
        assertEquals("patch-A", result.path("patchArtifactId").asText());
        assertEquals("digest-A", result.path("outputDigest").asText(),
                "终态产物 digest（测试原样落库；生产为 sha256）");
        assertTrue(result.path("artifactRefs").isArray());
        assertTrue(result.path("durationMs").asLong() >= 0);

        JsonNode bNode = plan.path("tasks").get(1);
        assertEquals(TaskStates.CANCELLING, bNode.path("state").asText(), "已请求取消");
        assertTrue(bNode.path("result").isNull());
        assertFalse(bNode.path("evidenceValid").asBoolean());
        assertTrue(bNode.path("attemptId").isNull());

        JsonNode cNode = plan.path("tasks").get(2);
        assertEquals(TaskStates.CANCELLED, cNode.path("state").asText(), "已停止");
        assertTrue(cNode.path("result").isNull());
    }

    @Test
    void unknownRunReturns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/runs/run_ma10pv_missing/plans")
                        .header("X-DSH-Client", "local-ui"))
                .andReturn();
        assertEquals(404, result.getResponse().getStatus());
        JsonNode error = mapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("RESOURCE_NOT_FOUND", error.path("code").asText());
    }

    @Test
    void runWithoutPlansReturnsEmptyList() throws Exception {
        Chain chain = buildChain();
        JsonNode root = getPlans(chain.runId(), 200);
        assertTrue(root.path("plans").isArray());
        assertEquals(0, root.path("plans").size(), "无计划返回空列表而非 404");
    }

    // ------------------------------------------------------- helpers

    private JsonNode getPlans(String runId, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/runs/" + runId + "/plans")
                        .header("X-DSH-Client", "local-ui"))
                .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(),
                "响应: " + result.getResponse().getStatus());
        return mapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private TaskDraft task(String id, String goal, List<String> deps) {
        return new TaskDraft(id, goal, deps, List.of(), "deliver-" + id,
                List.of("self-check-" + id), null, "DELEGATED", true);
    }

    private void completeSucceeded(String planId, String taskId, String childRunId,
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
    }

    private Chain buildChain() {
        Instant now = Instant.now();
        String projectId = "proj-ma10pv-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA10PV", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-ma10pv-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, "fake-fake-ma10",
                "fake", "fake-ma10", "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));
        String threadId = IdGen.next("thr_ma10pv_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA10PV 会话",
                profileId, now, now));
        String runId = IdGen.next("run_ma10pv_");
        String turnId = IdGen.next("turn_ma10pv_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING",
                1, IdGen.next("ws_"), "digest-ma10pv", 30, 120, 320_000,
                now.plusSeconds(600), now, now));
        return new Chain(projectId, threadId, turnId, runId);
    }

    private record Chain(String projectId, String threadId, String turnId, String runId) {
    }
}
