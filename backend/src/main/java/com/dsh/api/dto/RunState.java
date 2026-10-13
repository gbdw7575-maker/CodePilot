package com.dsh.api.dto;

/** 与 openapi.json 的 components/schemas/RunState 一致。 */
public enum RunState {
    QUEUED,
    PREPARING,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_INPUT,
    VERIFYING,
    CANCELLING,
    RECOVERING,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED
}
