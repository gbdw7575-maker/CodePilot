package com.dsh.api.dto.event;

/** events.schema.json 中 DSH-003 使用的事件 type 常量。 */
public final class EventType {

    public static final String RUN_STATE_CHANGED = "run.state_changed";
    public static final String RUN_COMPLETED = "run.completed";
    public static final String RUN_FAILED = "run.failed";
    public static final String RUN_CANCELLED = "run.cancelled";
    public static final String RUN_INPUT_APPLIED = "run.input_applied";
    public static final String ASSISTANT_TEXT_DELTA = "assistant.text.delta";
    public static final String ASSISTANT_MESSAGE_COMPLETED = "assistant.message.completed";
    public static final String MODEL_COMPLETED = "model.completed";
    public static final String TOOL_STARTED = "tool.started";
    public static final String TOOL_COMPLETED = "tool.completed";
    public static final String APPROVAL_REQUESTED = "approval.requested";
    public static final String APPROVAL_RESOLVED = "approval.resolved";
    public static final String CHECKPOINT_CREATED = "checkpoint.created";
    public static final String VERIFICATION_COMPLETED = "verification.completed";
    // MA-10 编排任务域事件（规范 §11；MA-01 ADR §6 冻结字段）
    public static final String PLAN_UPDATED = "plan.updated";
    public static final String TASK_DISPATCHED = "task.dispatched";
    public static final String TASK_STATE_CHANGED = "task.state.changed";
    public static final String TASK_RESULT_READY = "task.result.ready";
    public static final String RUN_WAITING_TASKS = "run.waiting_tasks";
    public static final String INTEGRATION_COMPLETED = "integration.completed";
    public static final String INTEGRATION_FAILED = "integration.failed";

    private EventType() {
    }
}
