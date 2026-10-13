package com.dsh.agent.model;

import java.util.List;

/**
 * 送模上下文消息（内部对象，不经公开消息 API 返回）。
 * role: system / user / assistant / tool；assistant 携带 toolCalls 时 content 可为空串。
 * system 仅由服务端装配（D-04 规则注入的稳定前缀），不接受用户输入充当。
 * reasoningContent 仅 assistant 角色使用：DeepSeek thinking 模式回传 reasoning_content，
 * 工具轮次必须原样回传以维持上下文关联；绝不作为公开回答文本。
 */
public record ModelMessage(
        String role,
        String content,
        List<ModelToolCall> toolCalls,
        String toolCallId,
        String toolName,
        String reasoningContent
) {

    /** 服务端装配的系统消息（角色提示 + 项目规则），不允许由用户输入构造。 */
    public static ModelMessage system(String content) {
        return new ModelMessage("system", content, List.of(), null, null, null);
    }

    public static ModelMessage user(String content) {
        return new ModelMessage("user", content, List.of(), null, null, null);
    }

    public static ModelMessage assistant(String content) {
        return new ModelMessage("assistant", content, List.of(), null, null, null);
    }

    /** assistant 携带 reasoning_content（thinking 模式续接）。 */
    public static ModelMessage assistant(String content, String reasoningContent) {
        return new ModelMessage("assistant", content, List.of(), null, null, reasoningContent);
    }

    public static ModelMessage assistantToolCalls(List<ModelToolCall> toolCalls) {
        return new ModelMessage("assistant", "", toolCalls, null, null, null);
    }

    /** assistant 携带工具调用与 reasoning_content。 */
    public static ModelMessage assistantToolCalls(List<ModelToolCall> toolCalls, String reasoningContent) {
        return new ModelMessage("assistant", "", toolCalls, null, null, reasoningContent);
    }

    public static ModelMessage toolResult(String toolCallId, String toolName, String content) {
        return new ModelMessage("tool", content, List.of(), toolCallId, toolName, null);
    }
}
