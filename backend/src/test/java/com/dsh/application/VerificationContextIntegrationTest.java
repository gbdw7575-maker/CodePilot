package com.dsh.application;

import com.dsh.agent.LoopSteps;
import com.dsh.common.IdGen;
import com.dsh.sandbox.SandboxRunner;
import com.dsh.storage.*;
import com.dsh.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class VerificationContextIntegrationTest {
    @Autowired RunRepository runs;
    @Autowired TurnRepository turns;
    @Autowired ThreadRepository threads;
    @Autowired ProjectRepository projects;
    @Autowired MessageRepository messages;
    @Autowired ArtifactRepository artifacts;
    @Autowired VerificationRepository verifications;
    @Autowired VerificationService service;
    @Autowired VerificationEvidenceContext context;
    @Autowired WorkspaceManager workspaces;
    @Autowired LoopSteps steps;
    @Autowired MockMvc mvc;
    @Autowired ArtifactStore store;
    @MockitoSpyBean SandboxRunner sandbox;
    @TempDir Path root;
    private static final String PLAN = "command: echo '2 passed in 0.01s'\nexpectTests: true\ntimeoutSeconds: 30\nplanVersion: stable-v1\n";
    record Fixture(String runId, String turnId, String threadId, Path workspace) { }

    private Fixture create() throws Exception {
        Files.createDirectories(root.resolve(".dsh"));
        Files.writeString(root.resolve(".dsh/verify.yaml"), PLAN);
        Files.writeString(root.resolve("source.txt"), "source\n");
        Instant now = Instant.now();
        String projectId = IdGen.next("proj_"), threadId = IdGen.next("thr_");
        String turnId = IdGen.next("turn_"), runId = IdGen.next("run_");
        var project = projects.save(new ProjectEntity(projectId, "C10", root.toString(), true, now, now));
        threads.save(new ThreadEntity(threadId, projectId, "C10", "unused-profile", now, now));
        turns.save(new TurnEntity(turnId, threadId, runId, "完成全部原始需求", "VERIFYING", "PENDING", threadId, now, now));
        var run = new RunEntity(runId, turnId, null, "MAIN", "VERIFYING", "PENDING", 1,
                IdGen.next("ws_"), "C10", 24, 40, 200000, now.plusSeconds(120), now, now);
        runs.save(run);
        var lease = workspaces.prepare(runId, project);
        run.setWorkspaceRef(lease.workspaceId()); runs.save(run);
        messages.save(new MessageEntity(IdGen.next("msg_"), threadId, turnId, "user",
                "完成全部原始需求", 1L, now));
        return new Fixture(runId, turnId, threadId, lease.dir());
    }

    private RunEntity run(Fixture f) { return runs.findById(f.runId()).orElseThrow(); }
    private VerificationEntity executeVerification(Fixture f) { return verifications.findById(service.run(f.runId())).orElseThrow(); }
    private void addInput(Fixture f) {
        messages.save(new MessageEntity(IdGen.next("msg_"), f.threadId(), f.turnId(), "user",
                "新增约束：不能省略异常路径", 2L, Instant.now()));
    }
    private ArtifactEntity bindingArtifact(Fixture f, VerificationEntity v) {
        return artifacts.findAll().stream().filter(a -> f.runId().equals(a.getRunId())
                && a.getName().equals("verification-context-" + v.getId() + ".json")).findFirst().orElseThrow();
    }
    private void stale(Fixture f, boolean expected) throws Exception {
        mvc.perform(get("/api/v1/runs/" + f.runId() + "/verifications").header("X-DSH-Client", "local-ui"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].stale").value(expected));
    }

    @Test void unchangedContextReusesEvidenceAndAllowsCompletion() throws Exception {
        Fixture f = create(); var v = executeVerification(f);
        assertEquals(v.getId(), service.run(f.runId()));
        assertEquals(v.getId(), service.runForCurrentWorkspace(f.runId()));
        verify(sandbox, times(1)).execute(any(), any());
        assertTrue(steps.verificationPassed(f.runId())); stale(f, false);
        var artifact = bindingArtifact(f, v);
        assertFalse(artifact.isDownloadable());
        mvc.perform(get("/api/v1/artifacts/" + artifact.getId() + "/content").header("X-DSH-Client", "local-ui"))
                .andExpect(status().isForbidden());
        steps.completeAfterVerification(f.runId());
        assertEquals("COMPLETED", run(f).getState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unconfirmed-cleanup", "all-skipped"})
    void incompleteExecutionEvidenceCannotCompleteRun(String failure) throws Exception {
        Fixture f = create();
        doAnswer(i -> new com.dsh.sandbox.CommandOutcome("op-c20", 0,
                com.dsh.sandbox.CommandSpec.Termination.COMPLETED,
                failure.equals("all-skipped") ? "2 skipped in 0.01s" : "2 passed in 0.01s",
                "", false, 1, !failure.equals("unconfirmed-cleanup")))
                .when(sandbox).execute(any(), any());
        var verification = executeVerification(f);
        assertEquals("FAILED", verification.getStatus());
        assertFalse(steps.verificationPassed(f.runId()));
        assertThrows(IllegalStateException.class, () -> steps.completeAfterVerification(f.runId()));
    }

    @Test void pipelineFailureCannotBeHiddenByTailAndLaterGreenSummary() throws Exception {
        Fixture f = create();
        Files.writeString(root.resolve(".dsh/verify.yaml"),
                "command: \"(exit 23) | tail -1; echo 1 passed in 0.01s\"\n"
                + "expectTests: false\ntimeoutSeconds: 30\nplanVersion: pipeline-v1\n");
        var verification = executeVerification(f);
        assertEquals("FAILED", verification.getStatus());
        assertEquals(23, verification.getExitCode());
        assertEquals(0, verification.getTestsPassed());
        assertFalse(steps.verificationPassed(f.runId()));
    }

    @Test void legacyPolicySuccessIsStaleAndMustBeExecutedUnderCurrentPolicy() throws Exception {
        Fixture f = create();
        var previous = executeVerification(f);
        var current = context.capture(run(f), VerificationPlan.load(root));
        var plan = VerificationPlan.load(root);
        var legacyPlan = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("command", plan.commandSummary()).put("expectTests", plan.expectTests())
                .put("timeoutSeconds", plan.timeoutSeconds()).put("planVersion", plan.planVersion());
        var legacy = new VerificationEvidenceContext.Binding(current.goalDigest(),
                com.dsh.common.Digest.sha256Hex(legacyPlan.toString()));
        artifacts.delete(bindingArtifact(f, previous));
        context.seal(run(f), previous, threads.findById(f.threadId()).orElseThrow().getProjectId(), legacy);
        assertFalse(steps.verificationPassed(f.runId()));
        stale(f, true);
        assertThrows(IllegalStateException.class, () -> steps.completeAfterVerification(f.runId()));
        assertNotEquals(previous.getId(), service.run(f.runId()));
        verify(sandbox, times(2)).execute(any(), any());
        assertTrue(steps.verificationPassed(f.runId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"command", "expectTests", "timeoutSeconds", "planVersion"})
    void everyEffectivePlanFactorInvalidatesReuseEvenWithSameWorkspace(String field) throws Exception {
        Fixture f = create(); var old = executeVerification(f);
        String changed = switch (field) {
            case "command" -> PLAN.replace("2 passed", "3 passed");
            case "expectTests" -> PLAN.replace("expectTests: true", "expectTests: false");
            case "timeoutSeconds" -> PLAN.replace("timeoutSeconds: 30", "timeoutSeconds: 31");
            default -> PLAN.replace("stable-v1", "stable-v2");
        };
        Files.writeString(root.resolve(".dsh/verify.yaml"), changed);
        assertEquals(old.getWorkspaceDigest(), WorkspaceManager.digestOf(f.workspace()));
        assertFalse(steps.verificationPassed(f.runId())); stale(f, true);
        assertThrows(IllegalStateException.class, () -> steps.completeAfterVerification(f.runId()));
        assertNotEquals(old.getId(), service.run(f.runId()));
        verify(sandbox, times(2)).execute(any(), any()); stale(f, false);
    }

    @Test void appendedRequirementInvalidatesEvidenceAndSuccessGate() throws Exception {
        Fixture f = create(); var old = executeVerification(f); addInput(f);
        assertFalse(steps.verificationPassed(f.runId())); stale(f, true);
        assertThrows(IllegalStateException.class, () -> steps.completeAfterVerification(f.runId()));
        assertNotEquals(old.getId(), service.runForCurrentWorkspace(f.runId()));
        assertTrue(steps.verificationPassed(f.runId())); stale(f, false);
    }

    @Test void otherTurnAndAssistantTextDoNotChangeCurrentGoalBinding() throws Exception {
        Fixture f = create(); var v = executeVerification(f);
        messages.save(new MessageEntity(IdGen.next("msg_"), f.threadId(), f.turnId(), "assistant", "猜测全部通过", 2L, Instant.now()));
        messages.save(new MessageEntity(IdGen.next("msg_"), f.threadId(), "another-turn", "user", "其他任务", 3L, Instant.now()));
        assertEquals(v.getId(), service.run(f.runId())); stale(f, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "tampered", "oversized", "symlink"})
    void damagedBindingCannotProduceSuccess(String damage) throws Exception {
        Fixture f = create(); var v = executeVerification(f); var artifact = bindingArtifact(f, v);
        Path file = workspaces.resolveByRef(run(f).getWorkspaceRef()).metaDir().resolve("artifacts").resolve(artifact.getId());
        byte[] original = Files.readAllBytes(file);
        switch (damage) {
            case "missing" -> Files.delete(file);
            case "tampered" -> { original[0] = 'X'; Files.write(file, original); }
            case "oversized" -> Files.writeString(file, "x".repeat(10000));
            default -> {
                Path target = root.resolve("replacement.json"); Files.write(target, original);
                Files.delete(file); Files.createSymbolicLink(file, target);
            }
        }
        assertFalse(steps.verificationPassed(f.runId())); stale(f, true);
        assertThrows(IllegalStateException.class, () -> steps.completeAfterVerification(f.runId()));
    }

    @Test void oldUnboundRecordIsNotBackfilledOrSilentlyReused() throws Exception {
        Fixture f = create(); var old = executeVerification(f); artifacts.delete(bindingArtifact(f, old));
        assertFalse(steps.verificationPassed(f.runId())); stale(f, true);
        assertNotEquals(old.getId(), service.run(f.runId()));
        assertEquals(2, verifications.findByRunIdOrderByStartedAtDesc(f.runId(), org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements());
    }

    @Test void workspaceChangeInvalidatesOrdinaryRunReuse() throws Exception {
        Fixture f = create(); var old = executeVerification(f);
        Files.writeString(f.workspace().resolve("source.txt"), "changed\n");
        assertFalse(steps.verificationPassed(f.runId())); stale(f, true);
        assertNotEquals(old.getId(), service.run(f.runId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"goal", "plan", "invalid-plan"})
    void driftDuringCommandKeepsTestResultButMarksEvidenceStale(String type) throws Exception {
        Fixture f = create();
        doAnswer(i -> {
            Object outcome = i.callRealMethod();
            if (type.equals("goal")) addInput(f);
            else Files.writeString(root.resolve(".dsh/verify.yaml"),
                    type.equals("plan") ? PLAN.replace("2 passed", "3 passed") : "expectTests: broken\n");
            return outcome;
        }).when(sandbox).execute(any(), any());
        var v = executeVerification(f);
        assertEquals("PASSED", v.getStatus()); assertTrue(v.isStale());
        assertFalse(steps.verificationPassed(f.runId())); stale(f, true);
        assertThrows(IllegalStateException.class, () -> steps.completeAfterVerification(f.runId()));
    }

    @Test void yamlLayoutChangeDoesNotInvalidateEffectivePlan() throws Exception {
        Fixture f = create(); var v = executeVerification(f);
        Files.writeString(root.resolve(".dsh/verify.yaml"), "# equivalent plan\nplanVersion: stable-v1\ntimeoutSeconds: 30\nexpectTests: true\ncommand: echo '2 passed in 0.01s'\n");
        assertEquals(v.getId(), service.run(f.runId())); stale(f, false);
    }

    @Test void onlyVerifiedInternalFeedbackIsExcludedFromGoalBinding() throws Exception {
        Fixture f = create(); var v = executeVerification(f);
        var feedback = new MessageEntity(IdGen.next("msg_"), f.threadId(), f.turnId(), "user",
                "[verification-fix:" + v.getId() + "]\ninternal diagnostic", 2L, Instant.now());
        messages.save(feedback);
        String projectId = threads.findById(f.threadId()).orElseThrow().getProjectId();
        context.markInternalFeedback(run(f), feedback, projectId);
        assertEquals(v.getId(), service.run(f.runId()));
        messages.save(new MessageEntity(IdGen.next("msg_"), f.threadId(), f.turnId(), "user",
                "[verification-fix:" + v.getId() + "]\n用户新增约束", 3L, Instant.now()));
        assertFalse(steps.verificationPassed(f.runId())); stale(f, true);
        assertNotEquals(v.getId(), service.run(f.runId()));
    }

    @ParameterizedTest
    @ValueSource(strings={"missing","same-size","append","symlink","metadata-hash","cross-run","cross-project","not-public"})
    void logDamageInvalidatesReuseApiAndCompletionAndAllowsFreshExecution(String damage) throws Exception {
        Fixture f=create(); var old=executeVerification(f);
        var artifact=artifacts.findById(old.getLogArtifactIds()).orElseThrow();
        Path file=workspaces.resolveByRef(run(f).getWorkspaceRef()).metaDir().resolve("artifacts").resolve(artifact.getId());
        byte[] original=Files.readAllBytes(file);
        if (damage.equals("missing")) Files.delete(file);
        else if (damage.equals("same-size")) {original[0]='X'; Files.write(file,original);}
        else if (damage.equals("append")) Files.writeString(file,"extra",java.nio.file.StandardOpenOption.APPEND);
        else if (damage.equals("symlink")) {
            Path target=root.resolve("log-target"); Files.write(target,original); Files.delete(file); Files.createSymbolicLink(file,target);
        } else {
            if (damage.equals("metadata-hash")) {original[0]='X'; Files.write(file,original);}
            artifacts.save(new ArtifactEntity(artifact.getId(),damage.equals("cross-run")?"run_other":artifact.getRunId(),
                    damage.equals("cross-project")?"proj_other":artifact.getProjectId(),artifact.getName(),artifact.getMediaType(),
                    artifact.getSizeBytes(),damage.equals("metadata-hash")?com.dsh.common.Digest.sha256Hex(original):artifact.getSha256(),
                    artifact.getCreatedAt(),!damage.equals("not-public")));
        }
        assertEquals(old.getWorkspaceDigest(),WorkspaceManager.digestOf(f.workspace()));
        assertFalse(steps.verificationPassed(f.runId())); stale(f,true);
        assertThrows(IllegalStateException.class,()->steps.completeAfterVerification(f.runId()));
        assertNotEquals(old.getId(),service.run(f.runId()));
        verify(sandbox,times(2)).execute(any(),any()); stale(f,false);
        assertTrue(steps.verificationPassed(f.runId()));
    }

    @Test void contextV1WithValidWorkspaceAndPolicyStillNeedsNewLogBinding() throws Exception {
        Fixture f=create(); var old=executeVerification(f); var a=bindingArtifact(f,old);
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)new com.fasterxml.jackson.databind.ObjectMapper().readTree(store.readContent(a.getId()));
        json.put("version","verification-context-v1"); json.remove("logArtifacts");
        artifacts.delete(a);
        store.save(f.runId(),threads.findById(f.threadId()).orElseThrow().getProjectId(),a.getName(),"application/json",
                json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),false);
        assertFalse(steps.verificationPassed(f.runId())); stale(f,true);
        assertNotEquals(old.getId(),service.run(f.runId()));
        verify(sandbox,times(2)).execute(any(),any());
    }
}
