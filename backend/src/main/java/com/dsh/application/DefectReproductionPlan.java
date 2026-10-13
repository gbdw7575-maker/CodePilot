package com.dsh.application;

import com.dsh.common.Digest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** Explicit assertion protocol: an execution comparison, never a semantic judge. */
public record DefectReproductionPlan(String assertionId, String fileName, String content, String command) {
    private static final Set<String> KEYS = Set.of("assertionId", "fileName", "content", "command");
    public DefectReproductionPlan {
        if (assertionId == null || !assertionId.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")
                || fileName == null || !fileName.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}") || fileName.contains("..")
                || content == null || content.isBlank() || content.indexOf('\0') >= 0
                || content.getBytes(StandardCharsets.UTF_8).length > 16384
                || command == null || command.isBlank() || command.length() > 4096 || command.indexOf('\0') >= 0)
            throw new IllegalArgumentException("无效的 defectReproduction 定义");
    }
    static DefectReproductionPlan parse(Object value) {
        if (!(value instanceof Map<?, ?> m) || !m.keySet().equals(KEYS)
                || m.values().stream().anyMatch(x -> !(x instanceof String)))
            throw new IllegalArgumentException("无效的 defectReproduction 字段");
        return new DefectReproductionPlan((String)m.get("assertionId"), (String)m.get("fileName"),
                (String)m.get("content"), (String)m.get("command"));
    }
    public String digest() {
        var n = new ObjectMapper().createObjectNode().put("version","defect-reproduction-v1")
                .put("assertionId",assertionId).put("fileName",fileName)
                .put("sourceSha256",Digest.sha256Hex(content)).put("command",command);
        return Digest.sha256Hex(n.toString());
    }
    public String marker(boolean pass) { return "DSH_DEFECT_V1 " + (pass ? "PASS " : "FAIL ") + assertionId; }
}
