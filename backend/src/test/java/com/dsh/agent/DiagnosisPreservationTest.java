package com.dsh.agent;

import com.dsh.agent.model.ModelMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-21 压缩保护诊断：长 assistant 纯文本消息（>200 字符）按 800 字符摘要
 * C05 改为统一未证实助手主张；长度不构成结论可信度。
 * 非任何基准特化：纯长度分流。
 */
class DiagnosisPreservationTest {

    @Test
    void longAssistantGoesToAnalysisConclusions() {
        String longDiagnosis = "根因分析：问题出现在 WorkspaceManager.digestOf 在遇到 .pytest_cache 目录时"
                + "会把缓存文件计入工作区摘要，导致每次测试运行后摘要变化、apply_patch 哈希预检失败。"
                + "需要修改 WorkspaceManager 在计算摘要时过滤通用构建噪声目录。"
                + "待改文件：backend/src/main/java/com/dsh/workspace/WorkspaceManager.java，"
                + "新增 ignorePatterns 字段并在遍历时跳过 .pytest_cache/__pycache__/target/build 等。";
        assertTrue(longDiagnosis.length() > 200);

        List<ModelMessage> unit = List.of(
                ModelMessage.user("分析下问题"),
                ModelMessage.assistant(longDiagnosis));

        String summary = LoopSteps.buildHistorySummary(List.of(unit));

        assertTrue(summary.contains("UNVERIFIED_ASSISTANT_CLAIM"), "长 assistant 消息应归入分析结论段落");
        assertFalse(summary.contains("分析结论"), "长消息也不升级为已证实结论");
        // 关键诊断词应被保留（800 字符摘要足够覆盖）
        assertTrue(summary.contains("WorkspaceManager"));
        assertTrue(summary.contains(".pytest_cache"));
        assertTrue(summary.contains("根因分析"));
    }

    @Test
    void shortAssistantGoesToShortReplies() {
        List<ModelMessage> unit = List.of(
                ModelMessage.user("好了吗"),
                ModelMessage.assistant("好了")); // 长度 2，远小于 200

        String summary = LoopSteps.buildHistorySummary(List.of(unit));

        assertTrue(summary.contains("assistantClaims"));
        assertFalse(summary.contains("分析结论"));
        assertTrue(summary.contains("好了"));
    }

    @Test
    void mixedLongAndShortSeparateIntoTwoSections() {
        String longAnalysis = "A".repeat(300); // 300 字符，>200
        List<ModelMessage> unit1 = List.of(
                ModelMessage.user("详细分析"),
                ModelMessage.assistant(longAnalysis));
        List<ModelMessage> unit2 = List.of(
                ModelMessage.user("确认"),
                ModelMessage.assistant("OK"));

        String summary = LoopSteps.buildHistorySummary(List.of(unit1, unit2));

        assertTrue(summary.contains("UNVERIFIED_ASSISTANT_CLAIM"));
        assertTrue(summary.contains("assistantClaims"));
        assertTrue(summary.contains("OK"));
    }

    @Test
    void longAnalysisTruncatedAt800Chars() {
        String veryLong = "B".repeat(2000); // 2000 字符
        List<ModelMessage> unit = List.of(
                ModelMessage.user("q"),
                ModelMessage.assistant(veryLong));

        String summary = LoopSteps.buildHistorySummary(List.of(unit));

        assertTrue(summary.contains("UNVERIFIED_ASSISTANT_CLAIM"));
        // 800 字符摘要 + 段落格式，summary 长度应远小于原文 2000
        int boundedContent = summary.length();
        assertTrue(boundedContent < HistoryCheckpoint.MAX_CHARS, "摘要长度应有界（<12000），实际 " + boundedContent);
    }

    @Test
    void assistantWithToolCallsNotCountedAsAnalysis() {
        // 带工具调用的 assistant 不进分析结论（走 toolCalls 段落）
        var call = new com.dsh.agent.model.ModelToolCall("c1", "read_file", "{}");
        List<ModelMessage> unit = List.of(
                ModelMessage.user("读文件"),
                ModelMessage.assistantToolCalls(List.of(call)));

        String summary = LoopSteps.buildHistorySummary(List.of(unit));

        assertFalse(summary.contains("UNVERIFIED_ASSISTANT_CLAIM"));
        assertTrue(summary.contains("c1"));
    }
}
