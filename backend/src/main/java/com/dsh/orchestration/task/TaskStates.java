package com.dsh.orchestration.task;

import java.util.Set;

/**
 * MA-01 冻结 taskStates 的服务端常量（值域与
 * {@code ma01-task-contract-fixtures.json} 一致；禁止写入 RunState 枚举）。
 */
public final class TaskStates {

    public static final String DRAFT = "DRAFT";
    public static final String PENDING = "PENDING";
    public static final String READY = "READY";
    public static final String RUNNING = "RUNNING";
    public static final String VERIFYING = "VERIFYING";
    public static final String WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String BLOCKED = "BLOCKED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLING = "CANCELLING";
    public static final String CANCELLED = "CANCELLED";
    public static final String SUCCEEDED = "SUCCEEDED";

    /** MA-01 冻结终态。 */
    public static final Set<String> TERMINAL = Set.of(SUCCEEDED, FAILED, CANCELLED);

    /** attempt 有效态（READY 条件 5：同 taskVersion 不能并发两个执行者）。 */
    public static final Set<String> ACTIVE_ATTEMPTS =
            Set.of("QUEUED", RUNNING, CANCELLING, VERIFYING, WAITING_APPROVAL);

    public static boolean isTerminal(String state) {
        return state != null && TERMINAL.contains(state);
    }

    private TaskStates() {
    }
}
