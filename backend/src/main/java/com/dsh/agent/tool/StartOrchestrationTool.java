package com.dsh.agent.tool;

import com.dsh.application.DshProperties;
import com.dsh.orchestration.OrchestrationReport;
import com.dsh.orchestration.OrchestrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * start_orchestration 扩展工具（DSH-010，开发文档 §12）：
 * 启动固定顺序模板 coding-review v1（ARCHITECT→CODER→TESTER→REVIEWER→汇合），
 * 同步执行并返回结构化报告（逐阶段 runId/状态/用量、汇合结果、总用量、报告产物 ID）。
 *
 * <p>门控 dsh.orchestration.enabled（默认关）；仅 MAIN 角色 Run 可调用（worker 禁派生，
 * Dispatcher 实时复查）。需要工作区：CODER 以父工作区冻结副本为种子，
 * TESTER/REVIEWER 以 CODER 快照为种子，汇合是文件级三方对比，冲突整批不应用。</p>
 *
 * <p>OrchestrationService 经 SubagentService→AgentLoop→…→ToolRegistry 回环，@Lazy 断环。</p>
 */
@Component
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class StartOrchestrationTool implements Tool {

    private final OrchestrationService orchestrationService;
    private final DshProperties properties;

    public StartOrchestrationTool(@Lazy OrchestrationService orchestrationService,
                                  DshProperties properties) {
        this.orchestrationService = orchestrationService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "start_orchestration";
    }

    @Override
    public ExtensionMeta extensionMeta() {
        return new ExtensionMeta(
                "启动多 Agent 顺序编排模板 coding-review：架构→编码→测试→评审→工作区汇合；"
                        + "需要完整研发流水线、独立测试与评审角色、且要自动汇总产物与冲突时使用",
                properties.orchestration().enabled());
    }

    @Override
    public ToolDefinition customDefinition() {
        var f = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;

        ObjectNode template = f.objectNode();
        template.put("type", "string");
        template.put("description", "编排模板标识（当前仅 coding-review）");
        ArrayNode enums = f.arrayNode();
        enums.add(OrchestrationService.TEMPLATE_CODING_REVIEW);
        template.set("enum", enums);

        ObjectNode task = f.objectNode();
        task.put("type", "string");
        task.put("description", "整条流水线要完成的业务任务（完整上下文与验收标准）");

        ObjectNode props = f.objectNode();
        props.set("template", template);
        props.set("task", task);

        ObjectNode schema = f.objectNode();
        schema.put("type", "object");
        schema.set("properties", props);
        ArrayNode required = f.arrayNode();
        required.add("template");
        required.add("task");
        schema.set("required", required);

        return new ToolDefinition(name(),
                "启动 coding-review 多 Agent 编排（架构/编码/测试/评审顺序执行后三方汇合），"
                        + "返回逐阶段状态、用量与冲突报告；总预算不突破当前 Run 上限",
                "1.0.0", "ORCHESTRATION", "NONE", schema);
    }

    @Override
    public boolean requiresWorkspace() {
        return true;
    }

    @Override
    public ToolOutcome execute(JsonNode arguments, ToolExecutionContext ctx) {
        String template = arguments.path("template").asText("");
        String task = arguments.path("task").asText("");

        OrchestrationReport report =
                orchestrationService.start(ctx.runId(), template, task);

        ObjectNode structured = ctx.objectMapper().createObjectNode();
        structured.put("template", report.template());
        structured.put("templateVersion", report.templateVersion());
        structured.put("overallStatus", report.overallStatus());
        if (report.failureReason() != null) {
            structured.put("failureReason", report.failureReason());
        }
        structured.put("reportArtifactId",
                report.reportArtifactId() == null ? "" : report.reportArtifactId());

        ArrayNode stages = ctx.objectMapper().createArrayNode();
        for (OrchestrationReport.Stage s : report.stages()) {
            ObjectNode node = ctx.objectMapper().createObjectNode();
            node.put("role", s.role());
            node.put("runId", s.runId());
            node.put("executed", s.executed());
            node.put("state", s.state());
            if (s.stopReason() != null) {
                node.put("stopReason", s.stopReason());
            }
            node.put("modelRequests", s.modelRequests());
            node.put("toolCalls", s.toolCalls());
            if (s.tokens() != null) {
                node.put("tokens", s.tokens());
            }
            node.put("durationMs", s.durationMs());
            if (s.patchArtifactId() != null) {
                node.put("patchArtifactId", s.patchArtifactId());
            }
            stages.add(node);
        }
        structured.set("stages", stages);

        ObjectNode totals = ctx.objectMapper().createObjectNode();
        totals.put("modelRequests", report.totals().modelRequests());
        totals.put("toolCalls", report.totals().toolCalls());
        totals.put("tokens", report.totals().tokens());
        totals.put("durationMs", report.totals().durationMs());
        structured.set("totals", totals);

        ObjectNode mergeNode = ctx.objectMapper().createObjectNode();
        OrchestrationReport.Merge merge = report.merge();
        mergeNode.put("attempted", merge.attempted());
        mergeNode.put("status", merge.status());
        if (merge.conflicts() != null && !merge.conflicts().isEmpty()) {
            ArrayNode conflicts = ctx.objectMapper().createArrayNode();
            for (var c : merge.conflicts()) {
                ObjectNode cn = ctx.objectMapper().createObjectNode();
                cn.put("path", c.path());
                cn.put("writerChange", c.writerChange());
                cn.put("parentChange", c.parentChange());
                conflicts.add(cn);
            }
            mergeNode.set("conflicts", conflicts);
        }
        mergeNode.put("appliedCount",
                merge.appliedPaths() == null ? 0 : merge.appliedPaths().size());
        structured.set("merge", mergeNode);

        StringBuilder text = new StringBuilder();
        text.append("编排 ").append(report.template()).append(" ")
                .append(report.templateVersion()).append(" 结束: ")
                .append(report.overallStatus()).append("\n");
        if (report.failureReason() != null) {
            text.append("原因: ").append(report.failureReason()).append("\n");
        }
        for (OrchestrationReport.Stage s : report.stages()) {
            text.append("- ").append(s.role()).append(": ").append(s.state())
                    .append(" (run=").append(s.runId()).append(", mr=")
                    .append(s.modelRequests()).append(", tc=").append(s.toolCalls())
                    .append(")\n");
        }
        text.append("汇合: ").append(merge.status());
        if (merge.appliedPaths() != null && !merge.appliedPaths().isEmpty()) {
            text.append("，应用 ").append(merge.appliedPaths().size()).append(" 个文件");
        }
        if (merge.conflicts() != null && !merge.conflicts().isEmpty()) {
            text.append("，冲突 ").append(merge.conflicts().size()).append(" 个");
        }
        text.append("\n总用量: mr=").append(report.totals().modelRequests())
                .append(" tc=").append(report.totals().toolCalls())
                .append(" tok=").append(report.totals().tokens())
                .append(" 耗时=").append(report.totals().durationMs()).append("ms\n");
        text.append("报告产物: ").append(report.reportArtifactId());

        return ToolOutcome.succeeded(
                "编排 " + report.overallStatus() + "（报告: " + report.reportArtifactId() + "）",
                text.toString(), structured, name());
    }
}
