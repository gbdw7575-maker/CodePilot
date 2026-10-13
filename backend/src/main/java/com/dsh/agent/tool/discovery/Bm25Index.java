package com.dsh.agent.tool.discovery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具目录 BM25 检索（开发文档 §7.2）：索引对象仅为工具目录文本
 * （名称、描述、何时使用、必要参数说明），不是项目代码向量库。
 * 分词：小写化 + 非 alnum 切分 + CJK 字符二元组，保证中英文目录都可召回。
 */
public final class Bm25Index {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final List<String[]> docs = new ArrayList<>();
    private final Map<String, List<Integer>> postings = new HashMap<>();
    private final Map<String, Integer> docFreq = new HashMap<>();
    private double avgDocLength;

    public void add(String docId, String text) {
        String[] tokens = tokenize(text);
        int index = docs.size();
        docs.add(tokens);
        for (String token : tokens) {
            postings.computeIfAbsent(token, k -> new ArrayList<>()).add(index);
        }
        rebuildDocFreq();
        double total = docs.stream().mapToInt(d -> d.length).sum();
        avgDocLength = docs.isEmpty() ? 0 : total / docs.size();
    }

    private void rebuildDocFreq() {
        docFreq.clear();
        for (String[] tokens : docs) {
            Map<String, Boolean> seen = new HashMap<>();
            for (String token : tokens) {
                if (seen.put(token, Boolean.TRUE) == null) {
                    docFreq.merge(token, 1, Integer::sum);
                }
            }
        }
    }

    /** BM25 打分；返回 docIndex → 分数，仅含命中文档。 */
    public Map<Integer, Double> score(String query) {
        String[] queryTokens = tokenize(query);
        Map<String, Integer> queryFreq = new HashMap<>();
        for (String token : queryTokens) {
            queryFreq.merge(token, 1, Integer::sum);
        }
        Map<Integer, Double> scores = new HashMap<>();
        int n = docs.size();
        for (Map.Entry<String, Integer> entry : queryFreq.entrySet()) {
            List<Integer> posting = postings.get(entry.getKey());
            if (posting == null) {
                continue;
            }
            int df = docFreq.getOrDefault(entry.getKey(), 0);
            if (df == 0) {
                continue;
            }
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            // 同一文档多次命中需要按词频计算，重扫 posting 去重
            Map<Integer, Integer> termFreq = new HashMap<>();
            for (Integer docIndex : posting) {
                termFreq.merge(docIndex, 1, Integer::sum);
            }
            for (Map.Entry<Integer, Integer> tf : termFreq.entrySet()) {
                String[] doc = docs.get(tf.getKey());
                double docLength = doc.length;
                double weight = tf.getValue() * (K1 + 1)
                        / (tf.getValue() + K1 * (1 - B + B * docLength / Math.max(avgDocLength, 1)));
                scores.merge(tf.getKey(), idf * weight * entry.getValue(), Double::sum);
            }
        }
        return scores;
    }

    /** 小写化 + 非 alnum 切分 + CJK 字符二元组。 */
    static String[] tokenize(String text) {
        if (text == null || text.isBlank()) {
            return new String[0];
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String lower = text.toLowerCase();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean alnum = Character.isLetterOrDigit(c);
            if (alnum) {
                current.append(c);
            } else if (current.length() > 0) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        // CJK 二元组：对连续 CJK 段补充 bigram
        List<String> withCjk = new ArrayList<>(tokens);
        for (String token : tokens) {
            if (token.codePoints().anyMatch(Bm25Index::isCjk) && token.length() > 1) {
                for (int i = 0; i < token.length() - 1; i++) {
                    withCjk.add(token.substring(i, i + 2));
                }
            }
        }
        return withCjk.toArray(new String[0]);
    }

    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF);
    }

    public int size() {
        return docs.size();
    }
}
