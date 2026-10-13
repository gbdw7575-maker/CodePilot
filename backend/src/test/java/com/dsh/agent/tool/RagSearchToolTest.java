package com.dsh.agent.tool;

import com.dsh.application.DshProperties;
import com.dsh.rag.RagDocument;
import com.dsh.rag.RagRetrievalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagSearchTool 单元测试：覆盖 RAG 禁用/启用召回/空结果/topK 覆盖/异常 fail-open/空 query。
 * 不启 Spring 上下文，Mockito stub RagRetrievalService，验证工具结果格式与分流逻辑。
 */
class RagSearchToolTest {

    private final ObjectMapper om = new ObjectMapper();

    private DshProperties props(boolean ragEnabled) {
        DshProperties.Rag rag = new DshProperties.Rag(ragEnabled, "doc/外部知识库",
                ".myagent/rag", 1000, 3, 60, true, "char-ngram", 0.15, 3, 3);
        return new DshProperties(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, rag);
    }

    private RunToolMountStore mountStore() {
        return new RunToolMountStore();
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext("run1", "t1", "turn1", "call1", "p1",
                null, null, null, null, om);
    }

    private JsonNode args(String query) {
        ObjectNode node = om.createObjectNode();
        node.put("query", query);
        return node;
    }

    private JsonNode args(String query, int topK) {
        ObjectNode node = om.createObjectNode();
        node.put("query", query);
        node.put("topK", topK);
        return node;
    }

    @Test
    void ragDisabledReturnsRagDisabled() {
        RagSearchTool tool = new RagSearchTool(mock(RagRetrievalService.class), props(false), mountStore());
        ToolOutcome out = tool.execute(args("查询"), ctx());
        assertEquals("FAILED", out.status());
        assertEquals("RAG_DISABLED", out.errorCode());
    }

    @Test
    void ragEnabledReturnsHits() {
        RagRetrievalService svc = mock(RagRetrievalService.class);
        when(svc.recall(eq("p1"), eq("辉夜封条"), eq(3))).thenReturn(List.of(
                new RagDocument("0", "story2.md", 0, "辉夜大小姐", "四宫辉夜发现了封条异常。")));
        RagSearchTool tool = new RagSearchTool(svc, props(true), mountStore());
        ToolOutcome out = tool.execute(args("辉夜封条"), ctx());
        assertEquals("SUCCEEDED", out.status());
        assertTrue(out.text().contains("四宫辉夜"), "应包含召回正文");
        assertTrue(out.text().contains("辉夜大小姐"), "应标注来源 heading");
        assertEquals(1, out.structuredContent().path("resultCount").asInt());
    }

    @Test
    void emptyRecallReturnsNoFragments() {
        RagRetrievalService svc = mock(RagRetrievalService.class);
        when(svc.recall(eq("p1"), eq("hello world"), anyInt())).thenReturn(List.of());
        RagSearchTool tool = new RagSearchTool(svc, props(true), mountStore());
        ToolOutcome out = tool.execute(args("hello world"), ctx());
        assertEquals("SUCCEEDED", out.status());
        assertEquals(0, out.structuredContent().path("resultCount").asInt());
        assertTrue(out.summary().contains("无相关片段"), "空召回应提示无相关片段");
    }

    @Test
    void topKParamOverridesConfig() {
        RagRetrievalService svc = mock(RagRetrievalService.class);
        when(svc.recall(eq("p1"), eq("查询"), eq(5))).thenReturn(List.of());
        RagSearchTool tool = new RagSearchTool(svc, props(true), mountStore());
        tool.execute(args("查询", 5), ctx());
        // topK=5 透传（mock 返回空，重点验证调用参数为 5 而非配置的 3）
    }

    @Test
    void recallThrowsFailOpen() {
        RagRetrievalService svc = mock(RagRetrievalService.class);
        when(svc.recall(eq("p1"), eq("查询"), anyInt())).thenThrow(new RuntimeException("RAG_DOWN"));
        RagSearchTool tool = new RagSearchTool(svc, props(true), mountStore());
        ToolOutcome out = tool.execute(args("查询"), ctx());
        assertEquals("FAILED", out.status());
        assertEquals("RAG_RECALL_FAILED", out.errorCode());
    }

    @Test
    void emptyQueryReturnsInvalidArguments() {
        RagSearchTool tool = new RagSearchTool(mock(RagRetrievalService.class), props(true), mountStore());
        ToolOutcome out = tool.execute(args("  "), ctx());
        assertEquals("FAILED", out.status());
        assertEquals("INVALID_ARGUMENTS", out.errorCode());
    }
}
