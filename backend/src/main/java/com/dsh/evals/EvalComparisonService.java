package com.dsh.evals;

import com.dsh.evals.model.ComparisonSide;
import com.dsh.evals.model.EvalBudget;
import com.dsh.evals.model.EvalCategory;
import com.dsh.evals.model.EvalComparison;
import com.dsh.evals.model.EvalConfig;
import com.dsh.evals.model.EvalDataset;
import com.dsh.evals.model.EvalSample;
import com.dsh.evals.model.EvalFailureType;
import com.dsh.evals.model.EvalReport;
import com.dsh.evals.model.EvalStrategy;
import com.dsh.evals.model.EvalTrial;
import com.dsh.evals.model.CategoryComparison;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 单/多 Agent 对照聚合（MA-11，AT-18）。
 *
 * <p>输入两份冻结配置与两份报告，严格校验除 strategy（及由其决定的 configDigest）外
 * 全部因子逐字一致、报告归属与样本覆盖一致；否则拒绝生成对照（报错而不是静默比较）。
 * 统计口径：成功率为样本首轮（attempt 1）PASS 占比；成本任一 trial 未知则总计 null；
 * 类别来自 trial 冻结行，类别缺失或两侧覆盖不一致均拒绝。</p>
 */
@Service
public class EvalComparisonService {

    /** 生产入口：报告之间一致不代表完整，必须对冻结数据集核验。 */
    public EvalComparison compare(EvalConfig first, EvalConfig second,
                                  EvalReport firstReport, EvalReport secondReport,
                                  EvalDataset dataset) {
        validateDatasetCoverage(first, firstReport, dataset);
        validateDatasetCoverage(second, secondReport, dataset);
        return compare(first, second, firstReport, secondReport);
    }

    private static void validateDatasetCoverage(EvalConfig config, EvalReport report, EvalDataset dataset) {
        if (!Objects.equals(config.datasetName(), dataset.name())
                || !Objects.equals(config.datasetVersion(), dataset.version())) {
            throw new IllegalArgumentException("冻结数据集名称/版本与配置不匹配");
        }
        Map<String, EvalSample> index = new LinkedHashMap<>();
        for (EvalSample sample : dataset.samples()) {
            if (index.putIfAbsent(sample.id(), sample) != null) {
                throw new IllegalArgumentException("冻结数据集含重复样本 ID: " + sample.id());
            }
        }
        if (!index.keySet().containsAll(dataset.devSplitIds())
                || !index.keySet().containsAll(dataset.heldoutSplitIds())
                || !java.util.Collections.disjoint(dataset.devSplitIds(), dataset.heldoutSplitIds())) {
            throw new IllegalArgumentException("冻结数据集分区引用未知样本或 dev/heldout 重叠");
        }
        Set<String> split = "heldout".equalsIgnoreCase(config.split())
                ? dataset.heldoutSplitIds() : dataset.devSplitIds();
        Set<String> expected = config.sampleIds().isEmpty() ? split : config.sampleIds();
        if (expected.isEmpty() || !split.containsAll(expected)) {
            throw new IllegalArgumentException("冻结数据集选定样本为空或越出分区");
        }
        for (String id : expected) {
            if (index.get(id).grader().gradingMode() == com.dsh.evals.model.EvalGradingMode.SMOKE) {
                throw new IllegalArgumentException("冻结数据集含仅运行观察的评分，不能生成解决率对照: " + id);
            }
        }
        Set<String> actual = report.trials().stream().map(EvalTrial::sampleId)
                .collect(Collectors.toSet());
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("报告样本覆盖与冻结数据集不一致: expected="
                    + new TreeSet<>(expected) + " actual=" + actual);
        }
        for (EvalTrial trial : report.trials()) {
            EvalCategory expectedCategory = index.get(trial.sampleId()).category();
            if (expectedCategory == null || trial.category() != expectedCategory) {
                throw new IllegalArgumentException("样本类别与冻结数据集不一致: " + trial.sampleId());
            }
        }
    }

    /** 包内统计测试入口；生产调用必须使用包含 dataset 的重载。 */
    EvalComparison compare(EvalConfig first, EvalConfig second,
                                  EvalReport firstReport, EvalReport secondReport) {
        EvalConfig singleCfg;
        EvalConfig multiCfg;
        EvalReport singleReport;
        EvalReport multiReport;
        if (first.strategy() == EvalStrategy.SINGLE && second.strategy() == EvalStrategy.MULTI) {
            singleCfg = first;
            multiCfg = second;
            singleReport = firstReport;
            multiReport = secondReport;
        } else if (first.strategy() == EvalStrategy.MULTI && second.strategy() == EvalStrategy.SINGLE) {
            singleCfg = second;
            multiCfg = first;
            singleReport = secondReport;
            multiReport = firstReport;
        } else {
            throw new IllegalArgumentException(
                    "对照要求两份配置策略恰为 SINGLE 与 MULTI，实际为 "
                            + first.strategy() + " 与 " + second.strategy());
        }

        validateSameFactors(singleCfg, multiCfg);
        validateReport(singleCfg, multiCfg, singleReport, multiReport);

        Set<String> sampleIds = singleReport.trials().stream()
                .map(EvalTrial::sampleId).collect(Collectors.toCollection(TreeSet::new));
        Map<String, EvalCategory> categoryBySample = resolveCategories(singleReport, multiReport);

        List<String> warnings = new ArrayList<>(com.dsh.evals.model.EvalExecutionManifest.comparisonWarnings(
                singleReport.executionManifest(), multiReport.executionManifest()));
        ComparisonSide overallSingle = buildSide(singleCfg, singleReport.trials(), warnings, "SINGLE");
        ComparisonSide overallMulti = buildSide(multiCfg, multiReport.trials(), warnings, "MULTI");

        List<CategoryComparison> categories = new ArrayList<>();
        for (EvalCategory category : EvalCategory.values()) {
            Set<String> categorySamples = categoryBySample.entrySet().stream()
                    .filter(e -> e.getValue() == category).map(Map.Entry::getKey)
                    .collect(Collectors.toCollection(TreeSet::new));
            if (categorySamples.isEmpty()) {
                continue;
            }
            List<EvalTrial> singleTrials = filterBySamples(singleReport.trials(), categorySamples);
            List<EvalTrial> multiTrials = filterBySamples(multiReport.trials(), categorySamples);
            categories.add(new CategoryComparison(category.name(),
                    buildSide(singleCfg, singleTrials, warnings, "SINGLE:" + category.name()),
                    buildSide(multiCfg, multiTrials, warnings, "MULTI:" + category.name())));
        }

        return new EvalComparison(singleCfg.datasetName(), singleCfg.datasetVersion(),
                singleCfg.split(), singleCfg.repeatCount(), factorKey(singleCfg, sampleIds) + (singleReport.executionManifest() == null ? ""
                        : "|execution-v1=" + singleReport.executionManifest().comparisonFactorDigest()),
                overallSingle, overallMulti, List.copyOf(categories), List.copyOf(warnings));
    }

    private static void validateSameFactors(EvalConfig single, EvalConfig multi) {
        List<String> mismatches = new ArrayList<>();
        addIfDifferent(mismatches, "datasetName", single.datasetName(), multi.datasetName());
        addIfDifferent(mismatches, "datasetVersion", single.datasetVersion(), multi.datasetVersion());
        addIfDifferent(mismatches, "split", single.split(), multi.split());
        addIfDifferent(mismatches, "modelProfileId", single.modelProfileId(), multi.modelProfileId());
        addIfDifferent(mismatches, "modelProfileConfigDigest",
                single.modelProfileConfigDigest(), multi.modelProfileConfigDigest());
        addIfDifferent(mismatches, "imageDigest", single.imageDigest(), multi.imageDigest());
        addIfDifferent(mismatches, "seed", single.seed(), multi.seed());
        addIfDifferent(mismatches, "repeatCount", single.repeatCount(), multi.repeatCount());
        EvalBudget b1 = single.budget();
        EvalBudget b2 = multi.budget();
        addIfDifferent(mismatches, "budget.maxModelRequests",
                b1.maxModelRequests(), b2.maxModelRequests());
        addIfDifferent(mismatches, "budget.maxToolCalls", b1.maxToolCalls(), b2.maxToolCalls());
        addIfDifferent(mismatches, "budget.maxCumulativeTokens",
                b1.maxCumulativeTokens(), b2.maxCumulativeTokens());
        addIfDifferent(mismatches, "budget.deadlineMinutes",
                b1.deadlineMinutes(), b2.deadlineMinutes());
        if (!Objects.equals(single.sampleIds(), multi.sampleIds())) {
            mismatches.add("sampleIds: " + single.sampleIds() + " != " + multi.sampleIds());
        }
        if (!mismatches.isEmpty()) {
            throw new IllegalArgumentException("两份配置存在策略以外的不同因子，拒绝对照: "
                    + String.join("; ", mismatches));
        }
    }

    private static void validateReport(EvalConfig singleCfg, EvalConfig multiCfg,
                                       EvalReport single, EvalReport multi) {
        validateSide(singleCfg, single);
        validateSide(multiCfg, multi);
        if (!Objects.equals(single.datasetName(), multi.datasetName())
                || !Objects.equals(single.datasetVersion(), multi.datasetVersion())
                || !Objects.equals(single.split(), multi.split())) {
            throw new IllegalArgumentException("两份报告数据集/分区不一致，拒绝对照");
        }
        Set<String> s1 = single.trials().stream().map(EvalTrial::sampleId)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> s2 = multi.trials().stream().map(EvalTrial::sampleId)
                .collect(Collectors.toCollection(TreeSet::new));
        if (!s1.equals(s2)) {
            throw new IllegalArgumentException(
                    "两份报告样本覆盖不一致，拒绝对照: single=" + s1 + " multi=" + s2);
        }
        if (s1.isEmpty()) {
            throw new IllegalArgumentException("报告不含任何 trial，拒绝对照");
        }
    }

    private static void validateSide(EvalConfig config, EvalReport report) {
        if (report.executionManifest() != null) report.executionManifest().validateFor(config);
        String side = config.strategy().name();
        if (!config.configDigest().equals(report.configDigest())) {
            throw new IllegalArgumentException(side + " 报告 configDigest 与配置不匹配");
        }
        if (!Objects.equals(config.datasetName(), report.datasetName())
                || !Objects.equals(config.datasetVersion(), report.datasetVersion())
                || !Objects.equals(config.split(), report.split())) {
            throw new IllegalArgumentException(side + " 报告数据集/分区与配置不匹配");
        }
        Map<String, Set<Integer>> attempts = new LinkedHashMap<>();
        Set<String> trialIds = new java.util.HashSet<>();
        for (EvalTrial trial : report.trials()) {
            if (trial.failureType() == EvalFailureType.GRADER_UNSCORED || "UNSCORED".equals(trial.graderStatus())) {
                throw new IllegalArgumentException(side + " 含未评分试验，不能生成解决率对照");
            }
            if (trial.sampleId() == null || trial.sampleId().isBlank()
                    || trial.trialId() == null || trial.trialId().isBlank()
                    || !trialIds.add(trial.trialId())
                    || trial.strategy() != config.strategy()
                    || !Objects.equals(trial.split(), config.split())
                    || trial.failureType() == null) {
                throw new IllegalArgumentException(side + " trial 标识/策略/分区/结果无效");
            }
            if (trial.attempt() < 1 || trial.attempt() > config.repeatCount()
                    || !attempts.computeIfAbsent(trial.sampleId(), k -> new TreeSet<>())
                    .add(trial.attempt())) {
                throw new IllegalArgumentException(side + " trial attempt 越界或重复");
            }
        }
        if (attempts.isEmpty()
                || attempts.values().stream().anyMatch(a -> a.size() != config.repeatCount())
                || report.totalTrials() != report.trials().size()
                || (!config.sampleIds().isEmpty() && !config.sampleIds().equals(attempts.keySet()))) {
            throw new IllegalArgumentException(side + " 样本或重复轮次覆盖不完整");
        }
    }

    /** 样本类别以两侧 trial 冻结行为准：任一侧缺失或同一样本类别冲突均拒绝。 */
    private static Map<String, EvalCategory> resolveCategories(EvalReport single, EvalReport multi) {
        Map<String, EvalCategory> resolved = new LinkedHashMap<>();
        for (EvalReport report : List.of(single, multi)) {
            for (EvalTrial trial : report.trials()) {
                if (trial.category() == null) {
                    throw new IllegalArgumentException(
                            "trial " + trial.trialId() + " 缺少 category，无法按类别对照");
                }
                EvalCategory existing = resolved.putIfAbsent(trial.sampleId(), trial.category());
                if (existing != null && existing != trial.category()) {
                    throw new IllegalArgumentException("样本 " + trial.sampleId()
                            + " 在 trial 行中出现不同 category: " + existing + " vs "
                            + trial.category());
                }
            }
        }
        return resolved;
    }

    private static List<EvalTrial> filterBySamples(List<EvalTrial> trials, Set<String> sampleIds) {
        return trials.stream().filter(t -> sampleIds.contains(t.sampleId())).toList();
    }

    private static ComparisonSide buildSide(EvalConfig config, List<EvalTrial> trials,
                                            List<String> warnings, String warningPrefix) {
        int trialCount = trials.size();

        Map<String, List<EvalTrial>> bySample = new LinkedHashMap<>();
        for (EvalTrial t : trials) {
            bySample.computeIfAbsent(t.sampleId(), k -> new ArrayList<>()).add(t);
        }
        bySample.values().forEach(l -> l.sort(Comparator.comparingInt(EvalTrial::attempt)));
        int passedAt1 = 0;
        for (List<EvalTrial> st : bySample.values()) {
            if (st.get(0).failureType() == EvalFailureType.PASS) {
                passedAt1++;
            }
        }
        int sampleCount = Math.max(1, bySample.size());
        double passAt1 = (double) passedAt1 / sampleCount;

        long latencyMean = trialCount == 0 ? 0
                : trials.stream().mapToLong(EvalTrial::latencyMs).sum() / trialCount;
        long[] sorted = trials.stream().mapToLong(EvalTrial::latencyMs).sorted().toArray();
        long p95 = percentile(sorted, 95);

        int totalModelRequests = trials.stream().mapToInt(EvalTrial::modelRequests).sum();
        Long totalTokens = trials.stream().anyMatch(t -> t.settledTokens() == null) ? null
                : trials.stream().mapToLong(EvalTrial::settledTokens).sum();
        if (totalTokens == null) {
            warnings.add(warningPrefix + " 总 token 未知（至少一条 trial usage 缺失），不补零");
        }
        int childRunCount = trials.stream().mapToInt(EvalTrial::childRunCount).sum();
        int conflictCount = trials.stream().mapToInt(EvalTrial::conflictCount).sum();
        int reworkAttempts = trials.stream().mapToInt(EvalTrial::reworkAttempts).sum();

        Set<String> currencies = trials.stream().map(EvalTrial::currency)
                .filter(c -> c != null && !c.isBlank()).collect(Collectors.toSet());
        boolean sameCurrency = currencies.size() == 1
                && trials.stream().allMatch(t -> t.currency() != null && !t.currency().isBlank());
        String currency = sameCurrency ? currencies.iterator().next() : null;
        String totalCost = sameCurrency ? sumCosts(trials) : null;
        if (totalCost == null) {
            warnings.add(warningPrefix + " 总成本未知（cost 缺失、不可解析或币种不一致/缺失），不补零");
        }

        return new ComparisonSide(config.configDigest(), config.strategy().name(),
                trialCount, bySample.size(), passAt1, latencyMean, p95,
                totalModelRequests, totalTokens, totalCost, currency,
                childRunCount, conflictCount, reworkAttempts);
    }

    private static String sumCosts(List<EvalTrial> trials) {
        if (trials.stream().anyMatch(t -> t.cost() == null)) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (EvalTrial t : trials) {
            try {
                sum = sum.add(new BigDecimal(t.cost()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return sum.toPlainString();
    }

    private static long percentile(long[] sortedAsc, int pct) {
        if (sortedAsc.length == 0) return 0;
        int idx = (int) Math.ceil(pct / 100.0 * sortedAsc.length) - 1;
        return sortedAsc[Math.max(0, Math.min(idx, sortedAsc.length - 1))];
    }

    private static void addIfDifferent(List<String> mismatches, String name, Object a, Object b) {
        if (!Objects.equals(a, b)) {
            mismatches.add(name + ": " + a + " != " + b);
        }
    }

    /** 同因子证据串（与 configDigest 输入同源，便于人读核对），不含策略本身。 */
    private static String factorKey(EvalConfig cfg, Set<String> sampleIds) {
        String samples = sampleIds.isEmpty() ? ""
                : "|samples=" + sampleIds.stream().sorted().collect(Collectors.joining(","));
        return String.join("|",
                cfg.datasetName(), cfg.datasetVersion(), cfg.split(),
                cfg.modelProfileId(), cfg.modelProfileConfigDigest(),
                cfg.budget().maxModelRequests() + ":" + cfg.budget().maxToolCalls() + ":"
                        + cfg.budget().maxCumulativeTokens() + ":" + cfg.budget().deadlineMinutes(),
                cfg.imageDigest(), String.valueOf(cfg.repeatCount()),
                cfg.seed() == null ? "" : String.valueOf(cfg.seed())) + samples;
    }
}
