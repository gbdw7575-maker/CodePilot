package com.dsh.orchestration.acceptance;

import com.dsh.api.dto.TaskCheckSelectionPreview;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import java.util.TreeSet;

/** Internal self-consistent storage input, NOT user authorization or a current-source CAS. */
final class TaskCheckRegistrationBinding {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(524288).build()).build());
    final String projectId, rootRunId, turnId, planId, taskId;
    final int taskVersion, observedPlanRevision;
    final String canonicalBinding, bindingDigest, canonicalSnapshot, snapshotDigest;

    private TaskCheckRegistrationBinding(TaskCheckSelectionPreview selected) {
        require(selected != null && "SELECTED_PREVIEW_ONLY".equals(selected.status()));
        var p = selected.taskPreview();
        require(p != null && "PREVIEW_ONLY".equals(p.status()) && p.task() != null);
        projectId = id(p.projectId(), 128); rootRunId = id(p.rootRunId(), 128);
        turnId = id(p.turnId(), 128); planId = id(p.planId(), 128); taskId = id(p.task().taskId(), 64);
        taskVersion = p.task().taskVersion(); observedPlanRevision = p.planRevision();
        require(taskVersion > 0 && observedPlanRevision > 0 && p.taskState() != null);
        hash(p.registryDigest()); hash(p.imageDigest()); hash(p.executionPolicyDigest());
        require(CanonicalTaskJson.digest(MAPPER.valueToTree(p.task())).equals(p.taskDefinitionDigest()));
        require(!p.requirements().isEmpty() && p.requirements().size() <= 32);
        long chars = 0;
        for (var origin : p.requirements()) {
            require(origin != null && origin.content() != null && !origin.content().isBlank()
                    && origin.content().length() <= 100000);
            chars += origin.content().length();
        }
        require(chars <= 262144);
        require(CanonicalTaskJson.digest(MAPPER.valueToTree(p.requirements())).equals(p.requirementsDigest()));
        var base = MAPPER.createObjectNode().put("version", "task-check-preview-v1")
                .put("projectId", projectId).put("rootRunId", rootRunId).put("turnId", turnId)
                .put("planId", planId).put("planRevision", observedPlanRevision).put("taskState", p.taskState())
                .put("taskDefinitionDigest", p.taskDefinitionDigest()).put("requirementsDigest", p.requirementsDigest())
                .put("registryDigest", p.registryDigest());
        require(CanonicalTaskJson.digest(base).equals(p.previewDigest()));
        require(selected.checkRefs() != null && !selected.checkRefs().isEmpty() && selected.checkRefs().size() <= 8);
        var refs = new TreeSet<String>();
        for (var ref : selected.checkRefs()) require(refs.add(id(ref, 64)));
        require(selected.checkRefs().equals(refs.stream().toList()));
        var available = new TreeMap<String, TrustedTaskCheckCatalog.Check>();
        for (var check : p.availableChecks()) {
            require(check != null && available.put(id(check.id(), 64), check) == null);
        }
        require(available.keySet().containsAll(refs));
        require(refs.stream().map(available::get).toList().equals(selected.selectedChecks()));
        require(CanonicalTaskJson.digest(MAPPER.valueToTree(selected.selectedChecks())).equals(selected.checksDigest()));
        if(selected.execution()!=null)selected.execution().validateAgainst(p);
        require(TaskCheckSelectionPreviewService.selectionDigest(p.previewDigest(),selected.checksDigest(),selected.checkRefs(),selected.execution()).equals(selected.selectionDigest()));

        // Observation revision/state/available unselected checks remain in the original snapshot.
        // Stable identity deliberately excludes them; full registry digest still binds all checks.
        var binding = MAPPER.createObjectNode().put("version", selected.execution()==null?"task-check-registration-v1":"task-check-registration-v2")
                .put("projectId", projectId).put("rootRunId", rootRunId).put("turnId", turnId).put("planId", planId)
                .put("taskDefinitionDigest", p.taskDefinitionDigest()).put("requirementsDigest", p.requirementsDigest())
                .put("registryDigest", p.registryDigest()).put("imageDigest", p.imageDigest())
                .put("executionPolicyDigest", p.executionPolicyDigest()).put("checksDigest", selected.checksDigest());
        binding.set("task", MAPPER.valueToTree(p.task())); binding.set("requirements", MAPPER.valueToTree(p.requirements()));
        binding.set("checkRefs", MAPPER.valueToTree(selected.checkRefs())); binding.set("selectedChecks", MAPPER.valueToTree(selected.selectedChecks()));
        if(selected.execution()!=null)binding.set("execution",MAPPER.valueToTree(selected.execution()));
        canonicalBinding = encode(binding); bindingDigest = CanonicalTaskJson.sha256(bytes(canonicalBinding));
        canonicalSnapshot = encode(MAPPER.valueToTree(selected)); snapshotDigest = CanonicalTaskJson.sha256(bytes(canonicalSnapshot));
    }

    static TaskCheckRegistrationBinding from(TaskCheckSelectionPreview selected) { return new TaskCheckRegistrationBinding(selected); }
    static TaskCheckRegistrationBinding decode(String snapshot) {
        require(snapshot != null && bytes(snapshot).length <= MAX_BYTES);
        try (var parser = MAPPER.createParser(snapshot)) {
            var selected = MAPPER.readValue(parser, TaskCheckSelectionPreview.class);
            require(parser.nextToken() == null);
            var binding = from(selected);
            require(binding.canonicalSnapshot.equals(snapshot));
            return binding;
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid registration snapshot"); }
    }
    private static String encode(com.fasterxml.jackson.databind.JsonNode value) {
        byte[] encoded = CanonicalTaskJson.encode(value); require(encoded.length <= MAX_BYTES);
        return new String(encoded, StandardCharsets.UTF_8);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String id(String value, int max) { require(value != null && value.matches("[A-Za-z0-9_-]{1," + max + "}")); return value; }
    private static void hash(String value) { require(value != null && value.matches("[a-f0-9]{64}")); }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid registration binding"); }
}
