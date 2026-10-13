package com.dsh.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-19 预算软提醒：消耗达 70%/85% 阈值时返回追加到 system 末尾的段落。
 * 非任何基准特化：仅基于 Run 自身预算与剩余。
 */
class BudgetAdvisoryTest {

    private LoopSteps.RoundSnapshot snapshot(int used, int max, int reqUsed, int reqMax) {
        return new LoopSteps.RoundSnapshot("run-1", "RUNNING", "deepseek", "deepseek-chat",
                4096, null, reqUsed, reqMax, used, max, Instant.now().plusSeconds(600));
    }

    @Test
    void noAdvisoryBelow70Percent() {
        // token 50%, requests 50%
        assertNull(LoopSteps.budgetAdvisoryText(snapshot(50000, 200000, 6, 24)));
    }

    @Test
    void noAdvisoryAtBoundaryJustBelow70() {
        // 69.99% 不到 70
        assertNull(LoopSteps.budgetAdvisoryText(snapshot(139999, 200000, 16, 24)));
    }

    @Test
    void advisoryAt70PercentByTokens() {
        String hint = LoopSteps.budgetAdvisoryText(snapshot(140000, 200000, 5, 24));
        assertNotNull(hint);
        assertTrue(hint.contains("预算已消耗过半"));
        assertTrue(hint.contains("70%"));
        assertTrue(hint.contains("收敛探索"));
    }

    @Test
    void advisoryAt85PercentEscalates() {
        String hint = LoopSteps.budgetAdvisoryText(snapshot(170000, 200000, 5, 24));
        assertNotNull(hint);
        assertTrue(hint.contains("剩余预算即将耗尽"));
        assertTrue(hint.contains("立即停止进一步探索"));
        assertTrue(hint.contains("产出最终补丁"));
    }

    @Test
    void advisoryByRequestsDimensionAlone() {
        // token 维度未达 70%，但请求次数 19/24 ≈ 79% 达 70 档
        String hint = LoopSteps.budgetAdvisoryText(snapshot(10000, 200000, 19, 24));
        assertNotNull(hint);
        assertTrue(hint.contains("预算已消耗过半"));
    }

    @Test
    void advisoryByRequests85Escalates() {
        // 请求次数 21/24 ≈ 87% 达 85 档
        String hint = LoopSteps.budgetAdvisoryText(snapshot(10000, 200000, 21, 24));
        assertNotNull(hint);
        assertTrue(hint.contains("剩余预算即将耗尽"));
    }

    @Test
    void noAdvisoryWhenBudgetZero() {
        // maxCumulativeTokens=0 视为未知，token 维度不计；requests 也为 0
        assertNull(LoopSteps.budgetAdvisoryText(snapshot(100, 0, 1, 0)));
    }

    @Test
    void advisoryCapsAt100Percent() {
        // 超额也算 100%
        String hint = LoopSteps.budgetAdvisoryText(snapshot(300000, 200000, 30, 24));
        assertNotNull(hint);
        assertTrue(hint.contains("剩余预算即将耗尽"));
    }

    @Test
    void advisoryTextIsByteStableWithinTier() {
        // 本段被拼进 system 消息，而 prefixHash 覆盖 system 内容；
        // 同一档位内文本必须逐字节稳定，否则前缀缓存每轮全部失效。
        String at70 = LoopSteps.budgetAdvisoryText(snapshot(140000, 200000, 5, 24));
        String at84 = LoopSteps.budgetAdvisoryText(snapshot(168000, 200000, 5, 24));
        assertEquals(at70, at84, "70 档内文本必须逐字节稳定");

        String at85 = LoopSteps.budgetAdvisoryText(snapshot(170000, 200000, 5, 24));
        String at99 = LoopSteps.budgetAdvisoryText(snapshot(198000, 200000, 5, 24));
        assertEquals(at85, at99, "85 档内文本必须逐字节稳定");

        assertNotEquals(at70, at85, "跨档位应当升级文案");
    }

    @Test
    void advisoryProducesExactlyTwoTextsAcrossWholeRange() {
        // 70%~100% 全区间只应产生 2 种文本；若出现第 3 种说明又把实时值插值进去了。
        java.util.Set<String> texts = new java.util.HashSet<>();
        for (int pct = 70; pct <= 100; pct++) {
            String text = LoopSteps.budgetAdvisoryText(snapshot(200000 * pct / 100, 200000, 5, 24));
            assertNotNull(text, "pct=" + pct);
            texts.add(text);
        }
        assertEquals(2, texts.size(), "整段只应产生 70 档与 85 档两种文本");
    }
}
