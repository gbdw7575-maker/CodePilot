package com.dsh.contract;

import com.dsh.evals.EvalRunner;
import com.dsh.evals.model.EvalBudget;
import com.dsh.evals.model.EvalConfig;
import com.dsh.evals.model.EvalDataset;
import com.dsh.evals.model.EvalFailureType;
import com.dsh.evals.model.EvalGraderSpec;
import com.dsh.evals.model.EvalReport;
import com.dsh.evals.model.EvalSample;
import com.dsh.evals.model.EvalTrial;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-008 契约对齐（doc/内部接口契约.md §8 + Agent项目开发文档.md §17 + 开发准备与验收.md EVAL-01）：
 * EvalConfig/EvalReport 字段齐全、独立评分器、pass@1 非 best-of-retries、
 * EVAL-01 改预算对照可行性、区分开发集/留出集。
 */
class ContractAlignmentDsh008Test {

    @Test
    void evalConfigContainsAllContractFields() {
        EvalBudget budget = new EvalBudget(24, 80, 200000, 20);
        String digest = EvalConfig.computeDigest("d1", "v1", "dev", Set.of("s1"),
                "modelDigest", budget, "imgDigest", 3, 42L);
        EvalConfig config = new EvalConfig("d1", "v1", "dev", Set.of("s1"), "profile-1",
                "modelDigest", budget, 42L, "imgDigest", 3, digest);

        // §8 EvalConfig：dataset/version/split、样本 ID、模型/提示词/工具/策略摘要、budget、seed、镜像摘要、重复次数
        assertEquals("d1", config.datasetName());
        assertEquals("v1", config.datasetVersion());
        assertEquals("dev", config.split());
        assertEquals(Set.of("s1"), config.sampleIds());
        assertEquals("profile-1", config.modelProfileId());
        assertNotNull(config.modelProfileConfigDigest(), "模型/提示词/工具/策略摘要必填");
        assertNotNull(config.budget());
        assertEquals(42L, config.seed());
        assertNotNull(config.imageDigest(), "镜像摘要必填");
        assertEquals(3, config.repeatCount());
        assertNotNull(config.configDigest(), "冻结配置摘要必填（EVAL-01 对照）");
    }

    @Test
    void evalReportContainsAllContractFields() {
        EvalConfig config = baseConfig("dev", 2, null);
        EvalSample a = new EvalSample("a", "task-a", grader());
        EvalSample b = new EvalSample("b", "task-b", grader());
        // A: attempt1 失败、attempt2 通过；B: attempt1 通过
        List<EvalTrial> trials = List.of(
                trial("a", 1, EvalFailureType.GRADER_FAILED, 100, "run-a1"),
                trial("a", 2, EvalFailureType.PASS, 200, "run-a2"),
                trial("b", 1, EvalFailureType.PASS, 150, "run-b1"));
        EvalReport report = EvalRunner.aggregate(config, trials, List.of(a, b));

        // §8 EvalReport：通过率、失败分型、延迟、请求/token/成本、证据与原始 trial 引用
        assertEquals(3, report.totalTrials());
        assertEquals(2, report.passedTrials());
        assertTrue(report.passAt1() >= 0 && report.passAt1() <= 1);
        assertTrue(report.passAtK() >= 0 && report.passAtK() <= 1);
        assertNotNull(report.failureBreakdown());
        assertTrue(report.latencyMeanMs() >= 0);
        assertTrue(report.latencyP95Ms() >= 0);
        assertTrue(report.totalModelRequests() >= 0);
        assertTrue(report.totalTokens() >= 0);
        assertNotNull(report.sampleResults());
        assertEquals(2, report.sampleResults().size());
        assertEquals(trials.size(), report.trials().size());
        // 证据与原始 trial 引用：每个 trial 携带 runId
        report.trials().forEach(t -> assertNotNull(t.runId()));
    }

    @Test
    void passAt1IsNotBestOfRetries() {
        // 核心契约：不把重试后的最好结果当默认 pass@1
        EvalConfig config = baseConfig("dev", 2, null);
        EvalSample a = new EvalSample("a", "task-a", grader());
        EvalSample b = new EvalSample("b", "task-b", grader());
        // A 首轮失败、次轮通过 → best-of 会算通过，但 pass@1 不算
        // B 首轮通过
        List<EvalTrial> trials = List.of(
                trial("a", 1, EvalFailureType.GRADER_FAILED, 100, "ra1"),
                trial("a", 2, EvalFailureType.PASS, 200, "ra2"),
                trial("b", 1, EvalFailureType.PASS, 150, "rb1"));
        EvalReport report = EvalRunner.aggregate(config, trials, List.of(a, b));

        // pass@1：仅首轮通过样本占比 = B 通过 → 1/2 = 0.5（A 首轮失败，不计）
        assertEquals(0.5, report.passAt1(), 1e-9);
        // pass@K：K 轮内至少一次通过 = A（次轮）+ B → 2/2 = 1.0
        assertEquals(1.0, report.passAtK(), 1e-9);
        assertNotEquals(report.passAt1(), report.passAtK(),
                "pass@1 与 pass@K 必须区分，不得用 best-of 冒充 pass@1");

        // 每样本：A 首次通过=第 2 轮，B=第 1 轮
        EvalTrial ra2 = report.trials().stream()
                .filter(t -> t.sampleId().equals("a") && t.attempt() == 2).findFirst().orElseThrow();
        assertEquals(EvalFailureType.PASS, ra2.failureType());
        report.sampleResults().stream().filter(r -> r.sampleId().equals("a"))
                .forEach(r -> assertEquals(2, r.passedAttempt()));
    }

    @Test
    void independentGraderSkippedWhenNoPatch() {
        // §8：模型声称改过代码但无变更记录不算完成 → AGENT_NO_CHANGES，评分器跳过（无补丁无可评）
        EvalConfig config = baseConfig("dev", 1, null);
        EvalSample a = new EvalSample("a", "task-a", grader());
        List<EvalTrial> trials = List.of(
                new EvalTrial("t1", "a", "dev", 1, "run-1", "COMPLETED", "ANSWER_COMPLETE",
                        null, "SKIPPED", null, 0, 0, 0, null,
                        EvalFailureType.AGENT_NO_CHANGES, 100, 1, 0, null, null, null));
        EvalReport report = EvalRunner.aggregate(config, trials, List.of(a));

        assertEquals(1, report.failureBreakdown().get(EvalFailureType.AGENT_NO_CHANGES.name()));
        assertEquals(0, report.passedTrials());
        assertEquals(0.0, report.passAt1());
        // 成本未知为 null，不补零（§4：缺失值为 UNKNOWN/null）
        assertNull(report.totalCost());
        assertNull(report.currency());
    }

    @Test
    void eval01ChangingBudgetProducesDifferentComparableDigest() {
        // EVAL-01：改预算后对照实验，固定其他因素 → 不同 configDigest，可对照
        EvalBudget budgetA = new EvalBudget(24, 80, 200000, 20);
        EvalBudget budgetB = new EvalBudget(48, 80, 200000, 20); // 仅 maxModelRequests 翻倍
        String digestA = EvalConfig.computeDigest("d1", "v1", "dev", "modelDigest", budgetA, "img", 2, null);
        String digestB = EvalConfig.computeDigest("d1", "v1", "dev", "modelDigest", budgetB, "img", 2, null);
        assertNotEquals(digestA, digestB, "改预算必须产出不同冻结摘要以供对照");
        // 固定其他因素 → 相同摘要
        assertEquals(digestA, EvalConfig.computeDigest("d1", "v1", "dev", "modelDigest", budgetA, "img", 2, null));
        assertNotEquals(
                EvalConfig.computeDigest("d1", "v1", "dev", Set.of("s01"),
                        "modelDigest", budgetA, "img", 2, null),
                EvalConfig.computeDigest("d1", "v1", "dev", Set.of("s02"),
                        "modelDigest", budgetA, "img", 2, null));
    }

    @Test
    void datasetDistinguishesDevAndHeldoutSplits() {
        // §17：统计全部尝试，区分开发集/独立测试集
        EvalSample dev = new EvalSample("dev-1", "t-dev", grader());
        EvalSample held = new EvalSample("held-1", "t-held", grader());
        EvalDataset dataset = new EvalDataset("d", "v1", List.of(dev, held),
                Set.of("dev-1"), Set.of("held-1"));
        assertEquals(List.of(dev), dataset.samplesOfSplit("dev"));
        assertEquals(List.of(held), dataset.samplesOfSplit("heldout"));
    }

    private static EvalConfig baseConfig(String split, int repeatCount, Long seed) {
        EvalBudget budget = new EvalBudget(24, 80, 200000, 20);
        String digest = EvalConfig.computeDigest("d1", "v1", split, "modelDigest", budget, "img", repeatCount, seed);
        return new EvalConfig("d1", "v1", split, Set.of(), "profile-1", "modelDigest",
                budget, seed, "img", repeatCount, digest);
    }

    private static EvalGraderSpec grader() {
        return new EvalGraderSpec("echo Tests run: 1, Failures: 0", ".", 60, true, 1);
    }

    private static EvalTrial trial(String sampleId, int attempt, EvalFailureType failureType,
                                   long latencyMs, String runId) {
        return new EvalTrial("trial-" + sampleId + "-" + attempt, sampleId, "dev", attempt, runId,
                "COMPLETED", "ANSWER_COMPLETE", "patch-art-" + attempt,
                failureType == EvalFailureType.PASS ? "PASSED" : "FAILED",
                failureType == EvalFailureType.PASS ? 0 : 1, 1,
                failureType == EvalFailureType.PASS ? 1 : 0, 0, "log-art-" + attempt,
                failureType, latencyMs, 1, 0, 100, null, null);
    }
}
