package com.dsh.rag;

import com.dsh.agent.tool.discovery.CharNgramEmbeddingClient;
import com.dsh.agent.tool.discovery.EmbeddingClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG 召回服务（R2 新增）：向量 top-K + 关键词匹配双路召回，RRF 融合去重取 top-K。
 * <p>
 * 向量腿：调 {@link RagVectorStore#search} 余弦相似度排序。
 * 关键词腿：CJK 二元组分词 + 英文单词，逐词匹配 chunk 文本，按命中数排序。
 * RRF 融合：{@code Σ 1/(rrfK + rank)}，rank 从 1，去重取 top-K。
 * <p>
 * Fail-open：配置关闭、无索引、嵌入异常时返回空列表，不阻断主 Loop。
 */
public class RagRetrievalService {

    private final RagVectorStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final int rrfK;
    private final boolean bm25Enabled;
    private final double cosineFloor;
    private final int bm25MinHits;

    public RagRetrievalService(RagVectorStore vectorStore, EmbeddingClient embeddingClient,
                               int rrfK, boolean bm25Enabled, double cosineFloor, int bm25MinHits) {
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient != null ? embeddingClient : new CharNgramEmbeddingClient();
        this.rrfK = Math.max(1, rrfK);
        this.bm25Enabled = bm25Enabled;
        this.cosineFloor = cosineFloor <= 0 ? 0.55 : cosineFloor;
        this.bm25MinHits = Math.max(1, bm25MinHits);
    }

    /** 召回与查询相关的 top-K 文档片段。无索引/异常时返回空列表（fail-open）。 */
    public List<RagDocument> recall(String projectId, String query, int topK) {
        if (projectId == null || projectId.isBlank() || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        if (!vectorStore.exists(projectId)) {
            return List.of();
        }
        // RRF 融合：docId → 分数
        Map<String, Double> rrfScore = new HashMap<>();
        Map<String, RagDocument> docById = new LinkedHashMap<>();

        // 向量腿：cosine 阈值过滤，低于 cosineFloor 的片段不计分（避免不相关 query 污染）
        try {
            float[] queryVec = embeddingClient.embed(query);
            List<RagVectorStore.ScoredDoc> vecHits = vectorStore.search(projectId, queryVec, topK * 2);
            int rank = 0;
            for (RagVectorStore.ScoredDoc hit : vecHits) {
                if (hit.score() < cosineFloor) {
                    continue;  // 绝对相似度不够，不注入
                }
                rank++;
                String id = hit.doc().id();
                docById.putIfAbsent(id, hit.doc());
                rrfScore.merge(id, 1.0 / (rrfK + rank), Double::sum);
            }
        } catch (Exception ignored) {
            // 嵌入异常：fail-open，仅靠关键词腿
        }

        // 关键词腿：命中数需达到 bm25MinHits 才计入（过滤仅 1 个常见词弱命中的无关 query）。
        // 短 query 保护：有效阈值不超过 query 关键词数，避免"辉夜"这类单二元组 query 被误杀。
        if (bm25Enabled) {
            List<RagDocument> allDocs = vectorStore.documents(projectId);
            Map<String, Integer> hitCount = new HashMap<>();
            Set<String> keywords = tokenize(query);
            int effectiveMinHits = Math.min(bm25MinHits, Math.max(1, keywords.size()));
            for (RagDocument d : allDocs) {
                docById.putIfAbsent(d.id(), d);
                int hits = countHits(d.text(), keywords);
                if (hits >= effectiveMinHits) {
                    hitCount.put(d.id(), hits);
                }
            }
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(hitCount.entrySet());
            sorted.sort(Comparator.comparingInt(Map.Entry<String, Integer>::getValue).reversed());
            for (int i = 0; i < sorted.size(); i++) {
                String id = sorted.get(i).getKey();
                rrfScore.merge(id, 1.0 / (rrfK + i + 1), Double::sum);
            }
        }

        if (rrfScore.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(rrfScore.entrySet());
        ranked.sort(Comparator.comparingDouble(Map.Entry<String, Double>::getValue).reversed());
        List<RagDocument> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, ranked.size()); i++) {
            result.add(docById.get(ranked.get(i).getKey()));
        }
        return result;
    }

    /** CJK 二元组 + 英文单词分词（与 EpisodicMemoryService 一致策略）。 */
    private Set<String> tokenize(String text) {
        Set<String> sink = new HashSet<>();
        int n = text.length();
        int i = 0;
        StringBuilder englishWord = new StringBuilder();
        while (i < n) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushEnglish(englishWord, sink);
                if (i + 1 < n && isCjk(text.charAt(i + 1))) {
                    sink.add(text.substring(i, i + 2));
                    i += 2;
                } else {
                    sink.add(String.valueOf(c));
                    i += 1;
                }
            } else if (Character.isLetterOrDigit(c)) {
                englishWord.append(c);
                i += 1;
            } else {
                flushEnglish(englishWord, sink);
                i += 1;
            }
        }
        flushEnglish(englishWord, sink);
        return sink;
    }

    private void flushEnglish(StringBuilder word, Set<String> sink) {
        if (word.length() == 0) {
            return;
        }
        String w = word.toString().toLowerCase();
        word.setLength(0);
        if (w.length() >= 2) {
            sink.add(w);
        }
    }

    private boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    private int countHits(String text, Set<String> keywords) {
        if (text == null || text.isEmpty() || keywords.isEmpty()) {
            return 0;
        }
        String lower = text.toLowerCase();
        int count = 0;
        for (String kw : keywords) {
            if (lower.contains(kw.toLowerCase())) {
                count++;
            }
        }
        return count;
    }
}
