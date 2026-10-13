package com.dsh.agent;

import com.dsh.agent.model.ModelMessage;
import com.dsh.agent.model.ModelToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-07 单次上下文预算：token 估算、有界缩减、工具配对保护、不可缩减失败。
 */
class ContextBudgetTest {

    // ---------- TokenEstimator ----------

    @Test
    void estimateAsciiRoughlyOneTokenPerFourChars() {
        // 40 字符 ASCII ≈ 10 token
        assertEquals(10, TokenEstimator.estimateText("abcdefghij".repeat(4)));
    }

    @Test
    void estimateCjkOneTokenPerChar() {
        // 10 个汉字 ≈ 10 token
        assertEquals(10, TokenEstimator.estimateText("你好世界测试中文符号".repeat(1).substring(0, 10)));
    }

    @Test
    void estimateMixedCjkAndAscii() {
        // "你好" (2 CJK) + "abcd" (4 ASCII ≈ 1) = 3 token
        assertEquals(3, TokenEstimator.estimateText("你好abcd"));
    }

    @Test
    void estimateNullOrEmptyIsZero() {
        assertEquals(0, TokenEstimator.estimateText(null));
        assertEquals(0, TokenEstimator.estimateText(""));
    }

    @Test
    void estimateMessageIncludesToolCalls() {
        ModelToolCall call = new ModelToolCall("c1", "read_file", "{\"path\":\"a.txt\"}");
        ModelMessage m = ModelMessage.assistantToolCalls(List.of(call));
        // name "read_file" + args JSON 都计入
        assertTrue(TokenEstimator.estimateMessage(m) > TokenEstimator.estimateText("read_file"));
    }

    // ---------- 有界缩减 trimHistoryToWindow ----------

    private List<ModelMessage> buildHistory() {
        List<ModelMessage> msgs = new ArrayList<>();
        msgs.add(ModelMessage.system("你是助手"));
        msgs.add(ModelMessage.user("第一轮用户输入"));
        msgs.add(ModelMessage.assistant("第一轮回答"));
        msgs.add(ModelMessage.user("第二轮用户输入"));
        return msgs;
    }

    @Test
    void underWindowReturnsAllMessages() {
        List<ModelMessage> msgs = buildHistory();
        int sys = TokenEstimator.estimateMessage(msgs.get(0));
        // 窗口很大，不缩减
        List<ModelMessage> result = LoopSteps.trimHistoryToWindow(msgs, sys, 0, 100, 1_000_000);
        assertNotNull(result);
        assertEquals(msgs.size(), result.size());
    }

    @Test
    void overWindowTrimsOldestHistoryKeepsSystemAndLastUser() {
        List<ModelMessage> msgs = buildHistory();
        int sys = TokenEstimator.estimateMessage(msgs.get(0));
        // 窗口仅够 system + 最后一条 user，迫使移除全部中间历史
        int lastUserTokens = TokenEstimator.estimateMessage(msgs.get(3));
        int window = sys + lastUserTokens;
        List<ModelMessage> result = LoopSteps.trimHistoryToWindow(msgs, sys, 0, 0, window);
        assertNotNull(result);
        // 保留 system（第 1 条）与最后一条 user
        assertEquals("system", result.get(0).role());
        assertEquals("第二轮用户输入", result.get(result.size() - 1).content());
        // 最早的 user/assistant 被移除
        assertFalse(result.stream().anyMatch(m -> "第一轮用户输入".equals(m.content())));
        assertFalse(result.stream().anyMatch(m -> "第一轮回答".equals(m.content())));
    }

    @Test
    void toolCallAndResultRemovedAsUnitNoOrphan() {
        List<ModelMessage> msgs = new ArrayList<>();
        msgs.add(ModelMessage.system("sys"));
        msgs.add(ModelMessage.user("u1"));
        ModelToolCall call = new ModelToolCall("c1", "read_file", "{}");
        msgs.add(ModelMessage.assistantToolCalls(List.of(call)));
        msgs.add(ModelMessage.toolResult("c1", "read_file", "文件内容很长" + "x".repeat(200)));
        msgs.add(ModelMessage.user("最后用户输入"));

        int sys = TokenEstimator.estimateMessage(msgs.get(0));
        // 窗口只够 system + 最后 user，迫使移除前面的 user + 工具调用/结果单元
        int lastUser = TokenEstimator.estimateMessage(msgs.get(4));
        int window = sys + lastUser + 10;
        List<ModelMessage> result = LoopSteps.trimHistoryToWindow(msgs, sys, 0, 0, window);

        assertNotNull(result);
        // 工具调用与结果必须同时消失，不得残留孤立 tool 结果
        long assistantToolCount = result.stream()
                .filter(m -> "assistant".equals(m.role()) && m.toolCalls() != null && !m.toolCalls().isEmpty())
                .count();
        long toolResultCount = result.stream().filter(m -> "tool".equals(m.role())).count();
        assertEquals(0, assistantToolCount, "工具调用必须整体移除");
        assertEquals(0, toolResultCount, "不得残留孤立的工具结果");
        // 最后用户原文保留
        assertEquals("最后用户输入", result.get(result.size() - 1).content());
    }

    @Test
    void untrimmableReturnsNullWhenProtectedPartExceedsWindow() {
        List<ModelMessage> msgs = buildHistory();
        int sys = TokenEstimator.estimateMessage(msgs.get(0));
        // system + tools + reserved 已超窗，无法缩减
        List<ModelMessage> result = LoopSteps.trimHistoryToWindow(msgs, sys, 1_000_000, 0, 100);
        assertNull(result);
    }

    @Test
    void noUserMessageReturnsNull() {
        List<ModelMessage> msgs = List.of(
                ModelMessage.system("sys"),
                ModelMessage.assistant("只有 assistant，没有 user"));
        List<ModelMessage> result = LoopSteps.trimHistoryToWindow(msgs, 5, 0, 0, 10);
        assertNull(result);
    }
}
