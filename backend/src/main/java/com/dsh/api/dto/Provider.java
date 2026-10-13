package com.dsh.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 枚举与 openapi.json 的 components/schemas/Provider 一致。
 * openai-compat 含连字符不能作为枚举常量名：JSON 映射走 wire 值（wire()/fromWire），
 * 持久化与路由（GatewayRouter）也使用 wire 值。
 */
public enum Provider {
    deepseek,
    fake,
    @JsonProperty("openai-compat")
    openai_compat;

    /** 契约/持久化/路由使用的 wire 值。 */
    public String wire() {
        return this == openai_compat ? "openai-compat" : name();
    }

    /** 从 wire 值解析；未知值抛 IllegalArgumentException（fail-closed）。 */
    public static Provider fromWire(String value) {
        if ("openai-compat".equals(value)) {
            return openai_compat;
        }
        return Provider.valueOf(value);
    }
}
