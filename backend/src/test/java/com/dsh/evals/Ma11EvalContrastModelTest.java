package com.dsh.evals;

import com.dsh.evals.model.EvalBudget;
import com.dsh.evals.model.EvalCategory;
import com.dsh.evals.model.EvalComparison;
import com.dsh.evals.model.EvalConfig;
import com.dsh.evals.model.EvalDataset;
import com.dsh.evals.model.EvalSample;
import com.dsh.evals.model.EvalGraderSpec;
import com.dsh.evals.model.EvalFailureType;
import com.dsh.evals.model.EvalReport;
import com.dsh.evals.model.EvalStrategy;
import com.dsh.evals.model.EvalTrial;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-11 对照评测模型纯单测（AT-18，无 Spring/无数据库/无模型调用）：
 * strategy 入冻结摘要（SINGLE 旧摘要兼容、MULTI 摘要不同）；
 * 对照聚合同因子校验、按类别独立记账、成功率首轮口径、成本未知保留 null、
 * 失败 trial 原始行保留；评测模型 JSON 往返（含旧配置缺 strategy）。
 */
class Ma11EvalContrastModelTest {

    private static final EvalBudget BUDGET = new EvalBudget(24, 80, 200_000, 20);
    private static final String DATASET = "dsh-orch-contrast";
    private static final String PROFILE_DIGEST = "prof-digest";
    private static final String IMAGE = "sha256:img";

    private final EvalComparisonService comparisonService = new EvalComparisonService();
    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------- 冻结摘要：strategy 因子 ----------------

    @Test
    void digestChangesOnlyWithMultiStrategy() {
        String singleDigest = EvalConfig.computeDigest(DATASET, "v1", "dev", Set.of(),
                PROFILE_DIGEST, BUDGET, IMAGE, 2, null, EvalStrategy.SINGLE);
        String multiDigest = EvalConfig.computeDigest(DATASET, "v1", "dev", Set.of(),
                PROFILE_DIGEST, BUDGET, IMAGE, 2, null, EvalStrategy.MULTI);
        // 旧 9 参重载 = SINGLE，保证 MA-11 前 6 份配置与历史报告摘要不变
        assertEquals(singleDigest, EvalConfig.computeDigest(DATASET, "v1", "dev",
                Set.of(), PROFILE_DIGEST, BUDGET, IMAGE, 2, null));
        assertNotEquals(singleDigest, multiDigest, "仅策略不同也必须产出不同冻结摘要");
    }

    @Test
    void configRejectsSingleDigestForMulti() {
        String singleDigest = EvalConfig.computeDigest(DATASET, "v1", "dev", Set.of(),
                PROFILE_DIGEST, BUDGET, IMAGE, 2, null, EvalStrategy.SINGLE);
        assertThrows(IllegalArgumentException.class, () -> config(EvalStrategy.MULTI, BUDGET, 2, singleDigest));
    }

    @Test
    void oldConfigJsonWithoutStrategyDefaultsToSingle() throws Exception {
        String multiDigest = EvalConfig.computeDigest(DATASET, "v1", "dev", Set.of(),
                PROFILE_DIGEST, BUDGET, IMAGE, 1, null, EvalStrategy.MULTI);
        // 旧格式 JSON 无 strategy 字段
        String oldJson = """
                {
                  "datasetName": "dsh-orch-contrast",
                  "datasetVersion": "v1",
                  "split": "dev",
                  "sampleIds": [],
                  "modelProfileId": "prof-1",
                  "modelProfileConfigDigest": "prof-digest",
                  "budget": {"maxModelRequests": 24, "maxToolCalls": 80,
                             "maxCumulativeTokens": 200000, "deadlineMinutes": 20},
                  "seed": null,
                  "imageDigest": "sha256:img",
                  "repeatCount": 1,
                  "configDigest": "%s"
                }
                """.formatted(EvalConfig.computeDigest(DATASET, "v1", "dev", Set.of(),
                PROFILE_DIGEST, BUDGET, IMAGE, 1, null));
        EvalConfig parsed = mapper.readValue(oldJson, EvalConfig.class);
        assertEquals(EvalStrategy.SINGLE, parsed.strategy());

        // MULTI 配置 JSON 往返一致
        EvalConfig multi = config(EvalStrategy.MULTI, BUDGET, 1, multiDigest);
        EvalConfig roundtrip = mapper.readValue(mapper.writeValueAsString(multi), EvalConfig.class);
        assertEquals(multi, roundtrip);
    }

    // ---------------- 对照聚合：类别独立记账 / 成功率口径 / 未知成本 / 失败行 ----------------

    @Test
    void aggregatesOverallAndPerCategoryWithFirstAttemptPassRate() {
        EvalConfig single = config(EvalStrategy.SINGLE, BUDGET, 2);
        EvalConfig multi = config(EvalStrategy.MULTI, BUDGET, 2);

        // SINGLE：4 样本 × 2 重复全部首轮 PASS，每条成本 0.01，token 100
        List<EvalTrial> singleTrials = new java.util.ArrayList<>();
        for (EvalCategory category : EvalCategory.values()) {
            String sample = sampleOf(category);
            singleTrials.add(trial(sample, category, 1, EvalFailureType.PASS,
                    EvalStrategy.SINGLE, 0, 0, 0, 1000, 100, "0.01"));
            singleTrials.add(trial(sample, category, 2, EvalFailureType.PASS,
                    EvalStrategy.SINGLE, 0, 0, 0, 1000, 100, "0.01"));
        }
        // MULTI：c01 首轮失败次轮通过（首轮成功率不把重试当通过）；c04 两次汇合冲突后成功
        List<EvalTrial> multiTrials = new java.util.ArrayList<>();
        multiTrials.add(trial("c01", EvalCategory.SIMPLE, 1, EvalFailureType.GRADER_FAILED,
                EvalStrategy.MULTI, 0, 0, 0, 1000, 100, "0.01"));
        multiTrials.add(trial("c01", EvalCategory.SIMPLE, 2, EvalFailureType.PASS,
                EvalStrategy.MULTI, 0, 0, 0, 1000, 100, "0.01"));
        multiTrials.add(trial("c02", EvalCategory.INDEPENDENT, 1, EvalFailureType.PASS,
                EvalStrategy.MULTI, 2, 0, 0, 1000, 100, null)); // 成本未知
        multiTrials.add(trial("c02", EvalCategory.INDEPENDENT, 2, EvalFailureType.PASS,
                EvalStrategy.MULTI, 2, 0, 0, 1000, 100, "0.01"));
        multiTrials.add(trial("c03", EvalCategory.DEPENDENT, 1, EvalFailureType.PASS,
                EvalStrategy.MULTI, 1, 0, 0, 1000, 100, "0.01"));
        multiTrials.add(trial("c03", EvalCategory.DEPENDENT, 2, EvalFailureType.PASS,
                EvalStrategy.MULTI, 1, 0, 0, 1000, 100, "0.01"));
        multiTrials.add(trial("c04", EvalCategory.CONFLICT, 1, EvalFailureType.PASS,
                EvalStrategy.MULTI, 2, 1, 1, 1000, 100, "0.01"));
        multiTrials.add(trial("c04", EvalCategory.CONFLICT, 2, EvalFailureType.PASS,
                EvalStrategy.MULTI, 2, 1, 1, 1000, 100, "0.01"));

        EvalReport singleReport = report(single, singleTrials);
        EvalReport multiReport = report(multi, multiTrials);

        EvalComparison comparison = comparisonService.compare(
                single, multi, singleReport, multiReport);

        assertEquals(1.0, comparison.overallSingle().passAt1(), 1e-9);
        assertEquals(0.75, comparison.overallMulti().passAt1(), 1e-9,
                "首轮失败、次轮通过的样本不计入 pass@1");
        assertEquals(4, comparison.overallSingle().samples());
        assertEquals(8, comparison.overallSingle().trials());
        assertEquals(8, comparison.overallMulti().trials(),
                "失败/取消行同样计入 trials，不静默丢弃");

        assertEquals(4, comparison.categories().size());
        var byCategory = new java.util.LinkedHashMap<String, com.dsh.evals.model.CategoryComparison>();
        comparison.categories().forEach(c -> byCategory.put(c.category(), c));
        assertEquals(0.0, byCategory.get("SIMPLE").multi().passAt1(), 1e-9);
        assertEquals(1.0, byCategory.get("SIMPLE").single().passAt1(), 1e-9);
        assertEquals(2, byCategory.get("SIMPLE").multi().trials());

        // c04 每轮 2 个子 Run、1 次冲突、1 次返工，两次重复独立累计
        assertEquals(4, byCategory.get("CONFLICT").multi().childRunCount());
        assertEquals(2, byCategory.get("CONFLICT").multi().conflictCount());
        assertEquals(2, byCategory.get("CONFLICT").multi().reworkAttempts());
        assertEquals(0, byCategory.get("CONFLICT").single().conflictCount());

        // 总成本独立记录：SINGLE 8*0.01；MULTI 任一 trial 未知 → null + warning，不补零
        assertEquals("0.08", comparison.overallSingle().totalCost());
        assertNull(comparison.overallMulti().totalCost());
        assertTrue(comparison.warnings().stream().anyMatch(w -> w.startsWith("MULTI ")));
        assertEquals(800, comparison.overallSingle().totalTokens());
        assertTrue(comparison.factorKey().contains(PROFILE_DIGEST));

        // 失败原始行保留在报告 trial 列表中（不因为失败被剔除）
        assertEquals(1, multiReport.trials().stream()
                .filter(t -> t.failureType() == EvalFailureType.GRADER_FAILED).count());
    }

    @Test
    void rejectsComparisonWhenFactorOtherThanStrategyDiffers() {
        EvalConfig single = config(EvalStrategy.SINGLE, BUDGET, 2);
        EvalConfig multiRich = config(EvalStrategy.MULTI,
                new EvalBudget(48, 80, 200_000, 20), 2);
        EvalReport rs = report(single, List.of(trial("c01", EvalCategory.SIMPLE, 1,
                EvalFailureType.PASS, EvalStrategy.SINGLE, 0, 0, 0, 10, 10, "0.01")));
        EvalReport rm = report(multiRich, List.of(trial("c01", EvalCategory.SIMPLE, 1,
                EvalFailureType.PASS, EvalStrategy.MULTI, 0, 0, 0, 10, 10, "0.01")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> comparisonService.compare(single, multiRich, rs, rm));
        assertTrue(ex.getMessage().contains("maxModelRequests"), ex.getMessage());
    }

    @Test
    void rejectsComparisonWhenStrategiesEqualOrCoverageOrCategoryInvalid() {
        EvalConfig single = config(EvalStrategy.SINGLE, BUDGET, 1);
        EvalConfig anotherSingle = config(EvalStrategy.SINGLE, BUDGET, 1);
        EvalReport rs = report(single, List.of(trial("c01", EvalCategory.SIMPLE, 1,
                EvalFailureType.PASS, EvalStrategy.SINGLE, 0, 0, 0, 10, 10, "0.01")));
        EvalReport rs2 = report(anotherSingle, List.of(trial("c01", EvalCategory.SIMPLE, 1,
                EvalFailureType.PASS, EvalStrategy.SINGLE, 0, 0, 0, 10, 10, "0.01")));
        // 策略相同 → 不是单/多对照
        assertThrows(IllegalArgumentException.class,
                () -> comparisonService.compare(single, anotherSingle, rs, rs2));

        // 样本覆盖不一致 → 拒绝
        EvalConfig multi = config(EvalStrategy.MULTI, BUDGET, 1);
        EvalReport rmOther = report(multi, List.of(trial("c02", EvalCategory.INDEPENDENT, 1,
                EvalFailureType.PASS, EvalStrategy.MULTI, 0, 0, 0, 10, 10, "0.01")));
        assertThrows(IllegalArgumentException.class,
                () -> comparisonService.compare(single, multi, rs, rmOther));

        // trial 缺 category → 拒绝
        EvalTrial noCategory = new EvalTrial("t-x", "c01", "dev", 1, "run-x",
                "COMPLETED", "ANSWER_COMPLETE", "patch", "PASSED", 0, 1, 1, 0, "log",
                EvalFailureType.PASS, 10, 1, 0, 10, "0.01", "CNY",
                null, null, null, null, "pv",
                EvalStrategy.MULTI, null, 0, 0, 0);
        EvalReport rmNoCategory = report(multi, List.of(noCategory));
        assertThrows(IllegalArgumentException.class,
                () -> comparisonService.compare(single, multi, rs, rmNoCategory));
    }

    @Test
    void trialAndComparisonJsonRoundtripPreservesNewFields() throws Exception {
        EvalTrial trial = trial("c04", EvalCategory.CONFLICT, 1, EvalFailureType.PASS,
                EvalStrategy.MULTI, 2, 1, 1, 1234, 567, "0.02");
        EvalTrial back = mapper.readValue(mapper.writeValueAsString(trial), EvalTrial.class);
        assertEquals(trial, back);
        assertEquals(EvalStrategy.MULTI, back.strategy());
        assertEquals(EvalCategory.CONFLICT, back.category());
        assertEquals(1, back.conflictCount());
        assertEquals(1, back.reworkAttempts());
        assertEquals(2, back.childRunCount());

        EvalConfig single = config(EvalStrategy.SINGLE, BUDGET, 1);
        EvalConfig multi = config(EvalStrategy.MULTI, BUDGET, 1);
        EvalComparison comparison = comparisonService.compare(single, multi,
                report(single, List.of(trial("c01", EvalCategory.SIMPLE, 1,
                        EvalFailureType.PASS, EvalStrategy.SINGLE, 0, 0, 0, 10, 10, "0.01"))),
                report(multi, List.of(trial("c01", EvalCategory.SIMPLE, 1,
                        EvalFailureType.PASS, EvalStrategy.MULTI, 1, 0, 0, 10, 10, "0.01"))));
        EvalComparison cmpBack = mapper.readValue(
                mapper.writeValueAsString(comparison), EvalComparison.class);
        assertEquals(comparison, cmpBack);
        assertEquals("SINGLE", cmpBack.overallSingle().strategy());
        assertEquals("MULTI", cmpBack.overallMulti().strategy());
    }

    @Test
    void rejectsWrongMultiDigestAndMislabelledTrials() {
        EvalConfig single = config(EvalStrategy.SINGLE, BUDGET, 1);
        EvalConfig multi = config(EvalStrategy.MULTI, BUDGET, 1);
        EvalTrial st = trial("c01", EvalCategory.SIMPLE, 1, EvalFailureType.PASS,
                EvalStrategy.SINGLE, 0, 0, 0, 10, 10, "0.01");
        EvalTrial mt = trial("c01", EvalCategory.SIMPLE, 1, EvalFailureType.PASS,
                EvalStrategy.MULTI, 0, 0, 0, 10, 10, "0.01");
        assertThrows(IllegalArgumentException.class, () -> comparisonService.compare(
                single, multi, report(single, List.of(st)), report(single, List.of(mt))));
        assertThrows(IllegalArgumentException.class, () -> comparisonService.compare(
                single, multi, report(single, List.of(st)), report(multi, List.of(st))));
        // 参数顺序可交换，但每份报告必须属于相应配置。
        assertEquals(1.0, comparisonService.compare(multi, single,
                report(multi, List.of(mt)), report(single, List.of(st))).overallMulti().passAt1());
    }

    @Test
    void rejectsMissingDuplicatedAndOutOfRangeAttempts() {
        EvalConfig single = config(EvalStrategy.SINGLE, BUDGET, 2);
        EvalConfig multi = config(EvalStrategy.MULTI, BUDGET, 2);
        EvalTrial st1 = trial("c01", EvalCategory.SIMPLE, 1, EvalFailureType.PASS,
                EvalStrategy.SINGLE, 0, 0, 0, 10, 10, "0.01");
        EvalTrial st2 = trial("c01", EvalCategory.SIMPLE, 2, EvalFailureType.PASS,
                EvalStrategy.SINGLE, 0, 0, 0, 10, 10, "0.01");
        EvalTrial mt1 = trial("c01", EvalCategory.SIMPLE, 1, EvalFailureType.PASS,
                EvalStrategy.MULTI, 0, 0, 0, 10, 10, "0.01");
        EvalTrial mt2 = trial("c01", EvalCategory.SIMPLE, 2, EvalFailureType.PASS,
                EvalStrategy.MULTI, 0, 0, 0, 10, 10, "0.01");
        EvalTrial mt3 = trial("c01", EvalCategory.SIMPLE, 3, EvalFailureType.PASS,
                EvalStrategy.MULTI, 0, 0, 0, 10, 10, "0.01");
        for (List<EvalTrial> invalid : List.of(List.of(mt2), List.of(mt1, mt1), List.of(mt1, mt3))) {
            assertThrows(IllegalArgumentException.class, () -> comparisonService.compare(single, multi,
                    report(single, List.of(st1, st2)), report(multi, invalid)));
        }
    }

    @Test
    void unknownTokensStayUnknownInComparisonAndJson() throws Exception {
        EvalConfig single = config(EvalStrategy.SINGLE, BUDGET, 1);
        EvalConfig multi = config(EvalStrategy.MULTI, BUDGET, 1);
        EvalTrial st = trial("c01", EvalCategory.SIMPLE, 1, EvalFailureType.PASS,
                EvalStrategy.SINGLE, 0, 0, 0, 10, null, null);
        EvalTrial mt = trial("c01", EvalCategory.SIMPLE, 1, EvalFailureType.PASS,
                EvalStrategy.MULTI, 0, 0, 0, 10, 0, null);
        EvalComparison result = comparisonService.compare(single, multi,
                report(single, List.of(st)), report(multi, List.of(mt)));
        assertNull(result.overallSingle().totalTokens());
        assertEquals(0L, result.overallMulti().totalTokens());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("token")));
        assertEquals(result, mapper.readValue(mapper.writeValueAsString(result), EvalComparison.class));
    }

    @Test
    void rejectsReportMetadataAndConfiguredSampleMismatch() throws Exception {
        EvalConfig single = config(EvalStrategy.SINGLE, BUDGET, 1);
        EvalConfig multi = config(EvalStrategy.MULTI, BUDGET, 1);
        EvalReport rs = report(single, List.of(trial("c01", EvalCategory.SIMPLE, 1,
                EvalFailureType.PASS, EvalStrategy.SINGLE, 0, 0, 0, 10, 10, "0.01")));
        EvalReport rm = report(multi, List.of(trial("c01", EvalCategory.SIMPLE, 1,
                EvalFailureType.PASS, EvalStrategy.MULTI, 0, 0, 0, 10, 10, "0.01")));
        var badMetadata = mapper.valueToTree(rm);
        ((com.fasterxml.jackson.databind.node.ObjectNode) badMetadata).put("datasetVersion", "v2");
        EvalReport bad = mapper.treeToValue(badMetadata, EvalReport.class);
        assertThrows(IllegalArgumentException.class, () -> comparisonService.compare(single, multi, rs, bad));

        EvalConfig selectedSingle = new EvalConfig(DATASET, "v1", "dev", Set.of("c02"),
                "prof-1", PROFILE_DIGEST, BUDGET, null, IMAGE, 1, EvalStrategy.SINGLE,
                EvalConfig.computeDigest(DATASET, "v1", "dev", Set.of("c02"), PROFILE_DIGEST,
                        BUDGET, IMAGE, 1, null, EvalStrategy.SINGLE));
        EvalConfig selectedMulti = new EvalConfig(DATASET, "v1", "dev", Set.of("c02"),
                "prof-1", PROFILE_DIGEST, BUDGET, null, IMAGE, 1, EvalStrategy.MULTI,
                EvalConfig.computeDigest(DATASET, "v1", "dev", Set.of("c02"), PROFILE_DIGEST,
                        BUDGET, IMAGE, 1, null, EvalStrategy.MULTI));
        assertThrows(IllegalArgumentException.class, () -> comparisonService.compare(
                selectedSingle, selectedMulti, report(selectedSingle, rs.trials()), report(selectedMulti, rm.trials())));
    }

    @Test
    void mixedCurrenciesAreNotAddedTogether() throws Exception {
        EvalConfig single = config(EvalStrategy.SINGLE, BUDGET, 2);
        EvalConfig multi = config(EvalStrategy.MULTI, BUDGET, 2);
        var singleTrials = new java.util.ArrayList<EvalTrial>();
        var multiTrials = new java.util.ArrayList<EvalTrial>();
        for (int attempt = 1; attempt <= 2; attempt++) {
            singleTrials.add(trial("c01", EvalCategory.SIMPLE, attempt, EvalFailureType.PASS,
                    EvalStrategy.SINGLE, 0, 0, 0, 10, 10, "0.01"));
            var mt = mapper.valueToTree(trial("c01", EvalCategory.SIMPLE, attempt, EvalFailureType.PASS,
                    EvalStrategy.MULTI, 0, 0, 0, 10, 10, "0.01"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) mt).put("currency", attempt == 1 ? "CNY" : "USD");
            multiTrials.add(mapper.treeToValue(mt, EvalTrial.class));
        }
        EvalComparison result = comparisonService.compare(single, multi,
                report(single, singleTrials), report(multi, multiTrials));
        assertEquals("0.02", result.overallSingle().totalCost());
        assertNull(result.overallMulti().totalCost());
        assertNull(result.overallMulti().currency());
    }

    @Test
    void frozenDatasetRejectsSampleMissingFromBothReports() {
        var one = sample("c01", EvalCategory.SIMPLE);
        var two = sample("c02", EvalCategory.INDEPENDENT);
        var dataset = dataset(List.of(one, two), Set.of("c01", "c02"), Set.of());
        var single = config(EvalStrategy.SINGLE, BUDGET, 1);
        var multi = config(EvalStrategy.MULTI, BUDGET, 1);
        assertThrows(IllegalArgumentException.class, () -> comparisonService.compare(single, multi,
                reportOf(single, List.of(one)), reportOf(multi, List.of(one)), dataset));
    }

    @Test
    void frozenDatasetRejectsSameFalseCategoryOnBothSides() {
        var one = sample("c01", EvalCategory.SIMPLE);
        var mislabelled = sample("c01", EvalCategory.CONFLICT);
        var dataset = dataset(List.of(one), Set.of("c01"), Set.of());
        var single = config(EvalStrategy.SINGLE, BUDGET, 1);
        var multi = config(EvalStrategy.MULTI, BUDGET, 1);
        assertThrows(IllegalArgumentException.class, () -> comparisonService.compare(single, multi,
                reportOf(single, List.of(mislabelled)), reportOf(multi, List.of(mislabelled)), dataset));
    }

    @Test
    void completeDatasetCoveragePassesInEitherArgumentOrder() {
        var samples = List.of(sample("c01", EvalCategory.SIMPLE), sample("c02", EvalCategory.INDEPENDENT));
        var dataset = dataset(samples, Set.of("c01", "c02"), Set.of());
        var single = config(EvalStrategy.SINGLE, BUDGET, 1);
        var multi = config(EvalStrategy.MULTI, BUDGET, 1);
        var result = comparisonService.compare(single, multi,
                reportOf(single, samples), reportOf(multi, samples), dataset);
        assertEquals(2, result.overallSingle().samples());
        assertEquals(result, comparisonService.compare(multi, single,
                reportOf(multi, samples), reportOf(single, samples), dataset));
    }

    @Test
    void explicitSubsetPassesButCannotSelectOutsideItsPartition() {
        var one = sample("c01", EvalCategory.SIMPLE);
        var two = sample("c02", EvalCategory.INDEPENDENT);
        var single = selectedConfig(EvalStrategy.SINGLE, "c01");
        var multi = selectedConfig(EvalStrategy.MULTI, "c01");
        assertEquals(1, comparisonService.compare(single, multi, reportOf(single, List.of(one)),
                reportOf(multi, List.of(one)), dataset(List.of(one, two), Set.of("c01", "c02"), Set.of()))
                .overallSingle().samples());
        assertThrows(IllegalArgumentException.class, () -> comparisonService.compare(single, multi,
                reportOf(single, List.of(one)), reportOf(multi, List.of(one)),
                dataset(List.of(one, two), Set.of("c02"), Set.of("c01"))));
    }

    @Test
    void invalidDatasetIdentityAndPartitionsAreRejected() {
        var one = sample("c01", EvalCategory.SIMPLE);
        var single = config(EvalStrategy.SINGLE, BUDGET, 1);
        var multi = config(EvalStrategy.MULTI, BUDGET, 1);
        for (var invalid : List.of(
                dataset(List.of(one, one), Set.of("c01"), Set.of()),
                dataset(List.of(one), Set.of("missing"), Set.of()),
                dataset(List.of(one), Set.of("c01"), Set.of("c01")),
                dataset(List.of(one), Set.of(), Set.of("c01")),
                new EvalDataset("other", "v1", List.of(one), Set.of("c01"), Set.of()),
                new EvalDataset(DATASET, "v2", List.of(one), Set.of("c01"), Set.of()))) {
            assertThrows(IllegalArgumentException.class, () -> comparisonService.compare(single, multi,
                    reportOf(single, List.of(one)), reportOf(multi, List.of(one)), invalid));
        }
    }

    private static EvalConfig selectedConfig(EvalStrategy strategy, String sample) {
        var ids = Set.of(sample);
        return new EvalConfig(DATASET, "v1", "dev", ids, "prof-1", PROFILE_DIGEST, BUDGET,
                null, IMAGE, 1, strategy, EvalConfig.computeDigest(DATASET, "v1", "dev", ids,
                PROFILE_DIGEST, BUDGET, IMAGE, 1, null, strategy));
    }

    private static EvalDataset dataset(List<EvalSample> samples, Set<String> dev, Set<String> heldout) {
        return new EvalDataset(DATASET, "v1", samples, dev, heldout);
    }

    private static EvalSample sample(String id, EvalCategory category) {
        return new EvalSample(id, "fixture", category, new EvalGraderSpec("echo fixture", ".", 1, true, 1));
    }

    private static EvalReport reportOf(EvalConfig config, List<EvalSample> samples) {
        return report(config, samples.stream().map(s -> trial(s.id(), s.category(), 1,
                EvalFailureType.PASS, config.strategy(), 0, 0, 0, 10, 10, "0.01")).toList());
    }


    @Test
    void newManifestComparisonGatesRuntimeFactorsAndSeparatesLegacy() {
        EvalConfig single = config(EvalStrategy.SINGLE, BUDGET, 1);
        EvalConfig multi = config(EvalStrategy.MULTI, BUDGET, 1);
        EvalReport rs = report(single, List.of(trial("c01", EvalCategory.SIMPLE, 1,
                EvalFailureType.PASS, EvalStrategy.SINGLE, 0, 0, 0, 10, 10, "0.01")));
        EvalReport rm = report(multi, List.of(trial("c01", EvalCategory.SIMPLE, 1,
                EvalFailureType.PASS, EvalStrategy.MULTI, 0, 0, 0, 10, 10, "0.01")));
        EvalReport ns = rs.withExecutionManifest(manifest(single, "KEYWORD"));
        EvalReport nm = rm.withExecutionManifest(manifest(multi, "KEYWORD"));
        var result = comparisonService.compare(single, multi, ns, nm);
        assertTrue(result.factorKey().contains("execution-v1="));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("EXECUTION_INCOMPLETE")));
        assertThrows(IllegalArgumentException.class, () -> comparisonService.compare(single, multi,
                ns, rm.withExecutionManifest(manifest(multi, "IDENTIFIER"))));
        assertThrows(IllegalArgumentException.class, () -> comparisonService.compare(single, multi, ns, rm));
        assertTrue(comparisonService.compare(single, multi, rs, rm).warnings().stream()
                .anyMatch(w -> w.contains("LEGACY_EXECUTION_UNVERIFIED")));
        assertThrows(IllegalArgumentException.class, () -> comparisonService.compare(single, multi,
                ns, rm.withExecutionManifest(manifest(single, "KEYWORD"))));
    }

    private com.dsh.evals.model.EvalExecutionManifest manifest(EvalConfig c, String mode) {
        var f = mapper.createObjectNode();
        f.put("eval.strategy", c.strategy().name());f.put("eval.datasetName", c.datasetName());
        f.put("eval.datasetVersion", c.datasetVersion());f.put("eval.split", c.split());
        f.put("eval.modelProfileConfigDigest", c.modelProfileConfigDigest());
        f.set("eval.budget", mapper.valueToTree(c.budget()));f.put("eval.repeatCount", c.repeatCount());
        f.set("eval.sampleIds", mapper.valueToTree(c.sampleIds().stream().sorted().toList()));
        f.putNull("eval.seed");f.put("sandbox.declaredImageDigest", c.imageDigest());
        f.put("orchestration.enabled", c.strategy() == EvalStrategy.MULTI);
        f.put("orchestration.acceptanceCoverageMode", mode);
        return com.dsh.evals.model.EvalExecutionManifest.create(f, List.of("MEMORY_UNFROZEN"));
    }

    // ---------------- helpers ----------------

    private static EvalConfig config(EvalStrategy strategy, EvalBudget budget, int repeat) {
        String digest = EvalConfig.computeDigest(DATASET, "v1", "dev", Set.of(),
                PROFILE_DIGEST, budget, IMAGE, repeat, null, strategy);
        return config(strategy, budget, repeat, digest);
    }

    private static EvalConfig config(EvalStrategy strategy, EvalBudget budget, int repeat,
                                     String digest) {
        return new EvalConfig(DATASET, "v1", "dev", Set.of(), "prof-1", PROFILE_DIGEST,
                budget, null, IMAGE, repeat, strategy, digest);
    }

    private static EvalReport report(EvalConfig config, List<EvalTrial> trials) {
        int passed = (int) trials.stream().filter(t -> t.failureType() == EvalFailureType.PASS).count();
        return new EvalReport(config.configDigest(), config.datasetName(), config.datasetVersion(),
                config.split(), trials.size(), passed, 0, 0, Map.of(), 0, 0, 0, 0,
                null, null, List.of(), List.copyOf(trials));
    }

    private static EvalTrial trial(String sampleId, EvalCategory category, int attempt,
                                   EvalFailureType failure, EvalStrategy strategy,
                                   int childRunCount, int conflictCount, int reworkAttempts,
                                   long latencyMs, Integer settledTokens, String cost) {
        boolean passed = failure == EvalFailureType.PASS;
        return new EvalTrial(
                "trial-" + sampleId + "-" + attempt + "-" + strategy, sampleId, "dev", attempt,
                "run-" + sampleId + "-" + attempt + "-" + strategy,
                "COMPLETED", "ANSWER_COMPLETE", "patch-" + sampleId,
                passed ? "PASSED" : "FAILED", passed ? 0 : 1, 1,
                passed ? 1 : 0, 0, "log-" + sampleId,
                failure, latencyMs, 1, 0, settledTokens, cost, "CNY",
                null, null, null, null, "pv",
                strategy, category, childRunCount, conflictCount, reworkAttempts);
    }

    private static String sampleOf(EvalCategory category) {
        return switch (category) {
            case SIMPLE -> "c01";
            case INDEPENDENT -> "c02";
            case DEPENDENT -> "c03";
            case CONFLICT -> "c04";
        };
    }
}
