package com.dsh.orchestration;

import com.dsh.agent.AgentLoop;
import com.dsh.agent.model.FakeModelGateway;
import com.dsh.common.IdGen;
import com.dsh.orchestration.task.TaskStates;
import com.dsh.storage.MessageEntity;
import com.dsh.storage.MessageRepository;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.PlanEntity;
import com.dsh.storage.PlanRepository;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.TaskRepository;
import com.dsh.storage.TaskWaitEntity;
import com.dsh.storage.TaskWaitRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-05 主循环停车/恢复端到端（规范 §9 串行邮箱）：
 * await_tasks 条件未满足时主循环让出线程（Run 保持 RUNNING、不新增 RunState），
 * worker 终态信号/超时扫描在独立短事务 CAS 成功后只回填一次 tool result，
 * 经 LoopScheduler 串行恢复。测试环境 dsh.loop.synchronous=true，恢复发生在
 * worker/扫描线程上，同一根 Run 仍至多一条执行循环（等待行唯一约束结构性保证）。
 */
@SpringBootTest
@TestPropertySource(properties = "dsh.policy.require-for-side-effects=NONE")
class Ma05AwaitParkLoopIntegrationTest {

    @Autowired private AgentLoop agentLoop;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ModelProfileRepository profileRepository;
    @Autowired private ThreadRepository threadRepository;
    @Autowired private TurnRepository turnRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskWaitRepository waitRepository;

    @TempDir Path projectRoot;

    @Test
    void parkUntilTerminalThenResumeToCompletion() {
        Chain chain = start("MA05-AT07 并行汇合场景", "park-at07-" + UUID.randomUUID());

        agentLoop.resumeRootRun(chain.runId());

        awaitUntil(Duration.ofSeconds(30),
                () -> "COMPLETED".equals(runState(chain.runId())));
        String planId = onlyPlan(chain.runId());
        awaitUntil(Duration.ofSeconds(30),
                () -> TaskStates.SUCCEEDED.equals(taskState(planId, "A"))
                        && TaskStates.SUCCEEDED.equals(taskState(planId, "B")));

        // 同根等待行恰好一行且 DONE（串行邮箱：同一主 Run 同时至多一个等待者）
        List<TaskWaitEntity> waits = waitRepository.findAll().stream()
                .filter(w -> chain.runId().equals(w.getRootRunId())).toList();
        assertEquals(1, waits.size());
        assertEquals(TaskWaitEntity.DONE, waits.get(0).getState());
        // 唯一配对 tool result
        long toolResults = messageRepository.listByThread(chain.threadId()).stream()
                .filter(m -> "tool".equals(m.getRole())
                        && m.getContent().contains(waits.get(0).getToolCallId())).count();
        assertEquals(1, toolResults, "await_tasks 唤醒只能回填一次结果");
    }

    @Test
    void shortTimeoutResumesWithTimedOutAndTasksSurvive() {
        Chain chain = start("MA05-AT09 短超时观察场景", "park-at09-" + UUID.randomUUID());

        agentLoop.resumeRootRun(chain.runId());

        awaitUntil(Duration.ofSeconds(30),
                () -> "COMPLETED".equals(runState(chain.runId())));
        List<TaskWaitEntity> waits = waitRepository.findAll().stream()
                .filter(w -> chain.runId().equals(w.getRootRunId())).toList();
        assertEquals(1, waits.size());
        assertEquals(TaskWaitEntity.TIMED_OUT, waits.get(0).getState());
        assertTrue(messageRepository.listByThread(chain.threadId()).stream()
                .anyMatch(m -> "tool".equals(m.getRole())
                        && m.getContent().contains(waits.get(0).getToolCallId())
                        && m.getContent().contains("\"timedOut\":true")));

        // timedOut 不是取消也不是成功：慢任务随后正常完成
        String planId = onlyPlan(chain.runId());
        awaitUntil(Duration.ofSeconds(20),
                () -> TaskStates.SUCCEEDED.equals(taskState(planId, "B")));
    }

    // ------------------------------------------------------- helpers

    private Chain start(String userText, String unique) {
        Instant now = Instant.now();
        String projectId = "proj-" + unique;
        projectRepository.save(new ProjectEntity(projectId, "MA05 park", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-" + unique;
        profileRepository.save(new ModelProfileEntity(profileId, "fake-ma05",
                FakeModelGateway.PROVIDER, "fake-ma05", "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));
        String threadId = IdGen.next("thr_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA05 park",
                profileId, now, now));
        String runId = IdGen.next("run_");
        String turnId = IdGen.next("turn_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, userText,
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), "digest-ma05-park", 12, 40, 200_000,
                now.plusSeconds(600), now, now));
        messageRepository.save(new MessageEntity(IdGen.next("msg_"), threadId, turnId,
                "user", userText, 1L, now));
        return new Chain(threadId, turnId, runId);
    }

    private String runState(String runId) {
        return runRepository.findById(runId).map(RunEntity::getState).orElse("<missing>");
    }

    private String taskState(String planId, String taskId) {
        return taskRepository.findByPlanIdAndTaskId(planId, taskId)
                .map(TaskEntity::getState).orElse("<missing>");
    }

    private String onlyPlan(String rootRunId) {
        return planRepository.findByRootRunIdOrderByCreatedAtDesc(rootRunId).stream()
                .findFirst().map(PlanEntity::getId)
                .orElseThrow(() -> new AssertionError("根 Run 应有一个计划"));
    }

    private void awaitUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("条件在 " + timeout + " 内未满足");
    }

    private record Chain(String threadId, String turnId, String runId) {
    }
}
