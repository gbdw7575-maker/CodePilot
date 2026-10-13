package com.dsh.orchestration.event;

import com.dsh.api.dto.event.EventPayloads;
import com.dsh.api.dto.event.EventType;
import com.dsh.application.event.EventMeta;
import com.dsh.application.event.EventStore;
import com.dsh.orchestration.IntegrationService;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.TaskResultEntity;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * MA-10 任务域 → 统一事件流桥接（规范 §11/§10.3，AT-17）。
 *
 * <p>MA-05～MA-09 的任务状态流水只写 {@code agent_task_events}（per-rootRun seq，供
 * await_tasks 增量消费），前端不可见。本桥接在<strong>同一业务事务</strong>内把领域变化
 * 额外写入 {@link EventStore}（per-thread sequence），使前端能经 WebSocket 实时接收、
 * 经 REST 断线补拉，并保证状态与事件同提交（不产生"有状态无事件"）。</p>
 *
 * <p>归属字段由 rootRunId 解析（Run→Turn→Thread），任务事件统一挂在根 Run 上，
 * 前端按当前根 Run 过滤即可实现"历史任务不污染当前 Turn"。映射缺失时记录错误并跳过：
 * 领域状态机不依赖事件桥接，权威状态始终可经 REST 快照重建。payload 只含状态/引用/
 * 摘要/digest，不含隐藏推理与凭据。</p>
 */
@Component
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class OrchestrationEventBridge {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationEventBridge.class);

    private final EventStore eventStore;
    private final RunRepository runRepository;
    private final TurnRepository turnRepository;
    private final ThreadRepository threadRepository;
    private final ObjectMapper objectMapper;

    public OrchestrationEventBridge(EventStore eventStore, RunRepository runRepository,
                                    TurnRepository turnRepository,
                                    ThreadRepository threadRepository,
                                    ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.runRepository = runRepository;
        this.turnRepository = turnRepository;
        this.threadRepository = threadRepository;
        this.objectMapper = objectMapper;
    }

    /** plan.updated：计划草案创建/修订落库后。 */
    public void planUpdated(String rootRunId, String planId, int revision) {
        emit(rootRunId, EventType.PLAN_UPDATED,
                new EventPayloads.PlanUpdated(planId, revision));
    }

    /** task.dispatched：dispatch_tasks 登记（DRAFT→PENDING）。 */
    public void taskDispatched(String rootRunId, String planId, String taskId, int taskVersion,
                               String attemptId, String state) {
        emit(rootRunId, EventType.TASK_DISPATCHED,
                new EventPayloads.TaskDispatched(planId, taskId, taskVersion, attemptId, state));
    }

    /** task.state.changed：任务状态迁移（attemptId/childRunId 可空）。 */
    public void taskStateChanged(String rootRunId, String planId, String taskId, int taskVersion,
                                 String attemptId, String childRunId,
                                 String from, String to, String reasonCode) {
        emit(rootRunId, EventType.TASK_STATE_CHANGED,
                new EventPayloads.TaskStateChanged(planId, taskId, taskVersion, attemptId,
                        childRunId, from, to, reasonCode));
    }

    /** task.result.ready：attempt 终态结果已封存（与状态推进同事务）。 */
    public void taskResultReady(String rootRunId, TaskResultEntity result, String childRunId) {
        List<String> artifactRefs;
        try {
            artifactRefs = result.getArtifactRefsJson() == null || result.getArtifactRefsJson().isBlank()
                    ? List.of()
                    : objectMapper.readValue(result.getArtifactRefsJson(),
                            new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                            });
        } catch (Exception e) {
            log.warn("task.result.ready artifactRefs 解析失败 task={}: {}", result.getTaskId(),
                    e.getMessage());
            artifactRefs = List.of();
        }
        emit(rootRunId, EventType.TASK_RESULT_READY,
                new EventPayloads.TaskResultReady(result.getPlanId(), result.getTaskId(),
                        result.getTaskVersion(), result.getAttemptId(), childRunId,
                        result.getState(), artifactRefs, result.getVerificationRef(),
                        result.getInputDigest(), result.getOutputDigest()));
    }

    /** run.waiting_tasks：主 Agent 持久化停车（cursor 为十进制任务事件游标）。 */
    public void waitingTasks(String rootRunId, List<String> taskIds, String waitMode,
                             long afterCursor, Instant waitUntil) {
        emit(rootRunId, EventType.RUN_WAITING_TASKS,
                new EventPayloads.WaitingTasks(List.copyOf(taskIds), waitMode,
                        Long.toString(afterCursor), waitUntil));
    }

    /** integration.completed（APPLIED）/ integration.failed（CONFLICTS/RECOVERED_BLOCKED）。 */
    public void integrationFinished(String rootRunId,
                                    IntegrationService.IntegrationOutcome outcome) {
        String type = IntegrationService.STATE_APPLIED.equals(outcome.state())
                ? EventType.INTEGRATION_COMPLETED : EventType.INTEGRATION_FAILED;
        emit(rootRunId, type,
                new EventPayloads.IntegrationFinished(outcome.integrationId(), outcome.state(),
                        outcome.resultDigest(), outcome.message()));
    }

    private void emit(String rootRunId, String type, Object payload) {
        try {
            EventMeta meta = resolveMeta(rootRunId);
            if (meta == null) {
                log.error("任务域事件归属解析失败，跳过桥接 root={} type={}（REST 快照仍可重建视图）",
                        rootRunId, type);
                return;
            }
            eventStore.append(meta, type, payload);
        } catch (RuntimeException e) {
            // 与状态机同事务：桥接失败应让事务回滚而非静默丢事件；仅映射缺失（上方）跳过
            throw e;
        }
    }

    private EventMeta resolveMeta(String rootRunId) {
        RunEntity run = runRepository.findById(rootRunId).orElse(null);
        if (run == null) {
            return null;
        }
        String threadId = null;
        if (run.getTurnId() != null) {
            TurnEntity turn = turnRepository.findById(run.getTurnId()).orElse(null);
            if (turn != null) {
                threadId = turn.getThreadId();
            }
        }
        String projectId = null;
        if (threadId != null) {
            ThreadEntity thread = threadRepository.findById(threadId).orElse(null);
            if (thread != null) {
                projectId = thread.getProjectId();
            }
        }
        return new EventMeta(projectId, threadId, run.getTurnId(), rootRunId,
                run.getParentRunId(), run.getTraceId(), null);
    }
}
