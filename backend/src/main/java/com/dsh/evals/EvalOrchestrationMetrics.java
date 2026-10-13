package com.dsh.evals;

import com.dsh.storage.IntegrationRecordRepository;
import com.dsh.storage.PlanEntity;
import com.dsh.storage.PlanRepository;
import com.dsh.storage.RunRepository;
import com.dsh.storage.TaskAttemptEntity;
import com.dsh.storage.TaskAttemptRepository;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 编排可观测指标只读提取（MA-11，AT-18/§12：对照必须独立记录冲突、返工与子 Run）。
 *
 * <p>全部数据来自既有持久化行，不改变任何调度/合并/预算语义：</p>
 * <ul>
 *   <li>childRunCount：{@code RunRepository.listChildren(rootRunId)} 直属子 Run 数；</li>
 *   <li>conflictCount：rootRunId 下 state=CONFLICTS 的集成记录数（汇合冲突整批不应用）；</li>
 *   <li>reworkAttempts：每个计划任务最大 attemptNo-1 之和
 *       （MA-08：requeue 重排同一 attempt 递增 attemptNo；首 attempt 不计返工）。</li>
 * </ul>
 * 单 Agent（SINGLE）运行无计划/子 Run/集成行，三项均为 0。
 */
@Service
public class EvalOrchestrationMetrics {

    /** 一次根 Run 的编排指标快照。 */
    public record Snapshot(int childRunCount, int conflictCount, int reworkAttempts) {
        public static final Snapshot ZERO = new Snapshot(0, 0, 0);
    }

    private final RunRepository runRepository;
    private final IntegrationRecordRepository integrationRecordRepository;
    private final PlanRepository planRepository;
    private final TaskRepository taskRepository;
    private final TaskAttemptRepository taskAttemptRepository;

    public EvalOrchestrationMetrics(RunRepository runRepository,
                                    IntegrationRecordRepository integrationRecordRepository,
                                    PlanRepository planRepository,
                                    TaskRepository taskRepository,
                                    TaskAttemptRepository taskAttemptRepository) {
        this.runRepository = runRepository;
        this.integrationRecordRepository = integrationRecordRepository;
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.taskAttemptRepository = taskAttemptRepository;
    }

    @Transactional(readOnly = true)
    public Snapshot collect(String rootRunId) {
        int childRunCount = runRepository.listChildren(rootRunId).size();
        int conflictCount = integrationRecordRepository
                .findByRootRunIdAndStateOrderByCreatedAtDesc(rootRunId, "CONFLICTS").size();

        int reworkAttempts = 0;
        List<PlanEntity> plans = planRepository.findByRootRunIdOrderByCreatedAtDesc(rootRunId);
        for (PlanEntity plan : plans) {
            for (TaskEntity task : taskRepository.findByPlanIdOrderByTaskIdAsc(plan.getId())) {
                List<TaskAttemptEntity> attempts = taskAttemptRepository
                        .findByPlanIdAndTaskIdOrderByAttemptNoDesc(plan.getId(), task.getTaskId());
                if (!attempts.isEmpty()) {
                    reworkAttempts += Math.max(0, attempts.get(0).getAttemptNo() - 1);
                }
            }
        }
        return new Snapshot(childRunCount, conflictCount, reworkAttempts);
    }
}
