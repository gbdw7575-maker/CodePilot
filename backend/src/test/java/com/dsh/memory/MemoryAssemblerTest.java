package com.dsh.memory;

import com.dsh.agent.TokenEstimator;
import com.dsh.application.DshProperties;
import com.dsh.rag.RagDocument;
import com.dsh.rag.RagRetrievalService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S3 Policy 治理层验收：MemoryAssembler 配额注入 / 优先级截断 / 空记忆兜底 / L4 禁用跳过。
 * 纯单元测试（Mockito stub MemoryProvider 与 EpisodicMemoryService），不启 Spring 上下文。
 */
class MemoryAssemblerTest {

    private DshProperties propsWithMemory() {
        DshProperties.Memory mem = new DshProperties.Memory(true, true, true,
                "AGENTS.md", 200, 25000, ".myagent/memory", 0.15);
        DshProperties.Episodic ep = new DshProperties.Episodic(true, 3, 2, 10, 0.05, 0.5, 200);
        return new DshProperties(null, null, null, null, null, null, null, null, mem,
                null, null, null, null, ep, null);
    }

    /** 默认返回空命中的 RAG mock（RAG 禁用或无相关片段时 fail-open）。 */
    private RagRetrievalService emptyRagService() {
        RagRetrievalService rag = mock(RagRetrievalService.class);
        when(rag.recall(anyString(), anyString(), anyInt())).thenReturn(List.of());
        return rag;
    }

    @Test
    void emptyBudgetReturnsEmpty() {
        DshProperties props = propsWithMemory();
        MemoryPolicy policy = new MemoryPolicy(props);
        MemoryProvider provider = mock(MemoryProvider.class);
        EpisodicMemoryService episodic = mock(EpisodicMemoryService.class);
        MemoryAssembler assembler = new MemoryAssembler(provider, episodic, policy, props, emptyRagService());
        assertEquals("", assembler.assembleMemory("p1", "intent", 0));
        verifyNoInteractions(provider);
        verifyNoInteractions(episodic);
    }

    @Test
    void l4OnlyWhenL5Empty() {
        DshProperties props = propsWithMemory();
        MemoryPolicy policy = new MemoryPolicy(props);
        MemoryProvider provider = mock(MemoryProvider.class);
        when(provider.retrieve("p1")).thenReturn(
                new MemoryIndex(1L, true, 2, "经验1 经验2", false));
        EpisodicMemoryService episodic = mock(EpisodicMemoryService.class);
        when(episodic.recall("p1", "intent")).thenReturn(List.of());
        MemoryAssembler assembler = new MemoryAssembler(provider, episodic, policy, props, emptyRagService());
        String seg = assembler.assembleMemory("p1", "intent", 100);
        assertTrue(seg.contains("相关经验记忆"), "应注入 L4 段落");
        assertFalse(seg.contains("相关历史情景"), "L5 为空不应注入");
    }

    @Test
    void l4AndL5BothInjectedInOrder() {
        DshProperties props = propsWithMemory();
        MemoryPolicy policy = new MemoryPolicy(props);
        MemoryProvider provider = mock(MemoryProvider.class);
        when(provider.retrieve("p1")).thenReturn(
                new MemoryIndex(1L, true, 1, "经验记忆内容", false));
        EpisodicMemoryService episodic = mock(EpisodicMemoryService.class);
        EpisodicMemoryEntry entry = new EpisodicMemoryEntry("ep_1", "p1", "run_1", null,
                "修复PostgreSQL连接池", "postgresql 连接", "COMPLETED", "PASSED",
                Instant.now(), 1.0);
        when(episodic.recall("p1", "intent")).thenReturn(List.of(entry));
        MemoryAssembler assembler = new MemoryAssembler(provider, episodic, policy, props, emptyRagService());
        String seg = assembler.assembleMemory("p1", "intent", 200);
        assertTrue(seg.contains("相关经验记忆"), "应注入 L4 段落");
        assertTrue(seg.contains("相关历史情景"), "应注入 L5 段落");
        assertTrue(seg.contains("run_1"), "应标注来源 run");
        // 注入顺序：L4 在 L5 之前
        assertTrue(seg.indexOf("相关经验记忆") < seg.indexOf("相关历史情景"),
                "L4 应在 L5 之前");
    }

    @Test
    void l4TruncatedToTokenBudget() {
        DshProperties props = propsWithMemory();
        MemoryPolicy policy = new MemoryPolicy(props);
        MemoryProvider provider = mock(MemoryProvider.class);
        // 构造远超预算的 L4 文本（CJK 约 1 token/char）
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            big.append("经验段落").append(i);
        }
        when(provider.retrieve("p1")).thenReturn(
                new MemoryIndex(1L, true, 1, big.toString(), false));
        EpisodicMemoryService episodic = mock(EpisodicMemoryService.class);
        MemoryAssembler assembler = new MemoryAssembler(provider, episodic, policy, props, emptyRagService());
        // 给 10 token 的预算：L4 内容被二分截断到约 10 token
        String seg = assembler.assembleMemory("p1", "intent", 10);
        assertTrue(seg.contains("相关经验记忆"));
        int segTokens = TokenEstimator.estimateText(seg);
        // 截断后段落 token 远小于原文（原文 ~2000 token），头部标题约占 ~10 token
        assertTrue(segTokens < 50, "截断后段落应受预算约束，实际 " + segTokens);
        assertTrue(seg.length() < big.length(), "L4 应被截断");
    }

    @Test
    void l4DisabledSkipsL4InjectsL5() {
        DshProperties.Memory mem = new DshProperties.Memory(false, false, false,
                "AGENTS.md", 200, 25000, ".myagent/memory", 0.15);
        DshProperties.Episodic ep = new DshProperties.Episodic(true, 3, 2, 10, 0.05, 0.5, 200);
        DshProperties props = new DshProperties(null, null, null, null, null, null, null, null, mem,
                null, null, null, null, ep, null);
        MemoryPolicy policy = new MemoryPolicy(props);
        MemoryProvider provider = mock(MemoryProvider.class);
        EpisodicMemoryService episodic = mock(EpisodicMemoryService.class);
        EpisodicMemoryEntry entry = new EpisodicMemoryEntry("ep_1", "p1", "run_1", null,
                "情景记忆摘要", "情景", "COMPLETED", "PASSED", Instant.now(), 1.0);
        when(episodic.recall("p1", "intent")).thenReturn(List.of(entry));
        MemoryAssembler assembler = new MemoryAssembler(provider, episodic, policy, props, emptyRagService());
        String seg = assembler.assembleMemory("p1", "intent", 100);
        assertFalse(seg.contains("相关经验记忆"), "L4 禁用不应注入");
        assertTrue(seg.contains("相关历史情景"), "L5 仍应注入");
        verify(provider, never()).retrieve("p1");
    }

    @Test
    void memoryPolicyBudgetCalculation() {
        DshProperties props = propsWithMemory();
        MemoryPolicy policy = new MemoryPolicy(props);
        // remaining = 10000 - 1000 - 500 - 200 - 3000 = 5300; budget = 5300 * 0.15 = 795
        assertEquals(795, policy.memoryBudget(10000, 1000, 500, 200, 3000),
                "配额应为剩余窗口的 15%");
        // remaining <= 0 时返回 0
        assertEquals(0, policy.memoryBudget(1000, 1000, 500, 200, 3000),
                "余额非正时配额为 0");
    }

    @Test
    void emptyProjectIdReturnsEmpty() {
        DshProperties props = propsWithMemory();
        MemoryPolicy policy = new MemoryPolicy(props);
        MemoryProvider provider = mock(MemoryProvider.class);
        EpisodicMemoryService episodic = mock(EpisodicMemoryService.class);
        MemoryAssembler assembler = new MemoryAssembler(provider, episodic, policy, props, emptyRagService());
        assertEquals("", assembler.assembleMemory("", "intent", 100));
        verifyNoInteractions(provider);
        verifyNoInteractions(episodic);
    }

    @Test
    void l4ReadThrowsFailOpen() {
        DshProperties props = propsWithMemory();
        MemoryPolicy policy = new MemoryPolicy(props);
        MemoryProvider provider = mock(MemoryProvider.class);
        when(provider.retrieve("p1")).thenThrow(new RuntimeException("MEMORY_DISABLED"));
        EpisodicMemoryService episodic = mock(EpisodicMemoryService.class);
        EpisodicMemoryEntry entry = new EpisodicMemoryEntry("ep_1", "p1", "run_1", null,
                "情景摘要", "情景", "COMPLETED", "PASSED", Instant.now(), 1.0);
        when(episodic.recall("p1", "intent")).thenReturn(List.of(entry));
        MemoryAssembler assembler = new MemoryAssembler(provider, episodic, policy, props, emptyRagService());
        String seg = assembler.assembleMemory("p1", "intent", 100);
        // L4 抛异常 fail-open，仅注入 L5
        assertFalse(seg.contains("相关经验记忆"));
        assertTrue(seg.contains("相关历史情景"));
    }

    private DshProperties propsWithRagEnabled() {
        DshProperties.Memory mem = new DshProperties.Memory(true, true, true,
                "AGENTS.md", 200, 25000, ".myagent/memory", 0.15);
        DshProperties.Episodic ep = new DshProperties.Episodic(true, 3, 2, 10, 0.05, 0.5, 200);
        DshProperties.Rag rag = new DshProperties.Rag(true, "doc/外部知识库",
                ".myagent/rag", 1000, 3, 60, true, "char-ngram", 0.15, 3, 3);
        return new DshProperties(null, null, null, null, null, null, null, null, mem,
                null, null, null, null, ep, rag);
    }

    @Test
    void ragHitsInjectedWithEvidenceRules() {
        DshProperties props = propsWithRagEnabled();
        MemoryPolicy policy = new MemoryPolicy(props);
        MemoryProvider provider = mock(MemoryProvider.class);
        EpisodicMemoryService episodic = mock(EpisodicMemoryService.class);
        RagRetrievalService rag = mock(RagRetrievalService.class);
        when(rag.recall("p1", "辉夜大小姐", 3)).thenReturn(List.of(
                new RagDocument("f#0", "story2.md", 0, "故事一：辉夜大小姐…",
                        "四宫辉夜发现封条被整齐裁开")));
        MemoryAssembler assembler = new MemoryAssembler(provider, episodic, policy, props, rag);
        String seg = assembler.assembleMemory("p1", "辉夜大小姐", 500);
        assertTrue(seg.contains("外部知识库证据"), "应注入 RAG 证据段");
        assertTrue(seg.contains("四宫辉夜发现封条"), "应包含命中片段正文");
        assertTrue(seg.contains("来源: 故事一"), "应标注来源");
        assertTrue(seg.contains("同名且无法确定指代"), "应附带证据判断规则");
        // 证据规则在片段之后
        assertTrue(seg.indexOf("四宫辉夜发现封条") < seg.indexOf("同名且无法确定指代"),
                "片段应在规则之前");
    }

    @Test
    void ragEmptyNotInjected() {
        DshProperties props = propsWithRagEnabled();
        MemoryPolicy policy = new MemoryPolicy(props);
        MemoryProvider provider = mock(MemoryProvider.class);
        EpisodicMemoryService episodic = mock(EpisodicMemoryService.class);
        RagRetrievalService rag = mock(RagRetrievalService.class);
        when(rag.recall(anyString(), anyString(), anyInt())).thenReturn(List.of());
        MemoryAssembler assembler = new MemoryAssembler(provider, episodic, policy, props, rag);
        String seg = assembler.assembleMemory("p1", "hello world", 500);
        assertFalse(seg.contains("外部知识库证据"), "无命中不应注入 RAG 段");
    }

    @Test
    void ragDisabledNotCalled() {
        DshProperties props = propsWithMemory(); // rag 默认禁用
        MemoryPolicy policy = new MemoryPolicy(props);
        MemoryProvider provider = mock(MemoryProvider.class);
        EpisodicMemoryService episodic = mock(EpisodicMemoryService.class);
        RagRetrievalService rag = mock(RagRetrievalService.class);
        MemoryAssembler assembler = new MemoryAssembler(provider, episodic, policy, props, rag);
        assembler.assembleMemory("p1", "辉夜大小姐", 500);
        verifyNoInteractions(rag); // RAG 禁用时不应调用 recall
    }

    @Test
    void ragRecallThrowsFailOpen() {
        DshProperties props = propsWithRagEnabled();
        MemoryPolicy policy = new MemoryPolicy(props);
        MemoryProvider provider = mock(MemoryProvider.class);
        EpisodicMemoryService episodic = mock(EpisodicMemoryService.class);
        RagRetrievalService rag = mock(RagRetrievalService.class);
        when(rag.recall(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("index corrupt"));
        MemoryAssembler assembler = new MemoryAssembler(provider, episodic, policy, props, rag);
        String seg = assembler.assembleMemory("p1", "辉夜大小姐", 500);
        // 异常 fail-open，不抛也不注入 RAG 段
        assertFalse(seg.contains("外部知识库证据"));
    }
}
