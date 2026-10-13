package com.dsh.agent.tool;

import com.dsh.orchestration.task.TaskDispatchService;
import com.dsh.orchestration.task.TaskViews.TaskView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * dispatch_tasks（MA-05，规范 §5.1）：把已提交计划中的任务原子登记派发。
 * 幂等键 (requestKey) 在根 Run 范围内；登记立即返回，不等待子任务结束。
 * 仅 dsh.orchestration.enabled=true 时存在；MAIN-only 由 RunCapabilityPolicy 三层执行。
 */
@Component
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class DispatchTasksTool implements Tool {

    private final TaskDispatchService dispatchService;

    public DispatchTasksTool(TaskDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Override
    public String name() {
        return "dispatch_tasks";
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public ToolOutcome execute(JsonNode arguments, ToolExecutionContext context) {
        String planId = arguments.path("planId").asText();
        int expectedRevision = arguments.path("expectedRevision").asInt();
        String requestKey = arguments.path("requestKey").asText();
        List<String> taskIds = new ArrayList<>();
        for (JsonNode node : arguments.path("taskIds")) {
            taskIds.add(node.asText());
        }

        TaskDispatchService.DispatchResult result =
                dispatchService.register(context.runId(), planId, expectedRevision,
                        taskIds, requestKey);
        ObjectNode structured = context.objectMapper().createObjectNode();
        structured.put("planId", planId);
        if (!result.accepted()) {
            return ToolOutcome.failed(result.errorCode(), result.errorMessage(), name());
        }
        structured.put("dispatchId", result.dispatchId());
        structured.put("replay", result.replay());
        ArrayNode tasks = structured.putArray("tasks");
        for (TaskView view : result.tasks()) {
            tasks.add(context.objectMapper().valueToTree(view));
        }
        String summary = (result.replay() ? "幂等重放既有派发登记 " : "派发登记已接受 ")
                + result.dispatchId() + "（" + result.tasks().size() + " 个任务）";
        return ToolOutcome.succeeded(summary, summary + "，任务在后台就绪队列执行，"
                + "用 await_tasks 等待终态、read_task_result 读取权威结果。", structured, name());
    }
}
