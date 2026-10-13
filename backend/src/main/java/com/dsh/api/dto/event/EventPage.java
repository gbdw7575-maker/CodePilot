package com.dsh.api.dto.event;

import java.util.List;

/** 对应 openapi.json 的 components/schemas/EventPage（断线补拉分页）。 */
public record EventPage(
        List<EventEnvelope> items,
        String nextAfterSequence,
        boolean hasMore,
        String highWatermark
) {
}
