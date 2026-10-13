package com.dsh.agent.tool;

import com.dsh.application.DshProperties;
import com.dsh.rag.RagDocument;
import com.dsh.rag.RagRetrievalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * rag_search 扩展工具：从已索引的项目外部知识库召回与查询相关的文档片段。
 * <p>
 * 取代 MemoryAssembler 的自动注入——改为模型按需调用，避免不相关 query 被注入低相关片段
 * 污染上下文。recall 内部按 cosine 阈值过滤低相关片段；RAG 关闭时不可见不可执行（fail-closed）。
 * <p>
 * 工具发现：tool-search 关闭且 rag 启用时作为核心工具默认挂载（确保模型可直接调用）；
 * tool-search 启用时经发现挂载；rag 关闭时 authorized=false 不可见。
 */
@Component
public class RagSearchTool implements Tool {

    private final RagRetrievalService ragService;
    private final DshProperties properties;
    private final RunToolMountStore mountStore;

    public RagSearchTool(RagRetrievalService ragService, DshProperties properties,
                         RunToolMountStore mountStore) {
        this.ragService = ragService;
        this.properties = properties;
        this.mountStore = mountStore;
    }

    @Override
    public String name() {
        return "rag_search";
    }

    @Override
    public ToolDefinition customDefinition() {
        var factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        ObjectNode query = factory.objectNode();
        query.put("type", "string");
        query.put("description", "检索关键词或问题，用于匹配外部知识库中的文档片段");
        ObjectNode topK = factory.objectNode();
        topK.put("type", "integer");
        topK.put("minimum", 1);
        topK.put("maximum", 10);
        topK.put("description", "可选；返回片段数上限，默认取配置值");
        ObjectNode properties = factory.objectNode();
        properties.set("query", query);
        properties.set("topK", topK);
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", factory.arrayNode().add("query"));
        schema.set("additionalProperties", factory.booleanNode(false));
        return new ToolDefinition(name(),
                "检索外部知识库：从已索引的项目文档（如故事、领域资料、项目文档）召回与查询相关的片段，"
                        + "返回片段正文与来源。仅用于需要外部知识库内容时调用。",
                "1.0.0", "RAG", "READ_ONLY", schema);
    }

    @Override
    public ExtensionMeta extensionMeta() {
        boolean ragEnabled = properties.rag() != null && properties.rag().enabled();
        if (!properties.toolSearch().enabled() && ragEnabled) {
            // tool-search 关闭时已授权的 RAG 读能力常驻，确保模型可直接调用
            return null;
        }
        return new ExtensionMeta(
                "用户询问外部知识库内容、已索引文档、故事情节、领域资料，或需要查找项目文档中的特定信息时调用；"
                        + "无需外部资料时不要调用",
                ragEnabled);
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public ToolOutcome execute(JsonNode arguments, ToolExecutionContext ctx) {
        if (properties.rag() == null || !properties.rag().enabled()) {
            return ToolOutcome.failed("RAG_DISABLED",
                    "RAG 检索未启用（dsh.rag.enabled=false），rag_search 不可用", name());
        }
        String query = arguments.path("query").asText("").trim();
        if (query.isEmpty()) {
            return ToolOutcome.failed("INVALID_ARGUMENTS", "query 不能为空", name());
        }
        int topK = arguments.path("topK").asInt(0);
        int configTopK = properties.rag().topK() <= 0 ? 3 : properties.rag().topK();
        if (topK <= 0 || topK > 10) {
            topK = configTopK;
        }

        // Run 内补查次数上限，避免模型循环检索
        int searches = mountStore.recordRagSearch(ctx.runId());
        int maxSearches = properties.rag().maxRagSearchesPerRun();
        if (searches > maxSearches) {
            ObjectNode structured = ctx.objectMapper().createObjectNode();
            structured.put("query", query);
            structured.put("ragSearchCount", searches);
            structured.put("maxRagSearchesPerRun", maxSearches);
            structured.put("limitReached", true);
            return ToolOutcome.succeeded(
                    "rag_search 补查次数已达本 Run 上限（" + maxSearches
                            + "），请基于已获取片段或通用知识回答，无需继续检索",
                    "补查次数超限", structured, name());
        }

        try {
            List<RagDocument> hits = ragService.recall(ctx.projectId(), query, topK);
            ObjectNode structured = ctx.objectMapper().createObjectNode();
            structured.put("query", query);
            structured.put("resultCount", hits.size());
            ArrayNode results = structured.putArray("results");

            if (hits.isEmpty()) {
                return ToolOutcome.succeeded(
                        "检索「" + abbreviate(query, 60) + "」无相关片段（cosine 阈值已过滤低相关结果）",
                        "无相关片段", structured, name());
            }
            int per = properties.rag().chunkSize() <= 0 ? 1000 : properties.rag().chunkSize();
            StringBuilder text = new StringBuilder();
            int index = 0;
            for (RagDocument d : hits) {
                if (index > 0) {
                    text.append('\n');
                }
                String t = d.text() == null ? "" : d.text();
                String trimmed = t.length() > per ? t.substring(0, per) : t;
                text.append(++index).append(". ").append(trimmed);
                if (d.heading() != null && !d.heading().isBlank()) {
                    text.append("\n   来源: ").append(d.heading());
                }
                ObjectNode entry = results.addObject();
                entry.put("id", d.id());
                entry.put("heading", d.heading() == null ? "" : d.heading());
                entry.put("text", trimmed);
            }
            return ToolOutcome.succeeded(
                    "检索「" + abbreviate(query, 60) + "」返回 " + hits.size() + " 个相关片段",
                    text.toString(), structured, name());
        } catch (Exception e) {
            // fail-open：RAG 召回异常不阻断主 Loop，返回失败结果由模型决策
            return ToolOutcome.failed("RAG_RECALL_FAILED",
                    "RAG 召回失败: " + abbreviate(e.toString(), 200), name());
        }
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "未知错误";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
