package com.dsh.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * 内部确定性工具 echo：仅供 fake Gateway 端到端复现「一次工具调用」（DSH-003 测试语义）。
 * 不属于 tools.schema.json 的 11 个契约工具，不挂载给真实供应商，不需要工作区。
 */
@Component
public class EchoTool implements Tool {

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public ToolDefinition customDefinition() {
        return ToolRegistry.internalDefinition("echo", "内部回显工具（仅 fake 供应商测试使用）");
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public ToolOutcome execute(JsonNode arguments, ToolExecutionContext context) {
        String text = arguments.path("text").asText("");
        String summary = "echo: " + text;
        return ToolOutcome.succeeded(summary, summary,
                context.objectMapper().createObjectNode().put("echo", text), name());
    }
}
