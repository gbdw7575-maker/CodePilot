package com.dsh.agent.tool.discovery;

import com.dsh.agent.tool.Tool;
import com.dsh.agent.tool.ToolDefinition;
import com.dsh.agent.tool.ToolRegistry;
import com.dsh.application.DshProperties;
import com.dsh.common.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool Discovery（内部接口契约 §1 / 开发文档 §7）：
 * 权限及可用性过滤 → BM25 主召回 → 可选字符 n-gram/RRF 实验 → 去重 → top-5。
 * 仅搜授权可见目录；空结果=成功空列表；向量腿失败时 BM25 降级并标记 degraded。
 * RRF(tool) = Σ 1 / (k + rank_i(tool))，rank 从 1 开始，k 为冻结配置。
 */
@Service
public class ToolDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ToolDiscoveryService.class);

    private final ToolRegistry registry;
    private final DshProperties properties;
    private final EmbeddingClient embeddingClient; // 可为 null（vector-enabled=false 或未装配）

    public ToolDiscoveryService(ToolRegistry registry, DshProperties properties,
                                org.springframework.beans.factory.ObjectProvider<EmbeddingClient> embedding) {
        this.registry = registry;
        this.properties = properties;
        this.embeddingClient = embedding.getIfAvailable();
    }

    /**
     * 检索扩展工具目录。query 为模型产生的检索串；返回条目按 RRF 分数降序，最多 maxResults 个。
     * BM25 主排序覆盖名称、描述、何时使用、参数名/参数说明；字符 n-gram 向量腿作为实验对照。
     * BM25 分数低于 minBm25Score 的候选视为低相关，不进入融合结果（D-11）。
     */
    public DiscoveryResult search(String query) {
        DshProperties.ToolSearch config = properties.toolSearch();
        int k = Math.max(config.rrfK(), 1);
        int limit = Math.max(config.maxResults(), 1);
        double minScore = config.minBm25Score();

        // 1. 权限及可用性过滤：仅授权可见的扩展工具进入候选
        List<Candidate> candidates = new ArrayList<>();
        for (Tool tool : registry.extensionTools()) {
            Tool.ExtensionMeta meta = tool.extensionMeta();
            if (meta == null || !meta.authorized()) {
                continue;
            }
            ToolDefinition definition = registry.definition(tool.name()).orElse(null);
            if (definition == null) {
                continue;
            }
            candidates.add(new Candidate(tool.name(), definition, meta.whenToUse()));
        }

        boolean vectorLeg = embeddingClient != null && config.vectorEnabled();
        boolean degraded = false;

        // 2a. BM25 召回（覆盖名称、描述、何时使用、参数名/参数说明）
        Bm25Index index = new Bm25Index();
        for (Candidate candidate : candidates) {
            index.add(candidate.name(), candidate.documentText());
        }
        Map<Integer, Double> bm25Scores = query == null || query.isBlank()
                ? Map.of() : index.score(query);
        // 低相关过滤：BM25 分数低于阈值的候选不进入融合结果
        if (minScore > 0) {
            bm25Scores = bm25Scores.entrySet().stream()
                    .filter(e -> e.getValue() >= minScore)
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        List<ScoredDoc> bm25Ranked = rankScores(bm25Scores, candidates);

        // 2b. 向量召回（失败降级 BM25 单路并标记 degraded）
        List<ScoredDoc> vectorRanked = List.of();
        String vectorStatus = "DISABLED";
        if (vectorLeg && !bm25Ranked.isEmpty()) {
            try {
                List<EmbeddingClient.Doc> docs = candidates.stream()
                        .map(c -> new EmbeddingClient.Doc(c.name(), c.documentText()))
                        .toList();
                if (query != null && !query.isBlank() && !docs.isEmpty()) {
                    vectorStatus = "ACTIVE";
                    List<EmbeddingClient.Scored> scored = embeddingClient.rank(query, docs);
                    List<ScoredDoc> ranked = new ArrayList<>();
                    int rank = 0;
                    for (EmbeddingClient.Scored s : scored) {
                        if (candidates.stream().noneMatch(c -> c.name().equals(s.docId()))) {
                            continue; // 不在授权候选内（防御）
                        }
                        ranked.add(new ScoredDoc(s.docId(), ++rank, s.score()));
                    }
                    vectorRanked = ranked;
                }
            } catch (Exception e) {
                log.warn("向量召回失败，BM25 单路降级: {}", e.toString());
                degraded = true;
                vectorStatus = "DEGRADED";
            }
        }

        // 3. RRF 融合：rank 从 1 开始；未进入某一路则该路不贡献分数
        Map<String, Double> rrf = new LinkedHashMap<>();
        Map<String, Double> bm25RankByTool = new HashMap<>();
        Map<String, Double> vectorRankByTool = new HashMap<>();
        for (ScoredDoc doc : bm25Ranked) {
            rrf.merge(doc.name(), 1.0 / (k + doc.rank()), Double::sum);
            bm25RankByTool.putIfAbsent(doc.name(), (double) doc.rank());
        }
        for (ScoredDoc doc : vectorRanked) {
            rrf.merge(doc.name(), 1.0 / (k + doc.rank()), Double::sum);
            vectorRankByTool.putIfAbsent(doc.name(), (double) doc.rank());
        }

        // 4. 去重（Map 天然去重）→ 仅保留 BM25 主路命中的工具（低相关/无命中不返回）→ top-5
        List<DiscoveryResult.Item> items = rrf.entrySet().stream()
                .filter(entry -> bm25RankByTool.containsKey(entry.getKey()))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(entry -> {
                    Candidate candidate = candidates.stream()
                            .filter(c -> c.name().equals(entry.getKey())).findFirst().orElseThrow();
                    return new DiscoveryResult.Item(candidate.name(),
                            candidate.definition().description(),
                            candidate.definition().version(),
                            bm25RankByTool.get(candidate.name()),
                            vectorRankByTool.get(candidate.name()),
                            entry.getValue());
                })
                .toList();

        // 目录内容版本指纹：更新后重建索引的观测依据
        return new DiscoveryResult(items, degraded, vectorStatus, candidates.size());
    }

    /** 目录条目内容哈希（版本/哈希观测；更新后重建对应索引）。 */
    public String catalogDigest() {
        StringBuilder builder = new StringBuilder();
        for (Tool tool : registry.extensionTools()) {
            ToolDefinition definition = registry.definition(tool.name()).orElse(null);
            if (definition == null) {
                continue;
            }
            builder.append(tool.name()).append(':').append(definition.version()).append(':')
                    .append(definition.description()).append(':')
                    .append(tool.extensionMeta() == null ? "" : tool.extensionMeta().whenToUse()).append(':');
            com.fasterxml.jackson.databind.JsonNode schema = definition.inputSchema();
            if (schema != null && schema.isObject()) {
                com.fasterxml.jackson.databind.JsonNode properties = schema.path("properties");
                if (properties.isObject()) {
                    java.util.Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = properties.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> field = fields.next();
                        builder.append(field.getKey()).append('=')
                                .append(field.getValue().path("description").asText("")).append(',');
                    }
                }
            }
            builder.append('\n');
        }
        return Digest.sha256Hex(builder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private List<ScoredDoc> rankScores(Map<Integer, Double> scores, List<Candidate> candidates) {
        List<ScoredDoc> ranked = new ArrayList<>();
        int rank = 0;
        for (Map.Entry<Integer, Double> entry : scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .toList()) {
            if (entry.getKey() < 0 || entry.getKey() >= candidates.size()) {
                continue;
            }
            ranked.add(new ScoredDoc(candidates.get(entry.getKey()).name(), ++rank, entry.getValue()));
        }
        return ranked;
    }

    private record Candidate(String name, ToolDefinition definition, String whenToUse) {

        /**
         * BM25 索引文档：名称 + 描述 + 何时使用 + 各参数名与参数说明。
         * 参数名/说明覆盖后，查询参数语义（如"经验 id""子 agent 任务"）可直接命中。
         */
        String documentText() {
            StringBuilder sb = new StringBuilder();
            sb.append(definition.name()).append('\n');
            sb.append(definition.description() == null ? "" : definition.description()).append('\n');
            sb.append(whenToUse == null ? "" : whenToUse);
            com.fasterxml.jackson.databind.JsonNode schema = definition.inputSchema();
            if (schema != null && schema.isObject()) {
                com.fasterxml.jackson.databind.JsonNode properties = schema.path("properties");
                if (properties.isObject()) {
                    java.util.Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = properties.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> field = fields.next();
                        sb.append('\n').append(field.getKey());
                        String desc = field.getValue().path("description").asText("");
                        if (!desc.isEmpty()) {
                            sb.append(' ').append(desc);
                        }
                    }
                }
            }
            return sb.toString();
        }
    }

    private record ScoredDoc(String name, int rank, double score) {
    }
}
