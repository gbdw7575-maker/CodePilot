package com.dsh.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** Raw input preserved verbatim. C29 verification is trusted intake, never an Agent tool argument. */
public record SubmitTurn(String input, JsonNode verification, String projectPlanDigest) {
    public SubmitTurn(String input) { this(input, null, null); }
}
