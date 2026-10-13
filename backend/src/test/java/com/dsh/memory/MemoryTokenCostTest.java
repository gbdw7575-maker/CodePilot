package com.dsh.memory;

import com.dsh.agent.TokenEstimator;
import com.dsh.application.DshProperties;
import com.dsh.rag.RagRetrievalService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 记忆注入 token 消耗实测：量化 L4/L5 段落在典型/大记忆/空记忆场景下的
 * token 开销与配额截断效果。输出数据供成本评估，断言保证不超配额。
 */
class MemoryTokenCostTest {

    private DshProperties props() {
        DshProperties.Memory mem = new DshProperties.Memory(true, true, true,
                "AGENTS.md", 200, 25000, ".myagent/memory", 0.15);
        DshProperties.Episodic ep = new DshProperties.Episodic(true, 3, 2, 10, 0.05, 0.5, 200);
        return new DshProperties(null, null, null, null, null, null, null, null, mem,
                null, null, null, null, ep, null);
    }

    private MemoryAssembler assemblerWith(String l4Text, List<EpisodicMemoryEntry> l5Hits) {
        DshProperties props = props();
        MemoryProvider provider = mock(MemoryProvider.class);
        when(provider.retrieve("p1")).thenReturn(
                new MemoryIndex(1L, true, 5, l4Text, false));
        EpisodicMemoryService episodic = mock(EpisodicMemoryService.class);
        when(episodic.recall("p1", "intent")).thenReturn(l5Hits);
        RagRetrievalService rag = mock(RagRetrievalService.class);
        when(rag.recall(anyString(), anyString(), anyInt())).thenReturn(List.of());
        return new MemoryAssembler(provider, episodic, new MemoryPolicy(props), props, rag);
    }

    private List<EpisodicMemoryEntry> l5Entries(int n, int summaryChars) {
        List<EpisodicMemoryEntry> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new EpisodicMemoryEntry("ep_" + i, "p1", "run_hist_" + i, null,
                    "修复数据库连接池泄漏问题，验证通过".repeat(Math.max(1, summaryChars / 20)),
                    "连接 接池", "COMPLETED", "PASSED", Instant.now(), 1.0));
        }
        return list;
    }

    @Test
    void typicalScenarioTokenCost() {
        // 典型场景：L4 索引 5 条经验（约 150 字符），L5 召回 3 条（每条 ≤200 字符）
        String l4 = "- 构建命令是 mvnw test\n- 生产库用 PostgreSQL + HikariCP\n"
                + "- 补丁必须 LF 行尾\n- 取消沿 parent 链传播\n- 验证证据高于模型声明";
        MemoryAssembler assembler = assemblerWith(l4, l5Entries(3, 120));
        // 预算给足（模拟窗口余额充足）：128k 窗口下 remaining*0.15 通常 >10000
        String seg = assembler.assembleMemory("p1", "intent", 10000);
        int tokens = TokenEstimator.estimateText(seg);
        System.out.println("=== 典型场景 ===");
        System.out.println("L4 字符数=" + l4.length() + "，L5 条数=3");
        System.out.println("注入段落 token=" + tokens + "（占 128k 窗口 "
                + String.format("%.2f", tokens * 100.0 / 128000) + "%）");
        assertTrue(tokens > 0);
        assertTrue(tokens < 2000, "典型注入应远小于 2000 token，实际 " + tokens);
    }

    @Test
    void largeMemoryTruncatedByBudget() {
        // 大记忆场景：L4 索引塞满 200 行（约 8000 字符），L5 3 条各 200 字符
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            big.append("- 第").append(i).append("条经验：取消沿 parent 链传播，子 Loop 安全点检测\n");
        }
        MemoryAssembler assembler = assemblerWith(big.toString(), l5Entries(3, 200));
        int budget = 1500;
        String seg = assembler.assembleMemory("p1", "intent", budget);
        int tokens = TokenEstimator.estimateText(seg);
        int fullTokens = TokenEstimator.estimateText(big.toString());
        System.out.println("=== 大记忆场景（budget=" + budget + "）===");
        System.out.println("L4 原文 token=" + fullTokens + "，截断后段落 token=" + tokens);
        System.out.println("截断率=" + String.format("%.1f", (1 - tokens * 1.0 / fullTokens) * 100) + "%");
        // 截断后段落（含标题开销）不应显著超预算
        assertTrue(tokens <= budget + 30, "截断后应受配额约束，实际 " + tokens);
    }

    @Test
    void emptyMemoryZeroCost() {
        MemoryAssembler assembler = assemblerWith("", List.of());
        String seg = assembler.assembleMemory("p1", "intent", 10000);
        System.out.println("=== 空记忆场景 ===");
        System.out.println("注入段落=" + (seg.isEmpty() ? "空（0 token，逐字节回到无记忆状态）" : seg));
        assertEquals("", seg, "空记忆不追加任何内容，system 与无记忆时逐字节一致");
    }

    @Test
    void budgetFormulaCheck() {
        // 配额公式实测：128000 窗口，system 基线 200，tools 2000，预留输出 4096，历史 10000
        MemoryPolicy policy = new MemoryPolicy(props());
        int effectiveWindow = 128000 - 128000 / 20; // 121600
        int budget = policy.memoryBudget(effectiveWindow, 200, 2000, 4096, 10000);
        int expected = (int) ((121600 - 200 - 2000 - 4096 - 10000) * 0.15); // 15795
        System.out.println("=== 配额公式（128k 窗口典型占用）===");
        System.out.println("remaining=" + (121600 - 200 - 2000 - 4096 - 10000)
                + "，memoryBudget=" + budget);
        assertEquals(expected, budget);
        // 历史膨胀到 100k 时配额收紧
        int tight = policy.memoryBudget(effectiveWindow, 200, 2000, 4096, 100000);
        System.out.println("历史 100k 时 memoryBudget=" + tight);
        assertTrue(tight < budget, "历史膨胀时记忆配额自动收紧");
    }
}
