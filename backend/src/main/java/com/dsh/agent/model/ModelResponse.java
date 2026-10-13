package com.dsh.agent.model;

import com.dsh.api.dto.Usage;

import java.util.List;

/**
 * 一次模型调用的完整响应。
 * finishReason 归一化值：stop / tool_calls / wait_input / length / protocol_error。
 * providerResponseId 用作 model.completed.modelReturned，可为 null。
 * reasoningContent 为 DeepSeek thinking 模式的私有续接字段，仅内部存储，不入公开事件。
 */
public record ModelResponse(
        String text,
        List<ModelToolCall> toolCalls,
        String finishReason,
        Usage usage,
        String providerResponseId,
        String reasoningContent
) {

    /** 兼容构造（无 reasoningContent）。 */
    public ModelResponse(String text, List<ModelToolCall> toolCalls, String finishReason,
                         Usage usage, String providerResponseId) {
        this(text, toolCalls, finishReason, usage, providerResponseId, null);
    }
}
