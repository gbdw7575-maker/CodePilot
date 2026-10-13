package com.dsh.agent.tool;

import com.dsh.orchestration.task.TaskQueryService;
import com.dsh.orchestration.task.TaskViews.TaskView;
import com.dsh.storage.TaskEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * read_task_result（MA-05，规范 §5.1）：读取任务当前权威状态与最新 attempt 的终态结果。
 * 回答始终来自持久化行实时组装，不用内存缓存；attemptId 非空时必须是该任务最新
 * attempt（MA-05 只暴露最新结果，历史结果对账在 MA-07 产物物化后放开）。
 */
@Component
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class ReadTaskResultTool implements Tool {

    private final TaskQueryService queryService;

    public ReadTaskResultTool(TaskQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public String name() {
        return "read_task_result";
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public ToolOutcome execute(JsonNode arguments, ToolExecutionContext context) {
        String taskId = arguments.path("taskId").asText();
        String attemptId = arguments.hasNonNull("attemptId")
                ? arguments.get("attemptId").asText() : null;

        Map<String, TaskEntity> index = queryService.indexRootTasks(context.runId());
        TaskEntity task = index.get(taskId);
        if (task == null) {
            return ToolOutcome.failed("TASK_NOT_FOUND",
                    "任务不存在或不属于当前根 Run: " + taskId, name());
        }
        TaskView view = queryService.view(task);
        if (attemptId != null && (view.attemptId() == null
                || !attemptId.equals(view.attemptId()))) {
            return ToolOutcome.failed("ATTEMPT_NOT_FOUND",
                    "attemptId 不属于该任务的最新 attempt（MA-05 只暴露最新结果）: " + attemptId,
                    name());
        }
        ObjectNode structured = context.objectMapper().valueToTree(view);
        String summary = "任务 " + taskId + " 当前状态 " + view.state()
                + (view.result() != null
                ? "（" + view.result().state() + ", modelRequests="
                        + view.result().modelRequestsUsed() + "）"
                : "（尚无终态结果）");
        return ToolOutcome.succeeded(summary, summary, structured, name());
    }
}
