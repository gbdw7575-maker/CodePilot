package com.dsh.agent.mcp;

import com.dsh.agent.tool.Tool;
import com.dsh.agent.tool.ToolDefinition;
import com.dsh.agent.tool.ToolExecutionContext;
import com.dsh.agent.tool.ToolOutcome;
import com.dsh.application.DshProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * 远程 MCP 工具的 Tool SPI 桥接（D-17b 只读切片）：
 * 定义从 MCP tools/list 透传（仅 readOnlyHint=true 的工具被注册）；
 * 执行转发 tools/call，结果按 D-12 保留内容块类型与 isError。
 * 经 tool_search 发现 + Run 挂载 + Dispatcher 实时复查授权后可达。
 */
public class McpTool implements Tool {

    private final McpClient client;
    private final DshProperties.Mcp config;
    private final String remoteName;
    private final String description;
    private final JsonNode inputSchema;
    private final ObjectMapper objectMapper;

    public McpTool(McpClient client, DshProperties.Mcp config, String remoteName,
                   String description, JsonNode inputSchema, ObjectMapper objectMapper) {
        this.client = client;
        this.config = config;
        this.remoteName = remoteName;
        this.description = description == null || description.isBlank()
                ? "MCP 只读工具 " + remoteName : description;
        this.inputSchema = inputSchema;
        this.objectMapper = objectMapper;
    }

    /** 本地工具名加 mcp_ 前缀，避免与核心/扩展工具冲突。 */
    static String localNameOf(String remoteName) {
        String sanitized = remoteName.replaceAll("[^A-Za-z0-9_-]", "_");
        return "mcp_" + sanitized;
    }

    String remoteName() {
        return remoteName;
    }

    @Override
    public String name() {
        return localNameOf(remoteName);
    }

    @Override
    public ToolDefinition customDefinition() {
        JsonNode schema = inputSchema != null && inputSchema.isObject() && "object".equals(inputSchema.path("type").asText("object"))
                ? inputSchema
                : minimalObjectSchema();
        return new ToolDefinition(name(),
                "[MCP 只读] " + description,
                "1.0.0", "MCP", "READ_ONLY", schema.deepCopy());
    }

    @Override
    public ExtensionMeta extensionMeta() {
        return new ExtensionMeta(description, config.enabled());
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public ToolOutcome execute(JsonNode arguments, ToolExecutionContext ctx) {
        JsonNode result;
        try {
            result = client.callTool(remoteName, arguments);
        } catch (McpClientException e) {
            if ("BLOCKED_HOST".equals(e.code()) || "MCP_CONFIG_INVALID".equals(e.code())) {
                return ToolOutcome.denied(e.code(), e.getMessage(), name());
            }
            return ToolOutcome.failed(e.code(), "MCP 调用失败: " + e.getMessage(), name());
        }

        ArrayNode blocks = objectMapper.createArrayNode();
        ArrayNode blockTypes = objectMapper.createArrayNode();
        StringBuilder text = new StringBuilder();
        int contentCount = 0;
        for (JsonNode block : result.path("content")) {
            contentCount++;
            String type = block.path("type").asText("unknown");
            blockTypes.add(type);
            if ("text".equals(type)) {
                if (text.length() > 0) {
                    text.append("\n");
                }
                text.append(block.path("text").asText());
            } else {
                text.append("\n[不支持的内容块类型: ").append(type).append("]");
            }
            // 保留 MCP 原始内容块（如 image.data / mimeType / resource.uri）。
            // 模型只读文本摘要，完整结构在后续持久化边界按大小受控保存。
            blocks.add(block.deepCopy());
        }
        boolean isError = result.path("isError").asBoolean(false);

        String output = text.toString();
        boolean truncated = false;
        if (output.length() > config.maxChars()) {
            output = output.substring(0, config.maxChars()) + "\n[正文已截断至 " + config.maxChars() + " 字符]";
            truncated = true;
        }

        ObjectNode structured = objectMapper.createObjectNode();
        structured.put("server", "mcp");
        structured.put("tool", remoteName);
        structured.put("isError", isError);
        structured.put("calledAt", Instant.now().toString());
        structured.put("blockCount", contentCount);
        structured.set("blockTypes", blockTypes);
        structured.set("content", blocks);
        structured.put("charCount", output.length());
        structured.put("truncated", truncated);

        String summary = "MCP 工具 " + remoteName + " 返回 " + contentCount + " 个内容块（"
                + output.length() + " 字符" + (truncated ? "，已截断" : "") + "）";
        if (isError) {
            // D-12：isError 保留原内容与 structured，仅状态映射为 FAILED
            return new ToolOutcome("FAILED", "MCP_TOOL_ERROR", summary, output,
                    structured, truncated, 0, null, true, name());
        }
        // 截断标志需透传（succeeded 工厂默认 false）
        return new ToolOutcome("SUCCEEDED", null, summary, output,
                structured, truncated, 0, null, false, name());
    }

    private JsonNode minimalObjectSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        schema.set("additionalProperties", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.booleanNode(true));
        return schema;
    }
}
