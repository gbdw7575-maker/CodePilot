package com.dsh.orchestration.task;

import com.dsh.application.DshProperties;
import com.dsh.orchestration.SubagentRequest;
import com.dsh.orchestration.SubagentResult;
import com.dsh.orchestration.SubagentService;
import com.dsh.storage.PlanRepository;
import com.dsh.storage.TaskAttemptEntity;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 常驻有界 worker 执行器（MA-05，规范 §6.2：worker 执行池与主循环分离）。
 *
 * <p>线程池全局有界（globalMaxWorkers）；实际全局/根/提供方三级并发与预算预约由
 * {@link SubagentService} 内 MA-04 机制裁决，满额/预约暂缺的尝试回到 QUEUED 等信号/定时重试，
 * 绝不静默丢弃也不在池内无限占线程。任务启动严格发生在领取短事务提交之后。</p>
 *
 * <p>并发度量（{@link Metrics}）只用于验收证据与诊断：真实重叠以 attempt 的领取/结束
 * Instant 区间交集为准，不以线程名或耗时推断。</p>
 */
@Component
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class TaskWorkerExecutor {
    @org.springframework.beans.factory.annotation.Autowired private StrongTaskPipeline strongPipeline;

    private static final Logger log = LoggerFactory.getLogger(TaskWorkerExecutor.class);

    private final TaskSchedulingService schedulingService;
    private final SubagentService subagentService;
    private final TaskAwaitService awaitService;
    private final PlanRepository planRepository;
    private final TaskQueryService queryService;
    private final DshProperties properties;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ExecutorService pool;

    /** 已提交到池的 attempt（去重；崩溃后内存集合清空，由启动补偿扫描重新投递）。 */
    private final Set<String> submitted = ConcurrentHashMap.newKeySet();

    private final Metrics metrics = new Metrics();

    public TaskWorkerExecutor(@Lazy TaskSchedulingService schedulingService,
                              SubagentService subagentService,
                              @Lazy TaskAwaitService awaitService,
                              PlanRepository planRepository,
                              TaskQueryService queryService,
                              DshProperties properties,
                              com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.schedulingService = schedulingService;
        this.subagentService = subagentService;
        this.awaitService = awaitService;
        this.planRepository = planRepository;
        this.queryService = queryService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        int poolSize = Math.max(1, properties.orchestration().quota().globalMaxWorkers());
        this.pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "dsh-task-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** 投递根 Run 下全部 QUEUED attempt（已在池的不重复提交）。 */
    public void pumpRoot(String rootRunId) {
        Instant now = Instant.now();
        List<TaskAttemptEntity> queued = schedulingService.queuedAttempts(rootRunId);
        for (TaskAttemptEntity attempt : queued) {
            // MA-08 退避（§10.2）：QUEUED attempt 的 leaseUntil 存退避时间 nextEligibleAt，
            // 未到则跳过（由 scheduledScan 在退避到期后重投）
            if (attempt.getLeaseUntil() != null && attempt.getLeaseUntil().isAfter(now)) {
                continue;
            }
            if (submitted.add(attempt.getAttemptId())) {
                pool.submit(() -> runAttempt(attempt.getAttemptId(), attempt.getPlanId(),
                        attempt.getTaskId()));
            }
        }
    }

    /** 完成信号：唤醒该根 Run 的 await 停车（唯一结果回填由 TaskAwaitService CAS 保证）。 */
    public void signalWaiters(String rootRunId) {
        try {
            awaitService.signal(rootRunId);
        } catch (RuntimeException e) {
            log.warn("await 唤醒失败 root={}: {}", rootRunId, e.getMessage());
        }
    }

    /**
     * 定时补偿（规范 §6.3）：QUEUED 行即使 afterCommit 信号丢失也会被重新投递；
     * PENDING 任务兜底做一次就绪推进。固定延迟而非固定速率，避免雪崩连打。
     */
    @Scheduled(fixedDelayString = "${dsh.orchestration.recovery-scan-ms:2000}",
               initialDelayString = "${dsh.orchestration.recovery-scan-ms:1000}")
    public void scheduledScan() {
        try {
            Instant now = Instant.now();
            for (TaskAttemptEntity attempt : schedulingService.allQueuedAttempts()) {
                if (submitted.contains(attempt.getAttemptId())) {
                    continue;
                }
                // MA-08 退避：QUEUED attempt 的 leaseUntil 存退避时间，未到则跳过
                if (attempt.getLeaseUntil() != null && attempt.getLeaseUntil().isAfter(now)) {
                    continue;
                }
                String rootRunId = planRepository.findById(attempt.getPlanId())
                        .map(p -> p.getRootRunId()).orElse(null);
                if (rootRunId != null && submitted.add(attempt.getAttemptId())) {
                    pool.submit(() -> runAttempt(attempt.getAttemptId(), attempt.getPlanId(),
                            attempt.getTaskId()));
                }
            }
            for (String rootRunId : schedulingService.rootsWithPendingTasks()) {
                if (rootRunId != null) {
                    schedulingService.promote(rootRunId);
                }
            }
        } catch (RuntimeException e) {
            log.warn("任务调度补偿扫描失败: {}", e.getMessage());
        }
    }

    public Metrics metrics() {
        return metrics;
    }

    private void runAttempt(String attemptId, String planId, String taskId) {
        String rootRunId = null;
        Metrics.Interval interval = null;
        boolean terminal = false;
        try {
            Instant leaseUntil = Instant.now()
                    .plusSeconds(properties.orchestration().subTimeoutSeconds() + 30L);
            TaskSchedulingService.Claim claim =
                    schedulingService.claim(attemptId, "worker-pool", leaseUntil);
            if (!claim.claimed()) {
                // 取消竞争赢家或已被其他执行者领取
                return;
            }
            rootRunId = claim.rootRunId();
            SubagentRequest request = buildRequest(claim);
            interval = metrics.begin(rootRunId, attemptId);
            SubagentResult result;
            try {
                result = subagentService.run(request);
            } catch (RuntimeException e) {
                log.error("worker 子 Run 执行异常 attempt={}", attemptId, e);
                result = new SubagentResult("", SubagentRequest.CODER, "FAILED",
                        "ENVIRONMENT_ERROR", "worker 执行异常: " + e, List.of(), null,
                        "", "", 0, 0, null, 0);
            }
            interval.childRunId(result.runId());

            if (TaskSchedulingService.isRetryable(result.stopReason())) {
                // 满额/预算暂缺：持久化状态回到 QUEUED，等完成信号或补偿扫描重试
                schedulingService.requeue(attemptId, result.stopReason());
                metrics.requeued(interval);
                return;
            }
            if(strongPipeline!=null&&strongPipeline.handles(attemptId))strongPipeline.complete(attemptId,result);
            else schedulingService.complete(attemptId, result);
            terminal = true;
        } catch (RuntimeException e) {
            log.error("worker attempt 处理失败 attempt={}", attemptId, e);
            if(rootRunId!=null&&strongPipeline!=null&&strongPipeline.handles(attemptId))strongPipeline.fail(attemptId,rootRunId,"WORKER_INPUT_OR_ENVIRONMENT_FAILURE");
        } finally {
            if (interval != null) {
                if (terminal) {
                    metrics.finished(interval);
                } else if (interval.end() == null) {
                    // claim 后异常退出：如实关闭区间并释放计数（attempt 留给 MA-08 租约回收；
                    // 定时扫描不会重投 RUNNING 行，避免双执行者）
                    metrics.requeued(interval);
                }
            }
            submitted.remove(attemptId);
            // 注意：可重试失败（CONCURRENCY_LIMIT/BUDGET_LIMIT）后绝不能在本线程立即
            // promoteAndPump 自我重投——满额条件不会在 25ms 内改变，会形成忙等风暴占满 worker
            // 池并制造大量废 attempt 区间。QUEUED 行由补偿扫描（recovery-scan-ms）与其他 worker
            // 终态后的 pump 重新投递（规范 §6.3：信号可能丢失，但持久化状态不饿死）。
        }
    }

    private SubagentRequest buildRequest(TaskSchedulingService.Claim claim) {
        var task = claim.task();
        StringBuilder sb = new StringBuilder();
        sb.append("【任务目标】\n").append(nullSafe(task.getGoal())).append("\n\n");
        sb.append("【交付物】\n").append(nullSafe(task.getDeliverable())).append("\n\n");
        sb.append("【验收标准】\n");
        List<String> acceptance = new ArrayList<>();
        if (task.getAcceptance() != null && task.getAcceptance().startsWith("[")) {
            try {
                acceptance = objectMapper.readValue(task.getAcceptance(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                        });
            } catch (Exception ignored) {
                acceptance = List.of();
            }
        }
        if (acceptance.isEmpty()) {
            sb.append("- 按任务目标完成并自检\n");
        } else {
            for (String item : acceptance) {
                sb.append("- ").append(item).append('\n');
            }
        }
        // MA-07 AT-12：解析 task.inputRefs，收集上游 SUCCEEDED 任务的已验收产物 ID。
        // 物化由 SubagentService.run 在 freeze 后调 DependencyMaterializationService 完成，
        // 落地子工作区 upstream/ 目录；多依赖同名冲突进入 INPUT_CONFLICT→BLOCKED（§10.2）。
        List<String> upstreamArtifactRefs = queryService.collectUpstreamArtifactRefs(
                task.getPlanId(), task);
        if (task.getInputRefs() != null && !task.getInputRefs().isBlank()
                && !"[]".equals(task.getInputRefs())) {
            sb.append("\n【上游任务输出引用】\n");
            if (upstreamArtifactRefs.isEmpty()) {
                sb.append("- 文本说明：").append(task.getInputRefs()).append('\n');
            } else {
                sb.append("- 已物化到工作区 upstream/ 目录（").append(upstreamArtifactRefs.size())
                        .append(" 个产物），可直接读取使用。\n");
            }
        }
        String writeScope = task.getResourceScope();
        return new SubagentRequest(claim.rootRunId(), SubagentRequest.CODER, sb.toString(),
                upstreamArtifactRefs, null,
                writeScope == null || writeScope.isBlank() ? null : writeScope,
                claim.attempt().getAttemptId());
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** 并发执行度量：根内活动计数/峰值与领取-结束时间区间（AT-07/AT-08 证据源）。 */
    public static class Metrics {

        private final Map<String, AtomicInteger> active = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> peak = new ConcurrentHashMap<>();
        private final List<Interval> intervals = new CopyOnWriteArrayList<>();

        Interval begin(String rootRunId, String attemptId) {
            Interval interval = new Interval(rootRunId, attemptId, Instant.now());
            intervals.add(interval);
            int now = active.computeIfAbsent(rootRunId, k -> new AtomicInteger())
                    .incrementAndGet();
            peak.computeIfAbsent(rootRunId, k -> new AtomicInteger()).updateAndGet(p -> Math.max(p, now));
            return interval;
        }

        void requeued(Interval interval) {
            interval.end(Instant.now());
            decrement(interval.rootRunId());
        }

        void finished(Interval interval) {
            if (interval.end() == null) {
                interval.end(Instant.now());
            }
            decrement(interval.rootRunId());
        }

        void decrement(String rootRunId) {
            active.computeIfPresent(rootRunId, (k, v) -> new AtomicInteger(Math.max(0, v.get() - 1)));
        }

        public int activeCount(String rootRunId) {
            return active.getOrDefault(rootRunId, new AtomicInteger(0)).get();
        }

        public int peakCount(String rootRunId) {
            return peak.getOrDefault(rootRunId, new AtomicInteger(0)).get();
        }

        public List<Interval> intervals(String rootRunId) {
            return intervals.stream().filter(i -> i.rootRunId().equals(rootRunId)).toList();
        }

        /** 一次 attempt 的真实执行区间；end==null 表示仍在执行。 */
        public static class Interval {
            private final String rootRunId;
            private final String attemptId;
            private final Instant start;
            private volatile Instant end;
            private volatile String childRunId;

            Interval(String rootRunId, String attemptId, Instant start) {
                this.rootRunId = rootRunId;
                this.attemptId = attemptId;
                this.start = start;
            }

            public String rootRunId() {
                return rootRunId;
            }

            public String attemptId() {
                return attemptId;
            }

            public Instant start() {
                return start;
            }

            public Instant end() {
                return end;
            }

            public String childRunId() {
                return childRunId;
            }

            void end(Instant end) {
                this.end = end;
            }

            void childRunId(String childRunId) {
                this.childRunId = childRunId;
            }

            /** 与另一执行区间是否真实重叠（端点相等不算重叠）。 */
            public boolean overlaps(Interval other) {
                Instant thisEnd = end == null ? Instant.now() : end;
                Instant otherEnd = other.end == null ? Instant.now() : other.end;
                return start.isBefore(otherEnd) && other.start.isBefore(thisEnd);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
        try{if(!pool.awaitTermination(30,java.util.concurrent.TimeUnit.SECONDS))log.warn("worker 未在关闭窗口退出，持久检查须恢复");}
        catch(InterruptedException stopped){Thread.currentThread().interrupt();}
    }
}
