package com.dsh.agent;

import com.dsh.agent.model.ModelMessage;
import com.dsh.agent.model.ModelToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 折叠「未归档」的历史工具结果（D-08 阈值以下的小输出在长任务里持续累积，
 * 实测占单轮请求约 43%，而既有折叠路径只对有产物的输出生效）。
 *
 * <p>契约：只改变送入模型的请求视图；status / summary / exitCode / errorCode 等判定性字段
 * 必须保留，非文本块与结构化观察原样保留，片段必须有界，且不得凭空声明一个不存在的产物。
 */
class ToolOutputFoldUnarchivedTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode resultJson(String text) {
        ObjectNode raw = MAPPER.createObjectNode();
        raw.put("status", "OK");
        raw.put("summary", "命令已执行");
        raw.put("callId", "c1");
        raw.put("name", "run_command");
        raw.put("exitCode", 0);
        raw.putArray("content").addObject().put("type", "text").put("text", text);
        return raw;
    }

    private static ModelMessage view() {
        return ModelMessage.toolResult("c1", "run_command", "原始正文");
    }

    @Test
    void foldShrinksLargeOutputAndKeepsIdentity() {
        String big = "x".repeat(8000);
        var folded = LoopSteps.foldUnarchivedToolResult(resultJson(big), view(), 600);
        assertEquals("tool", folded.role());
        assertEquals("c1", folded.toolCallId());
        assertEquals("run_command", folded.toolName());
        assertTrue(folded.toolCalls() == null || folded.toolCalls().isEmpty(), "折叠不得凭空产生工具调用");
        assertTrue(TokenEstimator.estimateMessage(folded)
                < TokenEstimator.estimateMessage(ModelMessage.toolResult("c1", "run_command", big)));
    }

    @Test
    void keepsStatusSummaryExitCodeAndErrorFlag() {
        ObjectNode raw = resultJson("y".repeat(4000));
        raw.put("isError", true);
        raw.put("errorCode", "TOOL_FAILED");
        raw.put("exitCode", 3);
        String text = LoopSteps.foldUnarchivedToolResult(raw, view(), 600).content();
        assertAll(
                () -> assertTrue(text.contains("[OK]"), "状态"),
                () -> assertTrue(text.contains("命令已执行"), "摘要"),
                () -> assertTrue(text.contains("[工具调用错误]"), "错误标记"),
                () -> assertTrue(text.contains("TOOL_FAILED"), "错误码"),
                () -> assertTrue(text.contains("exitCode: 3"), "退出码"));
    }

    @Test
    void excerptIsBounded() {
        String text = LoopSteps.foldUnarchivedToolResult(resultJson("z".repeat(200000)), view(), 600).content();
        assertTrue(text.length() < 1200, "片段必须有界，实测长度=" + text.length());
        assertFalse(text.contains("z".repeat(601)));
    }

    @Test
    void retainsStructuredObservationAndNonTextBlocks() {
        ObjectNode raw = resultJson("w".repeat(4000));
        raw.set("structuredContent", MAPPER.createObjectNode().put("k", "v"));
        raw.withArray("content").addObject().put("type", "image").put("artifactId", "art_1");
        String text = LoopSteps.foldUnarchivedToolResult(raw, view(), 600).content();
        assertTrue(text.contains("\"k\""), "结构化观察原样保留");
        assertTrue(text.contains("art_1"), "非文本块原样保留");
    }

    @Test
    void doesNotClaimAnArtifactThatDoesNotExist() {
        String text = LoopSteps.foldUnarchivedToolResult(resultJson("v".repeat(4000)), view(), 600).content();
        assertFalse(text.contains("read_artifact"), "未归档结果没有产物，不得引导模型回读不存在的产物");
    }

    @Test
    void replayFoldsOldResultAndProtectsRecentUnit() {
        String big = "q".repeat(8000);
        var history = List.of(
                ModelMessage.assistantToolCalls(List.of(new ModelToolCall("c1", "run_command", "{}")), null),
                ModelMessage.toolResult("c1", "run_command", big),
                ModelMessage.assistantToolCalls(List.of(new ModelToolCall("c2", "run_command", "{}")), null),
                ModelMessage.toolResult("c2", "run_command", big));
        var folded = ToolOutputReplay.fold(history, 1,
                m -> LoopSteps.foldUnarchivedToolResult(resultJson(big), m, 600));
        assertEquals(history.size(), folded.size());
        assertSame(history.get(3), folded.get(3), "最近一个单元必须逐字保留");
        assertTrue(folded.get(1).content().length() < big.length(), "旧结果必须被折叠");
        assertTrue(TokenEstimator.estimateMessages(folded) < TokenEstimator.estimateMessages(history));
    }

    @Test
    void replayGuardKeepsOriginalWhenFoldWouldNotShrink() {
        var history = List.of(
                ModelMessage.assistantToolCalls(List.of(new ModelToolCall("c1", "run_command", "{}")), null),
                ModelMessage.toolResult("c1", "run_command", "短"),
                ModelMessage.assistantToolCalls(List.of(new ModelToolCall("c2", "run_command", "{}")), null),
                ModelMessage.toolResult("c2", "run_command", "保护区内的正文"));
        var folded = ToolOutputReplay.fold(history, 1,
                m -> LoopSteps.foldUnarchivedToolResult(resultJson("短"), m, 600));
        assertEquals(history, folded, "折叠后没有变小就必须回退原消息，绝不更差");
    }
}
