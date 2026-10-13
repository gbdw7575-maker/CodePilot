package com.dsh.orchestration.acceptance;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Internal, immutable PREPARATION_ONLY input. Only the server factory can construct it.
 * This proves current confirmed inputs, NOT attempt/child/fencing authority to launch.
 * Never expose this type (including host paths) through controllers or model tools.
 */
public final class TaskCheckExecutionRequest {
    public record SourceFile(String path, long bytes, String sha256) { }

    private final String operationId, registrationId, bindingDigest, workspaceId, candidateDigest;
    private final Path candidateRoot;
    private final TrustedTaskExecutionSpec.Snapshot execution;
    private final TrustedTaskExecutionSpec.CheckProfile check;
    private final TaskCheckPreviewSource.Snapshot source;
    private final List<SourceFile> candidateFiles;
    private final Map<String, byte[]> verifiers;

    TaskCheckExecutionRequest(String operationId, String registrationId, String bindingDigest,
            String workspaceId, Path candidateRoot, TrustedTaskExecutionSpec.Snapshot execution,
            TrustedTaskExecutionSpec.CheckProfile check, TaskCheckPreviewSource.Snapshot source,
            List<SourceFile> candidateFiles, String candidateDigest, Map<String, byte[]> verifiers) {
        this.operationId = operationId;
        this.registrationId = registrationId;
        this.bindingDigest = bindingDigest;
        this.workspaceId = workspaceId;
        this.candidateRoot = candidateRoot;
        this.execution = execution;
        this.check = check;
        this.source = source;
        this.candidateFiles = List.copyOf(candidateFiles);
        this.candidateDigest = candidateDigest;
        var frozen = new TreeMap<String, byte[]>();
        verifiers.forEach((path, bytes) -> frozen.put(path, bytes.clone()));
        this.verifiers = java.util.Collections.unmodifiableMap(frozen);
    }

    public String operationId() { return operationId; }
    public String registrationId() { return registrationId; }
    public String bindingDigest() { return bindingDigest; }
    public String workspaceId() { return workspaceId; }
    public Path candidateRoot() { return candidateRoot; }
    public TrustedTaskExecutionSpec.Snapshot execution() { return execution; }
    public TrustedTaskExecutionSpec.CheckProfile check() { return check; }
    public String rootRunId() { return source.rootRunId(); }
    public String projectId() { return source.projectId(); }
    public String taskId() { return source.task().taskId(); }
    public int taskVersion() { return source.task().taskVersion(); }
    public List<SourceFile> candidateFiles() { return candidateFiles; }
    public String candidateDigest() { return candidateDigest; }
    TaskCheckPreviewSource.Snapshot sourceSnapshot() { return source; }
    public List<String> verifierPaths() { return List.copyOf(verifiers.keySet()); }
    public byte[] verifierBytes(String path) {
        byte[] bytes = verifiers.get(path);
        if (bytes == null) throw new IllegalArgumentException("Verifier was not selected");
        return bytes.clone();
    }

    boolean sameInputs(TaskCheckExecutionRequest other) {
        return registrationId.equals(other.registrationId) && bindingDigest.equals(other.bindingDigest)
                && workspaceId.equals(other.workspaceId) && candidateRoot.equals(other.candidateRoot)
                && execution.cacheRoot().equals(other.execution.cacheRoot())
                && execution.executionSpecDigest().equals(other.execution.executionSpecDigest())
                && check.equals(other.check) && source.equals(other.source)
                && candidateFiles.equals(other.candidateFiles) && candidateDigest.equals(other.candidateDigest);
    }
}
