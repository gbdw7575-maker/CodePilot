package com.dsh.orchestration.acceptance;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.TreeSet;

/** M1B Canonical v1 subset. Not a general JCS implementation or an HTTP mapper. */
public final class CanonicalTaskJson {
    private static final long SAFE_INTEGER = 9007199254740991L;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(4096).maxNumberLength(32).build()).build());

    private CanonicalTaskJson() { }

    public static JsonNode parse(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            try (var parser = MAPPER.createParser(text)) {
                JsonNode value = MAPPER.readTree(parser);
                if (value == null || parser.nextToken() != null) throw new IllegalArgumentException();
                encode(value); // Reject floats, unsafe integers and invalid strings even in unknown fields.
                return value;
            }
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid task catalog JSON");
        }
    }

    public static byte[] encode(JsonNode value) {
        StringBuilder out = new StringBuilder();
        append(value, out, 0);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static String digest(JsonNode value) { return sha256(encode(value)); }

    public static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void append(JsonNode value, StringBuilder out, int depth) {
        if (value == null || depth > 16) throw new IllegalArgumentException("Unsupported canonical value");
        if (value.isNull()) out.append("null");
        else if (value.isBoolean()) out.append(value.booleanValue());
        else if (value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() >= -SAFE_INTEGER && value.longValue() <= SAFE_INTEGER) out.append(value.longValue());
        else if (value.isTextual()) quoted(value.textValue(), out);
        else if (value.isArray()) {
            out.append('[');
            for (int i = 0; i < value.size(); i++) { if (i > 0) out.append(','); append(value.get(i), out, depth + 1); }
            out.append(']');
        } else if (value.isObject()) {
            TreeSet<String> keys = new TreeSet<>();
            value.fieldNames().forEachRemaining(key -> {
                if (key.chars().anyMatch(c -> c > 127)) throw new IllegalArgumentException("Non-ASCII canonical key");
                keys.add(key);
            });
            out.append('{'); boolean comma = false;
            for (String key : keys) { if (comma) out.append(','); comma = true; quoted(key, out); out.append(':'); append(value.get(key), out, depth + 1); }
            out.append('}');
        } else throw new IllegalArgumentException("Unsupported canonical value");
    }

    private static void quoted(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b"); case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n"); case '\f' -> out.append("\\f"); case '\r' -> out.append("\\r");
                default -> {
                    if (c < 32) out.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 15, 16));
                    else if (Character.isHighSurrogate(c)) {
                        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IllegalArgumentException("Invalid Unicode");
                        out.append(c).append(value.charAt(i));
                    } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid Unicode");
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
