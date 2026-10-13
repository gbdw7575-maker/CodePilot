package com.dsh.evals;

import com.dsh.api.dto.CreateProject;
import com.dsh.api.dto.CreateThread;
import com.dsh.api.dto.Diff;
import com.dsh.api.dto.DiffFile;
import com.dsh.application.ProjectService;
import com.dsh.application.ModelProfileService;
import com.dsh.application.ThreadService;
import com.dsh.application.TurnService;
import com.dsh.application.WorkspaceDiffService;
import com.dsh.common.IdGen;
import com.dsh.evals.model.EvalBudget;
import com.dsh.evals.model.EvalConfig;
import com.dsh.evals.model.EvalDataset;
import com.dsh.evals.model.EvalFailureType;
import com.dsh.evals.model.EvalGraderResult;
import com.dsh.evals.model.EvalGraderSpec;
import com.dsh.evals.model.EvalGradingMode;
import com.dsh.evals.model.EvalReport;
import com.dsh.evals.model.EvalSample;
import com.dsh.evals.model.EvalSampleResult;
import com.dsh.evals.model.EvalTrial;
import com.dsh.evals.model.EvalExecutionManifest;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 固定基线评测（doc/内部接口契约.md §8 EvalRunner：固定任务与环境、多次运行、独立评分、记录对照结果）。
 *
 * <p>复用真实 Agent 路径（TurnService.submitEval + AgentLoop + WorkspaceManager），不旁路权限/预算。
 * 每个样本×重复次数独立运行；Agent 终态后在「干净起点 + 最终补丁」工作区由 {@link EvalGrader} 独立评分，
 * 结果不回流给同次尝试。pass@1 为首轮通过率，非 best-of-retries。</p>
 */
@Service
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final ProjectService projectService;
    private final ModelProfileRepository modelProfileRepository;
    private final ThreadService threadService;
    private final TurnService turnService;
    private final RunRepository runRepository;
    private final WorkspaceDiffService workspaceDiffService;
    private final WorkspaceManager workspaceManager;
    private final CleanWorkspaceApplier cleanWorkspaceApplier;
    private final EvalGrader evalGrader;
    private final EvalReportWriter reportWriter;
    private final EvalRunAwaiter runAwaiter;
    private final EvalOrchestrationMetrics orchestrationMetrics;
    private final EvalManifestService manifests;
    private final EvalLaunchGate launchGate;

    public EvalRunner(ProjectService projectService, ModelProfileRepository modelProfileRepository,
                      ThreadService threadService,
                      TurnService turnService, RunRepository runRepository,
                      WorkspaceDiffService workspaceDiffService, WorkspaceManager workspaceManager,
                      CleanWorkspaceApplier cleanWorkspaceApplier, EvalGrader evalGrader,
                      EvalReportWriter reportWriter, EvalRunAwaiter runAwaiter,
                      EvalOrchestrationMetrics orchestrationMetrics, EvalManifestService manifests,
                      EvalLaunchGate launchGate) {
        this.projectService = projectService;
        this.modelProfileRepository = modelProfileRepository;
        this.threadService = threadService;
        this.turnService = turnService;
        this.runRepository = runRepository;
        this.workspaceDiffService = workspaceDiffService;
        this.workspaceManager = workspaceManager;
        this.cleanWorkspaceApplier = cleanWorkspaceApplier;
        this.evalGrader = evalGrader;
        this.reportWriter = reportWriter;
        this.runAwaiter = runAwaiter;
        this.orchestrationMetrics = orchestrationMetrics;
        this.manifests = manifests;
        this.launchGate = launchGate;
    }

    /**
     * @param config      评测配置（含冻结 configDigest、预算、模型配置摘要、重复次数）
     * @param dataset     数据集（含留出评分器规格，不进 Agent 工作区）
     * @param baselineDir 冻结基线目录（Agent 从未直接写入；每 Run 复制为隔离副本）
     * @param reportsDir  报告输出目录；null 则不写文件
     */
    public EvalReport evaluate(EvalConfig config, EvalDataset dataset, Path baselineDir, Path reportsDir) {
        return evaluate(config, dataset, baselineDir, reportsDir, null);
    }

    /** Explicit CLI execution: validates expected evidence before bootstrapping or submitting a Run. */
    public EvalReport evaluateGuarded(EvalConfig config, EvalDataset dataset, Path baselineDir,
                                      Path reportsDir, Path expectedFile) {
        if (reportsDir == null) throw new IllegalArgumentException("受控评测必须指定 reports-dir");
        validateReportDirectory(baselineDir, expectedFile);
        return evaluate(config, dataset, baselineDir, reportsDir, launchGate.load(expectedFile, config));
    }

    public record PreflightResult(Path manifestPath, EvalExecutionManifest manifest) {}

    /** Creates only project/rule context and private evidence, never a Thread/Turn/Run or model call. */
    public PreflightResult preflight(EvalConfig config, EvalDataset dataset, Path baselineDir, Path reportsDir) {
        if (reportsDir == null) throw new IllegalArgumentException("预检必须指定 reports-dir");
        validateInputs(config, dataset, baselineDir, reportsDir);
        String projectId = bootstrapProject(dataset, baselineDir);
        var execution = manifests.capture(config, dataset, baselineDir, projectId);
        Path file = reportWriter.writeExecutionManifest(execution, reportsDir);
        validateInputs(config, dataset, baselineDir, reportsDir);
        manifests.assertUnchanged(execution, config, dataset, baselineDir, projectId);
        return new PreflightResult(file, execution);
    }

    private void validateInputs(EvalConfig config, EvalDataset dataset, Path baselineDir, Path reportsDir) {
        if (!Files.isDirectory(baselineDir)) {
            throw new IllegalArgumentException("基线目录不存在: " + baselineDir);
        }
        if (!config.datasetName().equals(dataset.name())
                || !config.datasetVersion().equals(dataset.version())) {
            throw new IllegalArgumentException("评测配置与数据集名称/版本不匹配");
        }
        var profile = modelProfileRepository.findById(config.modelProfileId())
                .orElseThrow(() -> new IllegalArgumentException("模型配置不存在"));
        if (!config.modelProfileConfigDigest().equals(ModelProfileService.configDigest(profile))) {
            throw new IllegalArgumentException("模型配置已变化，冻结摘要不匹配");
        }
        if (config.sampleIds().stream().anyMatch(id -> dataset.samplesOfSplit(config.split())
                .stream().noneMatch(sample -> sample.id().equals(id)))) {
            throw new IllegalArgumentException("sampleIds 含当前分区外样本");
        }
        validateReportDirectory(baselineDir, reportsDir);
    }

    private EvalReport evaluate(EvalConfig config, EvalDataset dataset, Path baselineDir, Path reportsDir,
                                EvalExecutionManifest expected) {
        validateInputs(config, dataset, baselineDir, reportsDir);
        String projectId = bootstrapProject(dataset, baselineDir);
        List<EvalSample> samples = resolveSamples(config, dataset);
        var execution = manifests.capture(config, dataset, baselineDir, projectId);
        if (expected != null) launchGate.assertMatches(expected, execution);
        if(reportsDir!=null) reportWriter.writeExecutionManifest(execution,reportsDir);

        List<EvalTrial> trials = new ArrayList<>();
        for (EvalSample sample : samples) {
            for (int attempt = 1; attempt <= config.repeatCount(); attempt++) {
                if (expected != null) {
                    validateInputs(config, dataset, baselineDir, reportsDir);
                    manifests.assertUnchanged(execution, config, dataset, baselineDir, projectId);
                }
                EvalTrial trial = runTrial(sample, attempt, projectId, config, baselineDir);
                trials.add(trial);
                log.info("trial 完成 sample={} attempt={} failure={} latency={}ms",
                        sample.id(), attempt, trial.failureType(), trial.latencyMs());
            }
        }

        manifests.assertUnchanged(execution, config, dataset, baselineDir, projectId);
        EvalReport report = aggregate(config, trials, samples).withExecutionManifest(execution);
        if (reportsDir != null) {
            Path written = reportWriter.write(report, reportsDir);
            log.info("评测报告已写入: {}", written);
        }
        return report;
    }

    private static void validateReportDirectory(Path baseline, Path reports) {
        if (reports == null) return;
        try {
            Path target = reports.toAbsolutePath().normalize();
            Path ancestor = target;
            while (ancestor != null && !Files.exists(ancestor, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                ancestor = ancestor.getParent();
            if (ancestor == null) throw new java.io.IOException("报告目录没有可解析父目录");
            Path actualTarget = ancestor.toRealPath().resolve(ancestor.relativize(target)).normalize();
            if (actualTarget.startsWith(baseline.toRealPath()))
                throw new IllegalArgumentException("评测报告目录不能位于 Agent 基线内（含链接路径）");
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("评测报告目录无法安全解析", e);
        }
    }

    private String bootstrapProject(EvalDataset dataset, Path baselineDir) {
        String name = "eval-" + dataset.name() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String projectId = projectService.create(
                new CreateProject(name, baselineDir.toAbsolutePath().toString())).id();
        projectService.trust(projectId, true);
        return projectId;
    }

    private List<EvalSample> resolveSamples(EvalConfig config, EvalDataset dataset) {
        List<EvalSample> splitSamples = dataset.samplesOfSplit(config.split());
        if (config.sampleIds() == null || config.sampleIds().isEmpty()) {
            return splitSamples;
        }
        return splitSamples.stream().filter(s -> config.sampleIds().contains(s.id())).toList();
    }

    private EvalTrial runTrial(EvalSample sample, int attempt, String projectId,
                               EvalConfig config, Path baselineDir) {
        String threadId = threadService.create(projectId,
                new CreateThread(null, config.modelProfileId())).id();

        long startNanos = System.nanoTime();
        var accepted = turnService.submitEval(threadId, sample.input(),
                IdGen.next("eval_"), config.budget().maxModelRequests(),
                config.budget().maxToolCalls(), config.budget().maxCumulativeTokens(),
                config.budget().deadlineMinutes(), config.modelProfileConfigDigest());
        // 等到根、子 Run 与已派发任务收敛，再读取补丁和预算。
        runAwaiter.await(accepted, startNanos, java.time.Duration.ofMinutes(config.budget().deadlineMinutes()));
        long latencyMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);

        String runId = accepted.rootRunId();
        RunEntity run = runRepository.findById(runId).orElseThrow();

        // MA-11：编排可观测指标只读提取（SINGLE 运行三项均为 0）
        EvalOrchestrationMetrics.Snapshot orch = orchestrationMetrics.collect(runId);

        Diff diff = workspaceDiffService.compute(runId);
        List<DiffFile> diffFiles = diff.files();
        boolean hasChanges = diffFiles != null && !diffFiles.isEmpty();

        EvalGraderResult graderResult;
        EvalFailureType failureType;
        if (!"COMPLETED".equals(run.getState())) {
            log.warn("Agent 未完成 sample={} attempt={} state={} stopReason={} runId={}",
                    sample.id(), attempt, run.getState(), run.getStopReason(), runId);
            graderResult = new EvalGraderResult("SKIPPED", null, 0, 0, 0, null);
            failureType = classifyNonTerminal(run.getState(), run.getStopReason());
        } else if (!hasChanges) {
            // Agent 完成但无补丁：干净起点==基线，评分无意义，跳过（doc：模型声称改过代码但无变更记录不算完成）
            graderResult = new EvalGraderResult("SKIPPED", null, 0, 0, 0, null);
            failureType = EvalFailureType.AGENT_NO_CHANGES;
        } else {
            Path agentDir = resolveAgentWorkspaceDir(run);
            Path cleanDir = cleanWorkspaceApplier.apply(baselineDir, agentDir, diffFiles);
            try {
                graderResult = evalGrader.run(sample.grader(), cleanDir, projectId);
            } finally {
                cleanWorkspaceApplier.cleanup(cleanDir);
            }
            failureType = classifyGrader(graderResult.status());
        }

        return new EvalTrial(
                IdGen.next("trial_"), sample.id(), config.split(), attempt, runId,
                run.getState(), run.getStopReason(), diff.artifactId(),
                graderResult.status(), graderResult.exitCode(), graderResult.testsDiscovered(),
                graderResult.testsPassed(), graderResult.testsSkipped(), graderResult.logArtifactId(),
                failureType, latencyMs, run.getBudgetModelRequestsUsed(), run.getBudgetToolCallsUsed(),
                run.getBudgetSettledTokens(), run.getUsageCost(), run.getUsageCurrency(),
                run.getUsageInputTokens(), run.getUsageOutputTokens(),
                run.getUsageCacheHitTokens(), run.getUsageCacheMissTokens(),
                run.getUsagePriceVersion(),
                config.strategy(), sample.category(),
                orch.childRunCount(), orch.conflictCount(), orch.reworkAttempts());
    }

    private Path resolveAgentWorkspaceDir(RunEntity run) {
        String ref = run.getWorkspaceRef();
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException("Run 无工作区引用: " + run.getId());
        }
        WorkspaceManager.WorkspacePaths paths = workspaceManager.resolveByRef(ref);
        return paths.dir();
    }

    private static EvalFailureType classifyNonTerminal(String state, String stopReason) {
        if ("CANCELLED".equals(state) || "CANCELLING".equals(state)) {
            return EvalFailureType.AGENT_CANCELLED;
        }
        if ("BUDGET_LIMIT".equals(stopReason)) {
            return EvalFailureType.BUDGET_EXCEEDED;
        }
        return EvalFailureType.AGENT_FAILED;
    }

    private static EvalFailureType classifyGrader(String graderStatus) {
        return switch (graderStatus) {
            case "PASSED" -> EvalFailureType.PASS;
            case "NO_TESTS" -> EvalFailureType.GRADER_NO_TESTS;
            case "ERROR" -> EvalFailureType.GRADER_ERROR;
            case "UNSCORED" -> EvalFailureType.GRADER_UNSCORED;
            default -> EvalFailureType.GRADER_FAILED;
        };
    }

    /**
     * 聚合报告。public 静态，供契约测试直接构造 trials 验证 pass@1/pass@K 语义
     * （不把重试后的最好结果当默认 pass@1）。
     */
    public static EvalReport aggregate(EvalConfig config, List<EvalTrial> trials, List<EvalSample> samples) {
        int totalTrials = trials.size();
        int passedTrials = (int) trials.stream().filter(t -> t.failureType() == EvalFailureType.PASS).count();

        // 每样本汇总：按 attempt 排序，首次 PASS 轮次
        Map<String, List<EvalTrial>> bySample = new LinkedHashMap<>();
        for (EvalTrial t : trials) {
            bySample.computeIfAbsent(t.sampleId(), k -> new ArrayList<>()).add(t);
        }
        bySample.values().forEach(l -> l.sort(Comparator.comparingInt(EvalTrial::attempt)));

        List<EvalSampleResult> sampleResults = new ArrayList<>();
        int passedAt1 = 0;
        int passedAtK = 0;
        for (EvalSample s : samples) {
            List<EvalTrial> st = bySample.getOrDefault(s.id(), List.of());
            Integer firstPass = null;
            EvalFailureType lastType = EvalFailureType.AGENT_NO_CHANGES;
            for (EvalTrial t : st) {
                if (firstPass == null && t.failureType() == EvalFailureType.PASS) {
                    firstPass = t.attempt();
                }
                lastType = t.failureType();
            }
            if (!st.isEmpty() && st.get(0).failureType() == EvalFailureType.PASS) {
                passedAt1++;
            }
            if (firstPass != null) {
                passedAtK++;
            }
            sampleResults.add(new EvalSampleResult(s.id(), st.size(), firstPass,
                    firstPass != null ? EvalFailureType.PASS : lastType));
        }
        int sampleCount = Math.max(1, samples.size());
        boolean unscored = samples.stream().anyMatch(s -> s.grader().gradingMode() == EvalGradingMode.SMOKE)
                || trials.stream().anyMatch(t -> t.failureType() == EvalFailureType.GRADER_UNSCORED
                || "UNSCORED".equals(t.graderStatus()));
        Double passAt1 = unscored ? null : (double) passedAt1 / sampleCount;
        Double passAtK = unscored ? null : (double) passedAtK / sampleCount;

        Map<String, Integer> failureBreakdown = new LinkedHashMap<>();
        for (EvalFailureType ft : EvalFailureType.values()) {
            failureBreakdown.put(ft.name(), 0);
        }
        for (EvalTrial t : trials) {
            failureBreakdown.merge(t.failureType().name(), 1, Integer::sum);
        }

        long latencyMean = totalTrials == 0 ? 0
                : trials.stream().mapToLong(EvalTrial::latencyMs).sum() / totalTrials;
        long[] sorted = trials.stream().mapToLong(EvalTrial::latencyMs).sorted().toArray();
        long p95 = percentile(sorted, 95);

        int totalModelRequests = trials.stream().mapToInt(EvalTrial::modelRequests).sum();
        long totalTokens = trials.stream()
                .mapToLong(t -> t.settledTokens() == null ? 0L : t.settledTokens()).sum();

        boolean anyUnknownCost = trials.stream().anyMatch(t -> t.cost() == null);
        String totalCost = anyUnknownCost ? null : sumCosts(trials);
        String currency = trials.stream().map(EvalTrial::currency)
                .filter(c -> c != null && !c.isBlank()).findFirst().orElse(null);

        return new EvalReport(config.configDigest(), config.datasetName(), config.datasetVersion(),
                config.split(), totalTrials, passedTrials, passAt1, passAtK, failureBreakdown,
                latencyMean, p95, totalModelRequests, totalTokens, totalCost, currency,
                sampleResults, List.copyOf(trials), null);
    }

    private static String sumCosts(List<EvalTrial> trials) {
        BigDecimal sum = BigDecimal.ZERO;
        for (EvalTrial t : trials) {
            if (t.cost() != null) {
                try {
                    sum = sum.add(new BigDecimal(t.cost()));
                } catch (NumberFormatException e) {
                    return null; // 成本格式不可解析视为未知
                }
            }
        }
        return sum.toPlainString();
    }

    private static long percentile(long[] sortedAsc, int pct) {
        if (sortedAsc.length == 0) return 0;
        int idx = (int) Math.ceil(pct / 100.0 * sortedAsc.length) - 1;
        return sortedAsc[Math.max(0, Math.min(idx, sortedAsc.length - 1))];
    }
}
