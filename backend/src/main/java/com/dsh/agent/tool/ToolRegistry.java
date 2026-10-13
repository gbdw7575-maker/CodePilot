package com.dsh.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具目录（内部接口契约 §1 ToolRegistry / §5）：
 * 核心工具定义取自 classpath 内 contracts/tools.schema.json（由 doc 同源复制）的 x-tools，
 * Java 侧不另写 Schema，杜绝实现与契约漂移；未知工具不能回退到 shell。
 *
 * <p>DSH-004 核心工具少，全量挂载；echo 为 fake Gateway 的内部测试工具，标记 INTERNAL。</p>
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final String SCHEMA_RESOURCE = "contracts/tools.schema.json";

    private final List<Tool> toolBeans;
    private final ObjectMapper objectMapper;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, ToolDefinition> mounted = new LinkedHashMap<>();
    /** 扩展工具目录（DSH-009）：可执行但默认不挂载，仅经 tool_search 发现并挂载到 Run。 */
    private final Map<String, ToolDefinition> extension = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> toolBeans, ObjectMapper objectMapper) {
        this.toolBeans = toolBeans;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initialize() throws Exception {
        Map<String, ToolDefinition> contractDefs = loadContractDefinitions();
        for (Tool tool : toolBeans) {
            if (tools.containsKey(tool.name())) {
                throw new IllegalStateException("重复注册工具: " + tool.name());
            }
            ToolDefinition definition = tool.customDefinition();
            if (definition == null) {
                definition = contractDefs.get(tool.name());
                if (definition == null) {
                    throw new IllegalStateException("核心工具缺少契约定义: " + tool.name());
                }
            } else if (!definition.name().equals(tool.name())) {
                throw new IllegalStateException("内部工具名不一致: " + tool.name());
            }
            tools.put(tool.name(), tool);
            if (tool.extensionMeta() != null) {
                extension.put(tool.name(), definition);
            } else {
                mounted.put(tool.name(), definition);
            }
        }
        log.info("工具目录已加载: {} 个（含内部工具）: {}, 扩展工具: {}",
                mounted.size(), mounted.keySet(), extension.keySet());
    }

    private Map<String, ToolDefinition> loadContractDefinitions() throws Exception {
        JsonNode root = objectMapper.readTree(new ClassPathResource(SCHEMA_RESOURCE).getInputStream());
        Map<String, ToolDefinition> defs = new LinkedHashMap<>();
        for (JsonNode node : root.path("x-tools")) {
            String name = node.path("name").asText();
            defs.put(name, new ToolDefinition(
                    name,
                    node.path("description").asText(),
                    node.path("version").asText(),
                    node.path("category").asText(),
                    node.path("sideEffect").asText(),
                    node.path("inputSchema").deepCopy()));
        }
        return defs;
    }

    public Optional<Tool> lookup(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 定义查询：先核心挂载集，再扩展目录（扩展工具仅挂载后才进模型请求）。 */
    public Optional<ToolDefinition> definition(String name) {
        ToolDefinition def = mounted.get(name);
        if (def == null) {
            def = extension.get(name);
        }
        return Optional.ofNullable(def);
    }

    public boolean isExtension(String name) {
        return extension.containsKey(name);
    }

    /** 扩展工具目录条目（供 tool_search 检索与索引）。 */
    public List<Tool> extensionTools() {
        List<Tool> result = new ArrayList<>();
        for (String name : extension.keySet()) {
            result.add(tools.get(name));
        }
        return result;
    }

    /** 当前挂载集合（核心工具全量挂载；扩展工具经 RunToolMountStore 按 Run 合并）。 */
    public List<ToolDefinition> mountedDefinitions() {
        return new ArrayList<>(mounted.values());
    }

    /**
     * D-17b：启动完成后由 MCP 桥接动态注册的扩展工具；重名拒绝。
     * 动态工具一律进扩展目录（不默认挂载），经 tool_search 发现后按 Run 挂载。
     */
    public synchronized void registerDynamicTool(Tool tool, ToolDefinition definition) {
        if (tools.containsKey(tool.name())) {
            throw new IllegalStateException("重复注册工具: " + tool.name());
        }
        tools.put(tool.name(), tool);
        extension.put(tool.name(), definition);
    }

    /** 构造内部测试工具的最小参数 Schema（echo）。 */
    static ToolDefinition internalDefinition(String name, String description) {
        com.fasterxml.jackson.databind.node.ObjectNode textNode =
                JsonNodeFactory.instance.objectNode();
        textNode.put("type", "string");
        com.fasterxml.jackson.databind.node.ObjectNode properties =
                JsonNodeFactory.instance.objectNode();
        properties.set("text", textNode);
        com.fasterxml.jackson.databind.node.ObjectNode schema =
                JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("additionalProperties", JsonNodeFactory.instance.booleanNode(false));
        return new ToolDefinition(name, description, "1.0.0", "INTERNAL", "NONE", schema);
    }
}
