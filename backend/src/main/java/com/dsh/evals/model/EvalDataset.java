package com.dsh.evals.model;

import java.util.List;
import java.util.Set;

/**
 * 评测数据集：冻结任务集，区分开发集（dev）与留出集（heldout）。
 * doc/Agent项目开发文档.md §17：统计全部尝试，区分开发集/独立测试集。
 * 固定开发集调整 prompt/tool/预算，再在留出集报告。
 */
public record EvalDataset(
        String name,
        String version,
        List<EvalSample> samples,
        Set<String> devSplitIds,
        Set<String> heldoutSplitIds
) {
    public EvalDataset {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("dataset.name 必填");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("dataset.version 必填");
        }
        samples = samples == null ? List.of() : List.copyOf(samples);
        devSplitIds = devSplitIds == null ? Set.of() : Set.copyOf(devSplitIds);
        heldoutSplitIds = heldoutSplitIds == null ? Set.of() : Set.copyOf(heldoutSplitIds);
    }

    /** 按 split(dev/heldout) 返回样本子集。 */
    public List<EvalSample> samplesOfSplit(String split) {
        Set<String> ids = "heldout".equalsIgnoreCase(split) ? heldoutSplitIds : devSplitIds;
        return samples.stream().filter(s -> ids.contains(s.id())).toList();
    }

    public EvalSample sample(String id) {
        return samples.stream().filter(s -> s.id().equals(id))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("样本不存在: " + id));
    }
}
