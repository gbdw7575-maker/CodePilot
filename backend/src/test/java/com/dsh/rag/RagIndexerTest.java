package com.dsh.rag;

import com.dsh.agent.tool.discovery.CharNgramEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RagIndexerTest {

    @TempDir
    Path tmp;

    private RagIndexer newIndexer(RagVectorStore store) throws Exception {
        return new RagIndexer(
                new RagDocumentLoader(1000),
                new CharNgramEmbeddingClient(),
                store,
                tmp.resolve("manifest"));
    }

    @Test
    void indexThenRecallWorks() throws Exception {
        Path sources = tmp.resolve("src");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("s1.md"), "## 故事一\n孙悟空和路飞在料理世界相遇。");
        Files.writeString(sources.resolve("s2.md"), "## 故事二\n辉夜和白银在古籍研究部。");

        RagVectorStore store = new FileSystemRagVectorStore(tmp.resolve("vec"));
        RagIndexer indexer = newIndexer(store);
        assertTrue(indexer.indexProject("p1", sources));
        assertTrue(store.exists("p1"));
        RagRetrievalService svc = new RagRetrievalService(store, new CharNgramEmbeddingClient(), 60, true, 0.15, 3);
        List<RagDocument> hits = svc.recall("p1", "孙悟空料理大战", 1);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).text().contains("孙悟空"));
    }

    @Test
    void noChangeSkipsIndexing() throws Exception {
        Path sources = tmp.resolve("src");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("s.md"), "## 故事\n内容");
        RagVectorStore store = new FileSystemRagVectorStore(tmp.resolve("vec"));
        RagIndexer indexer = newIndexer(store);
        assertTrue(indexer.indexProject("p1", sources));
        // 再次索引无变更
        assertFalse(indexer.indexProject("p1", sources));
    }

    @Test
    void fileChangeTriggersReindex() throws Exception {
        Path sources = tmp.resolve("src");
        Files.createDirectories(sources);
        Path md = sources.resolve("s.md");
        Files.writeString(md, "## 故事一\n旧内容");
        RagVectorStore store = new FileSystemRagVectorStore(tmp.resolve("vec"));
        RagIndexer indexer = newIndexer(store);
        assertTrue(indexer.indexProject("p1", sources));
        // 修改文件
        Files.writeString(md, "## 故事一\n新内容完全不同");
        assertTrue(indexer.indexProject("p1", sources));
        List<RagDocument> docs = store.documents("p1");
        assertTrue(docs.stream().anyMatch(d -> d.text().contains("新内容")));
    }

    @Test
    void nonExistentSourceDirReturnsFalse() throws Exception {
        RagVectorStore store = new FileSystemRagVectorStore(tmp.resolve("vec"));
        RagIndexer indexer = newIndexer(store);
        assertFalse(indexer.indexProject("p1", tmp.resolve("nope")));
    }
}
