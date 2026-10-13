package com.dsh.api;

import com.dsh.application.VerificationService;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.VerificationRepository;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DSH-007 Verification 验收：在工作区副本执行验证命令，解析 Tests run 结果，判定 PASSED/FAILED。
 */
class VerificationIntegrationTest extends ApiTestBase {

    @Autowired
    private RunRepository runRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private WorkspaceManager workspaceManager;
    @Autowired
    private VerificationService verificationService;
    @Autowired
    private VerificationRepository verificationRepository;

    private String bootstrapRunWithWorkspace(String verifyYaml) throws Exception {
        String rootPath = "D:/code/MyAgent/DSH/backend/target/test-run";
        Path root = Path.of(rootPath);
        Files.createDirectories(root.resolve(".dsh"));
        if (verifyYaml != null) {
            Files.writeString(root.resolve(".dsh").resolve("verify.yaml"), verifyYaml);
        }
        String projectId = createProject("Ver-Proj-" + UUID.randomUUID(), rootPath);
        trustProject(projectId, 1);
        String profileId = createProfile("ver-test-" + UUID.randomUUID(), "fake-tool");
        String threadId = createThread(projectId, profileId);
        JsonNode accepted = submitTurn(threadId, "call echo tool");
        String runId = accepted.get("rootRunId").asText();

        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        WorkspaceLease lease = workspaceManager.prepare(runId, project);
        RunEntity run = runRepository.findById(runId).orElseThrow();
        run.setWorkspaceRef(lease.workspaceId());
        runRepository.save(run);
        return runId;
    }

    @Test
    void passingVerificationIsRecorded() throws Exception {
        String runId = bootstrapRunWithWorkspace("command: echo Tests run: 5\n");
        String verId = verificationService.run(runId);
        assertNotNull(verId);

        var ver = verificationRepository.findById(verId).orElseThrow();
        assertEquals("PASSED", ver.getStatus());
        assertEquals(0, ver.getExitCode());
        assertEquals(5, ver.getTestsDiscovered());
        assertEquals(5, ver.getTestsPassed());
        assertEquals(0, ver.getTestsSkipped());
        assertNotNull(ver.getLogArtifactIds());

        // Run.verificationStatus 同步更新
        RunEntity run = runRepository.findById(runId).orElseThrow();
        assertEquals("PASSED", run.getVerificationStatus());
    }

    @Test
    void noTestsDiscoveredFailsWhenExpected() throws Exception {
        // 命令输出无 Tests run，且 expectTests=true（默认）→ FAILED
        String runId = bootstrapRunWithWorkspace("command: echo hello world\n");
        String verId = verificationService.run(runId);
        assertNotNull(verId);

        var ver = verificationRepository.findById(verId).orElseThrow();
        assertEquals("FAILED", ver.getStatus());
        assertEquals(0, ver.getTestsDiscovered());
    }

    @Test
    void zeroTestsPassesWhenNotExpected() throws Exception {
        String runId = bootstrapRunWithWorkspace(
                "command: echo build ok\nexpectTests: false\n");
        String verId = verificationService.run(runId);
        assertNotNull(verId);

        var ver = verificationRepository.findById(verId).orElseThrow();
        assertEquals("PASSED", ver.getStatus());
    }

    @Test
    void verificationsListReturnsStaleFlag() throws Exception {
        String runId = bootstrapRunWithWorkspace("command: echo Tests run: 2\n");
        verificationService.run(runId);

        mockMvc.perform(get("/api/v1/runs/" + runId + "/verifications")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("PASSED"))
                .andExpect(jsonPath("$.items[0].stale").value(false))
                .andExpect(jsonPath("$.items[0].testsDiscovered").value(2));

        // 修改工作区使 digest 变化 → stale=true
        RunEntity run = runRepository.findById(runId).orElseThrow();
        Path wsDir = workspaceManager.resolveByRef(run.getWorkspaceRef()).dir();
        Files.writeString(wsDir.resolve("stale.txt"), "changed\n");

        mockMvc.perform(get("/api/v1/runs/" + runId + "/verifications")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].stale").value(true));
    }

    @Test
    void mavenFailuresWithZeroExitCannotPass() throws Exception {
        String runId = bootstrapRunWithWorkspace(
                "command: echo Tests run: 5, Failures: 1, Errors: 0, Skipped: 0\n");
        var ver = verificationRepository.findById(verificationService.run(runId)).orElseThrow();
        assertEquals(0, ver.getExitCode());
        assertEquals("FAILED", ver.getStatus());
        assertEquals(4, ver.getTestsPassed());
        assertEquals("FAILED", runRepository.findById(runId).orElseThrow().getVerificationStatus());
    }

    @Test
    void mavenErrorsWithZeroExitCannotPassEvenIfTestsNotRequired() throws Exception {
        String runId = bootstrapRunWithWorkspace(
                "command: echo Tests run: 2, Failures: 0, Errors: 1, Skipped: 0\nexpectTests: false\n");
        var ver = verificationRepository.findById(verificationService.run(runId)).orElseThrow();
        assertEquals(0, ver.getExitCode());
        assertEquals("FAILED", ver.getStatus());
        assertEquals(1, ver.getTestsPassed());
    }

    @Test
    void pytestFailureWithZeroExitCannotPass() throws Exception {
        String runId = bootstrapRunWithWorkspace("command: echo 1 failed, 2 passed in 0.10s\n");
        var ver = verificationRepository.findById(verificationService.run(runId)).orElseThrow();
        assertEquals(0, ver.getExitCode());
        assertEquals("FAILED", ver.getStatus());
    }

    @Test
    void yamlArgvListPreservesSpacesThroughSandboxExecution() throws Exception {
        String runId = bootstrapRunWithWorkspace("""
                command:
                  - printf
                  - 'Tests run: %s, Failures: 0, Errors: 0, Skipped: 0'
                  - '3'
                """);
        var ver = verificationRepository.findById(verificationService.run(runId)).orElseThrow();
        assertEquals("PASSED", ver.getStatus());
        assertEquals(3, ver.getTestsPassed());
    }
}
