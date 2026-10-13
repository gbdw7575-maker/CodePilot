package com.dsh.agent.tool;

import com.dsh.application.DshProperties;
import com.dsh.orchestration.FanoutService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * fanout_subagents 扩展工具（D-16 有界并行子 Agent，开发文档 §12）：
 * 主 Agent 把<strong>相互独立、产物可隔离</strong>的 N 个编码任务一次扇出，
 * N 个 CODER 子 Agent 在有界线程池内并行执行（独立上下文/预算切片/工作区副本，
 * 同一父快照基线），全部终态后 N+1 方一次汇合——冲突整批不应用并返回冲突归属，
 * 无冲突全部应用；是否通过仍以主 Run 收尾的验证为准。
 *
 * <p>门控同 run_subagent：dsh.orchestration.enabled=true 时可发现/执行；
 * 仅 MAIN 角色可调用（Dispatcher ORCHESTRATION_TOOLS 实时复查）。
 * 不适合有依赖顺序或会改同一文件的任务——那类任务用 run_subagent 顺序派生。</p>
 */
@Component
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class FanoutSubagentsTool implements Tool {

    private final FanoutService fanoutService;
    private final DshProperties properties;

    public FanoutSubagentsTool(@Lazy FanoutService fanoutService, DshProperties properties) {
        this.fanoutService = fanoutService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "fanout_subagents";
    }

    @Override
    public ExtensionMeta extensionMeta() {
        return new ExtensionMeta(
                "把多个相互独立、产物可隔离的编码任务有界并行扇出给同级 CODER 子 Agent："
                        + "独立工作区/预算，全部完成后自动三方汇合（冲突整批驳回并指明涉及子任务）；"
                        + "有依赖顺序或会同改一个文件的任务不要用本工具",
                properties.orchestration().enabled());
    }

    @Override
    public ToolDefinition customDefinition() {
        var f = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;

        ObjectNode tasks = f.objectNode();
        tasks.put("type", "array");
        tasks.put("description", "相互独立的编码任务列表（每项含完整上下文/范围/验收标准），"
                + "数量 1～" + properties.orchestration().maxFanout());
        ObjectNode items = f.objectNode();
        items.put("type", "string");
        tasks.set("items", items);
        tasks.put("minItems", 1);
        tasks.put("maxItems", properties.orchestration().maxFanout());

        ObjectNode props = f.objectNode();
        props.set("tasks", tasks);

        ObjectNode schema = f.objectNode();
        schema.put("type", "object");
        schema.set("properties", props);
        schema.set("required", f.arrayNode().add("tasks"));
        schema.set("additionalProperties", f.booleanNode(false));

        return new ToolDefinition(name(),
                "有界并行扇出多个独立编码子 Agent，隔离子工作区/预算执行后自动汇合产物；"
                        + "冲突整批不应用并返回冲突归属，由主 Agent 返工",
                "1.0.0", "ORCHESTRATION", "NONE", schema);
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public ToolOutcome execute(JsonNode arguments, ToolExecutionContext ctx) {
        List<String> tasks = new ArrayList<>();
        JsonNode tasksNode = arguments.path("tasks");
        if (tasksNode.isArray()) {
            for (JsonNode n : tasksNode) {
                String t = n.asText("").trim();
                if (!t.isEmpty()) {
                    tasks.add(t);
                }
            }
        }

        FanoutService.FanoutReport report = fanoutService.fanout(ctx.runId(), tasks);

        ObjectNode structured = ctx.objectMapper().createObjectNode();
        structured.put("state", report.state());
        if (report.reason() != null) {
            structured.put("reason", report.reason());
        }
        structured.put("parallelism", report.parallelism());
        structured.put("taskCount", report.taskCount());
        structured.put("wallClockMs", report.wallClockMs());

        ObjectNode totals = structured.putObject("totals");
        totals.put("modelRequests", report.totals().modelRequests());
        totals.put("toolCalls", report.totals().toolCalls());
        totals.put("tokens", report.totals().tokens());
        totals.put("sumDurationMs", report.totals().sumDurationMs());

        ObjectNode merge = structured.putObject("merge");
        merge.put("status", report.merge().status());
        merge.put("writersMerged", report.merge().writersMerged());
        ArrayNode applied = merge.putArray("appliedPaths");
        report.merge().appliedPaths().forEach(applied::add);
        ArrayNode conflicts = merge.putArray("conflicts");
        for (var c : report.merge().conflicts()) {
            ObjectNode co = conflicts.addObject();
            co.put("path", c.path());
            ArrayNode involved = co.putArray("writers");
            c.writerRunIds().forEach(involved::add);
            ArrayNode changes = co.putArray("changes");
            c.changes().forEach(changes::add);
        }

        ArrayNode children = structured.putArray("children");
        for (var ch : report.children()) {
            ObjectNode c = children.addObject();
            c.put("runId", ch.runId());
            c.put("state", ch.state());
            if (ch.stopReason() != null) {
                c.put("stopReason", ch.stopReason());
            }
            c.put("summary", ch.summary() == null ? "" : ch.summary());
            c.put("workspaceRef", ch.workspaceRef() == null ? "" : ch.workspaceRef());
            if (ch.patchArtifactId() != null) {
                c.put("patchArtifactId", ch.patchArtifactId());
            }
            ObjectNode usage = c.putObject("usage");
            usage.put("modelRequests", ch.modelRequestsUsed());
            usage.put("toolCalls", ch.toolCallsUsed());
            if (ch.settledTokens() != null) {
                usage.put("tokens", ch.settledTokens());
            }
            usage.put("durationMs", ch.durationMs());
            c.put("executionThread", ch.executionThread());
        }

        StringBuilder text = new StringBuilder();
        text.append("fan-out 结束: ").append(report.state())
                .append("（").append(report.taskCount()).append(" 任务，并行度 ")
                .append(report.parallelism()).append("，墙钟 ").append(report.wallClockMs())
                .append("ms；子任务串行耗时合计 ").append(report.totals().sumDurationMs()).append("ms）\n");
        if (report.reason() != null) {
            text.append("说明: ").append(report.reason()).append("\n");
        }
        text.append("汇合: ").append(report.merge().status());
        if (!report.merge().appliedPaths().isEmpty()) {
            text.append("，应用 ").append(report.merge().appliedPaths().size()).append(" 个文件");
        }
        text.append("\n");
        int i = 1;
        for (var ch : report.children()) {
            text.append(i++).append(". ").append(ch.state()).append(" ")
                    .append(ch.runId()).append(" [").append(ch.executionThread()).append("] ")
                    .append(ch.durationMs()).append("ms");
            if (ch.stopReason() != null) {
                text.append(" reason=").append(ch.stopReason());
            }
            text.append("\n");
        }
        if (!report.merge().conflicts().isEmpty()) {
            text.append("冲突清单（整批未应用，需返工）:\n");
            for (var c : report.merge().conflicts()) {
                text.append("- ").append(c.path()).append(" ← ").append(String.join(", ", c.changes()))
                        .append("\n");
            }
        }
        text.append("注意：汇合完成不代表目标通过；请核对各子结果与冲突后，由你完成最终验证。");

        return ToolOutcome.succeeded(
                "fan-out " + report.state() + "（" + report.taskCount() + " 任务，"
                        + report.merge().status() + "）",
                text.toString(), structured, name());
    }
}
