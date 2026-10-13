package com.dsh.contract;

import com.dsh.api.dto.ApiError;
import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.Health;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-001 验收：Java 类型与 JSON 契约对齐。
 * 将 DTO 的 Jackson 序列化结果交给 openapi.json 中对应 Schema（2020-12）校验。
 */
class ContractAlignmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static JsonNode contractComponents;

    @BeforeAll
    static void loadContract() throws Exception {
        Path openapi = Paths.get("..", "doc", "项目文档", "openapi.json").normalize().toAbsolutePath();
        assertTrue(Files.exists(openapi), "找不到契约文件: " + openapi);
        contractComponents = MAPPER.readTree(Files.readString(openapi)).get("components");
    }

    private static Set<ValidationMessage> validateAgainst(String schemaName, JsonNode instance) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("allOf", MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("$ref", "#/components/schemas/" + schemaName)));
        root.set("components", contractComponents);
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
        return schema.validate(instance);
    }

    private static void assertValid(String schemaName, JsonNode instance) {
        Set<ValidationMessage> errors = validateAgainst(schemaName, instance);
        assertTrue(errors.isEmpty(), schemaName + " 校验失败: " + errors);
    }

    private static void assertInvalid(String schemaName, JsonNode instance) {
        Set<ValidationMessage> errors = validateAgainst(schemaName, instance);
        assertFalse(errors.isEmpty(), schemaName + " 预期校验失败，实际通过");
    }

    @Test
    void healthDtoMatchesContractSchema() throws Exception {
        Health health = new Health(Health.Status.UP, "0.1.0-design", Instant.parse("2026-09-22T08:00:00Z"));
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(health));
        assertValid("Health", json);
        assertEquals(3, json.size(), "Health 恰好包含 status/contractVersion/timestamp");
    }

    @Test
    void healthSchemaRejectsUnknownField() throws Exception {
        ObjectNode bad = MAPPER.createObjectNode();
        bad.put("status", "UP");
        bad.put("contractVersion", "0.1.0-design");
        bad.put("timestamp", "2026-09-22T08:00:00Z");
        bad.put("intent", "fix");
        assertInvalid("Health", bad);
    }

    @Test
    void healthStatusRejectsUnknownEnumValue() throws Exception {
        ObjectNode bad = MAPPER.createObjectNode();
        bad.put("status", "OK");
        bad.put("contractVersion", "0.1.0-design");
        bad.put("timestamp", "2026-09-22T08:00:00Z");
        assertInvalid("Health", bad);
    }

    @Test
    void apiErrorDtoMatchesContractSchema() throws Exception {
        ApiError error = ApiError.of(ErrorCode.ACTIVE_TURN_EXISTS, "当前会话已有活动轮次", "req_example", false);
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(error));
        assertValid("ApiError", json);
        assertEquals(5, json.size(), "ApiError 恰好包含 code/message/requestId/retryable/details");
    }

    @Test
    void apiErrorDetailsCanCarryExtraProperties() throws Exception {
        ApiError error = new ApiError(ErrorCode.ACTIVE_TURN_EXISTS, "当前会话已有活动轮次", "req_example", false,
                Map.of("activeTurnId", "turn_example"));
        assertValid("ApiError", MAPPER.readTree(MAPPER.writeValueAsString(error)));
    }

    @Test
    void apiErrorRejectsUnknownErrorCode() throws Exception {
        ObjectNode bad = MAPPER.createObjectNode();
        bad.put("code", "SOMETHING_ELSE");
        bad.put("message", "x");
        bad.put("requestId", "req_example");
        bad.put("retryable", false);
        bad.putObject("details");
        assertInvalid("ApiError", bad);
    }
}
