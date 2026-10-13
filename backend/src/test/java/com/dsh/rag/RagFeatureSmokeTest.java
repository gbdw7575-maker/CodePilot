package com.dsh.rag;

import com.dsh.agent.tool.discovery.CharNgramEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 冒烟测试（R5）：端到端验证文档加载→嵌入→索引→召回链路。
 * 不启 Spring 上下文，直接用各组件构造，验证集成可工作。
 */
class RagFeatureSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void endToEndIndexAndRecall() throws IOException {
        // 准备测试文档
        Path sources = tempDir.resolve("sources");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("test.md"),
                "# 测试知识库\n\n## 辉夜大小姐\n\n四宫辉夜发现了封条异常。\n\n" +
                "## 孤独摇滚\n\n后藤一里背着吉他走在路上。\n");

        Path storage = tempDir.resolve("storage");
        Path manifest = tempDir.resolve("manifest");

        // 构造组件
        CharNgramEmbeddingClient embedding = new CharNgramEmbeddingClient();
        RagVectorStore store = new FileSystemRagVectorStore(storage);
        RagDocumentLoader loader = new RagDocumentLoader(1000);
        RagRetrievalService retrieval = new RagRetrievalService(store, embedding, 60, true, 0.15, 3);
        RagIndexer indexer = new RagIndexer(loader, embedding, store, manifest);

        // 索引
        boolean indexed = indexer.indexProject("proj-smoke", sources);
        assertTrue(indexed, "首次索引应返回 true");

        // 召回：查询应命中辉夜段落
        var hits = retrieval.recall("proj-smoke", "辉夜封条", 3);
        assertFalse(hits.isEmpty(), "召回不应为空");
        boolean hasKaguya = hits.stream()
                .anyMatch(d -> d.text() != null && d.text().contains("辉夜"));
        assertTrue(hasKaguya, "应命中辉夜段落");

        // 召回：不同查询命中不同段落
        var hits2 = retrieval.recall("proj-smoke", "吉他一里", 3);
        assertFalse(hits2.isEmpty(), "召回不应为空");
        boolean hasBocchi = hits2.stream()
                .anyMatch(d -> d.text() != null && d.text().contains("一里"));
        assertTrue(hasBocchi, "应命中孤独摇滚段落");
    }

    @Test
    void reindexAfterNoChangeIsIdempotent() throws IOException {
        Path sources = tempDir.resolve("src");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("doc.md"),
                "# 文档\n\n## 段落A\n\n内容A\n");
        Path storage = tempDir.resolve("store");
        Path manifest = tempDir.resolve("manifest");

        CharNgramEmbeddingClient embedding = new CharNgramEmbeddingClient();
        RagVectorStore store = new FileSystemRagVectorStore(storage);
        RagDocumentLoader loader = new RagDocumentLoader(1000);
        RagIndexer indexer = new RagIndexer(loader, embedding, store, manifest);

        boolean first = indexer.indexProject("proj-idem", sources);
        assertTrue(first, "首次索引应返回 true");
        boolean second = indexer.indexProject("proj-idem", sources);
        assertFalse(second, "未变更重新索引应返回 false（幂等跳过）");
    }
}
