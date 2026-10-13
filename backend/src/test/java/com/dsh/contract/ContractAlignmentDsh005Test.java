package com.dsh.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-05 验收：classpath 内 contracts/tools.schema.json（与 doc 同源）的 search_code
 * 输入 Schema 正反例：literal/regex 模式、query 必填、limit 1..200、未声明字段拒绝。
 */
class ContractAlignmentDsh005Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonSchema argumentsSchema;

    @BeforeAll
    static void loadContract() throws Exception {
        JsonNode tools = MAPPER.readTree(new ClassPathResource("contracts/tools.schema.json").getInputStream());
        JsonNode searchDef = null;
        for (JsonNode tool : tools.path("x-tools")) {
            if ("search_code".equals(tool.path("name").asText())) {
                searchDef = tool;
            }
        }
        assertTrue(searchDef != null, "x-tools 缺少 search_code 定义");

        ObjectNode root = MAPPER.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.setAll((ObjectNode) searchDef.path("inputSchema"));
        argumentsSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
    }

    private Set<ValidationMessage> errors(JsonNode args) {
        return argumentsSchema.validate(args);
    }

    @Test
    void literalAndRegexModesAccepted() {
        assertTrue(errors(MAPPER.createObjectNode().put("query", "register")).isEmpty());
        assertTrue(errors(MAPPER.createObjectNode().put("query", "register").put("mode", "literal")).isEmpty());
        assertTrue(errors(MAPPER.createObjectNode().put("query", "fo{2,3}").put("mode", "regex")).isEmpty());
        assertTrue(errors(MAPPER.createObjectNode()
                .put("query", "x").put("path", "src").put("mode", "literal").put("limit", 200)).isEmpty());
    }

    @Test
    void rejectsBadModeMissingQueryAndOutOfRangeLimit() {
        // 未知模式
        assertFalse(errors(MAPPER.createObjectNode().put("query", "x").put("mode", "glob")).isEmpty());
        // query 必填
        assertFalse(errors(MAPPER.createObjectNode().put("path", ".")).isEmpty());
        // limit 越界
        assertFalse(errors(MAPPER.createObjectNode().put("query", "x").put("limit", 0)).isEmpty());
        assertFalse(errors(MAPPER.createObjectNode().put("query", "x").put("limit", 201)).isEmpty());
        // 未声明字段
        assertFalse(errors(MAPPER.createObjectNode().put("query", "x").put("regexp", true)).isEmpty());
    }
}
