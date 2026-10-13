package com.dsh.rag;

import com.dsh.agent.tool.discovery.CharNgramEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RagRetrievalServiceTest {

    @TempDir
    Path tmp;

    private RagDocument doc(String id, String text) {
        return new RagDocument(id, "s.md", 0, "标题", text);
    }

    private RagRetrievalService service(RagVectorStore store) {
        return new RagRetrievalService(store, new CharNgramEmbeddingClient(), 60, true, 0.15, 3);
    }

    @Test
    void recallReturnsTopKByRRF() throws Exception {
        RagVectorStore store = new FileSystemRagVectorStore(tmp);
        List<RagDocument> docs = List.of(
                doc("0", "孙悟空料理大战"),
                doc("1", "辉夜大小姐密室事件"),
                doc("2", "进击的巨人地下城市"));
        store.save("p1", docs, embedAll(docs));
        RagRetrievalService svc = service(store);
        List<RagDocument> hits = svc.recall("p1", "孙悟空料理大战", 2);
        assertEquals(2, hits.size());
        assertEquals("0", hits.get(0).id());
    }

    @Test
    void recallOnMissingProjectReturnsEmpty() throws Exception {
        RagVectorStore store = new FileSystemRagVectorStore(tmp);
        RagRetrievalService svc = service(store);
        assertTrue(svc.recall("nope", "查询", 3).isEmpty());
    }

    @Test
    void recallEmptyQueryReturnsEmpty() throws Exception {
        RagVectorStore store = new FileSystemRagVectorStore(tmp);
        store.save("p1", List.of(doc("0", "内容")), embedAll(List.of(doc("0", "内容"))));
        RagRetrievalService svc = service(store);
        assertTrue(svc.recall("p1", "", 3).isEmpty());
        assertTrue(svc.recall("p1", null, 3).isEmpty());
    }

    @Test
    void bm25DisabledStillWorksViaVector() throws Exception {
        RagVectorStore store = new FileSystemRagVectorStore(tmp);
        List<RagDocument> docs = List.of(
                doc("0", "孙悟空料理大战"),
                doc("1", "辉夜大小姐"));
        store.save("p1", docs, embedAll(docs));
        RagRetrievalService svc = new RagRetrievalService(store, new CharNgramEmbeddingClient(), 60, false, 0.15, 3);
        List<RagDocument> hits = svc.recall("p1", "孙悟空料理大战", 1);
        assertEquals(1, hits.size());
        assertEquals("0", hits.get(0).id());
    }

    @Test
    void highCosineFloorFiltersAllVectorHits() throws Exception {
        RagVectorStore store = new FileSystemRagVectorStore(tmp);
        List<RagDocument> docs = List.of(
                doc("0", "孙悟空料理大战"),
                doc("1", "辉夜大小姐"));
        store.save("p1", docs, embedAll(docs));
        // cosineFloor=2.0 超过任何可能的 cosine（≤1），向量腿全被过滤；bm25 关闭则结果为空
        RagRetrievalService svc = new RagRetrievalService(store, new CharNgramEmbeddingClient(), 60, false, 2.0, 3);
        List<RagDocument> hits = svc.recall("p1", "孙悟空料理大战", 3);
        assertTrue(hits.isEmpty(), "cosineFloor 过高应过滤全部向量命中");
    }

    private List<float[]> embedAll(List<RagDocument> docs) {
        CharNgramEmbeddingClient emb = new CharNgramEmbeddingClient();
        List<float[]> vecs = new ArrayList<>();
        for (RagDocument d : docs) {
            vecs.add(emb.embed(d.text()));
        }
        return vecs;
    }
}
