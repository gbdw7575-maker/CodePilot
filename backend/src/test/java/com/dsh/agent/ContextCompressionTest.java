package com.dsh.agent;

import com.dsh.agent.model.ModelMessage;
import com.dsh.agent.model.ModelToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-08 输出与压缩：旧历史摘要带来源/未决事项、摘要式压缩保护 system+最后 user+工具配对。
 */
class ContextCompressionTest {

    private List<ModelMessage> baseHistory() {
        List<ModelMessage> msgs = new ArrayList<>();
        msgs.add(ModelMessage.system("sys"));
        msgs.add(ModelMessage.user("第一个用户问题"));
        msgs.add(ModelMessage.assistant("第一轮回答"+"x".repeat(40000)));
        msgs.add(ModelMessage.user("第二个用户问题"));
        return msgs;
    }

    @Test
    void buildHistorySummaryIncludesSourcesAndUnresolved() {
        ModelToolCall call = new ModelToolCall("call_1", "read_file", "{}");
        List<ModelMessage> unitWithResult = List.of(
                ModelMessage.assistantToolCalls(List.of(call)),
                ModelMessage.toolResult("call_1", "read_file", "ok"));
        ModelToolCall call2 = new ModelToolCall("call_2", "exec", "{}");
        List<ModelMessage> unitNoResult = List.of(
                ModelMessage.assistantToolCalls(List.of(call2))); // 无 tool 结果配对
        List<ModelMessage> userUnit = List.of(ModelMessage.user("旧的用户问题"));

        String summary = LoopSteps.buildHistorySummary(List.of(userUnit, unitWithResult, unitNoResult));

        assertTrue(summary.contains("目标与约束"), "目标和约束应指向逐字保留的 user 消息");
        assertTrue(summary.contains("call_1"), "摘要应列出工具调用 callId");
        assertTrue(summary.contains("call_2"), "摘要应列出未配对工具调用");
        assertTrue(summary.contains("未决事项") && summary.contains("call_2"),
                "无结果配对的工具调用应列为未决事项");
        assertFalse(summary.contains("未决事项：无"), "存在未决事项时不应写无");
    }

    @Test
    void buildHistorySummaryNoUnresolvedReportsNone() {
        List<ModelMessage> unit = List.of(ModelMessage.user("q"), ModelMessage.assistant("a"));
        String summary = LoopSteps.buildHistorySummary(List.of(unit));
        assertTrue(summary.contains("\"missingResultCount\":0"));
    }

    @Test
    void compressHistoryToWindowInsertsSummaryAndKeepsProtected() {
        List<ModelMessage> msgs = baseHistory();
        int sys = TokenEstimator.estimateMessage(msgs.get(0));
        int lastUserTokens = TokenEstimator.estimateMessage(msgs.get(3));
        // 空间计入检查点和逐字保留的全部用户原文，旧大回答仍需压缩
        int window = sys + lastUserTokens + 5000;
        List<ModelMessage> result = LoopSteps.compressHistoryToWindow(msgs, sys, 0, 0, window);

        assertNotNull(result);
        assertEquals("system", result.get(0).role());
        // 第 2 条应为摘要消息
        assertEquals("assistant", result.get(1).role());
        assertTrue(result.get(1).content().contains("历史摘要"), "压缩后应插入历史摘要消息");
        assertTrue(result.stream().anyMatch(m -> "user".equals(m.role()) && "第一个用户问题".equals(m.content())), "目标和约束不能只剩摘要");
        // 最后用户原文保留
        assertEquals("第二个用户问题", result.get(result.size() - 1).content());
    }

    @Test
    void compressHistoryToWindowKeepsToolPairsIntact() {
        List<ModelMessage> msgs = new ArrayList<>();
        msgs.add(ModelMessage.system("sys"));
        msgs.add(ModelMessage.user("u1"));
        ModelToolCall call = new ModelToolCall("c1", "read_file", "{}");
        msgs.add(ModelMessage.assistantToolCalls(List.of(call)));
        msgs.add(ModelMessage.toolResult("c1", "read_file", "result"+"x".repeat(40000)));
        msgs.add(ModelMessage.user("最后用户"));

        int sys = TokenEstimator.estimateMessage(msgs.get(0));
        int lastUser = TokenEstimator.estimateMessage(msgs.get(4));
        int window = sys + lastUser + 5000;
        List<ModelMessage> result = LoopSteps.compressHistoryToWindow(msgs, sys, 0, 0, window);

        assertNotNull(result);
        // 工具调用/结果必须整体进入摘要或整体保留，不得残留孤立
        long assistantToolCount = result.stream()
                .filter(m -> "assistant".equals(m.role()) && m.toolCalls() != null && !m.toolCalls().isEmpty())
                .count();
        long toolResultCount = result.stream().filter(m -> "tool".equals(m.role())).count();
        // 若工具单元被压缩，两者都为 0；若保留，两者都为 1；绝不能一边 0 一边 1
        assertTrue((assistantToolCount == 0 && toolResultCount == 0)
                        || (assistantToolCount >= 1 && toolResultCount >= 1),
                "工具调用与结果必须同生共死，不得孤立: at=" + assistantToolCount + " tool=" + toolResultCount);
        assertEquals("最后用户", result.get(result.size() - 1).content());
    }

    @Test
    void compressHistoryToWindowReturnsNullWhenProtectedExceedsWindow() {
        List<ModelMessage> msgs = baseHistory();
        int sys = TokenEstimator.estimateMessage(msgs.get(0));
        // system 本身已超窗
        assertNull(LoopSteps.compressHistoryToWindow(msgs, sys, 1_000_000, 0, 100));
    }

    @Test
    void compressHistoryToWindowUnderWindowKeepsAllAndNoSummary() {
        List<ModelMessage> msgs = baseHistory();
        int sys = TokenEstimator.estimateMessage(msgs.get(0));
        List<ModelMessage> result = LoopSteps.compressHistoryToWindow(msgs, sys, 0, 0, 1_000_000);
        assertNotNull(result);
        assertEquals(msgs.size(), result.size());
        // 未移除任何单元时不应插入摘要
        assertFalse(result.stream().anyMatch(m -> m.content() != null && m.content().contains("历史摘要")));
    }
}
