package com.dsh.api.dto;

/** 对应 openapi.json 的 components/schemas/ModelOptions。 */
public record ModelOptions(
        Thinking thinking,
        ReasoningEffort reasoningEffort,
        Integer maxOutputTokens,
        Boolean streaming
) {

    public enum Thinking {
        disabled,
        enabled
    }

    public enum ReasoningEffort {
        low,
        high,
        max
    }
}
