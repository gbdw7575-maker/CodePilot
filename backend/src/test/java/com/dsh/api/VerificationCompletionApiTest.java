package com.dsh.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.dsh.storage.ApprovalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 代码改动必须在验证事件之后才进入成功终态。 */
class VerificationCompletionApiTest extends ApiTestBase {

    @Autowired
    ApprovalRepository approvalRepository;

    @TempDir
    Path root;

    private record Result(String runId, JsonNode events) {}

    private Result runPatch(String command) throws Exception {
        Files.writeString(root.resolve("README.md"), "fixture\n");
        Files.createDirectories(root.resolve(".dsh"));
        Files.writeString(root.resolve(".dsh/verify.yaml"),
                "command: " + command + "\nexpectTests: true\n");
        String projectId = createProject("verify-" + UUID.randomUUID(),
                root.toAbsolutePath().toString().replace('\\', '/'));
        trustProject(projectId, 1);
        String profileId = createProfile("verify-patch-" + UUID.randomUUID(), "fake-patch");
        String threadId = createThread(projectId, profileId);
        String runId = submitTurn(threadId, "请修复 broken 模块").get("rootRunId").asText();
        var approval = approvalRepository.findByRunIdAndStatus(runId, "PENDING").get(0);
        var body = mapper.writeValueAsString(java.util.Map.of(
                "decision", "APPROVE",
                "parametersDigest", approval.getParametersDigest(),
                "workspaceDigest", approval.getWorkspaceDigest()));
        assertEquals(200, postJson("/api/v1/approvals/" + approval.getId() + "/decision",
                body, uniqueKey("approve")).getResponse().getStatus());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        JsonNode page;
        do {
            page = getEvents(threadId, "?limit=200");
            if (types(page).stream().anyMatch(t -> t.equals("run.completed") || t.equals("run.failed"))) {
                return new Result(runId, page);
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return new Result(runId, page);
    }

    private static List<String> types(JsonNode page) {
        List<String> result = new ArrayList<>();
        page.get("items").forEach(e -> result.add(e.get("type").asText()));
        return result;
    }

    @Test
    void changedWorkspaceCompletesOnlyAfterPassingVerification() throws Exception {
        Result result = runPatch("echo Tests run: 1");
        List<String> events = types(result.events());
        assertTrue(events.indexOf("verification.completed") >= 0);
        assertTrue(events.indexOf("verification.completed") < events.indexOf("run.completed"));
        mockMvc.perform(get("/api/v1/runs/" + result.runId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.verificationStatus").value("PASSED"));
    }

    @Test
    void traceLinksModelToolApprovalVerificationAndLogArtifact() throws Exception {
        Result result = runPatch("echo Tests run: 1");
        JsonNode items = result.events().path("items");
        String traceId = items.get(0).path("traceId").asText();
        assertTrue(traceId.startsWith("trace_"));
        String modelSpan = null;
        String verificationSpan = null;
        for (JsonNode event : items) {
            String type = event.path("type").asText();
            if (List.of("model.completed", "tool.started", "tool.completed",
                    "approval.requested", "approval.resolved", "verification.completed").contains(type)) {
                assertEquals(traceId, event.path("traceId").asText(), type);
                assertTrue(event.path("spanId").asText().startsWith("span_"), type);
            }
            if ("model.completed".equals(type)) modelSpan = event.path("spanId").asText();
            if ("verification.completed".equals(type)) verificationSpan = event.path("spanId").asText();
        }
        assertTrue(modelSpan != null && verificationSpan != null && !modelSpan.equals(verificationSpan));
        JsonNode verifications = mapper.readTree(mockMvc.perform(get(
                "/api/v1/runs/" + result.runId() + "/verifications"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(verifications.path("items").get(0).path("logArtifactIds").size() > 0);
    }

    @Test
    void failedVerificationNeverEmitsCompleted() throws Exception {
        Result result = runPatch("echo no tests");
        List<String> events = types(result.events());
        assertTrue(events.contains("verification.completed"));
        assertTrue(events.contains("run.failed"));
        assertFalse(events.contains("run.completed"));
        String traceId = result.events().path("items").get(0).path("traceId").asText();
        for (JsonNode event : result.events().path("items")) {
            if (List.of("model.completed", "tool.started", "tool.completed",
                    "approval.requested", "approval.resolved", "verification.completed", "run.failed")
                    .contains(event.path("type").asText())) {
                assertEquals(traceId, event.path("traceId").asText());
                assertTrue(event.path("spanId").asText().startsWith("span_"));
            }
        }
        JsonNode verifications = mapper.readTree(mockMvc.perform(get(
                "/api/v1/runs/" + result.runId() + "/verifications"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(verifications.path("items").get(0).path("logArtifactIds").size() > 0);
        mockMvc.perform(get("/api/v1/runs/" + result.runId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.stopReason").value("VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.verificationStatus").value("FAILED"));
    }
}
