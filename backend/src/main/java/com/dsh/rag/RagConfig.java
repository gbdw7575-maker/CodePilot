package com.dsh.rag;

import com.dsh.agent.tool.discovery.CharNgramEmbeddingClient;
import com.dsh.agent.tool.discovery.EmbeddingClient;
import com.dsh.application.DshProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * RAG 检索配置（R4 新增）：无条件注册所有 RAG bean（recall 内部检查 dsh.rag.enabled）。
 * <p>
 * 嵌入客户端直接 new {@link CharNgramEmbeddingClient}（无状态纯计算，不依赖 tool-search 条件）；
 * 向量存储从 dsh.rag.storage-dir 解析路径，创建失败降级 null baseDir（所有操作返回空）。
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Bean
    public RagVectorStore ragVectorStore(DshProperties props) {
        DshProperties.Rag rag = props.rag();
        try {
            return new FileSystemRagVectorStore(Path.of(rag.storageDir()));
        } catch (Exception e) {
            log.warn("RAG vector store init failed, degrade to no-op: {}", e.getMessage());
            try {
                return new FileSystemRagVectorStore(null);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingClient.class)
    public EmbeddingClient ragEmbeddingClient() {
        return new CharNgramEmbeddingClient();
    }

    @Bean
    public RagDocumentLoader ragDocumentLoader(DshProperties props) {
        return new RagDocumentLoader(props.rag().chunkSize());
    }

    @Bean
    public RagIndexer ragIndexer(RagDocumentLoader loader, EmbeddingClient emb,
                                 RagVectorStore store, DshProperties props) {
        return new RagIndexer(loader, emb, store, Path.of(props.rag().storageDir()));
    }

    @Bean
    public RagRetrievalService ragRetrievalService(RagVectorStore store, EmbeddingClient emb,
                                                   DshProperties props) {
        return new RagRetrievalService(store, emb, props.rag().rrfK(),
                props.rag().bm25Enabled(), props.rag().cosineFloor(),
                props.rag().bm25MinHits());
    }
}
