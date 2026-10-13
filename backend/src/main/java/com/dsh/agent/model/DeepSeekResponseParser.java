package com.dsh.agent.model;

import com.dsh.api.dto.Usage;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek /chat/completions 响应 → ModelResponse。
 * finish_reason 归一化；usage 缺失为 UNKNOWN，不把未知记 0；cache_hit/cache_miss 分开记录。
 */
public final class DeepSeekResponseParser {

    private DeepSeekResponseParser() {
    }

    public static ModelResponse parse(JsonNode root) {
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");

        String text = message.path("content").asText(null);
        String reasoningContent = message.path("reasoning_content").asText(null);

        List<ModelToolCall> toolCalls = new ArrayList<>();
        JsonNode tcs = message.path("tool_calls");
        if (tcs.isArray()) {
            for (JsonNode tc : tcs) {
                String callId = tc.path("id").asText();
                String name = tc.path("function").path("name").asText();
                String args = tc.path("function").path("arguments").asText("{}");
                toolCalls.add(new ModelToolCall(callId, name, args));
            }
        }

        String finishReason = mapFinishReason(choice.path("finish_reason").asText());
        Usage usage = parseUsage(root.path("usage"));
        String providerResponseId = root.path("id").asText(null);

        return new ModelResponse(text, toolCalls, finishReason, usage, providerResponseId, reasoningContent);
    }

    private static String mapFinishReason(String raw) {
        if (raw == null) {
            return FinishReason.PROTOCOL_ERROR;
        }
        return switch (raw) {
            case "stop" -> FinishReason.STOP;
            case "tool_calls" -> FinishReason.TOOL_CALLS;
            case "length" -> FinishReason.LENGTH;
            case "content_filter" -> FinishReason.LENGTH;
            default -> FinishReason.PROTOCOL_ERROR;
        };
    }

    /** usage 缺失为 UNKNOWN；存在但部分字段缺失则仅填已知字段，不补零。 */
    private static Usage parseUsage(JsonNode usage) {
        if (usage == null || usage.isNull() || usage.isMissingNode()) {
            return Usage.unknown();
        }
        Integer input = intOrNull(usage.path("prompt_tokens"));
        Integer output = intOrNull(usage.path("completion_tokens"));
        Integer cacheHit = intOrNull(usage.path("prompt_cache_hit_tokens"));
        Integer cacheMiss = intOrNull(usage.path("prompt_cache_miss_tokens"));
        if (input == null && output == null && cacheHit == null && cacheMiss == null) {
            return Usage.unknown();
        }
        return new Usage("KNOWN", input, output, cacheHit, cacheMiss, null, null, null);
    }

    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.asInt();
    }
}
