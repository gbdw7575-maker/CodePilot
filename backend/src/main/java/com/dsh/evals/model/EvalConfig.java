package com.dsh.evals.model;

import com.dsh.common.Digest;

import java.util.List;
import java.util.Set;

/**
 * 评测配置（doc/内部接口契约.md §8 EvalConfig）：
 * dataset/version/split、样本 ID、模型/提示词/工具/策略摘要（modelProfileConfigDigest）、
 * budget、seed（可用时）、镜像摘要、重复次数、对照策略 strategy（MA-11）。
 *
 * <p>configDigest 为冻结摘要：对全部配置因子取 SHA-256，用于 EVAL-01 跨实验对照
 * （改 prompt/工具/预算/策略后对照实验，固定其他因素）。</p>
 *
 * <p>摘要算法（MA-11 版本化，旧报告不重算）：SINGLE 与 MA-11 前摘要逐字节一致
 * （strategy 缺省即 SINGLE）；仅 MULTI 在 seed 段后追加 {@code |strategy=MULTI}。
 * 因此同因子两份配置仅策略不同时摘要必然不同，且旧 6 份 SINGLE 配置继续有效。</p>
 */
public record EvalConfig(
        String datasetName,
        String datasetVersion,
        String split,
        Set<String> sampleIds,
        String modelProfileId,
        String modelProfileConfigDigest,
        EvalBudget budget,
        Long seed,
        String imageDigest,
        int repeatCount,
        EvalStrategy strategy,
        String configDigest
) {
    public EvalConfig {
        if (datasetName == null || datasetName.isBlank()) {
            throw new IllegalArgumentException("datasetName 必填");
        }
        if (datasetVersion == null || datasetVersion.isBlank()) {
            throw new IllegalArgumentException("datasetVersion 必填");
        }
        if (split == null || split.isBlank()) {
            throw new IllegalArgumentException("split 必填");
        }
        if (!"dev".equalsIgnoreCase(split) && !"heldout".equalsIgnoreCase(split)) {
            throw new IllegalArgumentException("split 必须为 dev 或 heldout");
        }
        if (budget == null) {
            throw new IllegalArgumentException("budget 必填");
        }
        if (modelProfileId == null || modelProfileId.isBlank()
                || modelProfileConfigDigest == null || modelProfileConfigDigest.isBlank()) {
            throw new IllegalArgumentException("模型配置 ID 与摘要必填");
        }
        if (imageDigest == null || imageDigest.isBlank()) {
            throw new IllegalArgumentException("镜像摘要必填");
        }
        if (repeatCount < 1) {
            repeatCount = 1;
        }
        // MA-11：旧配置 JSON 无 strategy 字段（Jackson 传入 null），缺省 SINGLE
        strategy = strategy == null ? EvalStrategy.SINGLE : strategy;
        sampleIds = sampleIds == null ? Set.of() : Set.copyOf(sampleIds);
        String expectedDigest = computeDigest(datasetName, datasetVersion, split,
                sampleIds, modelProfileConfigDigest, budget, imageDigest, repeatCount, seed,
                strategy);
        if (!expectedDigest.equals(configDigest)) {
            throw new IllegalArgumentException("configDigest 与评测配置不匹配");
        }
    }

    /** 兼容 MA-11 前调用方：未显式策略即 SINGLE。 */
    public EvalConfig(String datasetName, String datasetVersion, String split,
                      Set<String> sampleIds, String modelProfileId,
                      String modelProfileConfigDigest, EvalBudget budget, Long seed,
                      String imageDigest, int repeatCount, String configDigest) {
        this(datasetName, datasetVersion, split, sampleIds, modelProfileId,
                modelProfileConfigDigest, budget, seed, imageDigest, repeatCount,
                EvalStrategy.SINGLE, configDigest);
    }

    /**
     * 计算冻结配置摘要：dataset|version|split|modelConfigDigest|budget|imageDigest|repeatCount|seed
     * （MULTI 追加 |strategy=MULTI；SINGLE 保持 MA-11 前摘要不变）。
     */
    public static String computeDigest(String datasetName, String datasetVersion, String split,
                                        String modelProfileConfigDigest, EvalBudget budget,
                                        String imageDigest, int repeatCount, Long seed) {
        return computeDigest(datasetName, datasetVersion, split, Set.of(),
                modelProfileConfigDigest, budget, imageDigest, repeatCount, seed,
                EvalStrategy.SINGLE);
    }

    public static String computeDigest(String datasetName, String datasetVersion, String split,
                                       Set<String> sampleIds, String modelProfileConfigDigest,
                                       EvalBudget budget, String imageDigest, int repeatCount,
                                       Long seed) {
        return computeDigest(datasetName, datasetVersion, split, sampleIds,
                modelProfileConfigDigest, budget, imageDigest, repeatCount, seed,
                EvalStrategy.SINGLE);
    }

    public static String computeDigest(String datasetName, String datasetVersion, String split,
                                       Set<String> sampleIds, String modelProfileConfigDigest,
                                       EvalBudget budget, String imageDigest, int repeatCount,
                                       Long seed, EvalStrategy strategy) {
        String base = String.join("|",
                datasetName, datasetVersion, split,
                modelProfileConfigDigest == null ? "" : modelProfileConfigDigest,
                budget.maxModelRequests() + ":" + budget.maxToolCalls() + ":"
                        + budget.maxCumulativeTokens() + ":" + budget.deadlineMinutes(),
                imageDigest == null ? "" : imageDigest,
                String.valueOf(repeatCount),
                seed == null ? "" : String.valueOf(seed));
        // SINGLE 不追加任何段，保证旧摘要兼容；仅 MULTI 进入摘要。
        if (strategy == EvalStrategy.MULTI) {
            base += "|strategy=MULTI";
        }
        if (sampleIds != null && !sampleIds.isEmpty()) {
            base += "|samples=" + sampleIds.stream().sorted().collect(java.util.stream.Collectors.joining(","));
        }
        return Digest.sha256Hex(base);
    }
}
