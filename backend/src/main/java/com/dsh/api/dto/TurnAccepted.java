package com.dsh.api.dto;

/** 对应 openapi.json 的 components/schemas/TurnAccepted。 */
public record TurnAccepted(String turnId, String rootRunId, RunState state, String eventsAfterSequence) {
}
