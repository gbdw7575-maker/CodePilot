package com.dsh.agent.tool;

import com.dsh.agent.tool.discovery.DiscoveryResult;
import com.dsh.agent.tool.discovery.ToolDiscoveryService;
import com.dsh.application.DshProperties;
import com.dsh.storage.RunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * tool_search 元工具（内部接口契约 §1 ToolDiscovery / 开发文档 §7.1）：
 * 模型主动产生查询 → 检索授权可见的扩展工具目录 → 返回最多 5 个匹配并挂载到本 Run；
 * 下一次模型请求携带新增工具的完整定义（LoopSteps.assemble 合并 RunToolMountStore）。
 * 返回工具名的文本不等于挂载：本工具直接更新 Run 级 active tools 并在结果中回报 mounted。
 * 空结果=成功空列表；查询次数超限返回明确状态（不抛异常、不冒充失败）。
 * discoveryService 用 @Lazy 注入：Tool beans 在 ToolRegistry 构造期创建，
 * 延迟解析避免 ToolRegistry → ToolSearchTool → Discovery → ToolRegistry 循环。
 */
@Component
@ConditionalOnProperty(name = "dsh.tool-search.enabled", havingValue = "true")
public class ToolSearchTool implements Tool {

    private final ToolDiscoveryService discoveryService;
    private final RunToolMountStore mountStore;
    private final DshProperties properties;
    private final RunRepository runRepository;
    private final com.dsh.orchestration.RunCapabilityPolicy capabilityPolicy;

    public ToolSearchTool(@Lazy ToolDiscoveryService discoveryService, RunToolMountStore mountStore,
                          DshProperties properties, RunRepository runRepository,
                          com.dsh.orchestration.RunCapabilityPolicy capabilityPolicy) {
        this.discoveryService = discoveryService;
        this.mountStore = mountStore;
        this.properties = properties;
        this.runRepository = runRepository;
        this.capabilityPolicy = capabilityPolicy;
    }

    @Override
    public String name() {
        return "tool_search";
    }

    @Override
    public ToolDefinition customDefinition() {
        var factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        ObjectNode query = factory.objectNode();
        query.put("type", "string");
        query.put("description", "检索扩展工具目录的查询串（工具名/能力关键词）");
        ObjectNode properties = factory.objectNode();
        properties.set("query", query);
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        ArrayNode required = factory.arrayNode();
        required.add("query");
        schema.set("required", required);
        return new ToolDefinition(name(),
                "搜索并挂载扩展工具：返回匹配的扩展工具清单，挂载后下一轮即可直接调用",
                "1.0.0", "DISCOVERY", "NONE", schema);
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public ToolOutcome execute(JsonNode arguments, ToolExecutionContext ctx) {
        DshProperties.ToolSearch config = properties.toolSearch();
        String query = arguments.path("query").asText("");
        int searches = mountStore.recordSearch(ctx.runId());

        ObjectNode structured = ctx.objectMapper().createObjectNode();
        structured.put("query", query);
        structured.put("searchCount", searches);
        structured.put("maxSearchesPerRun", config.maxSearchesPerRun());

        if (searches > config.maxSearchesPerRun()) {
            structured.put("limitReached", true);
            return ToolOutcome.succeeded(
                    "tool_search 查询次数已达本 Run 上限（" + config.maxSearchesPerRun() + "），请改用核心工具或说明能力不足",
                    "查询次数超限", structured, name());
        }

        DiscoveryResult result = discoveryService.search(query);
        // MA-03：非 MAIN 角色（worker 子 Run）经统一能力策略过滤 MAIN 专属工具
        // （派生/编排/计划/未来调度），发现侧即不可见、不可挂载。
        boolean mainRole = runRepository.findById(ctx.runId())
                .map(run -> capabilityPolicy.isMainRole(run.getRole())).orElse(false);
        List<DiscoveryResult.Item> items = result.items().stream()
                .filter(item -> mainRole || !capabilityPolicy.isMainOnlyTool(item.name()))
                .toList();
        ArrayNode matched = ctx.objectMapper().createArrayNode();
        ArrayNode mounted = ctx.objectMapper().createArrayNode();
        StringBuilder text = new StringBuilder();
        if (items.isEmpty()) {
            text.append("没有匹配的扩展工具；请更换 query、使用核心工具或说明能力不足。");
        } else {
            text.append("匹配到 ").append(items.size()).append(" 个扩展工具（已挂载到本 Run，下一轮可直接调用）：");
            for (DiscoveryResult.Item item : items) {
                matched.add(item.name());
                mountStore.mount(ctx.runId(), item.name());
                mounted.add(item.name());
                text.append("\n- ").append(item.name()).append(" v").append(item.version())
                        .append(": ").append(item.description());
            }
        }
        structured.set("matched", matched);
        structured.set("mounted", mounted);
        structured.put("limitReached", false);
        structured.put("degraded", result.degraded());
        structured.put("vectorStatus", result.vectorStatus());
        structured.put("catalogSize", result.catalogSize());
        String summary = items.isEmpty()
                ? "无匹配扩展工具" + (result.degraded() ? "（向量降级）" : "")
                : "发现并挂载 " + items.size() + " 个扩展工具" + (result.degraded() ? "（向量降级）" : "");
        return ToolOutcome.succeeded(summary, text.toString(), structured, name());
    }
}
