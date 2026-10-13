package com.dsh.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RagVectorStoreTest {

    @TempDir
    Path tmp;

    private RagDocument doc(String id, String text) {
        return new RagDocument(id, "s.md", 0, "标题", text);
    }

    private float[] vec(float... v) {
        return v;
    }

    @Test
    void saveThenSearchReturnsTopKByCosine() throws Exception {
        RagVectorStore store = new FileSystemRagVectorStore(tmp);
        List<RagDocument> docs = List.of(
                doc("0", "孙悟空料理大战"),
                doc("1", "辉夜大小姐密室事件"));
        store.save("p1", docs, List.of(
                vec(1, 0, 0),
                vec(0, 1, 0)));
        List<RagVectorStore.ScoredDoc> hits = store.search("p1", vec(0.9f, 0.1f, 0), 2);
        assertEquals(2, hits.size());
        // 查询向量更接近 doc 0
        assertEquals("0", hits.get(0).doc().id());
        assertTrue(hits.get(0).score() > hits.get(1).score());
    }

    @Test
    void isolatedByProject() throws Exception {
        RagVectorStore store = new FileSystemRagVectorStore(tmp);
        store.save("p1", List.of(doc("0", "A")), List.of(vec(1, 0)));
        store.save("p2", List.of(doc("0", "B")), List.of(vec(0, 1)));
        List<RagVectorStore.ScoredDoc> hits1 = store.search("p1", vec(0.9f, 0), 1);
        List<RagVectorStore.ScoredDoc> hits2 = store.search("p2", vec(0, 0.9f), 1);
        assertEquals(1, hits1.size());
        assertEquals("A", hits1.get(0).doc().text());
        assertEquals(1, hits2.size());
        assertEquals("B", hits2.get(0).doc().text());
    }

    @Test
    void clearRemovesIndex() throws Exception {
        RagVectorStore store = new FileSystemRagVectorStore(tmp);
        store.save("p1", List.of(doc("0", "X")), List.of(vec(1, 0)));
        assertTrue(store.exists("p1"));
        store.clear("p1");
        assertFalse(store.exists("p1"));
        assertTrue(store.search("p1", vec(1, 0), 1).isEmpty());
    }

    @Test
    void searchOnMissingProjectReturnsEmpty() throws Exception {
        RagVectorStore store = new FileSystemRagVectorStore(tmp);
        assertTrue(store.search("nope", vec(1, 0), 5).isEmpty());
    }

    @Test
    void saveOverwritesOldIndex() throws Exception {
        RagVectorStore store = new FileSystemRagVectorStore(tmp);
        store.save("p1", List.of(doc("0", "旧")), List.of(vec(1, 0)));
        store.save("p1", List.of(doc("0", "新")), List.of(vec(0, 1)));
        List<RagVectorStore.ScoredDoc> hits = store.search("p1", vec(0, 0.9f), 1);
        assertEquals(1, hits.size());
        assertEquals("新", hits.get(0).doc().text());
    }
}
