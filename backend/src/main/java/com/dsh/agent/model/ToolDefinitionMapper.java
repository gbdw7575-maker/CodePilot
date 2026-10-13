package com.dsh.agent.model;

import com.dsh.agent.tool.ToolDefinition;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * ToolDefinition → DeepSeek function 定义。
 * parameters 直接使用工具的 inputSchema（已是 JSON Schema object）。
 */
public final class ToolDefinitionMapper {

    private ToolDefinitionMapper() {
    }

    /** 挂载集合 → DeepSeek tools 数组。 */
    public static List<ObjectNode> toFunctionSchemas(List<ToolDefinition> definitions) {
        List<ObjectNode> result = new ArrayList<>(definitions.size());
        for (ToolDefinition def : definitions) {
            result.add(toFunctionSchema(def));
        }
        return result;
    }

    /** 单个工具 → {type:"function", function:{name, description, parameters}}。 */
    public static ObjectNode toFunctionSchema(ToolDefinition definition) {
        ObjectNode tool = com.dsh.agent.model.JsonSupport.mapper().createObjectNode();
        tool.put("type", "function");
        ObjectNode function = tool.putObject("function");
        function.put("name", definition.name());
        function.put("description", definition.description());
        function.set("parameters", definition.inputSchema().deepCopy());
        return tool;
    }
}
