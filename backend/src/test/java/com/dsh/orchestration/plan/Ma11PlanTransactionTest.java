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

/** MA-11：模拟 Loop 外层真实提交，覆盖可恢复拒绝与内部故障的事务边界。 */
@SpringBootTest
class Ma11PlanTransactionTest {

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
    @org.springframework.boot.test.mock.mockito.SpyBean
    private PlanRepository planRepository;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

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

    @Autowired
    private com.dsh.storage.TaskRepository taskRepository;

    private String taskArgs(String dependency) {
        return "{\"tasks\":[{\"taskId\":\"A\",\"goal\":\"g\",\"dependsOn\":"
                + dependency + ",\"deliverable\":\"d\",\"acceptance\":[\"a\"],"
                + "\"executionMode\":\"INLINE\",\"required\":false}]}";
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "SELF_DEPENDENCY,A", "UNKNOWN_DEPENDENCY,missing"
    })
    void recoverableSemanticFailureDoesNotPoisonLoopTransaction(String code, String dependency) {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            ToolOutcomeDto out = dispatch(taskArgs("[\"" + dependency + "\"]"));
            assertEquals(code, out.errorCode());
            RunEntity run = runRepository.findById(runId).orElseThrow();
            run.setSummary("tool-failed:" + out.errorCode());
            runRepository.save(run);
        });
        assertEquals("tool-failed:" + code, runRepository.findById(runId).orElseThrow().getSummary());
        assertTrue(planRepository.findByRootRunIdOrderByCreatedAtDesc(runId).isEmpty());
        assertEquals("SUCCEEDED", dispatch(taskArgs("[]")).status(), "下一次修正参数后仍可创建计划");
    }

    @Test
    void staleRevisionLeavesExistingPlanUntouchedAndOuterTransactionCommits() {
        var created = dispatch(taskArgs("[]"));
        String planId = created.structuredContent().path("planId").asText();
        String staleArgs = taskArgs("[]").replace("{\"tasks\":", "{\"planId\":\"" + planId
                + "\",\"expectedRevision\":2,\"tasks\":");
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            assertEquals("REVISION_MISMATCH", dispatch(staleArgs).errorCode());
            RunEntity run = runRepository.findById(runId).orElseThrow();
            run.setSummary("revision-rejected");
            runRepository.save(run);
        });
        assertEquals("revision-rejected", runRepository.findById(runId).orElseThrow().getSummary());
        assertEquals(1, planRepository.findById(planId).orElseThrow().getRevision());
    }

    @Test
    void successfulPlanStillRollsBackWithOuterFailure() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
            tx.executeWithoutResult(status -> {
                assertEquals("SUCCEEDED", dispatch(taskArgs("[]")).status());
                throw new IllegalStateException("injected loop persistence failure");
            }));
        assertTrue(planRepository.findByRootRunIdOrderByCreatedAtDesc(runId).isEmpty(),
                "不能用独立提交工具事务来掩盖 rollback-only，否则遗留已提交计划");
    }

    @Test
    void internalFailureAfterTaskDeletionStillRollsBackEntireRevision() {
        var created = dispatch(taskArgs("[]"));
        String planId = created.structuredContent().path("planId").asText();
        String updateArgs = taskArgs("[]").replace("{\"tasks\":", "{\"planId\":\"" + planId
                + "\",\"expectedRevision\":1,\"tasks\":");
        org.mockito.Mockito.doThrow(new PlanServiceException("INTERNAL_ERROR", "injected persistence failure"))
                .when(planRepository).save(org.mockito.ArgumentMatchers.any(com.dsh.storage.PlanEntity.class));
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.transaction.UnexpectedRollbackException.class,
                () -> tx.executeWithoutResult(status -> {
                    assertEquals("INTERNAL_ERROR", dispatch(updateArgs).errorCode());
                }));
        assertEquals(1, planRepository.findById(planId).orElseThrow().getRevision());
        assertEquals(1, taskRepository.findByPlanIdOrderByTaskIdAsc(planId).size(),
                "写入后故障必须恢复已删除的旧任务，不允许提交半份修订");
    }
}
