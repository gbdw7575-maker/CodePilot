package com.dsh.rag;

/**
 * RAG 检索单元（R0 新增）：外部知识库文档切分后的一个片段。
 * <p>
 * id 为 sourcePath + "#" + chunkIndex 全局唯一，供向量存储与召回引用。
 * heading 为该 chunk 所属的 {@code ##} 标题（无标题时为空），便于模型引用来源。
 * text 为 chunk 正文（已 strip）。
 */
public record RagDocument(String id, String sourcePath, int chunkIndex,
                         String heading, String text) {
}
