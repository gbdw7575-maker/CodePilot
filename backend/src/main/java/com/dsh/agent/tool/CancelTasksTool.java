package com.dsh.agent.tool;

import com.dsh.orchestration.task.TaskCancelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * cancel_tasks（MA-05，规范 §5.1；完整取消级联/租约 fencing 在 MA-08，AT-14/15）：
 * 登记取消并返回“已接受+当前状态”，不宣称已立即终止。不存在的任务逐项报告，
 * 不影响其他任务的取消登记（cancel 不是派发，不做整批拒绝）。
 */
@Component
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class CancelTasksTool implements Tool {

    private final TaskCancelService cancelService;

    public CancelTasksTool(TaskCancelService cancelService) {
        this.cancelService = cancelService;
    }

    @Override
    public String name() {
        return "cancel_tasks";
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public ToolOutcome execute(JsonNode arguments, ToolExecutionContext context) {
        List<String> taskIds = new ArrayList<>();
        for (JsonNode node : arguments.path("taskIds")) {
            taskIds.add(node.asText());
        }
        String reason = arguments.path("reason").asText();

        List<TaskCancelService.Item> items =
                cancelService.cancel(context.runId(), taskIds, reason);
        ObjectNode structured = context.objectMapper().createObjectNode();
        ArrayNode itemsNode = structured.putArray("items");
        int accepted = 0;
        for (TaskCancelService.Item item : items) {
            ObjectNode n = itemsNode.addObject();
            n.put("taskId", item.taskId());
            if (item.state() != null) {
                n.put("state", item.state());
            }
            n.put("accepted", item.accepted());
            n.put("signalDelivered", item.signalDelivered());
            n.put("message", item.message());
            if (item.accepted()) {
                accepted++;
            }
        }
        boolean anyFound = items.stream().anyMatch(i -> i.state() != null);
        if (!anyFound) {
            return ToolOutcome.failed("TASK_NOT_FOUND",
                    "取消集合中没有属于当前根 Run 的任务", name());
        }
        String summary = "已接受 " + accepted + "/" + items.size()
                + " 个任务的取消登记；运行中任务将在安全点终止后收敛 CANCELLED，"
                + "请用 read_task_result/await_tasks 确认最终状态";
        return ToolOutcome.succeeded(summary, summary, structured, name());
    }
}
