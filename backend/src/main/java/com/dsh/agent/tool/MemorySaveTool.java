package com.dsh.agent.tool;

import com.dsh.api.ApiException;
import com.dsh.memory.MemoryEntry;
import com.dsh.memory.MemoryProvider;
import com.dsh.application.DshProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * memory_save 扩展工具（开发文档 §10.3，EXTENSION）：
 * 把值得复用的经验写入本项目的经验层（user/feedback/project/reference 白名单）。
 * 保存必须携带 expectedVersion（来自 memory_recall/上次保存结果）做版本检查，
 * 冲突返回 VERSION_CONFLICT；经验层与规则文件完全隔离，本工具不接受任何路径参数。
 * 评测默认禁用可变 Auto Memory：以配置 write-enabled=false 或不挂载本工具实现。
 */
@Component
public class MemorySaveTool implements Tool {

    private final MemoryProvider memoryProvider;
    private final DshProperties properties;

    public MemorySaveTool(MemoryProvider memoryProvider, DshProperties properties) {
        this.memoryProvider = memoryProvider;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "memory_save";
    }

    @Override
    public ToolDefinition customDefinition() {
        var factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        ObjectNode category = factory.objectNode();
        category.put("type", "string");
        category.put("enum", factory.arrayNode().add("user").add("feedback").add("project").add("reference"));
        category.put("description", "经验类别：user=用户偏好 feedback=用户纠正 project=项目背景 reference=外部路标");
        ObjectNode content = factory.objectNode();
        content.put("type", "string");
        content.put("description", "经验正文（简洁、可验证，不含敏感凭据）");
        ObjectNode scope = factory.objectNode();
        scope.put("type", "string");
        scope.put("description", "有效范围（如 全局 / 仅某模块）");
        ObjectNode sourceRef = factory.objectNode();
        sourceRef.put("type", "string");
        sourceRef.put("description", "来源引用（如 用户本轮原话或 run/turn 标识）");
        ObjectNode expectedVersion = factory.objectNode();
        expectedVersion.put("type", "integer");
        expectedVersion.put("description", "并发版本号，来自 memory_recall 或上次保存结果");
        ObjectNode properties = factory.objectNode();
        properties.set("category", category);
        properties.set("content", content);
        properties.set("scope", scope);
        properties.set("sourceRef", sourceRef);
        properties.set("expectedVersion", expectedVersion);
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        ArrayNode required = factory.arrayNode();
        required.add("category");
        required.add("content");
        required.add("expectedVersion");
        schema.set("required", required);
        return new ToolDefinition(name(),
                "保存一条跨会话可复用的经验（用户偏好/纠正/项目背景/参考资料路标）",
                "1.0.0", "MEMORY", "NONE", schema);
    }

    @Override
    public ExtensionMeta extensionMeta() {
        if (!properties.toolSearch().enabled() && properties.memory().enabled()
                && properties.memory().writeEnabled()) {
            return null; // 搜索关闭时已授权写能力常驻；MemoryProvider 仍校验版本与权限。
        }
        return new ExtensionMeta(
                "用户明确表达偏好/纠正，或确认了可复用的项目背景与外部资料路标时保存经验",
                properties.memory().enabled() && properties.memory().writeEnabled());
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public ToolOutcome execute(JsonNode arguments, ToolExecutionContext ctx) {
        try {
            MemoryEntry saved = memoryProvider.save(ctx.projectId(),
                    arguments.path("category").asText(null),
                    arguments.path("content").asText(null),
                    arguments.path("scope").asText(null),
                    arguments.path("sourceRef").asText(null),
                    arguments.path("expectedVersion").asLong(-1));
            ObjectNode structured = ctx.objectMapper().createObjectNode();
            structured.put("entryId", saved.id());
            structured.put("category", saved.category());
            structured.put("version", arguments.path("expectedVersion").asLong(-1) + 1);
            return ToolOutcome.succeeded("经验已保存: " + saved.id(),
                    "已保存为 " + saved.category() + " 类经验 " + saved.id()
                            + "；记忆版本现为 v" + (arguments.path("expectedVersion").asLong(-1) + 1)
                            + "，下次保存请携带该版本号。",
                    structured, name());
        } catch (ApiException e) {
            return ToolOutcome.failed(e.code().name(), e.getMessage(), name());
        }
    }
}
