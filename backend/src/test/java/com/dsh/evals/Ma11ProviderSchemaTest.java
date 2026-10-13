package com.dsh.evals;

import com.dsh.agent.model.ToolDefinitionMapper;
import com.dsh.agent.tool.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证实际运行时资源经网关映射后的参数；不调用真实 Provider。 */
class Ma11ProviderSchemaTest {
    @Test
    void allProviderParametersDeclareObjectAndPlanKeepsExclusiveBranches() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (var resource = getClass().getResourceAsStream("/contracts/tools.schema.json")) {
            assertNotNull(resource);
            var definitions = mapper.readTree(resource).get("x-tools");
            assertFalse(definitions.isEmpty());
            boolean foundPlan = false;
            for (var json : definitions) {
                var definition = new ToolDefinition(json.path("name").asText(),
                        json.path("description").asText(), json.path("version").asText(),
                        json.path("category").asText(), json.path("sideEffect").asText(),
                        json.get("inputSchema"));
                var parameters = ToolDefinitionMapper.toFunctionSchema(definition)
                        .path("function").path("parameters");
                assertEquals("object", parameters.path("type").asText(), definition.name());
                if ("update_plan".equals(definition.name())) {
                    foundPlan = true;
                    assertEquals(2, parameters.path("oneOf").size());
                    assertEquals("steps", parameters.path("oneOf").get(0).path("required").get(0).asText());
                    assertEquals("tasks", parameters.path("oneOf").get(1).path("required").get(0).asText());
                }
            }
            assertTrue(foundPlan);
        }
    }
}
