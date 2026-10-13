package com.dsh.agent.tool.discovery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地确定性字符三元组哈希嵌入（DSH-009 实验对照）：
 * 无外部向量服务依赖，同文本恒同向量；工具规模增长后可替换为独立 Embedding 配置。
 * 维度 128；哈希范围内分桶 + L2 归一化，余弦相似度可区分词汇重叠度。
 */
@Component
@ConditionalOnProperty(prefix = "dsh.tool-search", name = "vector-enabled",
        havingValue = "true")
public class CharNgramEmbeddingClient implements EmbeddingClient {

    public static final int DIM = 128;
    private static final int NGRAM = 3;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIM];
        if (text == null || text.isEmpty()) {
            return vector;
        }
        String normalized = text.toLowerCase();
        for (int i = 0; i + NGRAM <= normalized.length(); i++) {
            String gram = normalized.substring(i, i + NGRAM);
            int bucket = Math.floorMod(gram.hashCode(), DIM);
            vector[bucket] += 1f;
        }
        // 单字符/短文本也要有信号：补充 unigram
        for (int i = 0; i < normalized.length(); i++) {
            int bucket = Math.floorMod(normalized.substring(i, i + 1).hashCode(), DIM);
            vector[bucket] += 0.5f;
        }
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        if (norm > 0) {
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < DIM; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }

    @Override
    public int dimension() {
        return DIM;
    }

    @Override
    public List<Scored> rank(String query, List<Doc> docs) {
        float[] queryVector = embed(query);
        List<Scored> scored = new ArrayList<>();
        for (Doc doc : docs) {
            float[] docVector = embed(doc.text());
            double dot = 0;
            for (int i = 0; i < DIM; i++) {
                dot += (double) queryVector[i] * docVector[i];
            }
            scored.add(new Scored(doc.docId(), dot));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored;
    }
}
