package com.dsh.api.dto;

/**
 * 对应 openapi.json 的 components/schemas/Usage。
 * status=UNKNOWN 时各计数字段必须为 null，不以 0 代替未知。
 */
public record Usage(
        String status,
        Integer inputTokens,
        Integer outputTokens,
        Integer cacheHitTokens,
        Integer cacheMissTokens,
        String cost,
        String currency,
        String priceVersion
) {

    public static Usage unknown() {
        return new Usage("UNKNOWN", null, null, null, null, null, null, null);
    }
}
