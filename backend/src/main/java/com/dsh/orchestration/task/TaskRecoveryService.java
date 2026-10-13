package com.dsh.orchestration.task;

import com.dsh.storage.PlanEntity;
import com.dsh.storage.PlanRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.TaskAttemptEntity;
import com.dsh.storage.TaskAttemptRepository;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 进程启动补偿（MA-05，规范 §6.3：不能只依赖 afterCommit，必须有可恢复的持久化
 * 待执行记录）。任务调度的持久化锚点是 QUEUED attempt 行：
 * <ul>
 *   <li>根 Run 仍活跃：QUEUED 行重新投递到 worker（claim CAS 保证不会双执行者）；
 *       PENDING 行兜底做就绪推进；</li>
 *   <li>根 Run 已终态/不存在：QUEUED 尝试与未启动任务置 CANCELLED 并发信号，
 *       不替用户重放。</li>
 * </ul>
 *
 * <p>RUNNING 孤儿 attempt：租约已过期者由 MA-08 {@link LeaseReaper} 与本服务的启动
 * 扫描一并回收（requeue 回 QUEUED 由补偿扫描重投，claim CAS 保证不双执行者）；
 * 租约未过期的孤儿不回收（避免双执行者）。五窗口裁决在 MA-08（AT-14/15）。</p>
 */
@Component
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class TaskRecoveryService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.dsh.orchestration.acceptance.TaskCheckLifecycleGuard checkLifecycle;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private TaskRecoveryService self;

    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryService.class);
    private static final Set<String> ACTIVE_RUN_STATES =
            Set.of("QUEUED", "PREPARING", "RUNNING", "VERIFYING", "WAITING_APPROVAL",
                    "WAITING_INPUT", "CANCELLING", "BLOCKED");

    private final TaskSchedulingService schedulingService;
    private final TaskWorkerExecutor workerExecutor;
    private final TaskAttemptRepository attemptRepository;
    private final TaskRepository taskRepository;
    private final PlanRepository planRepository;
    private final RunRepository runRepository;
    private final TaskEventJournal journal;

    public TaskRecoveryService(TaskSchedulingService schedulingService,
                               TaskWorkerExecutor workerExecutor,
                               TaskAttemptRepository attemptRepository,
                               TaskRepository taskRepository, PlanRepository planRepository,
                               RunRepository runRepository, TaskEventJournal journal) {
        this.schedulingService = schedulingService;
        this.workerExecutor = workerExecutor;
        this.attemptRepository = attemptRepository;
        this.taskRepository = taskRepository;
        this.planRepository = planRepository;
        this.runRepository = runRepository;
        this.journal = journal;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        int resumed = 0;
        int cancelled = 0;
        for (TaskAttemptEntity attempt : schedulingService.allQueuedAttempts()) {
            PlanEntity plan = planRepository.findById(attempt.getPlanId()).orElse(null);
            if (plan == null) {
                continue;
            }
            if (isRootActive(plan.getRootRunId())) {
                workerExecutor.pumpRoot(plan.getRootRunId());
                resumed++;
            } else {
                cancelled += self.cancelQueuedIfWon(attempt, plan.getRootRunId());
            }
        }
        // 根 Run 已终态：未启动任务（PENDING/READY）收敛 CANCELLED；活根 Run 的 PENDING 兜底推进
        List<TaskEntity> inflight = taskRepository.findByStateIn(
                List.of(TaskStates.PENDING, TaskStates.READY));
        for (TaskEntity task : inflight) {
            PlanEntity plan = planRepository.findById(task.getPlanId()).orElse(null);
            if (plan == null) {
                continue;
            }
            if (isRootActive(plan.getRootRunId())) {
                schedulingService.promoteAndPump(plan.getRootRunId());
            } else if (TaskStates.PENDING.equals(task.getState())) {
                if (self.cancelPendingTaskIfSafe(task, plan.getRootRunId())) cancelled++;
            }
        }
        // MA-08（AT-15 窗口2）：RUNNING 孤儿 attempt 租约过期回收。租约未过期者不回收
        // （避免双执行者）；活根与终态根一律回收（过期租约说明 worker 已不活跃）。
        int orphanReaped = 0;
        Instant reapNow = Instant.now();
        for (TaskAttemptEntity attempt : attemptRepository.findExpiredRunning(reapNow)) {
            try {
                if (schedulingService.reapExpiredLease(attempt.getAttemptId(), reapNow)) {
                    orphanReaped++;
                }
            } catch (RuntimeException e) {
                log.warn("启动恢复租约回收失败 attempt={}: {}", attempt.getAttemptId(),
                        e.getMessage());
            }
        }
        log.info("MA-05 任务调度启动恢复完成：重新投递 {} 个根 Run 的待执行尝试，取消 {} 项，"
                + "回收租约过期孤儿 {} 项", resumed, cancelled, orphanReaped);
    }

    private boolean isRootActive(String rootRunId) {
        RunEntity root = runRepository.findById(rootRunId).orElse(null);
        return root != null && ACTIVE_RUN_STATES.contains(root.getState());
    }

    @Transactional
    public int cancelQueuedIfWon(TaskAttemptEntity attempt, String rootRunId) {
        if (checkLifecycle.blocksLegacy(attempt.getAttemptId())) return 0;
        if (attemptRepository.cancelQueued(attempt.getAttemptId(), Instant.now()) != 1) {
            return 0;
        }
        TaskEntity task = taskRepository
                .findByPlanIdAndTaskId(attempt.getPlanId(), attempt.getTaskId()).orElse(null);
        if (task != null && !TaskStates.isTerminal(task.getState())) {
            String from = task.getState();
            task.setState(TaskStates.CANCELLED);
            task.setUpdatedAt(Instant.now());
            taskRepository.save(task);
            journal.append(rootRunId, task.getPlanId(), task.getTaskId(), task.getTaskVersion(),
                    attempt.getAttemptId(), null, from, TaskStates.CANCELLED,
                    "ROOT_TERMINAL_CANCELLED");
        }
        return 1;
    }

    @Transactional
    public void cancelPendingTask(TaskEntity task, String rootRunId) {
        self.cancelPendingTaskIfSafe(task, rootRunId);
    }

    @Transactional
    public boolean cancelPendingTaskIfSafe(TaskEntity task, String rootRunId) {
        var attempts = attemptRepository.findByPlanIdAndTaskIdOrderByAttemptNoDesc(task.getPlanId(), task.getTaskId());
        if (attempts.stream().anyMatch(a -> checkLifecycle.blocksLegacy(a.getAttemptId()))) return false;
        String from = task.getState();
        task.setState(TaskStates.CANCELLED);
        task.setUpdatedAt(Instant.now());
        taskRepository.save(task);
        journal.append(rootRunId, task.getPlanId(), task.getTaskId(), task.getTaskVersion(),
                null, null, from, TaskStates.CANCELLED, "ROOT_TERMINAL_CANCELLED");
        return true;
    }
}
