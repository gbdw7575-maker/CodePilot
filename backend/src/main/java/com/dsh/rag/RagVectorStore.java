package com.dsh.rag;

import java.util.List;

/**
 * RAG 向量存储（R1 新增）：按 projectId 隔离存储文档片段与向量，支持相似度搜索。
 * <p>
 * 实现可替换（文件系统 JSON / pgvector / 独立向量库）。默认 {@link FileSystemRagVectorStore}
 * 用 JSON 文件，与 L4 语义记忆存储一致，避免 PG 扩展依赖。
 */
public interface RagVectorStore {

    /** 带分数的召回项：文档 + 余弦相似度分数。 */
    record ScoredDoc(RagDocument doc, double score) {
    }

    /** 保存文档与向量（覆盖旧索引）。vectors 长度须与 docs 一致。 */
    void save(String projectId, List<RagDocument> docs, List<float[]> vectors);

    /** 按查询向量做余弦相似度排序，返回 top-K。无索引时返回空列表。 */
    List<ScoredDoc> search(String projectId, float[] queryVector, int topK);

    /** 清空指定项目的索引。 */
    void clear(String projectId);

    /** 是否存在索引。 */
    boolean exists(String projectId);

    /** 加载该项目的全部文档片段（供关键词腿遍历）。无索引时返回空列表。 */
    List<RagDocument> documents(String projectId);
}
