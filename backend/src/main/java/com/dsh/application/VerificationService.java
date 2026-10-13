package com.dsh.application;

import com.dsh.api.dto.event.EventPayloads;
import com.dsh.api.dto.event.EventType;
import com.dsh.application.event.EventMeta;
import com.dsh.application.event.EventStore;
import com.dsh.application.trace.TraceContext;
import com.dsh.sandbox.CommandOutcome;
import com.dsh.sandbox.CommandSpec;
import com.dsh.sandbox.SandboxRunner;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.dsh.storage.VerificationEntity;
import com.dsh.common.IdGen;
import com.dsh.storage.VerificationRepository;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 验证服务：在工作区副本中执行验证命令，解析测试结果，持久化验证记录。
 * DSH-007：独立验证子系统核心。
 */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    private static final Pattern MAVEN_COUNTS_PATTERN = Pattern.compile(
            "Tests run:\\s*(\\d+)(?:,\\s*Failures:\\s*(\\d+))?"
                    + "(?:,\\s*Errors:\\s*(\\d+))?(?:,\\s*Skipped:\\s*(\\d+))?");

    /**
     * pytest 收尾行，两种形态：
     * <ul>
     *   <li>带 {@code =} 装饰（默认模式）：{@code ===== 3 passed, 1 failed, 2 skipped in 2.04s =====}</li>
     *   <li>安静模式（{@code -q}，无装饰）：{@code 3 passed, 1 failed, 2 skipped in 2.04s}</li>
     * </ul>
     * 按关键字分别取数（failure 与 error 都计为未通过）。
     *
     * <p>安静形态的判据刻意收紧：行首即「数字+关键字」、且行尾带 {@code in <时长>}
     * （pytest 摘要行的稳定特征），避免把日志正文里偶然出现的「N errors occurred」之类
     * 普通文字误当测试摘要。两形态取**最后一个**匹配（失败重跑时摘要可能输出多次）。</p>
     */
    private static final Pattern PYTEST_SUMMARY_PATTERN = Pattern.compile(
            "=+[^=\n]*(passed|failed|error|skipped|xfailed|xpassed)[^=\n]*=+"
                    + "|(?m)^\\d+\\s+(passed|failed|errors?|skipped|xfailed|xpassed)\\b[^\\n]*"
                    + "\\bin\\s+[0-9.]+m?s\\b[^\\n]*$");
    private static final Pattern PYTEST_PASSED = Pattern.compile("(\\d+)\\s+passed\\b");
    private static final Pattern PYTEST_FAILED = Pattern.compile("(\\d+)\\s+failed\\b");
    private static final Pattern PYTEST_ERROR = Pattern.compile("(\\d+)\\s+error(?:s)?\\b");
    private static final Pattern PYTEST_SKIPPED = Pattern.compile("(\\d+)\\s+skipped\\b");
    private static final Pattern PYTEST_XPASSED = Pattern.compile("(\\d+)\\s+xpassed\\b");

    private final RunRepository runRepository;
    private final TurnRepository turnRepository;
    private final ThreadRepository threadRepository;
    private final ProjectRepository projectRepository;
    private final VerificationRepository verificationRepository;
    private final WorkspaceManager workspaceManager;
    private final SandboxRunner sandboxRunner;
    private final ArtifactStore artifactStore;
    private final EventStore eventStore;
    private final TraceContext traceContext;
    private final VerificationEvidenceContext evidenceContext;

    public VerificationService(RunRepository runRepository,
                               TurnRepository turnRepository,
                               ThreadRepository threadRepository,
                               ProjectRepository projectRepository,
                               VerificationRepository verificationRepository,
                               WorkspaceManager workspaceManager,
                               SandboxRunner sandboxRunner,
                               ArtifactStore artifactStore,
                               EventStore eventStore,
                               TraceContext traceContext, VerificationEvidenceContext evidenceContext) {
        this.runRepository = runRepository;
        this.turnRepository = turnRepository;
        this.threadRepository = threadRepository;
        this.projectRepository = projectRepository;
        this.verificationRepository = verificationRepository;
        this.workspaceManager = workspaceManager;
        this.sandboxRunner = sandboxRunner;
        this.artifactStore = artifactStore;
        this.eventStore = eventStore;
        this.traceContext = traceContext;
        this.evidenceContext = evidenceContext;
    }

    /**
     * 执行验证（同步）。返回验证记录 ID。
     * 由 Run 完成流程异步调用，或供测试直接调用。
     *
     * <p>D-14 幂等边界：同一 Run 的目标、有效计划、工作区与封存内容均一致时返回既有记录，
     * 不重复执行验证命令——恢复/重入路径不产生重复外部动作。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String run(String runId) {
        return runVerification(runId);
    }

    /** A4：修正后的工作区必须重新验证；恢复时相同执行后摘要仍复用已有证据。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String runForCurrentWorkspace(String runId) {
        return runVerification(runId);
    }

    private String runVerification(String runId) {
        var existing = verificationRepository.findByRunIdOrderByStartedAtDesc(
                runId, org.springframework.data.domain.PageRequest.of(0, 1));
        RunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run 不存在: " + runId));
        TurnEntity turn = turnRepository.findById(run.getTurnId())
                .orElseThrow(() -> new IllegalArgumentException("Turn 不存在"));
        ThreadEntity thread = threadRepository.findById(turn.getThreadId())
                .orElseThrow(() -> new IllegalArgumentException("Thread 不存在"));
        ProjectEntity project = projectRepository.findById(thread.getProjectId())
                .orElseThrow(() -> new IllegalArgumentException("Project 不存在"));

        Path projectRoot = Path.of(project.getRootPath());
        VerificationPlan plan = VerificationPlan.forRun(run, projectRoot);
        var binding = evidenceContext.capture(run, plan);

        WorkspaceManager.WorkspacePaths paths = workspaceManager.resolveByRef(run.getWorkspaceRef());
        Path workspaceDir = paths.dir();
        if (!Files.isDirectory(workspaceDir)) {
            log.warn("验证跳过：工作区不存在 run={} ref={}", runId, run.getWorkspaceRef());
            return null;
        }

        String preDigest = WorkspaceManager.digestOf(workspaceDir);
        if (!existing.isEmpty() && preDigest.equals(existing.getContent().get(0).getWorkspaceDigest())
                && evidenceContext.matches(run, existing.getContent().get(0), project.getId(), binding)) {
            run.setVerificationStatus(existing.getContent().get(0).getStatus());
            runRepository.save(run);
            return existing.getContent().get(0).getId();
        }
        String imageDigest = "local-" + sandboxRunner.mode();

        Instant startedAt = Instant.now();
        String commandLine = plan.commandSummary();
        String operationId = IdGen.next("op_");

        // 构造合成租约供沙箱使用（MVP：工作区目录直接作为 workdir）
        WorkspaceLease lease = workspaceManager.find(runId);
        if (lease == null) {
            lease = new WorkspaceLease(run.getWorkspaceRef(), runId, project.getId(),
                    projectRoot, workspaceDir, paths.metaDir(), preDigest, startedAt);
        }

        CommandOutcome outcome;
        String verificationSpanId;
        var reportBefore = plan.testReports() == null ? null : SurefireReports.before(workspaceDir, plan.testReports());
        // Millisecond boundary matches host filesystem precision; unchanged bytes still fail freshness.
        Instant commandStartedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        try (TraceContext.SpanScope ignored = traceContext.startChildSpan()) {
            verificationSpanId = traceContext.spanId();
            outcome = sandboxRunner.execute(
                    new CommandSpec(operationId, com.dsh.sandbox.StrictShellCommand.wrap(commandLine),
                            workspaceDir, plan.timeoutSeconds()),
                    lease);
        }
        var reportResult = plan.testReports() == null ? null : SurefireReports.after(workspaceDir,
                plan.testReports(), reportBefore, commandStartedAt, Instant.now());
        var reproductionLogs = new java.util.ArrayList<String>();
        DefectReproduction.Result reproduction = null;
        if (plan.defectFix()) {
            reproduction = new DefectReproduction(workspaceManager, sandboxRunner)
                    .execute(lease, plan.reproduction(), (int)Math.max(0,Math.min((long)plan.timeoutSeconds(),
                            java.time.Duration.between(Instant.now(),run.getBudgetDeadlineAt()).getSeconds())));
            if (plan.reproduction()!=null) {
                var source = artifactStore.save(runId, project.getId(), "defect-source-"+IdGen.next("")+".txt",
                        "text/plain;charset=utf-8",plan.reproduction().content().getBytes(StandardCharsets.UTF_8),true);
                reproductionLogs.add(source.getId());
            }
            for (var observation:reproduction.observations()) {
                String header="defect-reproduction-v1 side="+observation.side()+" definitionDigest="+plan.reproduction().digest()
                        +" baselineDigest="+reproduction.baselineDigest()+" candidateDigest="+reproduction.candidateDigest()
                        +" goalDigest="+binding.goalDigest()+" planDigest="+binding.planDigest()
                        +" exitCode="+observation.outcome().exitCode()+" termination="+observation.outcome().termination()
                        +" cleanupConfirmed="+observation.outcome().cleanupConfirmed()+" truncated="+observation.outcome().truncated()+"\n";
                var logArtifact=artifactStore.save(runId,project.getId(),"defect-"+observation.side()+"-"+IdGen.next("")+".log",
                        "text/plain;charset=utf-8",(header+observation.log()).getBytes(StandardCharsets.UTF_8),true);
                reproductionLogs.add(logArtifact.getId());
            }
        }
        Instant finishedAt = Instant.now();

        // D-13：验证命令可能产生产物（如 target/），重新计算执行后摘要。
        // workspaceDigest 存执行后摘要，使 stale 判定语义为「验证后工作区又被改动 → 证据过期」，
        // 而非「验证本身产生产物即立即过期」。preDigest 用于识别命令是否改变了工作区。
        String postDigest = WorkspaceManager.digestOf(workspaceDir);
        boolean workspaceModified = !preDigest.equals(postDigest);

        int exitCode = outcome.exitCode() != null ? outcome.exitCode() : -1;
        String combinedOutput = (outcome.stdout() == null ? "" : outcome.stdout())
                + "\n[stderr]\n" + (outcome.stderr() == null ? "" : outcome.stderr());

        // 解析测试统计：pytest 收尾行优先（python 项目），否则回退 Maven surefire 格式
        TestCounts logCounts = parseTestCounts(combinedOutput);
        TestCounts counts = reportResult == null ? logCounts : reportResult.counts();
        if (reportResult != null) {
            combinedOutput += "\nTEST_REPORTS=" + (reportResult.valid() ? "VALID" : "INVALID")
                    + " reason=" + (reportResult.error() == null ? "VALID" : reportResult.error())
                    + " format=" + plan.testReports().format() + " files=" + reportResult.files().size() + "\n";
            var reportArtifact = artifactStore.save(runId, project.getId(), "test-reports-" + IdGen.next("") + ".json",
                    "text/plain;charset=utf-8", reportResult.evidence(), true);
            reproductionLogs.add(reportArtifact.getId());
        }
        int testsDiscovered = counts.discovered();
        int failures = counts.failures();
        int skipped = counts.skipped();
        int passed = counts.passed();

        // 退出码不能掩盖报告中的失败/错误；不要求测试也不能豁免已发现的失败。
        boolean exitOk = outcome.termination() == CommandSpec.Termination.COMPLETED && exitCode == 0
                && outcome.cleanupConfirmed();
        boolean testsOk = failures == 0 && logCounts.failures() == 0
                && (reportResult == null || reportResult.valid()) && (!plan.expectTests() || passed > 0);
        boolean reproductionOk = reproduction==null || reproduction.proven() && postDigest.equals(reproduction.candidateDigest());
        String status = (exitOk && testsOk && reproductionOk) ? "PASSED" : "FAILED";
        if (reproduction!=null) combinedOutput += "\nDEFECT_REPRODUCTION="+(reproductionOk?"PROVEN":"UNPROVEN")
                +" reason="+(reproduction.proven()&&!reproductionOk?"CANDIDATE_DRIFT":reproduction.reason())+" definitionDigest="+(plan.reproduction()==null?"MISSING":plan.reproduction().digest())+"\n";
        boolean contextStale;
        try {
            contextStale = !binding.equals(evidenceContext.capture(run, VerificationPlan.forRun(run, projectRoot)));
        } catch (RuntimeException changedToInvalidPlan) { contextStale = true; }

        // 日志存为产物
        byte[] logBytes = combinedOutput.getBytes(StandardCharsets.UTF_8);
        String logName = "verification-" + IdGen.next("") + ".log";
        var logArtifact = artifactStore.save(runId, project.getId(), logName,
                "text/plain;charset=utf-8", logBytes, true);

        VerificationEntity entity = new VerificationEntity(
                IdGen.next("ver_"), runId, plan.planVersion(), postDigest, preDigest, imageDigest,
                commandLine, status, exitCode, testsDiscovered, passed, skipped, contextStale,
                java.util.stream.Stream.concat(java.util.stream.Stream.of(logArtifact.getId()),reproductionLogs.stream())
                        .collect(java.util.stream.Collectors.joining(",")), startedAt, finishedAt);
        verificationRepository.save(entity);
        evidenceContext.seal(run, entity, project.getId(), binding);

        // 更新 Run.verificationStatus
        run.setVerificationStatus(status);
        runRepository.save(run);

        // 发布事件：workspaceDigest 为执行后摘要；验证完成瞬间证据未过期，stale=false
        EventMeta meta = new EventMeta(project.getId(), thread.getId(), run.getTurnId(), runId,
                null, run.getTraceId(), verificationSpanId);
        eventStore.append(meta, EventType.VERIFICATION_COMPLETED,
                new EventPayloads.VerificationCompleted(entity.getId(), status, postDigest,
                        preDigest, workspaceModified, contextStale));

        log.info("验证完成 run={} status={} discovered={} passed={} skipped={}",
                runId, status, testsDiscovered, passed, skipped);
        return entity.getId();
    }

    /** 解析验证输出：pytest 收尾行优先，否则回退 Maven surefire 格式。 */
    public static TestCounts parseTestCounts(String combinedOutput) {
        String pytestSummary = lastMatch(combinedOutput, PYTEST_SUMMARY_PATTERN);
        if (pytestSummary != null) {
            int pyPassed = lastIntMatch(pytestSummary, PYTEST_PASSED, 0);
            int pyFailed = lastIntMatch(pytestSummary, PYTEST_FAILED, 0);
            int pyErrors = lastIntMatch(pytestSummary, PYTEST_ERROR, 0);
            int pyXpassed = lastIntMatch(pytestSummary, PYTEST_XPASSED, 0);
            int pySkipped = lastIntMatch(pytestSummary, PYTEST_SKIPPED, 0);
            int failures = pyFailed + pyErrors + pyXpassed;
            return new TestCounts(pyPassed + failures + pySkipped, pyPassed, failures, pySkipped);
        }
        return parseMavenCounts(combinedOutput);
    }

    /**
     * 每个 Maven 模块的 Results 总计替代该模块 suite 行，避免重复计数；模块之间累加。
     * -q 等没有 Results 的输出按 suite 行累加，兼容既有仅 Tests run 的内部探针。
     * 这是日志适配器，不能替代后续结构化报告或证明任意测试框架均可识别。
     */
    private static TestCounts parseMavenCounts(String output) {
        if (output == null || output.isEmpty()) return new TestCounts(0, 0, 0, 0);
        int discovered = 0, failures = 0, skipped = 0;
        int moduleDiscovered = 0, moduleFailures = 0, moduleSkipped = 0;
        boolean afterResults = false;
        for (String line : output.split("\\R")) {
            if (line.matches("\\s*(?:\\[[A-Z]+\\]\\s*)?Results:\\s*")) {
                afterResults = true;
                continue;
            }
            Matcher matcher = MAVEN_COUNTS_PATTERN.matcher(line);
            if (!matcher.find()) continue;
            int count = number(matcher.group(1));
            int failed = number(matcher.group(2)) + number(matcher.group(3));
            int ignored = number(matcher.group(4));
            boolean suiteLine = line.matches(".*--? in .*");
            if (afterResults && !suiteLine) {
                discovered += count;
                // 总计不能把同模块已出现的失败证据改成零。
                failures += Math.max(failed, moduleFailures);
                skipped += ignored;
                moduleDiscovered = moduleFailures = moduleSkipped = 0;
                afterResults = false;
            } else {
                moduleDiscovered += count;
                moduleFailures += failed;
                moduleSkipped += ignored;
            }
        }
        discovered += moduleDiscovered;
        failures += moduleFailures;
        skipped += moduleSkipped;
        return new TestCounts(discovered, Math.max(0, discovered - failures - skipped), failures, skipped);
    }

    private static int number(String value) {
        return value == null ? 0 : Integer.parseInt(value);
    }

    private static int lastIntMatch(String text, Pattern pattern, int defaultValue) {
        if (text == null || text.isEmpty()) return defaultValue;
        Matcher matcher = pattern.matcher(text);
        int result = defaultValue;
        while (matcher.find()) {
            try {
                result = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    /** 返回文本中最后一个完整匹配（pytest 失败时可能多次输出摘要，以末次为准）。 */
    private static String lastMatch(String text, Pattern pattern) {
        if (text == null || text.isEmpty()) return null;
        Matcher matcher = pattern.matcher(text);
        String result = null;
        while (matcher.find()) {
            result = matcher.group();
        }
        return result;
    }
}
