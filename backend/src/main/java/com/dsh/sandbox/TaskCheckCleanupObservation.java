package com.dsh.sandbox;

/** A control-plane observation, never permission to replay, release a root or mark a task successful. */
public final class TaskCheckCleanupObservation {
    private final String operationId, containerId, diagnostic;
    private final TaskCheckExecutionOutcome.Presence presence;
    private final long durationMs;

    TaskCheckCleanupObservation(String operationId, String containerId,
            TaskCheckExecutionOutcome.Presence presence, String diagnostic, long durationMs) {
        this.operationId = operationId;
        this.containerId = containerId;
        this.presence = presence;
        this.diagnostic = diagnostic;
        this.durationMs = durationMs;
    }

    public String operationId() { return operationId; }
    public String containerId() { return containerId; }
    public TaskCheckExecutionOutcome.Presence presence() { return presence; }
    public String diagnostic() { return diagnostic; }
    public long durationMs() { return durationMs; }
    public boolean successAuthority() { return false; }
    public boolean releaseAuthority() { return false; }
}
