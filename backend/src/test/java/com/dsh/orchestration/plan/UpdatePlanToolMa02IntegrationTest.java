package com.dsh.orchestration.plan;

import com.dsh.agent.tool.ToolDispatcher;
import com.dsh.agent.tool.UpdatePlanTool;
import com.dsh.api.dto.event.ToolOutcomeDto;
import com.dsh.common.IdGen;
import com.dsh.storage.PlanRepository;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-02 AT-01/AT-02 工具侧：update_plan 双分支经 ToolDispatcher 全链路（Schema oneOf 校验 →
 * 工具执行 → PlanService 落库）。本测试使用测试 profile（orchestration.enabled=true），
 * 验证开启态下计划仍只是草案；关闭态另见 UpdatePlanGateClosedMa02Test。
 */
@SpringBootTest
class UpdatePlanToolMa02IntegrationTest {

    @Autowired
    private ToolDispatcher dispatcher;
    @Autowired
    private UpdatePlanTool updatePlanTool;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ThreadRepository threadRepository;
    @Autowired
    private TurnRepository turnRepository;
    @Autowired
    private RunRepository runRepository;
    @Autowired
    private PlanRepository planRepository;

    @TempDir
    Path projectRoot;

    private String runId;
    private String threadId;
    private String projectId;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now();
        projectId = "proj-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA02工具", projectRoot.toString(),
                true, now, now));
        threadId = IdGen.next("thr_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA02工具",
                "prof-" + UUID.randomUUID(), now, now));
        runId = IdGen.next("run_");
        String turnId = IdGen.next("turn_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING",
                1, IdGen.next("ws_"), "a".repeat(64), 24, 80, 200_000, now.plusSeconds(600), now, now));
    }

    private ToolOutcomeDto dispatch(String argsJson) {
        return dispatcher.dispatch(runId, threadId, runId, projectId, now.plusSeconds(600),
                "update_plan", argsJson, "call-" + UUID.randomUUID());
    }

    @Test
    void legacyStepsStillWritesPlanJson() throws Exception {
        ToolOutcomeDto out = dispatch("{\"steps\":[{\"text\":\"定位\",\"status\":\"IN_PROGRESS\"},"
                + "{\"text\":\"修复\",\"status\":\"DONE\"}]}");
        assertEquals("SUCCEEDED", out.status(), out.errorCode() + ": " + out.summary());
        assertEquals("steps", out.structuredContent().path("mode").asText());
        assertEquals(1, out.structuredContent().path("done").asInt());
        Path planFile = Path.of(out.structuredContent().path("planRef").asText());
        assertTrue(Files.exists(planFile), "旧清单仍写工作区 plan.json");
        assertTrue(planRepository.findByRootRunIdOrderByCreatedAtDesc(runId).isEmpty(),
                "steps 不产生结构化计划行");
    }

    @Test
    void v2CreateAndUpdatePersistRevisionedDraftWithoutExecution() throws Exception {
        String createArgs = objectMapper.writeValueAsString(Map.of(
                "goal", "登录改造",
                "tasks", List.of(Map.of(
                        "taskId", "A", "goal", "确认契约",
                        "dependsOn", List.of(),
                        "deliverable", "契约文档",
                        "acceptance", List.of("覆盖成功/失败路径"),
                        "executionMode", "DELEGATED", "required", true))));
        ToolOutcomeDto created = dispatch(createArgs);
        assertEquals("SUCCEEDED", created.status(), created.errorCode() + ": " + created.summary());
        assertEquals("plan", created.structuredContent().path("mode").asText());
        assertEquals(1, created.structuredContent().path("revision").asInt());
        assertEquals("DRAFT", created.structuredContent().path("tasks").get(0).path("state").asText(),
                "计划只落 DRAFT，不启动执行");
        String planId = created.structuredContent().path("planId").asText();
        assertTrue(planId.startsWith("plan_"));

        String updateArgs = objectMapper.writeValueAsString(Map.of(
                "planId", planId, "expectedRevision", 1,
                "tasks", List.of(
                        Map.of("taskId", "A", "goal", "确认契约", "dependsOn", List.of(),
                                "deliverable", "契约文档", "acceptance", List.of("覆盖成功/失败路径"),
                                "executionMode", "DELEGATED", "required", true),
                        Map.of("taskId", "B", "goal", "实现后端", "dependsOn", List.of("A"),
                                "deliverable", "补丁", "acceptance", List.of("测试通过"),
                                "executionMode", "DELEGATED", "required", true))));
        ToolOutcomeDto updated = dispatch(updateArgs);
        assertEquals("SUCCEEDED", updated.status(), updated.errorCode() + ": " + updated.summary());
        assertEquals(2, updated.structuredContent().path("revision").asInt());
        assertEquals(2, updated.structuredContent().path("taskCount").asInt());
    }

    @Test
    void unknownExecutionFieldIsRejectedBySchema() {
        // execute/dispatch 等未定义字段：oneOf 两支都 additionalProperties=false → Schema 拒绝
        ToolOutcomeDto out = dispatch("{\"tasks\":[{\"taskId\":\"A\",\"goal\":\"g\","
                + "\"dependsOn\":[],\"deliverable\":\"d\",\"acceptance\":[\"a\"],"
                + "\"executionMode\":\"DELEGATED\",\"required\":true,\"dispatch\":true}]}");
        assertEquals("FAILED", out.status());
        assertEquals("INVALID_ARGUMENTS", out.errorCode());
    }

    @Test
    void mixedStepsAndTasksIsRejected() throws Exception {
        // 经 Dispatcher：Schema oneOf 两支都不匹配
        ToolOutcomeDto viaDispatcher = dispatch("{\"steps\":[{\"text\":\"x\",\"status\":\"PENDING\"}],"
                + "\"tasks\":[]}");
        assertEquals("FAILED", viaDispatcher.status());

        // 直接执行工具：服务端兜底给出稳定 reasonCode（Schema 隐藏不等于服务端免校验）
        var args = objectMapper.readTree("{\"steps\":[],\"tasks\":[]}");
        var outcome = updatePlanTool.execute(args, null);
        assertEquals("FAILED", outcome.status());
        assertEquals("MIXED_PLAN_FORMAT", outcome.errorCode());
    }

    @Test
    void semanticGraphErrorsReturnFrozenReasonCodes() {
        ToolOutcomeDto cycle = dispatch("{\"tasks\":["
                + "{\"taskId\":\"A\",\"goal\":\"g\",\"dependsOn\":[\"B\"],\"deliverable\":\"d\","
                + "\"acceptance\":[\"a\"],\"executionMode\":\"DELEGATED\",\"required\":true},"
                + "{\"taskId\":\"B\",\"goal\":\"g\",\"dependsOn\":[\"A\"],\"deliverable\":\"d\","
                + "\"acceptance\":[\"a\"],\"executionMode\":\"DELEGATED\",\"required\":true}]}");
        assertEquals("FAILED", cycle.status());
        assertEquals("CYCLE_DETECTED", cycle.errorCode());

        ToolOutcomeDto unknownDep = dispatch("{\"tasks\":["
                + "{\"taskId\":\"A\",\"goal\":\"g\",\"dependsOn\":[\"X\"],\"deliverable\":\"d\","
                + "\"acceptance\":[\"a\"],\"executionMode\":\"DELEGATED\",\"required\":true}]}");
        assertEquals("UNKNOWN_DEPENDENCY", unknownDep.errorCode());

        ToolOutcomeDto stale = dispatch("{\"planId\":\"plan_missing_0001\",\"expectedRevision\":1,"
                + "\"tasks\":[{\"taskId\":\"A\",\"goal\":\"g\",\"dependsOn\":[],\"deliverable\":\"d\","
                + "\"acceptance\":[\"a\"],\"executionMode\":\"DELEGATED\",\"required\":true}]}");
        assertEquals("PLAN_NOT_FOUND", stale.errorCode());

        assertTrue(planRepository.findByRootRunIdOrderByCreatedAtDesc(runId).isEmpty(),
                "反例不得为本 Run 留下任何计划/任务行");
    }

    @Test
    void planToolNeverCallsModelOrStartsWorkers() {
        ToolOutcomeDto out = dispatch("{\"tasks\":[{\"taskId\":\"A\",\"goal\":\"g\",\"dependsOn\":[],"
                + "\"deliverable\":\"d\",\"acceptance\":[\"a\"],"
                + "\"executionMode\":\"INLINE\",\"required\":false}]}");
        assertEquals("SUCCEEDED", out.status(), out.summary());
        RunEntity run = runRepository.findById(runId).orElseThrow();
        assertEquals("RUNNING", run.getState(), "计划工具不改变 Run 状态");
        assertEquals(0, runRepository.findAll().stream()
                        .filter(r -> r.getId().startsWith("sub_run_")).count(),
                "update_plan 不得创建子 Run");
        assertFalse(out.summary().contains("已启动"));
    }
}
