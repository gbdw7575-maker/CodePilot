package com.dsh.sandbox;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Raw execution evidence only. COMPLETED/exit 0 never implies semantic acceptance. No host paths. */
public final class TaskCheckExecutionOutcome {
    public enum Termination { COMPLETED, TIMEOUT, CANCELLED, LAUNCH_FAILED, LOST }
    public enum Presence { ABSENT, PRESENT, UNKNOWN }
    public record Logs(String stdout, String stderr, long totalBytes, long droppedBytes, boolean truncated) { }
    private final String operationId, containerId, diagnostic;
    private final Termination termination;
    private final Integer exitCode;
    private final Presence cleanup;
    private final Logs logs;
    private final long durationMs;
    private final Map<String, byte[]> reports;

    TaskCheckExecutionOutcome(String operationId, String containerId, Termination termination, Integer exitCode,
                              String diagnostic, Presence cleanup, Logs logs, long durationMs, Map<String, byte[]> reports) {
        if (termination != Termination.COMPLETED && exitCode != null) throw new IllegalArgumentException("Non-natural exit");
        this.operationId=operationId; this.containerId=containerId; this.termination=termination; this.exitCode=exitCode;
        this.diagnostic=diagnostic; this.cleanup=cleanup; this.logs=logs; this.durationMs=durationMs;
        var copy=new TreeMap<String,byte[]>(); reports.forEach((p,b)->copy.put(p,b.clone()));
        this.reports=Collections.unmodifiableMap(copy);
    }
    public String operationId(){return operationId;}
    public String containerId(){return containerId;}
    public Termination termination(){return termination;}
    public Integer exitCode(){return exitCode;}
    public String diagnostic(){return diagnostic;}
    public Presence cleanup(){return cleanup;}
    public boolean cleanupConfirmed(){return cleanup==Presence.ABSENT;}
    public Logs logs(){return logs;}
    public long durationMs(){return durationMs;}
    public List<String> reportPaths(){return List.copyOf(reports.keySet());}
    public byte[] reportBytes(String path){
        var bytes=reports.get(path);if(bytes==null)throw new IllegalArgumentException("Unknown report");return bytes.clone();
    }
    /** Revalidation failure preserves diagnostics/cleanup/raw bytes without current-success authority. */
    public TaskCheckExecutionOutcome staleInputs(){
        return new TaskCheckExecutionOutcome(operationId,containerId,Termination.LOST,null,"INPUTS_STALE",cleanup,logs,durationMs,reports);
    }
}
