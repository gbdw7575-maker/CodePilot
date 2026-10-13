package com.dsh.agent.tool;

import com.dsh.orchestration.plan.PlanDtos.InputRef;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.ResourceScope;
import com.dsh.orchestration.plan.PlanDtos.SavedPlan;
import com.dsh.orchestration.plan.PlanDtos.SavedTask;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.orchestration.plan.PlanService;
import com.dsh.orchestration.plan.PlanServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * update_plan（MA-02 双分支；Agent 编排升级规范 §5.2）：
 *
 * <ul>
 *   <li><b>legacy steps</b>：{steps:[{text,status}]}，行为不变——把展示清单写入工作区 meta
 *       的 plan.json（sideEffect=WORKSPACE_WRITE 仅指该产物），不碰用户源码；常驻 CORE。</li>
 *   <li><b>v2 plan</b>：{planId?, expectedRevision?, goal?, tasks:[...]}，经 {@link PlanService}
 *       落库为带修订号的权威计划草案，返回 planId/revision/校验结果；<b>只创建/更新草案，
 *       不启动任何 worker</b>。任务运行状态不由 steps.status 或模型写入控制。</li>
 * </ul>
 *
 * 两种结构互斥，混用返回 MIXED_PLAN_FORMAT；Schema 层 oneOf 已先拦截，此处为服务端兜底。
 */
@Component
public class UpdatePlanTool implements Tool {

    private final PlanService planService;

    public UpdatePlanTool(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public String name() {
        return "update_plan";
    }

    @Override
    public ToolOutcome execute(JsonNode args, ToolExecutionContext ctx) throws Exception {
        boolean legacy = args.has("steps");
        boolean v2 = args.has("tasks");
        if (legacy && v2) {
            return ToolOutcome.failed("MIXED_PLAN_FORMAT",
                    "steps 展示清单与 tasks 结构化计划不能混用：简单清单用 steps，"
                            + "带依赖/验收的计划用 planId/expectedRevision/tasks", name());
        }
        if (v2) {
            return executePlanV2(args, ctx);
        }
        return executeLegacySteps(args, ctx);
    }

    // ---------- v2：结构化计划草案（落库，不执行） ----------

    private ToolOutcome executePlanV2(JsonNode args, ToolExecutionContext ctx) {
        try {
            PlanDraft draft = new PlanDraft(
                    textOrNull(args, "planId"),
                    args.has("expectedRevision") && !args.get("expectedRevision").isNull()
                            ? args.get("expectedRevision").asInt() : null,
                    textOrNull(args, "goal"),
                    parseTasks(args.get("tasks")));
            SavedPlan saved = planService.saveDraft(ctx.runId(), draft);
            return toPlanOutcome(saved, ctx.objectMapper());
        } catch (PlanServiceException e) {
            return ToolOutcome.failed(e.reasonCode(), e.getMessage(), name());
        } catch (IllegalArgumentException e) {
            return ToolOutcome.failed("VALIDATION_FAILED", e.getMessage(), name());
        }
    }

    private static List<TaskDraft> parseTasks(JsonNode tasksNode) {
        if (tasksNode == null || !tasksNode.isArray() || tasksNode.isEmpty()) {
            throw new IllegalArgumentException("tasks 必须是非空数组");
        }
        List<TaskDraft> tasks = new ArrayList<>();
        for (JsonNode t : tasksNode) {
            String taskId = textOrNull(t, "taskId");
            String goal = textOrNull(t, "goal");
            String deliverable = textOrNull(t, "deliverable");
            List<String> dependsOn = toStringList(t.get("dependsOn"));
            List<InputRef> inputRefs = new ArrayList<>();
            if (t.has("inputRefs") && t.get("inputRefs").isArray()) {
                for (JsonNode ref : t.get("inputRefs")) {
                    inputRefs.add(new InputRef(textOrNull(ref, "taskId"), textOrNull(ref, "output")));
                }
            }
            List<String> acceptance = toStringList(t.get("acceptance"));
            ResourceScope scope = null;
            if (t.has("resourceScope") && t.get("resourceScope").isObject()) {
                JsonNode rs = t.get("resourceScope");
                scope = new ResourceScope(toStringList(rs.get("read")), toStringList(rs.get("write")));
            }
            String mode = t.path("executionMode").asText("DELEGATED");
            boolean required = t.path("required").asBoolean(false);
            tasks.add(new TaskDraft(taskId, goal, dependsOn, inputRefs, deliverable,
                    acceptance, scope, mode, required));
        }
        return List.copyOf(tasks);
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return List.copyOf(out);
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String v = node.get(field).asText("");
        return v.isBlank() ? null : v;
    }

    private static ToolOutcome toPlanOutcome(SavedPlan saved, ObjectMapper mapper) {
        ObjectNode structured = mapper.createObjectNode();
        structured.put("mode", "plan");
        structured.put("planId", saved.planId());
        structured.put("revision", saved.revision());
        structured.put("status", saved.status());
        structured.put("taskCount", saved.tasks().size());
        ArrayNode tasksNode = structured.putArray("tasks");
        for (SavedTask t : saved.tasks()) {
            ObjectNode n = tasksNode.addObject();
            n.put("taskId", t.taskId());
            n.put("taskVersion", t.taskVersion());
            n.put("state", t.state());
            n.put("executionMode", t.executionMode());
            n.put("required", t.required());
        }
        String text = "计划草案已保存：planId=" + saved.planId() + " revision=" + saved.revision()
                + "，任务 " + saved.tasks().size() + " 个（全部 DRAFT，未启动执行）";
        return ToolOutcome.succeeded("计划已更新（revision=" + saved.revision() + "）",
                text, structured, "update_plan");
    }

    // ---------- legacy：展示清单写 plan.json（行为与 MA-02 前一致） ----------

    private ToolOutcome executeLegacySteps(JsonNode args, ToolExecutionContext ctx) throws Exception {
        ArrayNode steps = (ArrayNode) args.path("steps");
        Path planFile = ctx.lease().metaDir().resolve("plan.json");
        Files.createDirectories(planFile.getParent());

        ObjectNode persisted = ctx.objectMapper().createObjectNode();
        persisted.put("runId", ctx.runId());
        persisted.put("workspaceId", ctx.lease().workspaceId());
        persisted.set("steps", steps.deepCopy());
        Files.writeString(planFile, ctx.objectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(persisted));
        ctx.lease().setPlanRef(planFile.toString());

        int done = 0;
        int inProgress = 0;
        for (JsonNode step : steps) {
            String status = step.path("status").asText();
            if ("DONE".equals(status)) {
                done++;
            } else if ("IN_PROGRESS".equals(status)) {
                inProgress++;
            }
        }
        ObjectNode structured = ctx.objectMapper().createObjectNode();
        structured.put("mode", "steps");
        structured.put("steps", steps.size());
        structured.put("done", done);
        structured.put("inProgress", inProgress);
        structured.put("planRef", planFile.toString());
        return ToolOutcome.succeeded("计划已更新：共 " + steps.size() + " 步，完成 " + done + " 步",
                summarize(steps), structured, name());
    }

    private static String summarize(ArrayNode steps) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (JsonNode step : steps) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(i++).append(". [")
                    .append(switch (step.path("status").asText()) {
                        case "DONE" -> "x";
                        case "IN_PROGRESS" -> "~";
                        default -> " ";
                    })
                    .append("] ").append(step.path("text").asText());
        }
        return sb.toString();
    }
}
