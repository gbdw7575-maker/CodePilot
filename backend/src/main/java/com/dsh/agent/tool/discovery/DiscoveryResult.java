package com.dsh.agent.tool.discovery;

import java.util.List;

/**
 * 工具发现结果（内部接口契约 §1 ToolDiscovery）：top-5 与挂载前置信息。
 * degraded=true 表示向量腿配置存在但失败、本次结果来自 BM25 单路降级（不得混作"没有匹配"）。
 */
public record DiscoveryResult(List<Item> items, boolean degraded, String vectorStatus,
                              int catalogSize) {

    /** 命中项：工具名 + 两路排名（未进入某一路为 null，不贡献 RRF 分数）。 */
    public record Item(String name, String description, String version,
                       Double bm25Rank, Double vectorRank, double rrfScore) {
    }
}
