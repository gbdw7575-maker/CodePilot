package com.dsh.agent.tool.discovery;

import java.util.List;

/**
 * 嵌入客户端（开发文档 §7.2）：独立可替换配置，不假设 DeepSeek 聊天接口自带 embedding。
 * 实现只索引工具定义文本；向量服务失败由调用方降级 BM25 并标记 degraded。
 */
public interface EmbeddingClient {

    /** 返回文本向量；实现须确定性（同文本同向量）。失败抛异常由调用方降级。 */
    float[] embed(String text);

    /** 向量维度；用于校验与存储布局。 */
    int dimension();

    /** 就绪的目录条目：docId + 检索文本。 */
    record Doc(String docId, String text) {
    }

    /** 查询与文档的余弦相似度排序结果项（rank 从 1 开始由调用方分配）。 */
    record Scored(String docId, double score) {
    }

    /** 对查询向量与文档向量集做相似度排序，返回全部条目（调用方截断）。 */
    List<Scored> rank(String query, List<Doc> docs);
}
