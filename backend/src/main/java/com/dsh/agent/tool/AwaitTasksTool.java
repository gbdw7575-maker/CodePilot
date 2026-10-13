package com.dsh.agent.tool;

import com.dsh.orchestration.task.TaskAwaitService;
import com.dsh.orchestration.task.TaskViews.TaskView;
import com.dsh.storage.TaskEventEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * await_tasks（MA-05，规范 §9）：等待任务 ANY/ALL 到达终态。
 *
 * <p>条件已满足或 timeoutMs=0：立即返回（timedOut 表达窗口结束但任务未完成，不是失败）。
 * 否则登记持久化等待行并由 {@link TaskAwaitService} 在主循环事务外停车：工具返回内部
 * PARKED 标记，LoopSteps 据此让出唯一主循环线程；唤醒后唯一 tool result 由服务回填，
 * 不重复追加结果、不启动第二条主循环。</p>
 */
@Component
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class AwaitTasksTool implements Tool {

    public static final String PARKED_STATUS = "PARKED";

    private final TaskAwaitService awaitService;

    public AwaitTasksTool(TaskAwaitService awaitService) {
        this.awaitService = awaitService;
    }

    @Override
    public String name() {
        return "await_tasks";
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
        String waitMode = arguments.path("waitMode").asText("ALL");
        int timeoutMs;
        if (arguments.hasNonNull("timeoutMs")) {
            timeoutMs = arguments.get("timeoutMs").asInt();
        } else {
            timeoutMs = awaitService.defaultTimeoutMs();
        }
        long afterCursor = 0L;
        if (arguments.hasNonNull("afterCursor")) {
            String raw = arguments.get("afterCursor").asText("");
            try {
                afterCursor = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                return ToolOutcome.failed("INVALID_ARGUMENTS",
                        "afterCursor 必须是十进制字符串: " + raw, name());
            }
        }

        TaskAwaitService.Evaluation evaluation;
        try {
            evaluation = awaitService.evaluate(context.runId(), taskIds, waitMode, afterCursor);
        } catch (RuntimeException e) {
            return ToolOutcome.failed("TASK_STATE_ERROR",
                    "等待条件求值失败: " + e.getMessage(), name());
        }
        if (evaluation.notFound()) {
            return ToolOutcome.failed("TASK_NOT_FOUND",
                    "等待集合含不属于当前根 Run 的任务: " + evaluation.missingTaskId(), name());
        }

        boolean immediate = evaluation.ready() || timeoutMs == 0;
        if (!immediate) {
            try {
                Instant waitUntil = TaskAwaitService.boundedWaitUntil(timeoutMs);
                awaitService.park(context.runId(), context.turnId(), context.callId(),
                        taskIds, waitMode, afterCursor, arguments.toString(), waitUntil);
            } catch (IllegalArgumentException e) {
                return ToolOutcome.failed("INVALID_STATE", e.getMessage(), name());
            }
            ObjectNode parked = context.objectMapper().createObjectNode();
            parked.put("park", true);
            parked.put("waitMode", waitMode);
            parked.put("waitUntilMs", timeoutMs);
            // 内部状态：LoopSteps 不保存 tool result、不写 TOOL_COMPLETED，让出主循环线程
            return new ToolOutcome(PARKED_STATUS, null, "主循环进入任务等待停车",
                    null, parked, false, 0, null, false, name());
        }

        ObjectNode structured = buildStructured(context, waitMode, afterCursor,
                !evaluation.ready(), evaluation);
        String summary = evaluation.ready()
                ? "await_tasks 条件满足（" + waitMode + "）"
                : "await_tasks 零等待窗口结束（timedOut=true），任务未全部完成";
        return ToolOutcome.succeeded(summary, summary, structured, name());
    }

    static ObjectNode buildStructured(ToolExecutionContext context, String waitMode,
                                      long afterCursor, boolean timedOut,
                                      TaskAwaitService.Evaluation evaluation) {
        ObjectNode node = context.objectMapper().createObjectNode();
        node.put("waitMode", waitMode);
        node.put("timedOut", timedOut);
        node.put("afterCursor", Long.toString(afterCursor));
        node.put("cursor", Long.toString(evaluation.newCursor()));
        ArrayNode changes = node.putArray("changes");
        for (TaskEventEntity event : evaluation.changes()) {
            ObjectNode c = changes.addObject();
            c.put("taskId", event.getTaskId());
            c.put("state", event.getState());
            if (event.getAttemptId() != null) {
                c.put("attemptId", event.getAttemptId());
            }
            if (event.getChildRunId() != null) {
                c.put("childRunId", event.getChildRunId());
            }
            if (event.getReasonCode() != null) {
                c.put("reasonCode", event.getReasonCode());
            }
        }
        ArrayNode tasks = node.putArray("tasks");
        for (TaskView view : evaluation.tasks()) {
            tasks.add(context.objectMapper().valueToTree(view));
        }
        return node;
    }
}
