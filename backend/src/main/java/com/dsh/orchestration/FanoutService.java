package com.dsh.orchestration;

import com.dsh.application.DshProperties;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * D-16 有界并行子 Agent（fan-out）。
 *
 * <p>与顺序 {@link OrchestrationService}（A→C→T→R 模板）不同，本服务只处理
 * <strong>相互独立、可隔离产物</strong>的同层编码任务：主 Agent 一次给出 N 个任务，
 * 服务在有界线程池内并行执行 N 个 CODER 子 Run（各自独立子 thread/预算切片/工作区副本，
 * 全部派生自同一父工作区快照），等全部终态后做 N+1 方一次汇合：
 * 任一路径冲突整批不应用并把冲突归属（涉及子 Run + 变化类型）交回主 Agent 返工；
 * 无冲突则全部应用，父 Run 收尾时按 D-02 对合并后的工作区验证。</p>
 *
 * <p>有界性：N ≤ {@code dsh.orchestration.max-fanout}（超出整体拒绝，不部分启动）；
 * 并发度 = min(N, maxFanout)；启动前按父剩余预算做 N 份预检，任一维不足整体拒绝；
 * 各子预算仍由 BudgetLedger 原子预约/实结；取消沿既有 registerLinked 链级联。
 * 部分子 Run 失败不阻断其他子结果：成功写者照常无冲突汇合，失败清单返回主 Agent 决策返工。</p>
 */
@Service
public class FanoutService {

    private static final Logger log = LoggerFactory.getLogger(FanoutService.class);

    private final SubagentService subagentService;
    private final WorkspaceMerge workspaceMerge;
    private final WorkspaceManager workspaceManager;
    private final RunRepository runRepository;
    private final TurnRepository turnRepository;
    private final ThreadRepository threadRepository;
    private final ProjectRepository projectRepository;
    private final DshProperties properties;

    public FanoutService(SubagentService subagentService, WorkspaceMerge workspaceMerge,
                         WorkspaceManager workspaceManager, RunRepository runRepository,
                         TurnRepository turnRepository, ThreadRepository threadRepository,
                         ProjectRepository projectRepository, DshProperties properties) {
        this.subagentService = subagentService;
        this.workspaceMerge = workspaceMerge;
        this.workspaceManager = workspaceManager;
        this.runRepository = runRepository;
        this.turnRepository = turnRepository;
        this.threadRepository = threadRepository;
        this.projectRepository = projectRepository;
        this.properties = properties;
    }

    /** 单个子任务结果 + 执行线程（并行性证据）。 */
    public record Child(String runId, String state, String stopReason, String summary,
                        String workspaceRef, String patchArtifactId,
                        int modelRequestsUsed, int toolCallsUsed, Integer settledTokens,
                        long durationMs, String executionThread) {
    }

    public record MergeSummary(String status, List<String> appliedPaths,
                               List<WorkspaceMerge.MultiConflict> conflicts, int writersMerged) {
        static MergeSummary notAttempted() {
            return new MergeSummary("NOT_ATTEMPTED", List.of(), List.of(), 0);
        }
    }

    public record Totals(int modelRequests, int toolCalls, int tokens, long sumDurationMs) {
    }

    /**
     * fan-out 报告。state：
     * COMPLETED（全部完成且无冲突汇合，含无写者产物）/ CONFLICTS（汇合冲突整批未应用）/
     * PARTIAL（部分子失败，成功方已汇合或无冲突）/ FAILED（预检失败或全部失败）。
     */
    public record FanoutReport(String state, String reason, List<Child> children,
                               MergeSummary merge, Totals totals,
                               long wallClockMs, int parallelism, int taskCount) {
    }

    /** 有界并行执行 N 个独立 CODER 任务并汇合；永不向调用方抛出。 */
    public FanoutReport fanout(String parentRunId, List<String> tasks) {
        long startNanos = System.nanoTime();
        int maxFanout = properties.orchestration().maxFanout();

        List<String> normalized = new ArrayList<>();
        if (tasks != null) {
            for (String t : tasks) {
                if (t != null && !t.isBlank()) {
                    normalized.add(t.strip());
                }
            }
        }
        if (normalized.isEmpty()) {
            return failure("INVALID_ARGUMENT", "tasks 为空：至少提供 1 个独立任务",
                    List.of(), MergeSummary.notAttempted(), 0, startNanos);
        }
        if (normalized.size() > maxFanout) {
            return failure("FANOUT_LIMIT",
                    "任务数 " + normalized.size() + " 超过单次扇出上限 " + maxFanout
                            + "（有界并行：整体拒绝，请拆分批次）",
                    List.of(), MergeSummary.notAttempted(), 0, startNanos);
        }

        RunEntity parent = runRepository.findById(parentRunId).orElse(null);
        if (parent == null) {
            return failure("NOT_FOUND", "父 Run 不存在: " + parentRunId,
                    List.of(), MergeSummary.notAttempted(), 0, startNanos);
        }

        // 预算预检：N 份最小额度，任一维不足整体拒绝（不启动任何子 Run）
        int n = normalized.size();
        int mrRemain = parent.getBudgetMaxModelRequests() - parent.getBudgetModelRequestsUsed();
        int tcRemain = parent.getBudgetMaxToolCalls() - parent.getBudgetToolCallsUsed();
        int tokRemain = parent.getBudgetMaxCumulativeTokens()
                - nz(parent.getBudgetSettledTokens()) - parent.getBudgetReservedTokens();
        if (mrRemain < n || tcRemain < n || tokRemain < n) {
            return failure("FANOUT_BUDGET_LIMIT",
                    "父 Run 剩余预算不足以并行 " + n + " 个子 Agent（剩余 mr=" + mrRemain
                            + "/tc=" + tcRemain + "/tok=" + tokRemain + "，每个子至少各 1）",
                    List.of(), MergeSummary.notAttempted(), n, startNanos);
        }

        // 父工作区：所有子工作区的共同种子（同一基线快照）
        String seedRef;
        try {
            WorkspaceLease lease = workspaceManager.find(parentRunId);
            if (lease == null) {
                ProjectEntity project = projectRepository.findById(projectIdOf(parent)).orElseThrow();
                lease = workspaceManager.prepare(parentRunId, project);
                if (!lease.workspaceId().equals(parent.getWorkspaceRef())) {
                    parent.setWorkspaceRef(lease.workspaceId());
                    runRepository.save(parent);
                }
            }
            seedRef = lease.workspaceId();
        } catch (RuntimeException e) {
            return failure("WORKSPACE_UNAVAILABLE",
                    "父工作区准备失败: " + e.getMessage(),
                    List.of(), MergeSummary.notAttempted(), n, startNanos);
        }

        int parallelism = Math.min(n, maxFanout);
        AtomicInteger threadSeq = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "fanout-worker-" + threadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        List<Child> children = new ArrayList<>();
        try {
            List<Future<Child>> futures = new ArrayList<>();
            for (String task : normalized) {
                final String seed = seedRef;
                futures.add(pool.submit(() -> runOne(parentRunId, task, seed)));
            }
            for (Future<Child> future : futures) {
                try {
                    children.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    children.add(syntheticChild("", "CANCELLED", "INTERRUPTED",
                            "fan-out 等待被中断", 0));
                } catch (Exception e) {
                    children.add(syntheticChild("", "FAILED", "ENVIRONMENT_ERROR",
                            "子任务等待异常: " + e, 0));
                }
            }
        } finally {
            pool.shutdown();
        }

        // 汇合：仅 COMPLETED 且确有产物改动的子 Run 作为写者；其余作为失败/无改动交回主 Agent
        List<WorkspaceMerge.Writer> writers = new ArrayList<>();
        for (Child child : children) {
            if ("COMPLETED".equals(child.state()) && child.patchArtifactId() != null
                    && child.workspaceRef() != null && !child.workspaceRef().isBlank()) {
                writers.add(new WorkspaceMerge.Writer(child.runId(), child.workspaceRef()));
            }
        }

        MergeSummary merge;
        if (writers.isEmpty()) {
            merge = MergeSummary.notAttempted();
        } else {
            try {
                WorkspaceMerge.MultiResult result =
                        workspaceMerge.mergeAll(writers, parentRunId, workspaceManager);
                merge = new MergeSummary(result.status(), result.appliedPaths(),
                        result.conflicts(), result.writerCount());
            } catch (RuntimeException e) {
                log.warn("fan-out 汇合异常 parent={}: {}", parentRunId, e.getMessage());
                merge = new MergeSummary("ERROR", List.of(),
                        List.of(new WorkspaceMerge.MultiConflict("*",
                                List.of("merge"), List.of("ERROR:" + safe(e.getMessage())))),
                        writers.size());
            }
        }

        Totals totals = sum(children);
        long wallClockMs = (System.nanoTime() - startNanos) / 1_000_000L;
        String state;
        String reason = null;
        long failures = children.stream().filter(c -> !"COMPLETED".equals(c.state())).count();
        switch (merge.status()) {
            case "CONFLICTS", "ERROR" -> {
                state = "CONFLICTS";
                reason = "工作区汇合存在冲突，整批未应用，请按冲突清单返工后重新扇出";
            }
            default -> {
                if (failures == children.size()) {
                    state = "FAILED";
                    reason = "全部 " + children.size() + " 个子 Agent 未完成";
                } else if (failures > 0) {
                    state = "PARTIAL";
                    reason = failures + " 个子 Agent 未完成，成功方产物已"
                            + ("APPLIED".equals(merge.status()) ? "汇合" : "无需汇合")
                            + "，请按子结果决定返工";
                } else {
                    state = "COMPLETED";
                }
            }
        }
        return new FanoutReport(state, reason, List.copyOf(children), merge, totals,
                wallClockMs, parallelism, n);
    }

    private Child runOne(String parentRunId, String task, String seedRef) {
        long start = System.nanoTime();
        String thread = Thread.currentThread().getName();
        SubagentResult r = subagentService.run(
                new SubagentRequest(parentRunId, SubagentRequest.CODER, task, List.of(), seedRef));
        long durationMs = Math.max(r.durationMs(), (System.nanoTime() - start) / 1_000_000L);
        return new Child(r.runId(), r.state(), r.stopReason(), r.summary(),
                r.workspaceRef(), r.patchArtifactId(), r.modelRequestsUsed(),
                r.toolCallsUsed(), r.settledTokens(), durationMs, thread);
    }

    private Child syntheticChild(String runId, String state, String reason, String message,
                                 long durationMs) {
        return new Child(runId, state, reason, message,
                null, null, 0, 0, null, durationMs, Thread.currentThread().getName());
    }

    private FanoutReport failure(String reason, String message, List<Child> children,
                                 MergeSummary merge, int taskCount, long startNanos) {
        long wallClockMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.warn("fan-out 预检失败: {}", message);
        return new FanoutReport("FAILED", reason + ": " + message, children, merge,
                new Totals(0, 0, 0, 0), wallClockMs, 0, taskCount);
    }

    private Totals sum(List<Child> children) {
        int mr = 0, tc = 0, tok = 0;
        long dur = 0;
        for (Child c : children) {
            mr += c.modelRequestsUsed();
            tc += c.toolCallsUsed();
            tok += c.settledTokens() == null ? 0 : c.settledTokens();
            dur += c.durationMs();
        }
        return new Totals(mr, tc, tok, dur);
    }

    private String projectIdOf(RunEntity run) {
        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElseThrow();
        ThreadEntity thread = threadRepository.findById(turn.getThreadId()).orElseThrow();
        return thread.getProjectId();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
