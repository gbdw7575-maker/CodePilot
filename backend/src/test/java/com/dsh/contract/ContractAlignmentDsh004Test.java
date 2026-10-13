package com.dsh.contract;

import com.dsh.api.dto.RuleFile;
import com.dsh.api.dto.Rules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-04 验收：Rules / RuleFile DTO 与 openapi.json components 正反例契约对齐。
 */
class ContractAlignmentDsh004Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SHA = "a".repeat(64);

    private static JsonNode openapiComponents;

    @BeforeAll
    static void loadContract() throws Exception {
        Path openapi = Paths.get("..", "doc", "项目文档", "openapi.json").normalize().toAbsolutePath();
        assertTrue(Files.exists(openapi), "找不到契约文件: " + openapi);
        openapiComponents = MAPPER.readTree(Files.readString(openapi)).get("components");
    }

    private Set<ValidationMessage> validate(String schemaName, JsonNode instance) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("allOf", MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("$ref", "#/components/schemas/" + schemaName)));
        root.set("components", openapiComponents);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root).validate(instance);
    }

    @Test
    void rulesDtoMatchesContract() {
        Rules rules = new Rules("proj_a1", List.of(
                new RuleFile("AGENTS.md", SHA, "全局规则", false),
                new RuleFile("src/AGENTS.md", SHA, "目录规则", true)), SHA);
        JsonNode instance = MAPPER.valueToTree(rules);
        assertTrue(validate("Rules", instance).isEmpty(),
                "Rules 应通过契约校验: " + validate("Rules", instance));

        Rules empty = new Rules("proj_a1", List.of(), SHA);
        assertTrue(validate("Rules", MAPPER.valueToTree(empty)).isEmpty(),
                "空 files 的 Rules 应通过契约校验");
    }

    @Test
    void ruleFileRejectsBadHashAndExtraFields() {
        // sha256 必须是 64 位小写十六进制
        RuleFile badHash = new RuleFile("AGENTS.md", "not-a-hash", "内容", false);
        assertFalse(validate("RuleFile", MAPPER.valueToTree(badHash)).isEmpty(),
                "非法 sha256 应被拒绝");

        // additionalProperties=false：多余字段（如内部 version）不允许出现在契约响应
        ObjectNode extra = (ObjectNode) MAPPER.valueToTree(new RuleFile("AGENTS.md", SHA, "内容", false));
        extra.put("version", "a".repeat(12));
        assertFalse(validate("RuleFile", extra).isEmpty(), "额外字段应被拒绝");

        // 必填字段缺失
        ObjectNode missing = (ObjectNode) MAPPER.valueToTree(new RuleFile("AGENTS.md", SHA, "内容", false));
        missing.remove("truncated");
        assertFalse(validate("RuleFile", missing).isEmpty(), "缺失必填字段应被拒绝");
    }
}
