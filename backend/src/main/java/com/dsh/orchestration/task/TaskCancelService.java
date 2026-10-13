package com.dsh.orchestration.task;

import com.dsh.agent.control.CancellationTokenRegistry;
import com.dsh.storage.TaskAttemptEntity;
import com.dsh.storage.TaskAttemptRepository;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * cancel_tasks 登记服务（MA-05 基础闭环；完整取消级联/租约 fencing 在 MA-08，AT-14/15）。
 *
 * <p>语义：取消是<strong>登记+信号</strong>，返回“已接受+当前状态”，不宣称已立即终止；
 * 实际终止以子 Run 终态为准（worker 完成收敛时落 CANCELLED）。PENDING/READY(QUEUED)
 * 在短事务 CAS 中直接终止；RUNNING 置 CANCELLING 并经取消令牌通知子 Run 安全点。
 * 已终态任务幂等返回当前状态。</p>
 */
@Service
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class TaskCancelService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.dsh.orchestration.acceptance.TaskCheckLifecycleGuard checkLifecycle;

    private static final Logger log = LoggerFactory.getLogger(TaskCancelService.class);

    private final TaskQueryService queryService;
    private final TaskRepository taskRepository;
    private final TaskAttemptRepository attemptRepository;
    private final TaskEventJournal journal;
    private final CancellationTokenRegistry tokenRegistry;
    private final TaskAwaitService awaitService;

    public TaskCancelService(TaskQueryService queryService, TaskRepository taskRepository,
                             TaskAttemptRepository attemptRepository, TaskEventJournal journal,
                             CancellationTokenRegistry tokenRegistry,
                             @Lazy TaskAwaitService awaitService) {
        this.queryService = queryService;
        this.taskRepository = taskRepository;
        this.attemptRepository = attemptRepository;
        this.journal = journal;
        this.tokenRegistry = tokenRegistry;
        this.awaitService = awaitService;
    }

    public record Item(String taskId, String state, boolean accepted, boolean signalDelivered,
                       String message) {
    }

    @Transactional
    public List<Item> cancel(String rootRunId, List<String> taskIds, String reason) {
        // Use the shared business-source lock order before any attempt/launch/task writes.
        checkLifecycle.lockRoot(rootRunId);
        Map<String, TaskEntity> index = queryService.indexRootTasks(rootRunId);
        List<Item> items = new ArrayList<>();
        boolean changed = false;
        for (String taskId : taskIds) {
            TaskEntity task = index.get(taskId);
            if (task == null) {
                items.add(new Item(taskId, null, false, false,
                        "任务不存在或不属于当前根 Run: " + taskId));
                continue;
            }
            Item item = cancelOne(rootRunId, task, reason);
            items.add(item);
            changed |= item.accepted();
        }
        if (changed) {
            // MA-08 取消级联（AT-14，§10.3）：取消 A 后，依赖 A 的非终态下游一并取消。
            Set<String> cancelled = new HashSet<>();
            for (Item item : items) {
                if (item.accepted()) {
                    cancelled.add(item.taskId());
                }
            }
            if (!cancelled.isEmpty()) {
                items.addAll(cascadeCancelDownstream(rootRunId, index, cancelled, reason));
            }
            registerSignalAfterCommit(rootRunId);
        }
        return items;
    }

    /**
     * MA-08 取消级联（AT-14，§10.3）：上游被取消后，依赖它的非终态下游一并取消。
     * 传递性 BFS：取消 A → B(依赖A) 取消 → C(依赖B) 取消。复用 cancelOne 的状态分支：
     * PENDING/BLOCKED→CANCELLED；READY/QUEUED→cancelQueued CAS；RUNNING 等→CANCELLING+信号。
     */
    private List<Item> cascadeCancelDownstream(String rootRunId, Map<String, TaskEntity> index,
                                               Set<String> cancelledUpstream, String reason) {
        List<Item> cascade = new ArrayList<>();
        Set<String> processed = new HashSet<>(cancelledUpstream);
        Queue<String> queue = new ArrayDeque<>(cancelledUpstream);
        while (!queue.isEmpty()) {
            String upstreamId = queue.poll();
            for (TaskEntity task : index.values()) {
                if (processed.contains(task.getTaskId())) {
                    continue;
                }
                if (TaskStates.isTerminal(task.getState())) {
                    continue;
                }
                List<String> deps = queryService.parseDependencies(task);
                if (deps.contains(upstreamId)) {
                    Item item = cancelOne(rootRunId, task,
                            "UPSTREAM_CANCELLED:" + upstreamId);
                    cascade.add(item);
                    processed.add(task.getTaskId());
                    if (item.accepted()) {
                        queue.add(task.getTaskId());
                    }
                }
            }
        }
        return cascade;
    }

    private Item cancelOne(String rootRunId, TaskEntity task, String reason) {
        Instant now = Instant.now();
        String state = task.getState();
        if (TaskStates.isTerminal(state)) {
            return new Item(task.getTaskId(), state, false, false, "任务已终态（" + state
                    + "），取消幂等返回当前状态");
        }
        List<TaskAttemptEntity> attempts = attemptRepository
                .findByPlanIdAndTaskIdOrderByAttemptNoDesc(task.getPlanId(), task.getTaskId());
        TaskAttemptEntity latest = attempts.isEmpty() ? null : attempts.get(0);
        // Cancel every generation, including an older UNKNOWN operation; never hide it behind the latest fence.
        attempts.stream().map(TaskAttemptEntity::getAttemptId).sorted().forEach(checkLifecycle::cancelChecks);
        if (attempts.stream().anyMatch(a -> checkLifecycle.blocksLegacy(a.getAttemptId()))) {
            return signalRunning(rootRunId, task, latest, now,
                    "检查取消已持久登记；进程停止和清理确认前保持 CANCELLING", true);
        }

        switch (state) {
            case TaskStates.PENDING, TaskStates.BLOCKED -> {
                moveTo(rootRunId, task, TaskStates.CANCELLED,
                        latest == null ? null : latest.getAttemptId(), null,
                        "CANCEL_REQUESTED", now);
                return new Item(task.getTaskId(), TaskStates.CANCELLED, true, false,
                        "未启动任务已登记取消并置 CANCELLED");
            }
            case TaskStates.READY -> {
                if (latest != null && "QUEUED".equals(latest.getState())
                        && attemptRepository.cancelQueued(latest.getAttemptId(), now) == 1) {
                    moveTo(rootRunId, task, TaskStates.CANCELLED, latest.getAttemptId(),
                            null, "CANCEL_REQUESTED", now);
                    return new Item(task.getTaskId(), TaskStates.CANCELLED, true, false,
                            "待领取尝试的取消 CAS 胜出，任务置 CANCELLED（未启动子 Run）");
                }
                // 竞争输给领取：转 RUNNING 取消路径
                return signalRunning(rootRunId, task, latest, now,
                        "领取竞争后转取消信号", false);
            }
            case TaskStates.RUNNING, TaskStates.VERIFYING, TaskStates.WAITING_APPROVAL,
                 TaskStates.CANCELLING -> {
                return signalRunning(rootRunId, task, latest, now,
                        "取消请求已登记（CANCELLING），子 Run 在安全点终止后收敛 CANCELLED", false);
            }
            default -> {
                return new Item(task.getTaskId(), state, false, false,
                        "当前状态 " + state + " 不接受取消登记");
            }
        }
    }

    private Item signalRunning(String rootRunId, TaskEntity task, TaskAttemptEntity latest,
                               Instant now, String message, boolean preserveCheckAttempt) {
        moveTo(rootRunId, task, TaskStates.CANCELLING,
                latest == null ? null : latest.getAttemptId(),
                latest == null ? null : latest.getChildRunId(),
                "CANCEL_REQUESTED", now);
        if (!preserveCheckAttempt && latest != null && "QUEUED".equals(latest.getState())) {
            attemptRepository.cancelQueued(latest.getAttemptId(), now);
        }
        boolean signalled = false;
        if (latest != null && latest.getChildRunId() != null) {
            signalled = tokenRegistry.requestCancel(latest.getChildRunId());
        }
        return new Item(task.getTaskId(), TaskStates.CANCELLING, true, signalled, message);
    }

    private void moveTo(String rootRunId, TaskEntity task, String to, String attemptId,
                        String childRunId, String reasonCode, Instant now) {
        String from = task.getState();
        if (to.equals(from)) {
            return;
        }
        task.setState(to);
        task.setUpdatedAt(now);
        taskRepository.save(task);
        journal.append(rootRunId, task.getPlanId(), task.getTaskId(), task.getTaskVersion(),
                attemptId, childRunId, from, to, reasonCode);
    }

    private void registerSignalAfterCommit(String rootRunId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    awaitService.signal(rootRunId);
                }
            });
        } else {
            awaitService.signal(rootRunId);
        }
    }
}
