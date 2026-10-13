package com.dsh.orchestration.plan;

import com.dsh.agent.tool.ToolDispatcher;
import com.dsh.api.dto.event.ToolOutcomeDto;
import com.dsh.common.IdGen;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MA-02 AT-01 关闭态：dsh.orchestration.enabled=false 时委派工具不注册（强行调用被拒），
 * 但 update_plan 两个分支都可用——计划是惰性草案，门控只管执行/委派能力。
 */
@SpringBootTest(properties = "dsh.orchestration.enabled=false")
class UpdatePlanGateClosedMa02Test {

    @Autowired
    private ToolDispatcher dispatcher;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ThreadRepository threadRepository;
    @Autowired
    private TurnRepository turnRepository;
    @Autowired
    private RunRepository runRepository;

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
        projectRepository.save(new ProjectEntity(projectId, "MA02关", projectRoot.toString(),
                true, now, now));
        threadId = IdGen.next("thr_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA02关",
                "prof-" + UUID.randomUUID(), now, now));
        runId = IdGen.next("run_");
        String turnId = IdGen.next("turn_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING",
                1, IdGen.next("ws_"), "a".repeat(64), 24, 80, 200_000, now.plusSeconds(600), now, now));
    }

    private ToolOutcomeDto dispatch(String tool, String args) {
        return dispatcher.dispatch(runId, threadId, runId, projectId, now.plusSeconds(600),
                tool, args, "call-" + UUID.randomUUID());
    }

    @Test
    void delegationToolIsNotRegisteredWhenGateClosed() {
        ToolOutcomeDto out = dispatch("run_subagent",
                "{\"role\":\"CODER\",\"task\":\"x\",\"artifactRefs\":[]}");
        assertEquals("FAILED", out.status());
        assertEquals("UNKNOWN_TOOL", out.errorCode(), "关闭时委派工具不可注册，强行调用被拒");
    }

    @Test
    void legacyAndV2PlanBothWorkWhenGateClosed() {
        ToolOutcomeDto legacy = dispatch("update_plan",
                "{\"steps\":[{\"text\":\"简单任务直接做\",\"status\":\"DONE\"}]}");
        assertEquals("SUCCEEDED", legacy.status(), legacy.errorCode() + ": " + legacy.summary());

        ToolOutcomeDto v2 = dispatch("update_plan",
                "{\"tasks\":[{\"taskId\":\"A\",\"goal\":\"先记录阶段\","
                        + "\"dependsOn\":[],\"deliverable\":\"清单\",\"acceptance\":[\"条目存在\"],"
                        + "\"executionMode\":\"INLINE\",\"required\":true}]}");
        assertEquals("SUCCEEDED", v2.status(), v2.errorCode() + ": " + v2.summary());
        assertEquals(1, v2.structuredContent().path("revision").asInt());
    }
}
