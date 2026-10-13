package com.dsh.orchestration.task;

import com.dsh.agent.LoopScheduler;
import com.dsh.agent.LoopSteps;
import com.dsh.application.DshProperties;
import com.dsh.common.IdGen;
import com.dsh.orchestration.event.OrchestrationEventBridge;
import com.dsh.orchestration.task.TaskViews.TaskView;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.TaskEventEntity;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.TaskWaitEntity;
import com.dsh.storage.TaskWaitRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * await_tasks 的等待登记、条件求值与持久化唤醒（MA-05，规范 §9）。
 *
 * <p>主循环在工具执行事务内发现“条件未满足且未超时”时落一行 WAITING（Run 保持 RUNNING，
 * 不新增 RunState），随后让出唯一主循环线程——模型调用资源与数据库事务都不持有。
 * 子任务终态提交后 {@link TaskWorkerExecutor} 发信号，或到点超时扫描触发：本服务在独立
 * 短事务内求值，CAS 赢家回填<strong>唯一</strong> tool result，再经 LoopScheduler 在同一
 * 根 Run 串行恢复（同一主 Run 同时最多一条执行循环；tool_call_id 一一配对，不追加重复
 * tool result）。timedOut=true 不写 SUCCEEDED、不等于任务失败。</p>
 */
@Service
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class TaskAwaitService {

    private static final Logger log = LoggerFactory.getLogger(TaskAwaitService.class);

    private final TaskQueryService queryService;
    private final TaskEventJournal journal;
    private final TaskWaitRepository waitRepository;
    private final RunRepository runRepository;
    private final LoopSteps loopSteps;
    private final LoopScheduler loopScheduler;
    private final DshProperties properties;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final TaskAwaitService self;
    private final OrchestrationEventBridge bridge;

    public TaskAwaitService(TaskQueryService queryService, TaskEventJournal journal,
                            TaskWaitRepository waitRepository, RunRepository runRepository,
                            @Lazy LoopSteps loopSteps,
                            @Lazy LoopScheduler loopScheduler,
                            DshProperties properties,
                            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                            @Lazy TaskAwaitService self,
                            OrchestrationEventBridge bridge) {
        this.queryService = queryService;
        this.journal = journal;
        this.waitRepository = waitRepository;
        this.runRepository = runRepository;
        this.loopSteps = loopSteps;
        this.loopScheduler = loopScheduler;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.self = self;
        this.bridge = bridge;
    }

    /** 求值结果；ready=ANY/ALL 是否已满足，newCursor 为等待集合相关流水的最大 seq。 */
    public record Evaluation(boolean ready, boolean notFound, String missingTaskId,
                             long afterCursor, long newCursor, List<TaskView> tasks,
                             List<TaskEventEntity> changes) {
    }

    /**
     * 登记停车（调用方持有 runTools 写事务）。参数原样保存，恢复时据此回填与请求配对的结果。
     * 同一根 Run 已有未完成等待时拒绝（串行邮箱：同一主 Run 同时至多一个等待者）。
     */
    public void park(String rootRunId, String turnId, String toolCallId, List<String> taskIds,
                     String waitMode, long afterCursor, String argumentsJson, Instant waitUntil) {
        if (waitRepository.findFirstByRootRunIdAndStateOrderByCreatedAtDesc(
                rootRunId, TaskWaitEntity.WAITING).isPresent()) {
            throw new IllegalArgumentException(
                    "同一主 Run 已有未完成的 await_tasks 等待；请在上一次等待返回后再发起");
        }
        waitRepository.save(new TaskWaitEntity(IdGen.next("wait_"), rootRunId, turnId, toolCallId,
                writeJson(taskIds), waitMode, afterCursor, argumentsJson, waitUntil,
                TaskWaitEntity.WAITING, Instant.now(), null));
        // MA-10：主 Agent 停车同事务桥接 run.waiting_tasks（前端展示待等任务与游标）
        bridge.waitingTasks(rootRunId, taskIds, waitMode, afterCursor, waitUntil);
    }

    /** 求等待条件；taskId 不属于根 Run 时 notFound=true（工具层映射 TASK_NOT_FOUND）。 */
    public Evaluation evaluate(String rootRunId, List<String> taskIds, String waitMode,
                               long afterCursor) {
        Map<String, TaskEntity> index = queryService.indexRootTasks(rootRunId);
        List<TaskEntity> resolved = new ArrayList<>();
        for (String taskId : taskIds) {
            TaskEntity task = index.get(taskId);
            if (task == null) {
                return new Evaluation(false, true, taskId, afterCursor, afterCursor,
                        List.of(), List.of());
            }
            resolved.add(task);
        }
        List<TaskView> views = queryService.views(resolved);
        boolean any = false;
        boolean all = true;
        for (TaskView view : views) {
            boolean terminal = TaskStates.isTerminal(view.state());
            any |= terminal;
            all &= terminal;
        }
        boolean ready = "ANY".equals(waitMode) ? any : all;
        List<TaskEventEntity> changes =
                journal.eventsAfter(rootRunId, taskIds, afterCursor);
        long newCursor = afterCursor;
        for (TaskEventEntity event : changes) {
            newCursor = Math.max(newCursor, event.getSeq());
        }
        return new Evaluation(ready, false, null, afterCursor, newCursor, views, changes);
    }

    /** 子任务终态提交后的唤醒信号（事务外调用；逐等待独立短事务 CAS）。 */
    public void signal(String rootRunId) {
        for (TaskWaitEntity wait : waitRepository
                .findByRootRunIdAndState(rootRunId, TaskWaitEntity.WAITING)) {
            try {
                self.tryComplete(wait.getId(), false);
            } catch (RuntimeException e) {
                log.warn("await 信号处理失败 wait={}: {}", wait.getId(), e.getMessage());
            }
        }
    }

    /** 到点扫描：超时的等待按 timedOut=true 回填并恢复主循环（不写任务成功态）。 */
    @Scheduled(fixedDelayString = "${dsh.orchestration.await-timeout-tick-ms:500}",
               initialDelayString = "${dsh.orchestration.await-timeout-tick-ms:500}")
    public void reapExpired() {
        Instant now = Instant.now();
        for (TaskWaitEntity wait : waitRepository.findByState(TaskWaitEntity.WAITING)) {
            if (!wait.getWaitUntil().isAfter(now)) {
                try {
                    self.tryComplete(wait.getId(), true);
                } catch (RuntimeException e) {
                    log.warn("await 超时回填失败 wait={}: {}", wait.getId(), e.getMessage());
                }
            }
        }
    }

    /**
     * CAS 完成等待并回填唯一 tool result。timeoutMode=true 表示由超时扫描触发。
     * REQUIRES_NEW：信号常在别的事务 afterCommit 中触发（旧物理事务已提交但连接仍绑定，
     * REQUIRED 会加入已提交事务导致写失败），也可能来自 worker/调度线程（无事务）。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void tryComplete(String waitId, boolean timeoutMode) {
        TaskWaitEntity wait = waitRepository.findById(waitId).orElse(null);
        if (wait == null || !TaskWaitEntity.WAITING.equals(wait.getState())) {
            return;
        }
        List<String> taskIds = readTaskIds(wait);
        Evaluation evaluation = evaluate(wait.getRootRunId(), taskIds,
                wait.getWaitMode(), wait.getAfterCursor());
        if (!evaluation.ready() && (!timeoutMode || wait.getWaitUntil().isAfter(Instant.now()))) {
            return;
        }
        boolean timedOut = !evaluation.ready();
        String toState = timedOut ? TaskWaitEntity.TIMED_OUT : TaskWaitEntity.DONE;
        int cas = waitRepository.casComplete(waitId, toState, Instant.now());
        if (cas == 0) {
            return;
        }
        ObjectNode structured = buildStructured(wait, evaluation, timedOut);
        String summary = timedOut
                ? "await_tasks 超时（" + wait.getWaitMode() + "）：等待窗口结束，任务未全部完成"
                : "await_tasks 条件满足（" + wait.getWaitMode() + "）";
        loopSteps.fillParkedAwaitResult(wait, structured, summary);
        registerResumeAfterCommit(wait.getRootRunId());
    }

    /** 启动恢复（规范 §6.3 持久化唤醒）：活根 Run 的等待按当前条件/到点收敛；死根 Run 直接关闭。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        for (TaskWaitEntity wait : waitRepository.findByState(TaskWaitEntity.WAITING)) {
            RunEntity run = runRepository.findById(wait.getRootRunId()).orElse(null);
            boolean rootActive = run != null && !isTerminalRun(run.getState());
            try {
                if (rootActive) {
                    self.tryComplete(wait.getId(),
                            !wait.getWaitUntil().isAfter(Instant.now()));
                    // 未满足且未到点：保持 WAITING，由信号/定时扫描继续接管
                } else {
                    self.closeOrphanWait(wait.getId());
                }
            } catch (RuntimeException e) {
                log.warn("启动等待恢复失败 wait={}: {}", wait.getId(), e.getMessage());
            }
        }
    }

    /** 根 Run 已不存在/终态：关闭等待并回填一次结果，不调度恢复。 */
    @Transactional
    public void closeOrphanWait(String waitId) {
        TaskWaitEntity wait = waitRepository.findById(waitId).orElse(null);
        if (wait == null || !TaskWaitEntity.WAITING.equals(wait.getState())) {
            return;
        }
        int cas = waitRepository.casComplete(waitId, TaskWaitEntity.TIMED_OUT, Instant.now());
        if (cas == 0) {
            return;
        }
        Evaluation evaluation = evaluate(wait.getRootRunId(), readTaskIds(wait),
                wait.getWaitMode(), wait.getAfterCursor());
        loopSteps.fillParkedAwaitResult(wait,
                buildStructured(wait, evaluation, true),
                "await_tasks 因根 Run 已终止而关闭");
    }

    /** 默认等待窗口（timeoutMs 缺省）。 */
    public int defaultTimeoutMs() {
        return properties.orchestration().plan().awaitDefaultTimeoutMs();
    }

    private ObjectNode buildStructured(TaskWaitEntity wait, Evaluation evaluation,
                                       boolean timedOut) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("waitMode", wait.getWaitMode());
        node.put("timedOut", timedOut);
        node.put("afterCursor", Long.toString(wait.getAfterCursor()));
        node.put("cursor", Long.toString(evaluation.newCursor()));
        ArrayNode changes = node.putArray("changes");
        for (TaskEventEntity event : evaluation.changes()) {
            ObjectNode c = changes.addObject();
            c.put("taskId", event.getTaskId());
            c.put("state", event.getState());
            if (event.getAttemptId() != null) {
                c.put("attemptId", event.getAttemptId());
            }
            if (event.getChildRunId() != null) {
                c.put("childRunId", event.getChildRunId());
            }
            if (event.getReasonCode() != null) {
                c.put("reasonCode", event.getReasonCode());
            }
        }
        node.set("tasks", objectMapper.valueToTree(evaluation.tasks()));
        return node;
    }

    private void registerResumeAfterCommit(String rootRunId) {
        Runnable resume = () -> loopScheduler.scheduleResume(rootRunId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    resume.run();
                }
            });
        } else {
            resume.run();
        }
    }

    private List<String> readTaskIds(TaskWaitEntity wait) {
        try {
            return objectMapper.readValue(wait.getTaskIdsJson(), new com.fasterxml.jackson.core
                    .type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(List<String> taskIds) {
        try {
            return objectMapper.writeValueAsString(taskIds);
        } catch (Exception e) {
            throw new IllegalStateException("taskIds 序列化失败", e);
        }
    }

    private boolean isTerminalRun(String state) {
        return "COMPLETED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state);
    }

    public static Instant boundedWaitUntil(int timeoutMs) {
        return Instant.now().plus(Math.max(0, timeoutMs), ChronoUnit.MILLIS);
    }

    /** 供工具层构造停车后的特殊内部结果标记（LoopSteps 据此让出主循环线程）。 */
    public JsonNode parkedMarker() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("park", true);
        return node;
    }
}
