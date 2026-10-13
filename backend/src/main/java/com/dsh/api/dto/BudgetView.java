package com.dsh.api.dto;

import java.time.Instant;

/** 对应 openapi.json 的 components/schemas/BudgetView。 */
public record BudgetView(
        int maxModelRequests,
        int modelRequestsUsed,
        int maxToolCalls,
        int toolCallsUsed,
        int maxCumulativeTokens,
        Integer settledTokens,
        int reservedTokens,
        Instant deadlineAt,
        String usageStatus
) {
}
