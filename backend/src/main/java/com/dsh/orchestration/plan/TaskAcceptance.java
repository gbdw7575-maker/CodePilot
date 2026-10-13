package com.dsh.orchestration.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Frozen task requirements use the same limits at registration and verification. */
public final class TaskAcceptance {
    public static final int MAX_ITEMS = 8;
    public static final int MAX_ITEM_LENGTH = 200;

    private TaskAcceptance() { }

    /** Reject missing/corrupted evidence; never coerce non-string items or discard trailing JSON. */
    public static List<String> readFrozen(ObjectMapper mapper, String raw) throws JsonProcessingException {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Frozen acceptance is missing");
        }
        JsonNode root = mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(raw);
        if (root == null || !root.isArray() || root.isEmpty() || root.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("Frozen acceptance must contain 1 to 8 string items");
        }
        List<String> items = new ArrayList<>(root.size());
        for (JsonNode item : root) {
            if (!item.isTextual() || item.textValue().isBlank()
                    || item.textValue().length() > MAX_ITEM_LENGTH) {
                throw new IllegalArgumentException("Frozen acceptance item is invalid");
            }
            items.add(item.textValue());
        }
        return List.copyOf(items);
    }
}
