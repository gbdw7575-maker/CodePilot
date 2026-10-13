package com.dsh.agent.tool;

import com.dsh.application.DshProperties;
import com.dsh.orchestration.SubagentRequest;
import com.dsh.orchestration.SubagentResult;
import com.dsh.orchestration.SubagentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * run_subagent 扩展工具（DSH-010，开发文档 §12）：主 Agent 以可信角色
 * （ARCHITECT/CODER/TESTER/REVIEWER）派单，子 Agent 在隔离上下文/预算切片/工作区副本中
 * 同步执行，返回状态、总结、产物（patch artifact）、用量。子工具收尾不等于目标通过：
 * 是否合并、是否重新验证由父 Agent 决定。
 *
 * <p>门控 dsh.orchestration.enabled（默认关）：关闭时 authorized=false，
 * 既不被 tool_search 发现，Dispatcher 也在执行点实时拒绝。仅 MAIN 角色 Run 可调用，
 * worker 再派生由 Dispatcher 实时复查拒绝。</p>
 *
 * <p>SubagentService 依赖链回指 ToolRegistry（Tool bean 构造期收集），@Lazy 断环。</p>
 */
@Component
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class RunSubagentTool implements Tool {

    private final SubagentService subagentService;
    private final DshProperties properties;

    public RunSubagentTool(@Lazy SubagentService subagentService, DshProperties properties) {
        this.subagentService = subagentService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "run_subagent";
    }

    @Override
    public ExtensionMeta extensionMeta() {
        return new ExtensionMeta(
                "派生隔离的子 Agent 执行可信角色任务：架构设计 ARCHITECT、编码 CODER、"
                        + "测试验证 TESTER、代码评审 REVIEWER；子 Agent 有独立上下文、预算切片与工作区副本，"
                        + "取消会传播；需要多角色分工、独立验证或评审时使用",
                properties.orchestration().enabled());
    }

    @Override
    public ToolDefinition customDefinition() {
        var f = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;

        ObjectNode role = f.objectNode();
        role.put("type", "string");
        role.put("description", "可信子 Agent 角色枚举");
        ArrayNode roleEnum = f.arrayNode();
        roleEnum.add("ARCHITECT").add("CODER").add("TESTER").add("REVIEWER");
        role.set("enum", roleEnum);

        ObjectNode task = f.objectNode();
        task.put("type", "string");
        task.put("description", "交给子 Agent 的完整自然语言任务（上下文、范围、验收标准）");

        ObjectNode refs = f.objectNode();
        refs.put("type", "array");
        refs.put("description", "父运行产物 ID 列表，供子 Agent 参考");
        ObjectNode refsItems = f.objectNode();
        refsItems.put("type", "string");
        refs.set("items", refsItems);
        refs.put("default", f.arrayNode());

        ObjectNode seed = f.objectNode();
        seed.put("type", "string");
        seed.put("description", "可选：种子工作区 ref（另一个 Run 的 workspaceRef）；"
                + "TESTER/REVIEWER 应传 CODER 的 workspaceRef 以基于冻结快照工作");

        ObjectNode props = f.objectNode();
        props.set("role", role);
        props.set("task", task);
        props.set("artifactRefs", refs);
        props.set("seedWorkspaceRef", seed);

        ObjectNode schema = f.objectNode();
        schema.put("type", "object");
        schema.set("properties", props);
        ArrayNode required = f.arrayNode();
        required.add("role");
        required.add("task");
        schema.set("required", required);

        return new ToolDefinition(name(),
                "派生一个隔离的子 Agent（架构师/工程师/测试/评审）同步执行任务，"
                        + "返回状态、总结、产物引用与用量；子 Run 受独立预算切片约束，取消沿派生链传播",
                "1.0.0", "ORCHESTRATION", "NONE", schema);
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public ToolOutcome execute(JsonNode arguments, ToolExecutionContext ctx) {
        String role = arguments.path("role").asText("").trim().toUpperCase();
        String task = arguments.path("task").asText("");
        List<String> refs = new ArrayList<>();
        JsonNode refsNode = arguments.get("artifactRefs");
        if (refsNode != null && refsNode.isArray()) {
            for (JsonNode n : refsNode) {
                if (!n.asText("").isBlank()) {
                    refs.add(n.asText());
                }
            }
        }
        String seedRef = arguments.path("seedWorkspaceRef").asText("").trim();
        String seed = seedRef.isEmpty() ? null : seedRef;

        SubagentRequest request = new SubagentRequest(ctx.runId(), role, task, refs, seed);
        SubagentResult result = subagentService.run(request);

        ObjectNode structured = ctx.objectMapper().createObjectNode();
        structured.put("runId", result.runId());
        structured.put("role", result.role());
        structured.put("state", result.state());
        if (result.stopReason() != null) {
            structured.put("stopReason", result.stopReason());
        }
        structured.put("summary", result.summary() == null ? "" : result.summary());
        structured.put("workspaceRef", result.workspaceRef() == null ? "" : result.workspaceRef());
        if (!result.workspaceDigest().isEmpty()) {
            structured.put("workspaceDigest", result.workspaceDigest());
        }
        ArrayNode artifactIds = ctx.objectMapper().createArrayNode();
        result.artifactRefs().forEach(artifactIds::add);
        structured.set("artifactIds", artifactIds);
        if (result.patchArtifactId() != null) {
            structured.put("patchArtifactId", result.patchArtifactId());
        }
        ObjectNode usage = ctx.objectMapper().createObjectNode();
        usage.put("modelRequests", result.modelRequestsUsed());
        usage.put("toolCalls", result.toolCallsUsed());
        if (result.settledTokens() != null) {
            usage.put("tokens", result.settledTokens());
        }
        usage.put("durationMs", result.durationMs());
        structured.set("usage", usage);

        String headline = switch (result.state()) {
            case "COMPLETED" -> "子 Agent(" + role + ") 已完成";
            case "CANCELLED" -> "子 Agent(" + role + ") 已取消";
            case "WAITING_APPROVAL", "WAITING_INPUT" -> "子 Agent(" + role + ") 暂停等待外部处理";
            default -> "子 Agent(" + role + ") 失败: " + result.stopReason();
        };
        StringBuilder text = new StringBuilder(headline).append("\n");
        text.append("子 Run: ").append(result.runId()).append("\n");
        if (result.summary() != null && !result.summary().isBlank()) {
            text.append("总结:\n").append(result.summary()).append("\n");
        }
        text.append("用量: 模型请求 ").append(result.modelRequestsUsed())
                .append(" / 工具调用 ").append(result.toolCallsUsed())
                .append(" / 耗时 ").append(result.durationMs()).append("ms\n");
        if (result.patchArtifactId() != null) {
            text.append("变更补丁产物: ").append(result.patchArtifactId())
                    .append("（workspaceRef=").append(result.workspaceRef()).append("）\n");
        }
        text.append("注意：子工具收尾不代表目标通过；请核对状态与总结后再决定合并或后续动作。");

        // 工具调用本身已完成：子任务成败作为结构化结果交回父模型决策，不硬失败父 Run。
        return ToolOutcome.succeeded(headline, text.toString(), structured, name());
    }
}
