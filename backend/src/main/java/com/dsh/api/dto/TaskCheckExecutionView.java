package com.dsh.api.dto;

import com.dsh.orchestration.acceptance.CanonicalTaskJson;
import com.dsh.orchestration.acceptance.TrustedTaskExecutionSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Complete immutable public definition; contains only relative verifier/cache names, no Host path. */
public record TaskCheckExecutionView(String sourceDigest, String canonicalDefinition,
        List<TrustedTaskExecutionSpec.CheckProfile> checks, String executionSpecDigest) {
    public TaskCheckExecutionView { checks = List.copyOf(checks); }
    public static TaskCheckExecutionView from(TrustedTaskExecutionSpec.Snapshot snapshot) {
        return new TaskCheckExecutionView(snapshot.sourceDigest(), snapshot.canonicalDefinition(),
                snapshot.checks(), snapshot.executionSpecDigest());
    }
    /** Self-consistency only. Host authorization and actual bytes must still be read separately. */
    public void validateAgainst(TaskCheckPreview preview) {
        var mapper = new ObjectMapper();
        var definition = TrustedTaskExecutionSpec.validateExport(canonicalDefinition);
        if (sourceDigest == null || !sourceDigest.matches("[a-f0-9]{64}")
                || !preview.projectId().equals(definition.path("projectId").asText())
                || !preview.registryDigest().equals(definition.path("registryDigest").asText())
                || !preview.imageDigest().equals(definition.path("imageDigest").asText())
                || !preview.executionPolicyDigest().equals(CanonicalTaskJson.digest(definition.path("executionPolicy")))
                || !definition.path("checkRefs").equals(mapper.valueToTree(preview.availableChecks().stream().map(c -> c.id()).toList()))
                || checks.size() != preview.availableChecks().size()) throw invalid();
        for (int i=0;i<checks.size();i++) {
            var profile=checks.get(i); var check=preview.availableChecks().get(i);
            if (!check.id().equals(profile.checkId()) || !check.argv().equals(profile.argv())
                    || check.timeoutSeconds()!=profile.timeoutSeconds() || check.minPassed()!=profile.minPassed()
                    || !check.verifiers().equals(profile.verifiers()) || !check.expectTests()
                    || !CanonicalTaskJson.digest(mapper.valueToTree(List.of(check))).equals(profile.checksDigest())) throw invalid();
        }
        var bound=mapper.createObjectNode().put("version",TrustedTaskExecutionSpec.VERSION).put("sourceDigest",sourceDigest);
        bound.set("definition",definition); bound.set("checks",mapper.valueToTree(checks));
        if (!CanonicalTaskJson.digest(bound).equals(executionSpecDigest)) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid execution preview binding"); }
}
