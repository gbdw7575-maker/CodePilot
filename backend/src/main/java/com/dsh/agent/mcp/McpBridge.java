package com.dsh.agent.mcp;

import com.dsh.agent.tool.ToolRegistry;
import com.dsh.application.DshProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * MCP 桥接（D-17b）：单例就绪后向 ToolRegistry 动态注册远程只读工具。
 * 仅注册 annotations.readOnlyHint=true 的工具（fail-closed：未标注一律拒绝）；
 * 初始化失败仅降级为"不可发现不可执行"，不影响应用启动。
 */
@Component
public class McpBridge implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(McpBridge.class);

    private final McpClient client;
    private final ToolRegistry registry;
    private final DshProperties properties;
    private final ObjectMapper objectMapper;

    public McpBridge(McpClient client, ToolRegistry registry, DshProperties properties,
                     ObjectMapper objectMapper) {
        this.client = client;
        this.registry = registry;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!client.enabled()) {
            return;
        }
        try {
            JsonNode tools = client.listTools().path("tools");
            int registered = 0;
            int skipped = 0;
            for (JsonNode tool : tools) {
                String remoteName = tool.path("name").asText("");
                if (remoteName.isBlank()) {
                    skipped++;
                    continue;
                }
                boolean readOnly = tool.path("annotations").path("readOnlyHint").asBoolean(false);
                if (!readOnly) {
                    skipped++;
                    continue;
                }
                McpTool bridge = new McpTool(client, properties.mcp(), remoteName,
                        tool.path("description").asText(""), tool.path("inputSchema"),
                        objectMapper);
                registry.registerDynamicTool(bridge, bridge.customDefinition());
                registered++;
            }
            log.info("MCP 扩展已注册 {} 个只读工具，跳过 {} 个非只读/无名工具", registered, skipped);
        } catch (Exception e) {
            log.warn("MCP server 初始化失败，扩展工具不可用（fail-closed）: {}", e.getMessage());
        }
    }
}
