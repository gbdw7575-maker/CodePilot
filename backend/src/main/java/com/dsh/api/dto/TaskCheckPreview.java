package com.dsh.api.dto;

import com.dsh.orchestration.acceptance.TrustedTaskCheckCatalog;
import java.util.List;

/** Read-only available checks, never a selected/confirmed registration or a success record. */
public record TaskCheckPreview(String status, String projectId, String rootRunId, String turnId,
        String planId, int planRevision, String taskState, Definition task,
        List<Requirement> requirements, String taskDefinitionDigest, String requirementsDigest,
        String registryDigest, String imageDigest, String executionPolicyDigest,
        List<TrustedTaskCheckCatalog.Check> availableChecks, String previewDigest) {
    public TaskCheckPreview { requirements = List.copyOf(requirements); availableChecks = List.copyOf(availableChecks); }
    public record Requirement(String messageId, String sequence, String content) { }
    public record InputRef(String taskId, String output) { }
    public record Scope(List<String> read, List<String> write) {
        public Scope { read = List.copyOf(read); write = List.copyOf(write); }
    }
    public record Definition(String taskId, int taskVersion, String goal, List<String> acceptance,
            String deliverable, List<String> dependsOn, List<InputRef> inputRefs,
            Scope resourceScope, String executionMode, boolean required) {
        public Definition { acceptance = List.copyOf(acceptance); dependsOn = List.copyOf(dependsOn); inputRefs = List.copyOf(inputRefs); }
    }
}
