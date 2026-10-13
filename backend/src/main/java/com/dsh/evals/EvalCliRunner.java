package com.dsh.evals;

import com.dsh.evals.model.CategoryComparison;
import com.dsh.evals.model.EvalComparison;
import com.dsh.evals.model.EvalConfig;
import com.dsh.evals.model.EvalDataset;
import com.dsh.evals.model.EvalReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 评测 CLI 入口（doc/内部接口契约.md §8：EvalRunner 为内部 CLI，不增加评测平台 HTTP 页面）。
 *
 * <p>C15：config模式默认仅预检，不提交Run。执行需显式preflight-only=false及expected-manifest。</p>
 * <p>原对照模式保持只读；内部evaluate兼容调用不经CLI闸门。</p>
 * <p>模式：</p>
 * <ol>
 *   <li>评测模式（设置 dsh.eval.config 激活）：
 *   <pre>
 *     mvn spring-boot:run -Dspring-boot.run.arguments="--dsh.eval.config=evals/configs/c1.json \
 *       --dsh.eval.dataset=evals/datasets/d1.json --dsh.eval.baseline=evals/baselines/d1 \
 *       --dsh.eval.reports-dir=evals/reports"
 *   </pre>
 *   单/多 Agent 对照（MA-11，AT-18）用同一命令分别在两个进程运行，仅 MULTI 侧追加
 *   {@code --dsh.orchestration.enabled=true}，其余因子逐字一致。</li>
 *   <li>对照模式（MA-11，设置 dsh.eval.compare-a 激活；离线只读，不改任何运行数据）：
 *   <pre>
 *     mvn spring-boot:run -Dspring-boot.run.arguments="--dsh.eval.compare-a=evals/configs/single.json \
 *       --dsh.eval.compare-b=evals/configs/multi.json \
 *       --dsh.eval.dataset=evals/datasets/d1.json \
 *       --dsh.eval.report-a=evals/reports/&lt;singleDigest&gt;-ts.json \
 *       --dsh.eval.report-b=evals/reports/&lt;multiDigest&gt;-ts.json \
 *       --dsh.eval.reports-dir=evals/reports"
 *   </pre>
 *   两份配置必须仅 strategy 不同，否则拒绝生成对照。</li>
 * </ol>
 */
@Component
@ConditionalOnExpression(
        "!('${dsh.eval.config:}'.isEmpty() and '${dsh.eval.compare-a:}'.isEmpty())")
public class EvalCliRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalCliRunner.class);

    private final EvalDatasetLoader loader;
    private final EvalRunner evalRunner;
    private final EvalComparisonService comparisonService;
    private final EvalComparisonWriter comparisonWriter;
    private final EvalPreflight preflight;

    public EvalCliRunner(EvalDatasetLoader loader, EvalRunner evalRunner,
                         EvalComparisonService comparisonService,
                         EvalComparisonWriter comparisonWriter, EvalPreflight preflight) {
        this.loader = loader;
        this.evalRunner = evalRunner;
        this.comparisonService = comparisonService;
        this.comparisonWriter = comparisonWriter;
        this.preflight = preflight;
    }

    @Override
    public void run(String... args) {
        if (execute(args)) System.exit(0);
    }

    // Kept separate from process exit so routing can be verified without starting a model/process.
    boolean execute(String... args) {
        EvalCliProps props = EvalCliProps.fromArgs(args);
        if (props.compareA != null) {
            runCompare(props);
        } else if (props.config != null) {
            runEval(props);
        } else {
            return false;
        }
        return true;
    }

    private void runEval(EvalCliProps props) {
        EvalConfig config = loader.loadConfig(Path.of(props.config));
        preflight.validate(config);
        EvalDataset dataset = loader.loadDataset(Path.of(props.dataset));
        Path baselineDir = Path.of(props.baseline);
        if (!Files.isDirectory(baselineDir)) {
            throw new IllegalArgumentException("基线目录不存在: " + baselineDir);
        }
        Path reportsDir = props.reportsDir == null ? null : Path.of(props.reportsDir);

        if (props.preflightOnly) {
            var result = evalRunner.preflight(config, dataset, baselineDir, reportsDir);
            System.out.printf("[eval-preflight] modelCalls=0 trials=0 executionDigest=%s manifest=%s%n",
                    result.manifest().executionDigest(), result.manifestPath());
            return;
        }
        log.info("启动受控评测 dataset={}/{} split={} strategy={} repeat={}", dataset.name(),
                dataset.version(), config.split(), config.strategy(), config.repeatCount());
        EvalReport report = evalRunner.evaluateGuarded(config, dataset, baselineDir, reportsDir,
                Path.of(props.expectedManifest));
        log.info("评测完成 strategy={} pass@1={} pass@K={} totalTrials={} passedTrials={}",
                config.strategy(), report.passAt1(), report.passAtK(),
                report.totalTrials(), report.passedTrials());
        String firstScore = report.passAt1() == null ? "UNSCORED"
                : String.format(java.util.Locale.ROOT, "%.3f", report.passAt1());
        String retryScore = report.passAtK() == null ? "UNSCORED"
                : String.format(java.util.Locale.ROOT, "%.3f", report.passAtK());
        System.out.printf("[eval] strategy=%s pass@1=%s pass@K=%s trials=%d passed=%d failures=%s%n",
                config.strategy(), firstScore, retryScore, report.totalTrials(),
                report.passedTrials(), report.failureBreakdown());
    }

    private void runCompare(EvalCliProps props) {
        if (props.compareB == null || props.reportA == null || props.reportB == null
                || props.dataset == null || props.dataset.isBlank()) {
            throw new IllegalArgumentException(
                    "compare 模式需要 dsh.eval.compare-b、dsh.eval.report-a、dsh.eval.report-b、dsh.eval.dataset");
        }
        EvalConfig configA = loader.loadConfig(Path.of(props.compareA));
        EvalConfig configB = loader.loadConfig(Path.of(props.compareB));
        EvalReport reportA = loader.loadReport(Path.of(props.reportA));
        EvalReport reportB = loader.loadReport(Path.of(props.reportB));
        EvalDataset dataset = loader.loadDataset(Path.of(props.dataset));

        EvalComparison comparison = comparisonService.compare(
                configA, configB, reportA, reportB, dataset);
        Path written = null;
        if (props.reportsDir != null) {
            written = comparisonWriter.write(comparison, Path.of(props.reportsDir));
        }
        log.info("对照完成 dataset={}/{} split={} singleDigest={} multiDigest={}",
                comparison.datasetName(), comparison.datasetVersion(), comparison.split(),
                comparison.overallSingle().configDigest(),
                comparison.overallMulti().configDigest());
        System.out.printf("%-13s %8s %8s %10s %12s %8s %8s %8s%n",
                "category", "trials", "pass@1", "latencyMs", "tokens",
                "child", "conflict", "rework");
        printSide("SINGLE", comparison.overallSingle(), "TOTAL");
        printSide("MULTI ", comparison.overallMulti(), "TOTAL");
        for (CategoryComparison row : comparison.categories()) {
            printSide("SINGLE", row.single(), row.category());
            printSide("MULTI ", row.multi(), row.category());
        }
        comparison.warnings().forEach(w -> System.out.println("[warn] " + w));
        if (written != null) {
            System.out.println("[compare] 对照报告: " + written.toAbsolutePath());
        }
    }

    private static void printSide(String side,
                                  com.dsh.evals.model.ComparisonSide stats, String category) {
        System.out.printf("%s %-6s %8d %8.3f %10d %12d %8d %8d %8d%n",
                side, category, stats.trials(), stats.passAt1(), stats.latencyMeanMs(),
                stats.totalTokens(), stats.childRunCount(), stats.conflictCount(),
                stats.reworkAttempts());
    }

    private record EvalCliProps(String config, String dataset, String baseline, String reportsDir,
                                String compareA, String compareB, String reportA, String reportB,
                                boolean preflightOnly, String expectedManifest) {
        static EvalCliProps fromArgs(String[] args) {
            String config = System.getProperty("dsh.eval.config");
            String dataset = System.getProperty("dsh.eval.dataset");
            String baseline = System.getProperty("dsh.eval.baseline");
            String reportsDir = System.getProperty("dsh.eval.reports-dir");
            String compareA = System.getProperty("dsh.eval.compare-a");
            String compareB = System.getProperty("dsh.eval.compare-b");
            String reportA = System.getProperty("dsh.eval.report-a");
            String reportB = System.getProperty("dsh.eval.report-b");
            String preflightOnly = System.getProperty("dsh.eval.preflight-only");
            String expectedManifest = System.getProperty("dsh.eval.expected-manifest");
            var supplied = new java.util.HashSet<String>();
            if (args != null) {
                for (String a : args) {
                    String p = a.startsWith("--") ? a.substring(2) : a;
                    int eq = p.indexOf('=');
                    if (eq < 0) {
                        if (p.equals("dsh.eval.preflight-only") || p.equals("dsh.eval.expected-manifest"))
                            throw new IllegalArgumentException("预检/启动参数必须明确提供值");
                        continue;
                    }
                    String k = p.substring(0, eq);
                    String v = p.substring(eq + 1);
                    if (k.startsWith("dsh.eval.") && !supplied.add(k))
                        throw new IllegalArgumentException("重复评测参数: " + k);
                    switch (k) {
                        case "dsh.eval.config" -> config = v;
                        case "dsh.eval.dataset" -> dataset = v;
                        case "dsh.eval.baseline" -> baseline = v;
                        case "dsh.eval.reports-dir" -> reportsDir = v;
                        case "dsh.eval.compare-a" -> compareA = v;
                        case "dsh.eval.compare-b" -> compareB = v;
                        case "dsh.eval.report-a" -> reportA = v;
                        case "dsh.eval.report-b" -> reportB = v;
                        case "dsh.eval.preflight-only" -> preflightOnly = v;
                        case "dsh.eval.expected-manifest" -> expectedManifest = v;
                        default -> { }
                    }
                }
            }
            if (preflightOnly != null && !preflightOnly.equals("true") && !preflightOnly.equals("false"))
                throw new IllegalArgumentException("preflight-only 只能为true或false");
            boolean dry = preflightOnly == null || preflightOnly.equals("true");
            if (compareA != null && (config != null || preflightOnly != null || expectedManifest != null))
                throw new IllegalArgumentException("compare不能与评测/预检/执行参数混用");
            if (config != null) {
                if (reportsDir == null || reportsDir.isBlank())
                    throw new IllegalArgumentException("预检与执行均必须指定 reports-dir");
                if (dry && expectedManifest != null)
                    throw new IllegalArgumentException("预检不接受 expected-manifest；执行必须显式preflight-only=false");
                if (!dry && (expectedManifest == null || expectedManifest.isBlank()))
                    throw new IllegalArgumentException("执行必须提供 expected-manifest");
            }
            if (compareA == null) {
                if (dataset == null) {
                    dataset = "evals/datasets/" + (config == null ? "default" : "") + ".json";
                }
                if (baseline == null) {
                    baseline = "evals/baselines/default";
                }
            }
            return new EvalCliProps(config, dataset, baseline, reportsDir,
                    compareA, compareB, reportA, reportB, dry, expectedManifest);
        }
    }
}
