package com.dsh.rag;

import com.dsh.agent.tool.discovery.CharNgramEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 阈值校准测试：索引真实知识库，扫描多个 cosine-floor，打印每个 floor 下相关/无关
 * query 的最终 recall heading。结论：char-ngram 嵌入 raw cosine 区分度有限，cosine-floor
 * 主要过滤向量腿；BM25 腿对含常见词的长 query 仍可能命中弱相关片段，由注入的证据判断规则兜底。
 */
class RagThresholdCalibrationTest {

    private static final Path KB_DIR = Path.of("src", "test", "resources", "rag-fixtures").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    @Test
    void scanCosineFloors() throws Exception {
        CharNgramEmbeddingClient embedding = new CharNgramEmbeddingClient();
        RagVectorStore store = new FileSystemRagVectorStore(tempDir.resolve("store"));
        RagDocumentLoader loader = new RagDocumentLoader(1000);
        RagIndexer indexer = new RagIndexer(loader, embedding, store, tempDir.resolve("manifest"));

        boolean indexed = indexer.indexProject("calib", KB_DIR);
        assertTrue(indexed, "真实知识库应索引成功");

        List<String> relevant = List.of(
                "四宫辉夜 封条 古籍研究室 密室",
                "孙悟空 鸣人 路飞 万象食谱 时之狭间",
                "辉夜大小姐想让我告白 白银御行 藤原千花"
        );
        List<String> irrelevant = List.of(
                "量子力学 薛定谔方程 波函数 算符",
                "Spring Boot 数据源配置 HikariCP 连接池",
                "比特币 区块链 挖矿 哈希算法"
        );

        System.out.println("=== RAG cosine-floor 扫描（知识库: " + KB_DIR + "） ===");
        for (double floor : new double[]{0.15, 0.30, 0.40, 0.45, 0.48, 0.50, 0.55, 0.60}) {
            RagRetrievalService retrieval = new RagRetrievalService(store, embedding, 60, true, floor, 3);
            int relHit = 0, irrEmpty = 0;
            for (String q : relevant) {
                if (!retrieval.recall("calib", q, 3).isEmpty()) relHit++;
            }
            for (String q : irrelevant) {
                if (retrieval.recall("calib", q, 3).isEmpty()) irrEmpty++;
            }
            System.out.println(String.format("floor=%.2f  相关命中=%d/%d  无关为空=%d/%d",
                    floor, relHit, relevant.size(), irrEmpty, irrelevant.size()));
        }

        // 选定 floor=0.55 + bm25MinHits=3：向量腿过滤 Spring Boot 等高分无关 query，
        // BM25 腿过滤仅 1-2 个常见词弱命中的无关 query；相关 query 靠 BM25 高命中数召回。
        double chosenFloor = 0.55;
        RagRetrievalService retrieval = new RagRetrievalService(store, embedding, 60, true, chosenFloor, 3);
        System.out.println("\n--- 选定 floor=" + chosenFloor + " + bm25MinHits=3 详细召回 ---");
        for (String q : relevant) {
            List<RagDocument> r = retrieval.recall("calib", q, 3);
            assertFalse(r.isEmpty(), "相关 query 应召回: " + q);
            System.out.println("[相关] " + q + " -> " + r.stream().map(RagDocument::heading).toList());
        }
        for (String q : irrelevant) {
            List<RagDocument> r = retrieval.recall("calib", q, 3);
            assertTrue(r.isEmpty(), "无关 query 应召回为空（避免污染）: " + q);
            System.out.println("[无关] " + q + " -> " + r.stream().map(RagDocument::heading).toList());
        }
    }
}
