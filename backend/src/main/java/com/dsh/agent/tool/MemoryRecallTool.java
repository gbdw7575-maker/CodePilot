package com.dsh.agent.tool;

import com.dsh.api.ApiException;
import com.dsh.memory.MemoryEntry;
import com.dsh.memory.MemoryIndex;
import com.dsh.memory.MemoryProvider;
import com.dsh.application.DshProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * memory_recall 扩展工具（开发文档 §10.3，EXTENSION）：
 * 读取本项目的有界经验索引（MEMORY.md 渲染，200 行/25,000 UTF-8 字节先到者，整行截断）；
 * entryId 参数按需返回单条经验全文（主题文件按需读取的等价实现）。
 * 搜索开启时经 tool_search 挂载；搜索关闭且读取获授权时作为核心工具可用。记忆禁用时返回 MEMORY_DISABLED。
 */
@Component
public class MemoryRecallTool implements Tool {

    private final MemoryProvider memoryProvider;
    private final DshProperties properties;

    public MemoryRecallTool(MemoryProvider memoryProvider, DshProperties properties) {
        this.memoryProvider = memoryProvider;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "memory_recall";
    }

    @Override
    public ToolDefinition customDefinition() {
        var factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        ObjectNode entryId = factory.objectNode();
        entryId.put("type", "string");
        entryId.put("description", "可选；传入经验 id 返回该条全文，缺省返回有界索引");
        ObjectNode properties = factory.objectNode();
        properties.set("entryId", entryId);
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        return new ToolDefinition(name(),
                "读取本项目积累的自动经验索引（用户偏好/反馈/项目背景/参考资料路标）",
                "1.0.0", "MEMORY", "NONE", schema);
    }

    @Override
    public ExtensionMeta extensionMeta() {
        if (!properties.toolSearch().enabled() && properties.memory().enabled()
                && properties.memory().readEnabled()) {
            return null; // 搜索关闭时必要的已授权读能力常驻，避免配置称可用却不可达。
        }
        return new ExtensionMeta(
                "开始任务前回顾本项目经验：用户偏好、历史反馈、项目背景、参考资料位置",
                properties.memory().enabled() && properties.memory().readEnabled());
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public ToolOutcome execute(JsonNode arguments, ToolExecutionContext ctx) {
        try {
            ObjectNode structured = ctx.objectMapper().createObjectNode();
            String entryId = arguments.path("entryId").asText("");
            if (!entryId.isBlank()) {
                MemoryEntry entry = memoryProvider.entry(ctx.projectId(), entryId);
                structured.put("entryId", entry.id());
                structured.put("category", entry.category());
                return ToolOutcome.succeeded("经验 " + entryId + " 全文", renderEntry(entry), structured, name());
            }
            MemoryIndex index = memoryProvider.retrieve(ctx.projectId());
            structured.put("version", index.version());
            structured.put("entryCount", index.entryCount());
            structured.put("truncated", index.truncated());
            String text = index.text() + (index.truncated()
                    ? "\n[索引已截断，可用 entryId 参数读取单条全文]" : "");
            return ToolOutcome.succeeded("经验索引 v" + index.version() + "（"
                    + index.entryCount() + " 条）", text, structured, name());
        } catch (ApiException e) {
            return ToolOutcome.failed(e.code().name(), e.getMessage(), name());
        }
    }

    private String renderEntry(MemoryEntry e) {
        return "[" + e.id() + "] " + e.category() + " | 范围: " + e.scope()
                + " | 更新: " + e.updatedAt() + "\n" + e.content()
                + "\n来源: " + (e.sourceRef() == null ? "unknown" : e.sourceRef());
    }
}
