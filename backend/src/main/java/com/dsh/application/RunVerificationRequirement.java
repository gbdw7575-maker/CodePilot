package com.dsh.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** C29: trusted intake only; immutable per-Run purpose, human acceptance and executable assertion. */
public record RunVerificationRequirement(String purpose, String acceptanceDescription,
        DefectReproductionPlan reproduction) {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public RunVerificationRequirement {
        if ("GENERAL".equals(purpose)) {
            if (acceptanceDescription != null || reproduction != null) throw invalid();
        } else if (!"DEFECT_FIX".equals(purpose) || reproduction == null || acceptanceDescription == null
                || acceptanceDescription.isBlank() || acceptanceDescription.length() > 2000
                || acceptanceDescription.indexOf('\0') >= 0) throw invalid();
    }
    public static RunVerificationRequirement parse(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid();
        Set<String> keys = new HashSet<>(); value.fieldNames().forEachRemaining(keys::add);
        if (!value.path("purpose").isTextual()) throw invalid();
        String purpose = value.path("purpose").textValue();
        if ("GENERAL".equals(purpose) && keys.equals(Set.of("purpose")))
            return new RunVerificationRequirement(purpose, null, null);
        if (!"DEFECT_FIX".equals(purpose)
                || !keys.equals(Set.of("purpose", "acceptanceDescription", "defectReproduction"))
                || !value.path("acceptanceDescription").isTextual()) throw invalid();
        String description = value.path("acceptanceDescription").textValue();
        if (description.isBlank() || description.length() > 2000 || description.indexOf('\0') >= 0) throw invalid();
        var definition = JSON.convertValue(value.get("defectReproduction"), Object.class);
        return new RunVerificationRequirement(purpose, description, DefectReproductionPlan.parse(definition));
    }
    public static RunVerificationRequirement decode(String persisted) {
        if (persisted == null || persisted.getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
        try { return parse(JSON.readTree(persisted)); }
        catch (java.io.IOException e) { throw invalid(); }
    }
    public String encode() {
        var value = JSON.createObjectNode().put("purpose", purpose);
        if ("DEFECT_FIX".equals(purpose)) {
            value.put("acceptanceDescription", acceptanceDescription);
            value.set("defectReproduction", JSON.valueToTree(reproduction));
        }
        // Revalidate even manually constructed instances; never persist an unchecked declaration.
        parse(value);
        String encoded = value.toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
        return encoded;
    }
    public static String general() { return "{\"purpose\":\"GENERAL\"}"; }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("无效的 verification：GENERAL 只允许 purpose；DEFECT_FIX 必须登记验收说明及完整复现定义");
    }
}
