package com.dsh.orchestration.task;

import com.dsh.application.DshProperties;
import com.dsh.common.IdGen;
import com.dsh.orchestration.IntegrationService;
import com.dsh.orchestration.SubagentResult;
import com.dsh.orchestration.event.OrchestrationEventBridge;
import com.dsh.storage.PlanEntity;
import com.dsh.storage.PlanRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.IntegrationRecordRepository;
import com.dsh.storage.TaskAttemptEntity;
import com.dsh.storage.TaskAttemptRepository;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.TaskRepository;
import com.dsh.storage.TaskResultEntity;
import com.dsh.storage.TaskResultRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 就绪队列推进与 attempt 生命周期（MA-05，规范 §6.1 就绪条件、§6.3 短事务领取）。
 *
 * <p>状态链：DRAFT —dispatch→ PENDING —依赖全部 SUCCEEDED→ READY（同时原子创建 QUEUED
 * attempt，即可恢复的持久化待执行记录）—worker 领取 CAS→ RUNNING —子 Run 终态→
 * SUCCEEDED/FAILED/CANCELLED。检查+领取全部在短事务内，事务提交后 worker 才启动子 Run，
 * 不持数据库事务等模型。依赖失败/取消使 PENDING 直接 FAILED（无 attempt）。</p>
 *
 * <p>MA-05 基线口径：子 Run COMPLETED 直接映射任务 SUCCEEDED；MA-06 会在 RUNNING 与
 * SUCCEEDED 之间插入 VERIFYING 验收闸门。过期租约回收/fencing 写校验在 MA-08。</p>
 */
@Service
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class TaskSchedulingService {
    @org.springframework.beans.factory.annotation.Value("${dsh.orchestration.strong-runtime-enabled:false}") private boolean strongRuntime;
    @org.springframework.beans.factory.annotation.Autowired private com.dsh.orchestration.acceptance.TaskCheckDispatchGate strongGate;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate strongJdbc;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager strongEntities;
    @org.springframework.beans.factory.annotation.Autowired
    private com.dsh.orchestration.acceptance.RootTaskAcceptanceModes acceptanceModes;
    @org.springframework.beans.factory.annotation.Autowired
    private com.dsh.orchestration.acceptance.TaskCheckLifecycleGuard checkLifecycle;

    private static final Logger log = LoggerFactory.getLogger(TaskSchedulingService.class);

    static final String ATTEMPT_QUEUED = "QUEUED";
    static final String ATTEMPT_RUNNING = "RUNNING";
    static final String ATTEMPT_CANCELLING = "CANCELLING";
    static final String ATTEMPT_CANCELLED = "CANCELLED";

    /** 可重试的子 Run 启动失败（满额/预约暂缺）：attempt 回到 QUEUED，等待信号/定时重试。 */
    static final List<String> RETRYABLE_REASONS = List.of("CONCURRENCY_LIMIT", "BUDGET_LIMIT");

    private final PlanRepository planRepository;
    private final TaskRepository taskRepository;
    private final TaskAttemptRepository attemptRepository;
    private final TaskResultRepository resultRepository;
    private final TaskQueryService queryService;
    private final TaskEventJournal journal;
    private final TaskWorkerExecutor workerExecutor;
    private final TaskVerificationService verificationService;
    private final IntegrationService integrationService;
    private final IntegrationRecordRepository integrationRecordRepository;
    private final RunRepository runRepository;
    private final DshProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskSchedulingService self;
    private final OrchestrationEventBridge bridge;
    // MA-07：集成须在 afterCommit 之外执行，避免 REQUIRES_NEW 嵌套事务在同步回调窗口中
    // 干扰 TaskSynchronizationManager 状态（MA-05 并发度量测试受影响）；单线程顺序执行即可。
    private final java.util.concurrent.ExecutorService integrationExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dsh-integration");
        t.setDaemon(true);
        return t;
    });

    public TaskSchedulingService(PlanRepository planRepository, TaskRepository taskRepository,
                                 TaskAttemptRepository attemptRepository,
                                 TaskResultRepository resultRepository, TaskQueryService queryService,
                                 TaskEventJournal journal,
                                 @Lazy TaskWorkerExecutor workerExecutor,
                                 TaskVerificationService verificationService,
                                 @Lazy IntegrationService integrationService,
                                 IntegrationRecordRepository integrationRecordRepository,
                                 RunRepository runRepository,
                                 DshProperties properties,
                                 ObjectMapper objectMapper,
                                 @Lazy TaskSchedulingService self,
                                 OrchestrationEventBridge bridge) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.attemptRepository = attemptRepository;
        this.resultRepository = resultRepository;
        this.queryService = queryService;
        this.journal = journal;
        this.workerExecutor = workerExecutor;
        this.verificationService = verificationService;
        this.integrationService = integrationService;
        this.integrationRecordRepository = integrationRecordRepository;
        this.runRepository = runRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.self = self;
        this.bridge = bridge;
    }

    /** worker 领取所需的全部任务上下文（快照值，不在执行期间重读任务行）。 */
    public record Claim(boolean claimed, TaskAttemptEntity attempt, TaskEntity task,
                        String rootRunId) {
        static Claim miss() {
            return new Claim(false, null, null, null);
        }
    }

    /**
     * 事务外入口：推进某根 Run 的就绪队列，并立即 pump 一次（补偿扫描/afterCommit 共用）。
     *
     * <p>必须经 self 代理且 REQUIRES_NEW：常见调用点在别的事务 afterCommit 中，此时旧物理
     * 事务已提交但连接资源仍绑定（transactionActive 仍为 true），REQUIRED 会加入这个已提交
     * 事务导致写操作报 TransactionRequiredException。新事务提交后再 pump，worker 才能读到
     * QUEUED 行。</p>
     */
    public void promoteAndPump(String rootRunId) {
        self.promote(rootRunId);
        workerExecutor.pumpRoot(rootRunId);
    }

    /** 就绪推进（独立短事务）：PENDING→READY+QUEUED，或依赖终态失败→FAILED。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void promote(String rootRunId) {
        boolean strong=acceptanceModes.isStrong(rootRunId);
        if(strong){if(!strongRuntime)return;var source=acceptanceModes.lockSource(rootRunId);
            if(!"RUNNING".equals(source.root().getState())||source.root().getCancelRequestedAt()!=null)return;}
        List<PlanEntity> plans = planRepository.findByRootRunIdOrderByCreatedAtDesc(rootRunId);
        if (plans.isEmpty()) {
            return;
        }
        boolean changed = false;
        Instant now = Instant.now();
        for (PlanEntity plan : plans) {
            Map<String, TaskEntity> byId = new LinkedHashMap<>();
            for (TaskEntity t : taskRepository.findByPlanIdOrderByTaskIdAsc(plan.getId())) {
                byId.put(t.getTaskId(), t);
            }
            // MA-08 取消级联（AT-14）：有 CANCELLING 任务的 plan 不推进新任务（禁止新调度）
            boolean hasCancelling = false;
            for (TaskEntity t : byId.values()) {
                if (TaskStates.CANCELLING.equals(t.getState())) {
                    hasCancelling = true;
                    break;
                }
            }
            if (hasCancelling) {
                continue;
            }
            for (TaskEntity task : byId.values()) {
                if (!TaskStates.PENDING.equals(task.getState())) {
                    continue;
                }
                List<String> deps = queryService.parseDependencies(task);
                boolean allSucceeded = true;
                String blocker = null;
                for (String dep : deps) {
                    TaskEntity dependency = byId.get(dep);
                    if (dependency == null) {
                        // 脏数据防御：依赖不存在，保持 PENDING（修订校验正常不会到这里）
                        allSucceeded = false;
                        break;
                    }
                    if (TaskStates.FAILED.equals(dependency.getState())
                            || TaskStates.CANCELLED.equals(dependency.getState())) {
                        blocker = dependency.getState();
                        allSucceeded = false;
                        break;
                    }
                    if (!TaskStates.SUCCEEDED.equals(dependency.getState())) {
                        // 依赖仍在途（DRAFT/PENDING/READY/RUNNING/…）：保持 PENDING
                        allSucceeded = false;
                        break;
                    }
                    if(strong&&!isEvidenceValid(dependency)){allSucceeded=false;break;}
                }
                if (allSucceeded) {
                    if(strong&&strongGate.inspect(rootRunId,plan.getId(),List.of(task))!=null)continue;
                    long active = attemptRepository
                            .countByPlanIdAndTaskIdAndTaskVersionAndStateIn(
                                    plan.getId(), task.getTaskId(), task.getTaskVersion(),
                                    new ArrayList<>(TaskStates.ACTIVE_ATTEMPTS));
                    if (active > 0) {
                        // 已有有效尝试（被满额打回 QUEUED 等场景），不重复创建执行者
                        continue;
                    }
                    int attemptNo = attemptRepository
                            .findByPlanIdAndTaskIdOrderByAttemptNoDesc(
                                    plan.getId(), task.getTaskId())
                            .stream().findFirst().map(a -> a.getAttemptNo() + 1).orElse(1);
                    String attemptId = IdGen.next("att_");
                    attemptRepository.save(new TaskAttemptEntity(attemptId, plan.getId(),
                            task.getTaskId(), task.getTaskVersion(), null, attemptNo, null,
                            null, null, null, ATTEMPT_QUEUED, now, now));
                    task.setState(TaskStates.READY);
                    task.setUpdatedAt(now);
                    taskRepository.save(task);
                    journal.append(rootRunId, plan.getId(), task.getTaskId(),
                            task.getTaskVersion(), attemptId, null,
                            TaskStates.PENDING, TaskStates.READY, "DEPENDENCIES_SATISFIED");
                    changed = true;
                } else if (blocker != null) {
                    task.setState(TaskStates.FAILED);
                    task.setUpdatedAt(now);
                    taskRepository.save(task);
                    journal.append(rootRunId, plan.getId(), task.getTaskId(),
                            task.getTaskVersion(), null, null,
                            TaskStates.PENDING, TaskStates.FAILED, "DEPENDENCY_TERMINAL_UNSUCCESSFUL");
                    changed = true;
                }
            }
        }
        if (changed) {
            registerPumpAfterCommit(rootRunId);
        }
    }

    /** 周期补偿：把所有 PENDING 任务按根 Run 兜底推进（信号丢失时不饿死）。 */
    @Transactional(readOnly = true)
    public List<String> rootsWithPendingTasks() {
        return taskRepository.findByStateIn(List.of(TaskStates.PENDING)).stream()
                .map(TaskEntity::getPlanId).distinct().map(this::rootOfPlan).distinct().toList();
    }

    private String rootOfPlan(String planId) {
        return planRepository.findById(planId).map(PlanEntity::getRootRunId).orElse(null);
    }

    /** QUEUED attempt 短事务 CAS 领取（QUEUED→RUNNING）；取消竞争或已被领取则 miss。 */
    @Transactional
    public Claim claim(String attemptId, String leaseOwner, Instant leaseUntil) {
        if(strongRuntime){var roots=strongJdbc.queryForList("SELECT p.root_run_id FROM agent_task_attempts a JOIN agent_plans p ON p.id=a.plan_id WHERE a.attempt_id=?",String.class,attemptId);
            if(roots.size()==1&&acceptanceModes.isStrong(roots.getFirst()))return claimStrong(attemptId,leaseOwner,roots.getFirst());}
        if (checkLifecycle.blocksLegacy(attemptId)) return Claim.miss();
        TaskAttemptEntity attempt = attemptRepository.findById(attemptId).orElse(null);
        if (attempt == null) {
            return Claim.miss();
        }
        TaskEntity task = taskRepository
                .findByPlanIdAndTaskId(attempt.getPlanId(), attempt.getTaskId()).orElse(null);
        if (task == null) {
            return Claim.miss();
        }
        PlanEntity plan = planRepository.findById(attempt.getPlanId()).orElse(null);
        if (plan == null) {
            return Claim.miss();
        }
        if (!acceptanceModes.legacyAllowed(plan.getId())) return Claim.miss();
        long nextFencing = Math.max(
                attemptRepository.maxFencingToken(List.of(attempt.getPlanId())) + 1,
                nz(attempt.getFencingToken()) + 1);
        int rows = attemptRepository.claim(attemptId, leaseOwner, leaseUntil, nextFencing,
                Instant.now());
        if (rows == 0) {
            return Claim.miss();
        }
        attemptRepository.flush();
        // 任务 READY→RUNNING（事件携带 attempt/child 尚未知，childRunId 在执行后随终态事件落盘）
        if (TaskStates.READY.equals(task.getState())) {
            task.setState(TaskStates.RUNNING);
            task.setUpdatedAt(Instant.now());
            taskRepository.save(task);
            journal.append(plan.getRootRunId(), plan.getId(), task.getTaskId(),
                    task.getTaskVersion(), attemptId, null,
                    TaskStates.READY, TaskStates.RUNNING, "ATTEMPT_CLAIMED");
        }
        return new Claim(true, attempt, task, plan.getRootRunId());
    }
    private Claim claimStrong(String attemptId,String owner,String root){
        var source=acceptanceModes.lockSource(root);
        if(!"RUNNING".equals(source.root().getState())||source.root().getCancelRequestedAt()!=null
                ||!"RUNNING".equals(source.turn().getState())||!source.turn().getId().equals(source.thread().getActiveTurnId()))return Claim.miss();
        var attempt=attemptRepository.findById(attemptId).orElse(null);if(attempt==null)return Claim.miss();
        var plan=planRepository.findForUpdate(attempt.getPlanId(),root).orElseThrow();
        var tasks=taskRepository.findForPlanUpdate(plan.getId());tasks.forEach(t->strongEntities.refresh(t,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
        strongJdbc.queryForList("SELECT attempt_id FROM agent_task_attempts WHERE plan_id=? ORDER BY attempt_id FOR UPDATE",plan.getId());
        strongEntities.refresh(attempt);var task=tasks.stream().filter(t->t.getTaskId().equals(attempt.getTaskId())).findFirst().orElse(null);
        if(task==null||!"READY".equals(task.getState())||!"QUEUED".equals(attempt.getState())||task.getTaskVersion()!=attempt.getTaskVersion()
                ||attempt.getChildRunId()!=null||(attempt.getLeaseUntil()!=null&&attempt.getLeaseUntil().isAfter(Instant.now())))return Claim.miss();
        if(strongJdbc.queryForObject("SELECT count(*) FROM task_check_executions WHERE attempt_id=?",Integer.class,attemptId)>0
                ||strongJdbc.queryForObject("SELECT count(*) FROM task_check_launches WHERE attempt_id=?",Integer.class,attemptId)>0)return Claim.miss();
        if(strongGate.inspect(root,plan.getId(),List.of(task))!=null)return Claim.miss();
        for(String dep:queryService.parseDependencies(task)){var upstream=tasks.stream().filter(t->t.getTaskId().equals(dep)).findFirst().orElse(null);if(upstream==null||!isEvidenceValid(upstream))return Claim.miss();}
        var deadline=source.root().getBudgetDeadlineAt();if(deadline==null||!deadline.isAfter(Instant.now()))return Claim.miss();
        long fence=attemptRepository.maxFencingToken(queryService.rootPlanIds(root))+1;
        if(attemptRepository.claim(attemptId,owner,deadline,fence,Instant.now())!=1)return Claim.miss();
        attemptRepository.flush();strongEntities.refresh(attempt);task.setState("RUNNING");task.setUpdatedAt(Instant.now());taskRepository.saveAndFlush(task);
        journal.append(root,plan.getId(),task.getTaskId(),task.getTaskVersion(),attemptId,null,"READY","RUNNING","STRONG_ATTEMPT_CLAIMED");
        return new Claim(true,attempt,task,root);
    }

    /**
     * 可重试失败：RUNNING 回到 QUEUED（任务回到 READY 表达待领取）。
     *
     * <p>MA-08 限定重试（§10.2）：
     * <ul>
     *   <li>外部操作结果未知（ENVIRONMENT_ERROR）→ 不自动重放，落 FAILED；</li>
     *   <li>attemptNo 超过 maxRetryAttempts → task FAILED（RETRY_LIMIT_EXCEEDED）；</li>
     *   <li>否则递增 attemptNo、设置退避（复用 leaseUntil 存 nextEligibleAt），
     *       补偿扫描跳过未到退避时间的 attempt。</li>
     * </ul>
     */
    @Transactional
    public void requeue(String attemptId, String reason) {
        if (checkLifecycle.blocksLegacy(attemptId)) {
            if(!strongRuntime)return;
            var roots=strongJdbc.queryForList("SELECT p.root_run_id FROM agent_task_attempts a JOIN agent_plans p ON p.id=a.plan_id WHERE a.attempt_id=?",String.class,attemptId);
            if(roots.size()!=1||!acceptanceModes.isStrong(roots.getFirst()))return;
            var source=acceptanceModes.lockSource(roots.getFirst());if(!"RUNNING".equals(source.root().getState())||source.root().getCancelRequestedAt()!=null)return;
            if(strongJdbc.queryForObject("SELECT count(*) FROM agent_task_attempts a WHERE attempt_id=? AND child_run_id IS NULL AND NOT EXISTS(SELECT 1 FROM task_check_executions WHERE attempt_id=a.attempt_id) AND NOT EXISTS(SELECT 1 FROM task_check_launches WHERE attempt_id=a.attempt_id)",Integer.class,attemptId)!=1)return;
        }
        TaskAttemptEntity attempt = attemptRepository.findById(attemptId).orElse(null);
        if (attempt == null || !ATTEMPT_RUNNING.equals(attempt.getState())) {
            return;
        }
        TaskEntity task = taskRepository
                .findByPlanIdAndTaskId(attempt.getPlanId(), attempt.getTaskId()).orElse(null);
        PlanEntity plan = planRepository.findById(attempt.getPlanId()).orElse(null);
        Instant now = Instant.now();

        // MA-08（§10.2）：外部操作结果未知 → 不盲目自动重放，落 FAILED 待核对
        if ("ENVIRONMENT_ERROR".equals(reason)) {
            failAttemptAndTask(attempt, task, plan, now, "ENVIRONMENT_ERROR_NOT_REPLAYED",
                    "外部结果未知不重放");
            return;
        }
        // MA-08（§10.2）：attemptNo 超限 → task FAILED（RETRY_LIMIT_EXCEEDED）
        int maxRetry = properties.orchestration().maxRetryAttempts();
        if (attempt.getAttemptNo() >= maxRetry) {
            failAttemptAndTask(attempt, task, plan, now, "RETRY_LIMIT_EXCEEDED",
                    "重试超限 max=" + maxRetry);
            return;
        }
        // MA-11c-1：BUDGET_LIMIT 重试收口——父剩余可分配额度不足以支撑一份同等切片时，
        // 重开子 Run 必然再次撞上限，直接落 FAILED，不消耗本可用于主 Agent 的额度。
        if ("BUDGET_LIMIT".equals(reason) && !budgetAllowsRetry(attempt, plan)) {
            failAttemptAndTask(attempt, task, plan, now, "BUDGET_LIMIT_NO_RETRY",
                    "父 Run 剩余可分配额度不足以支撑一次同等重试（safetyFactor="
                            + properties.orchestration().retryBudgetSafetyFactor() + "）");
            return;
        }

        // 递增 attemptNo（同一 attempt 重排视为新一次尝试）；退避=base*2^(attemptNo-1)
        int nextAttemptNo = attempt.getAttemptNo() + 1;
        long backoffMs = properties.orchestration().retryBackoffBaseMs()
                * (1L << Math.max(0, nextAttemptNo - 1));
        Instant nextEligibleAt = now.plusMillis(backoffMs);
        attempt.setAttemptNo(nextAttemptNo);
        attempt.setState(ATTEMPT_QUEUED);
        attempt.setLeaseOwner(null);
        attempt.setLeaseUntil(nextEligibleAt); // QUEUED 状态下存退避时间 nextEligibleAt
        attempt.setUpdatedAt(now);
        attemptRepository.save(attempt);
        if (task != null && TaskStates.RUNNING.equals(task.getState())) {
            String from = task.getState();
            task.setState(TaskStates.READY);
            task.setUpdatedAt(now);
            taskRepository.save(task);
            if (plan != null) {
                journal.append(plan.getRootRunId(), plan.getId(), task.getTaskId(),
                        task.getTaskVersion(), attemptId, attempt.getChildRunId(),
                        from, TaskStates.READY, "REQUEUED_WITH_BACKOFF");
            }
        }
        log.info("任务尝试重新排队 attempt={} reason={} attemptNo={} backoffMs={}",
                attemptId, reason, nextAttemptNo, backoffMs);
    }

    /**
     * MA-11c-1：{@code BUDGET_LIMIT} 重试前的额度校验。
     *
     * <p>判据与 {@code SubagentService} 的切片公式一致：可分配 = 父上限 − 已结算 − 进行中预约
     * − 主任务保留；需求 = 上次子 Run 切片（子 Run 的三项上限即当时下发的切片）× 安全系数。
     * 任一维不足即判定重试必然再次撞上限。</p>
     *
     * <p>信息不足（无 plan / 无 childRunId / 查不到 Run）时保守返回 true，保持 MA-08 既有行为。</p>
     */
    private boolean budgetAllowsRetry(TaskAttemptEntity attempt, PlanEntity plan) {
        double factor = properties.orchestration().retryBudgetSafetyFactor();
        if (factor <= 0) {
            return true; // 显式关闭收口
        }
        if (plan == null || attempt.getChildRunId() == null || attempt.getChildRunId().isBlank()) {
            return true;
        }
        RunEntity child = runRepository.findById(attempt.getChildRunId()).orElse(null);
        RunEntity parent = runRepository.findById(plan.getRootRunId()).orElse(null);
        if (child == null || parent == null) {
            return true;
        }
        DshProperties.Orchestration.Quota quota = properties.orchestration().quota();
        long mrRemain = (long) parent.getBudgetMaxModelRequests()
                - parent.getBudgetModelRequestsUsed() - quota.mainReserveModelRequests();
        long tcRemain = (long) parent.getBudgetMaxToolCalls()
                - parent.getBudgetToolCallsUsed() - quota.mainReserveToolCalls();
        long tokRemain = (long) parent.getBudgetMaxCumulativeTokens()
                - nzToken(parent.getBudgetSettledTokens()) - parent.getBudgetReservedTokens()
                - quota.mainReserveTokens();
        long needMr = (long) Math.ceil((double) child.getBudgetMaxModelRequests() * factor);
        long needTc = (long) Math.ceil((double) child.getBudgetMaxToolCalls() * factor);
        long needTok = (long) Math.ceil((double) child.getBudgetMaxCumulativeTokens() * factor);
        return mrRemain >= needMr && tcRemain >= needTc && tokRemain >= needTok;
    }

    private static long nzToken(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    /** MA-08 requeue 超限/外部未知时的收敛：attempt FAILED + task FAILED + 流水。 */
    private void failAttemptAndTask(TaskAttemptEntity attempt, TaskEntity task, PlanEntity plan,
                                    Instant now, String reasonCode, String message) {
        attempt.setState(TaskStates.FAILED);
        attempt.setLeaseOwner(null);
        attempt.setLeaseUntil(null);
        attempt.setUpdatedAt(now);
        attemptRepository.save(attempt);
        if (task != null && !TaskStates.isTerminal(task.getState())) {
            String from = task.getState();
            task.setState(TaskStates.FAILED);
            task.setUpdatedAt(now);
            taskRepository.save(task);
            if (plan != null) {
                journal.append(plan.getRootRunId(), plan.getId(), task.getTaskId(),
                        task.getTaskVersion(), attempt.getAttemptId(), attempt.getChildRunId(),
                        from, TaskStates.FAILED, reasonCode);
            }
        }
        log.warn("{} attempt={} task={} reasonCode={}", message, attempt.getAttemptId(),
                task != null ? task.getTaskId() : "?", reasonCode);
    }

    /**
     * MA-08 租约过期回收（AT-15 窗口2，§10.3"运行中断"）：LeaseReaper 扫描到
     * leaseUntil &lt; now 的 RUNNING attempt。回收策略：
     * <ul>
     *   <li>任务 CANCELLING → attempt CANCELLED, task CANCELLED（取消已登记，
     *       租约过期直接收敛，不再等子 Run 安全点）；</li>
     *   <li>否则 → requeue（attempt RUNNING→QUEUED 清 lease，task RUNNING→READY），
     *       等补偿扫描重投（claim CAS 保证不会双执行者）。</li>
     * </ul>
     * fencingToken 不清除：保留单调递增，旧 token 的迟到产物由 MA-08 fencing 写校验拒绝（§8.3）。
     * 返回 false 表示已不在 RUNNING 或租约未过期（回收竞态赢家已先行收敛）。
     */
    @Transactional
    public boolean reapExpiredLease(String attemptId, Instant now) {
        // Expiry is not proof that a claimed check process stopped. Preserve all durable identities.
        if (checkLifecycle.blocksLegacy(attemptId)) return false;
        TaskAttemptEntity attempt = attemptRepository.findById(attemptId).orElse(null);
        if (attempt == null || !ATTEMPT_RUNNING.equals(attempt.getState())) {
            return false;
        }
        if (attempt.getLeaseUntil() == null || !attempt.getLeaseUntil().isBefore(now)) {
            return false; // 未过期或已被重新领取
        }
        TaskEntity task = taskRepository
                .findByPlanIdAndTaskId(attempt.getPlanId(), attempt.getTaskId()).orElse(null);
        PlanEntity plan = planRepository.findById(attempt.getPlanId()).orElse(null);
        if (task == null || plan == null) {
            return false;
        }
        if (TaskStates.CANCELLING.equals(task.getState())) {
            // 取消已登记，租约过期直接收敛 CANCELLED
            attempt.setState(ATTEMPT_CANCELLED);
            attempt.setLeaseOwner(null);
            attempt.setLeaseUntil(null);
            attempt.setUpdatedAt(now);
            attemptRepository.save(attempt);
            task.setState(TaskStates.CANCELLED);
            task.setUpdatedAt(now);
            taskRepository.save(task);
            journal.append(plan.getRootRunId(), plan.getId(), task.getTaskId(),
                    task.getTaskVersion(), attemptId, attempt.getChildRunId(),
                    TaskStates.CANCELLING, TaskStates.CANCELLED, "LEASE_EXPIRED_CANCELLED");
            log.info("租约过期回收收敛 CANCELLED attempt={} task={}", attemptId, task.getTaskId());
            return true;
        }
        // 非取消：requeue 回 QUEUED，等补偿扫描重投（claim CAS 保证不双执行者）
        attempt.setState(ATTEMPT_QUEUED);
        attempt.setLeaseOwner(null);
        attempt.setLeaseUntil(null);
        attempt.setUpdatedAt(now);
        attemptRepository.save(attempt);
        if (TaskStates.RUNNING.equals(task.getState())) {
            String from = task.getState();
            task.setState(TaskStates.READY);
            task.setUpdatedAt(now);
            taskRepository.save(task);
            journal.append(plan.getRootRunId(), plan.getId(), task.getTaskId(),
                    task.getTaskVersion(), attemptId, attempt.getChildRunId(),
                    from, TaskStates.READY, "LEASE_EXPIRED_REQUEUED");
        }
        log.info("租约过期回收 requeue attempt={} task={}", attemptId, task.getTaskId());
        return true;
    }

    /**
     * 子 Run 终态收敛：落 result + attempt/task 终态 + 流水（同一短事务）。
     *
     * <p>MA-06：子 Run COMPLETED 不直接 SUCCEEDED。先落 RUNNING→VERIFYING 中间态，
     * 再执行 {@link TaskVerificationService#verify} 验收；通过才 SUCCEEDED 并封存
     * 不可变产物（outputDigest + verificationRef + inputDigest）。验收失败（测试失败/
     * 证据缺失）落 FAILED，不释放下游依赖（AT-10）。CANCELLED/FAILED/WAITING_APPROVAL
     * 子 Run 终态沿用 MA-05 路径，不经过验收闸门。</p>
     */
    @Transactional
    public void complete(String attemptId, SubagentResult result) {
        // Only the future complete-binding CAS may consume strong evidence; a late legacy callback cannot.
        if (checkLifecycle.blocksLegacy(attemptId)) return;
        TaskAttemptEntity attempt = attemptRepository.findById(attemptId).orElse(null);
        if (attempt == null) {
            log.warn("终态收敛找不到 attempt={}（忽略，可能已被取消路径收敛）", attemptId);
            return;
        }
        // MA-08（AT-15 窗口4，完成事件重复）：complete 幂等——attempt 已终态则忽略重复 complete。
        // 重复 complete 可能来自崩溃后补偿扫描重投 + 原 worker 迟到回调，attempt 终态后不应再落 result。
        if (TaskStates.isTerminal(attempt.getState())) {
            log.info("complete 幂等忽略：attempt={} 已终态 {}（重复完成事件）", attemptId,
                    attempt.getState());
            return;
        }
        TaskEntity task = taskRepository
                .findByPlanIdAndTaskId(attempt.getPlanId(), attempt.getTaskId()).orElseThrow();
        PlanEntity plan = planRepository.findById(attempt.getPlanId()).orElseThrow();
        Instant now = Instant.now();

        String taskState;
        String reasonCode;
        String verifyDetail = null;
        String verificationRef = null;
        String inputDigest = null;
        boolean cancelAlreadyRegistered = TaskStates.CANCELLING.equals(task.getState());
        if (result.succeeded()) {
            if (cancelAlreadyRegistered) {
                // MA-05 取消竞态：取消登记先于子 Run 终态落盘（或取消信号晚到一步），
                // 迟到的 COMPLETED 绝不能把 CANCELLING 复活成 SUCCEEDED。完整级联在 MA-08。
                taskState = TaskStates.CANCELLED;
                reasonCode = "COMPLETED_AFTER_CANCEL_REQUEST";
            } else {
                // MA-06：VERIFYING 验收闸门——子 Run COMPLETED 不自动等同任务 SUCCEEDED。
                // 1. 先落 RUNNING → VERIFYING 中间态 + 事件
                String runningFrom = task.getState();
                if (!TaskStates.VERIFYING.equals(runningFrom)) {
                    task.setState(TaskStates.VERIFYING);
                    task.setUpdatedAt(now);
                    taskRepository.save(task);
                    journal.append(plan.getRootRunId(), plan.getId(), task.getTaskId(),
                            task.getTaskVersion(), attemptId, emptyToNull(result.runId()),
                            runningFrom, TaskStates.VERIFYING, "CHILD_COMPLETED_ENTER_VERIFYING");
                }
                // 2. 执行验收（read-only 查验证记录，事务内安全）
                TaskVerificationService.Outcome outcome =
                        verificationService.verify(task, result);
                if (outcome.passed()) {
                    taskState = TaskStates.SUCCEEDED;
                    reasonCode = "VERIFICATION_PASSED";
                    verificationRef = outcome.verificationRef();
                } else {
                    // AT-10：worker 自述成功但测试失败/证据缺失 → FAILED，不释放下游依赖
                    taskState = TaskStates.FAILED;
                    reasonCode = outcome.reasonCode();
                    verifyDetail = outcome.detail();
                }
                // 3. 封存 inputDigest（AT-11：上游产物摘要，用于后续证据有效性校验）
                inputDigest = computeInputDigest(plan, task);
            }
        } else if ("CANCELLED".equals(result.state())) {
            taskState = TaskStates.CANCELLED;
            reasonCode = "CHILD_CANCELLED";
        } else if ("WAITING_APPROVAL".equals(result.state())) {
            // 子 Run 卡在审批：任务照实停在 WAITING_APPROVAL（不写终态 result）；MA-08 前不自动续跑
            attempt.setChildRunId(emptyToNull(result.runId()));
            attempt.setState(TaskStates.WAITING_APPROVAL);
            attempt.setUpdatedAt(now);
            attemptRepository.save(attempt);
            if (!TaskStates.WAITING_APPROVAL.equals(task.getState())) {
                String from = task.getState();
                task.setState(TaskStates.WAITING_APPROVAL);
                task.setUpdatedAt(now);
                taskRepository.save(task);
                journal.append(plan.getRootRunId(), plan.getId(), task.getTaskId(),
                        task.getTaskVersion(), attemptId, emptyToNull(result.runId()),
                        from, TaskStates.WAITING_APPROVAL, "CHILD_WAITING_APPROVAL");
            }
            registerPostCompletion(plan.getRootRunId());
            return;
        } else {
            // MA-07 AT-12：物化冲突（多依赖同名产物内容不同）映射为 BLOCKED（§10.2 输入冲突/
            // 需求不明 → BLOCKED 主 Agent 决策）；attempt/result 如实记 FAILED（执行失败事实），
            // task 转 BLOCKED 不释放下游依赖（等 MA-09 重规划解除）。
            boolean inputConflict = "INPUT_CONFLICT".equals(result.stopReason());
            taskState = inputConflict ? TaskStates.BLOCKED : TaskStates.FAILED;
            reasonCode = result.stopReason() == null ? "CHILD_FAILED" : result.stopReason();
        }

        // attempt/result 状态：物化冲突时 task=BLOCKED 但 attempt/result 记 FAILED（执行失败）。
        String attemptState = TaskStates.CANCELLED.equals(taskState)
                ? ATTEMPT_CANCELLED
                : (TaskStates.BLOCKED.equals(taskState) ? TaskStates.FAILED : taskState);
        attempt.setChildRunId(emptyToNull(result.runId()));
        attempt.setState(attemptState);
        // MA-08 取消级联/租约回收（AT-14/AT-15）：终态收敛时释放租约
        attempt.setLeaseOwner(null);
        attempt.setLeaseUntil(null);
        attempt.setUpdatedAt(now);
        attemptRepository.save(attempt);

        String artifactRefsJson;
        try {
            artifactRefsJson = objectMapper.writeValueAsString(
                    result.artifactRefs() == null ? List.of() : result.artifactRefs());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("artifactRefs 序列化失败", e);
        }
        // MA-06：SUCCEEDED 时 errorMessage=null，验收失败时优先存验收详情
        String errorMessageField = verifyDetail != null
                ? abbreviate(verifyDetail, 4000)
                : (TaskStates.SUCCEEDED.equals(taskState) ? null
                        : abbreviate(result.summary(), 4000));
        TaskResultEntity resultRow = new TaskResultEntity(attemptId, attempt.getPlanId(),
                attempt.getTaskId(), attempt.getTaskVersion(), emptyToNull(result.runId()),
                attemptState,
                TaskStates.SUCCEEDED.equals(taskState) ? null : reasonCode,
                errorMessageField,
                artifactRefsJson, result.patchArtifactId(),
                blankToNull(result.workspaceDigest()),
                inputDigest, verificationRef,
                result.modelRequestsUsed(), result.toolCallsUsed(), result.settledTokens(),
                result.durationMs(), now);
        resultRepository.save(resultRow);
        // MA-10：终态结果同事务桥接 task.result.ready（前端展示产物/验证证据；含 BLOCKED 任务的
        // FAILED 执行结果，权威任务态以 task.state.changed 为准）
        bridge.taskResultReady(plan.getRootRunId(), resultRow, emptyToNull(result.runId()));

        String from = task.getState();
        if (!taskState.equals(from)) {
            task.setState(taskState);
            task.setUpdatedAt(now);
            taskRepository.save(task);
            journal.append(plan.getRootRunId(), plan.getId(), task.getTaskId(),
                    task.getTaskVersion(), attemptId, emptyToNull(result.runId()),
                    from, taskState, reasonCode);
        }
        // MA-09 AT-11/AT-02 版本失效传播：上游重跑 SUCCEEDED 后，下游 SUCCEEDED 任务
        // 的旧证据（inputDigest 基于旧上游产物）不再有效，主动回退到 BLOCKED。
        // 不改写旧 result 行（历史可追溯），但 task 状态不再误导集成判定。
        if (TaskStates.SUCCEEDED.equals(taskState)) {
            invalidateDownstreamEvidence(plan, task, now);
        }
        registerPostCompletion(plan.getRootRunId());
    }

    /**
     * MA-09 主动版本失效传播（AT-11/AT-02）。
     *
     * <p>当上游任务 SUCCEEDED（含重跑）后，检查所有依赖此任务的下游 SUCCEEDED 任务：
     * 如果下游旧结果的 inputDigest 已与当前上游产物不匹配（isEvidenceValid=false），
     * 将下游任务从 SUCCEEDED 回退到 BLOCKED，等待主 Agent 重规划或重新 dispatch。
     * 旧 result 行保留不改写（历史可追溯）。只处理同 plan 内下游。</p>
     */
    private void invalidateDownstreamEvidence(PlanEntity plan, TaskEntity upstreamTask,
                                              Instant now) {
        List<TaskEntity> allTasks =
                taskRepository.findByPlanIdOrderByTaskIdAsc(plan.getId());
        for (TaskEntity downstream : allTasks) {
            if (downstream.getTaskId().equals(upstreamTask.getTaskId())) {
                continue;
            }
            if (!TaskStates.SUCCEEDED.equals(downstream.getState())) {
                continue;
            }
            List<String> deps = queryService.parseDependencies(downstream);
            if (!deps.contains(upstreamTask.getTaskId())) {
                continue;
            }
            // 下游 SUCCEEDED 且依赖本上游 → 检查证据是否仍有效
            if (!isEvidenceValid(downstream)) {
                String prev = downstream.getState();
                downstream.setState(TaskStates.BLOCKED);
                downstream.setUpdatedAt(now);
                taskRepository.save(downstream);
                journal.append(plan.getRootRunId(), plan.getId(),
                        downstream.getTaskId(), downstream.getTaskVersion(),
                        null, null, prev, TaskStates.BLOCKED, "EVIDENCE_INVALIDATED");
                log.info("版本失效传播：上游 {} 重跑后下游 {} 证据失效 → BLOCKED（plan={})",
                        upstreamTask.getTaskId(), downstream.getTaskId(), plan.getId());
            }
        }
    }

    /**
     * 计算上游依赖产物的冻结摘要（MA-06 AT-11），委托 TaskQueryService 保证 read 路径一致。
     * 上游重跑后 outputDigest 变化，本值不匹配 → 旧结果不再当有效证据。
     */
    String computeInputDigest(PlanEntity plan, TaskEntity task) {
        return queryService.computeInputDigest(plan, task);
    }

    /**
     * 检查任务结果是否仍为有效证据（MA-06 AT-11）。
     * 上游产物变化或任务版本变化后，旧结果可追溯但不再有效。
     */
    @Transactional(readOnly = true)
    public boolean isEvidenceValid(TaskEntity task) {
        TaskResultEntity result = resultRepository
                .findTopByPlanIdAndTaskIdOrderByCreatedAtDesc(task.getPlanId(), task.getTaskId())
                .orElse(null);
        return queryService.isEvidenceValid(task, result);
    }

    /** 列出根 Run 全部 QUEUED attempt（pump 用，短事务只读）。 */
    @Transactional(readOnly = true)
    public List<TaskAttemptEntity> queuedAttempts(String rootRunId) {
        List<String> planIds = planRepository.findByRootRunIdOrderByCreatedAtDesc(rootRunId)
                .stream().map(PlanEntity::getId).toList();
        if (planIds.isEmpty()) {
            return List.of();
        }
        return attemptRepository.findByStateOrderByCreatedAtAsc(ATTEMPT_QUEUED).stream()
                .filter(a -> planIds.contains(a.getPlanId())).toList();
    }

    /** 全部 QUEUED attempt（启动补偿扫描用）。 */
    @Transactional(readOnly = true)
    public List<TaskAttemptEntity> allQueuedAttempts() {
        return attemptRepository.findByStateOrderByCreatedAtAsc(ATTEMPT_QUEUED);
    }

    /** 取消路径用：QUEUED 尝试 CAS 置 CANCELLED，返回是否赢家。 */
    @Transactional
    public boolean casCancelQueued(String attemptId) {
        return attemptRepository.cancelQueued(attemptId, Instant.now()) == 1;
    }

    static boolean isRetryable(String stopReason) {
        return stopReason != null && RETRYABLE_REASONS.contains(stopReason);
    }

    private void registerPostCompletion(String rootRunId) {
        // 先就绪推进（释放只依赖本任务的下游 C，不等独立的 B），再唤醒 await；均在提交后。
        // MA-07：所有当前版本必需任务 SUCCEEDED 后触发父工作区集成（§9 根 Run 完成要求）。
        Runnable hook = () -> {
            promoteAndPump(rootRunId);
            workerExecutor.signalWaiters(rootRunId);
            // MA-07：集成异步执行，避免在 afterCommit 同步回调中嵌套事务
            integrationExecutor.execute(() -> maybeIntegrate(rootRunId));
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    hook.run();
                }
            });
        } else {
            hook.run();
        }
    }
    public void onStrongCompletion(String rootRunId){registerPostCompletion(rootRunId);}
    @jakarta.annotation.PreDestroy void closeIntegrationExecutor(){integrationExecutor.shutdownNow();
        try{integrationExecutor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}

    /**
     * MA-07 §9：所有当前版本必需任务 SUCCEEDED 后触发 IntegrationService.integrate。
     *
     * <p>检查最新 plan 的 required=true 任务是否全部 SUCCEEDED；若是且无已 APPLIED/CONFLICTS
     * 终态 record（integrate 内部幂等），则触发集成。集成失败/冲突/阻塞只记录日志，
     * 不影响已完成任务的终态（根 Run 完成判定由调用方/主 Agent 据集成结果决策）。</p>
     */
    private void maybeIntegrate(String rootRunId) {
        try {
            List<PlanEntity> plans = planRepository.findByRootRunIdOrderByCreatedAtDesc(rootRunId);
            if (plans.isEmpty()) {
                return;
            }
            PlanEntity plan = plans.get(0);
            List<TaskEntity> tasks = taskRepository.findByPlanIdOrderByTaskIdAsc(plan.getId());
            boolean hasRequired = false;
            for (TaskEntity task : tasks) {
                if (!task.isRequired()) {
                    continue;
                }
                hasRequired = true;
                if (!TaskStates.SUCCEEDED.equals(task.getState())) {
                    return; // 存在未 SUCCEEDED 的必需任务，不触发集成
                }
            }
            if (!hasRequired) {
                return; // 无必需任务（如全可选），不触发集成
            }
            // MA-10：集成为独立 REQUIRES_NEW 事务；比较调用前后 record 状态，仅在本次产生新的
            // 终态结论时发 integration.completed/failed（幂等重放不重复发事件，AT-17 去重）。
            String priorState = integrationRecordRepository
                    .findFirstByRootRunIdOrderByCreatedAtDesc(rootRunId)
                    .map(com.dsh.storage.IntegrationRecordEntity::getState).orElse(null);
            IntegrationService.IntegrationOutcome outcome = integrationService.integrate(rootRunId);
            log.info("父工作区集成触发 root={} state={} applied={} conflicts={} msg={}",
                    rootRunId, outcome.state(), outcome.appliedPaths().size(),
                    outcome.conflicts().size(), outcome.message());
            if (isIntegrationTerminal(outcome.state())
                    && !outcome.state().equals(priorState)) {
                bridge.integrationFinished(rootRunId, outcome);
            }
        } catch (RuntimeException e) {
            // 集成异常不回滚已完成任务（终态已提交）；记录错误，根 Run 完成判定据此失败
            log.error("父工作区集成异常 root={}: {}", rootRunId, e.getMessage(), e);
        }
    }

    private static boolean isIntegrationTerminal(String state) {
        return IntegrationService.STATE_APPLIED.equals(state)
                || IntegrationService.STATE_CONFLICTS.equals(state)
                || IntegrationService.STATE_RECOVERED_BLOCKED.equals(state);
    }

    private void registerPumpAfterCommit(String rootRunId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    workerExecutor.pumpRoot(rootRunId);
                }
            });
        }
    }

    private static int nz(Long v) {
        return v == null ? 0 : v.intValue();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
