package com.dsh.sandbox;

import com.dsh.orchestration.acceptance.TaskCheckExecutionRequest;
import java.math.BigDecimal;
import java.util.List;

/**
 * PREPARATION_ONLY, deliberately NOT a runnable Docker command.
 * The next slice must materialize ONLY frozen verifier bytes and validate real runtime policy,
 * attempt authority, cancellation, report handshake and owned-ID cleanup before launching.
 */
public final class DockerCheckIsolationPlan {
    private final TaskCheckExecutionRequest request;
    private final List<String> createOptions;

    private DockerCheckIsolationPlan(TaskCheckExecutionRequest request) {
        this.request = java.util.Objects.requireNonNull(request);
        var limits = request.execution().limits();
        createOptions = List.of("--name", containerName(), "--label", "dsh.check-operation=" + request.operationId(),
                "--network", "none", "--read-only", "--user", "1000:1000", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--memory", Long.toString(limits.memoryBytes()),
                "--cpus", BigDecimal.valueOf(limits.cpuMillis(), 3).stripTrailingZeros().toPlainString(),
                "--pids-limit", Integer.toString(limits.pids()), "--tmpfs",
                "/tmp:rw,exec,nosuid,nodev,size=" + limits.tmpfsBytes() + ",mode=1777", "--workdir", "/verifier");
    }
    static DockerCheckIsolationPlan from(TaskCheckExecutionRequest request) { return new DockerCheckIsolationPlan(request); }
    public String status() { return "PREPARATION_ONLY"; }
    public String containerName() { return "dsh-check-" + request.operationId(); }
    public List<String> createOptions() { return createOptions; }
    public String image() { return "sha256:" + request.execution().imageDigest(); }
    public List<String> argv() { return request.check().argv(); }
    public List<String> readOnlyMountTargets() { return List.of("/candidate", "/verifier", "/cache"); }
    public TaskCheckExecutionRequest inputs() { return request; }
}
