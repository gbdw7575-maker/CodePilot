package com.dsh.api;

import com.dsh.agent.CompletionReviewService;
import com.dsh.application.*;
import com.dsh.orchestration.ChildRunFactory;
import com.dsh.storage.*;
import com.dsh.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class RunVerificationRegistrationApiTest extends ApiTestBase {
    @Autowired RunRepository runs; @Autowired TurnRepository turns; @Autowired MessageRepository messages;
    @Autowired ThreadRepository threads; @Autowired ModelProfileRepository profiles;
    @Autowired CompletionReviewService completion; @Autowired ChildRunFactory children;
    @Autowired WorkspaceManager workspaces;
    @TempDir Path root;
    static final String SOURCE="if [ \"$(cat source.txt)\" = fixed ]; then\necho 'DSH_DEFECT_V1 PASS sample'; exit 0\nelse\necho 'DSH_DEFECT_V1 FAIL sample'; exit 1\nfi\n";
    String projectId; String profileId;
    String thread() throws Exception {
        if(projectId==null){
            Files.writeString(root.resolve("source.txt"),"broken");
            Files.createDirectories(root.resolve(".dsh"));
            Files.writeString(root.resolve(".dsh/verify.yaml"),"command: echo '1 passed in 0.01s'\nexpectTests: true\ntimeoutSeconds: 30\n");
            projectId=createProject("C29",root.toString());trustProject(projectId,1);
            profileId=createProfile("C29 waiting");
        }
        return createThread(projectId,profileId);
    }
    Map<String,Object> requirement() {
        return Map.of("purpose","DEFECT_FIX","acceptanceDescription","只接受 source.txt 的值为 fixed",
                "defectReproduction",Map.of("assertionId","sample","fileName","check.sh","content",SOURCE,"command","sh \"$DSH_PROBE_FILE\""));
    }
    String body(Object verification) throws Exception { return mapper.writeValueAsString(Map.of("input","修复 source.txt","verification",verification)); }
    org.springframework.test.web.servlet.MvcResult submit(String tid,String key,String body) throws Exception {
        return postJson("/api/v1/threads/"+tid+"/turns",body,key);
    }
    String accepted(String tid,Object requirement) throws Exception {
        var response=submit(tid,uniqueKey("c29-turn"),body(requirement));assertEquals(202,response.getResponse().getStatus());
        return jsonField(response,"rootRunId");
    }
    @Test void registrationIsPersistedBeforeSchedulingAndShownInEffectivePrompt() throws Exception {
        String rid=accepted(thread(),requirement());var run=runs.findById(rid).orElseThrow();
        assertEquals("WAITING_INPUT",run.getState()); // Fake already ran after commit.
        assertEquals("DEFECT_FIX",RunVerificationRequirement.decode(run.getVerificationRequirement()).purpose());
        var frozen = workspaces.resolveByRef(run.getWorkspaceRef());
        assertEquals("broken",Files.readString(frozen.dir().resolve("source.txt")));
        assertEquals("broken",Files.readString(workspaces.baselineContentDir(frozen.metaDir()).resolve("source.txt")));
        Files.writeString(root.resolve("source.txt"),"changed-after-start");
        assertEquals("broken",Files.readString(frozen.dir().resolve("source.txt")));
        var prompt=completion.entryPrompt(completion.entryContext(run));
        assertTrue(prompt.contains("只接受 source.txt 的值为 fixed"));assertTrue(prompt.contains("登记断言=sample"));
        var response=mockMvc.perform(get("/api/v1/runs/"+rid+"/verification-plan")).andReturn();
        assertEquals(200,response.getResponse().getStatus());assertEquals("RUN_REGISTRATION",jsonField(response,"source"));
        assertEquals(SOURCE,mapper.readTree(response.getResponse().getContentAsString()).path("defectReproduction").path("content").asText());
    }
    @ParameterizedTest @ValueSource(strings={"null","{}","[]","{\"purpose\":\"DEFECT_FIX\"}","{\"purpose\":\"AUTO\"}",
            "{\"purpose\":\"GENERAL\",\"extra\":true}","{\"purpose\":\"GENERAL\",\"defectReproduction\":{}}"})
    void invalidDeclarationHasNoRunTurnOrMessageSideEffects(String invalid) throws Exception {
        String tid=thread();long r=runs.count(),t=turns.count(),m=messages.count();
        var response=submit(tid,uniqueKey("c29-invalid"),"{\"input\":\"修复\",\"verification\":"+invalid+"}");
        assertEquals(400,response.getResponse().getStatus());assertEquals("VALIDATION_FAILED",jsonField(response,"code"));
        assertEquals(r,runs.count());assertEquals(t,turns.count());assertEquals(m,messages.count());
        assertNull(threads.findById(tid).orElseThrow().getActiveTurnId());
    }
    @Test void previewIsReadOnlyAndConfigurationDriftIsRejectedBeforeCreatingResources() throws Exception {
        String tid=thread();long count=runs.count();
        var preview=mockMvc.perform(get("/api/v1/projects/"+projectId+"/verification-plan")).andReturn();
        assertEquals(200,preview.getResponse().getStatus());assertEquals(count,runs.count());String digest=jsonField(preview,"digest");
        Files.writeString(root.resolve(".dsh/verify.yaml"),"command: echo changed\nexpectTests: false\n");
        var response=submit(tid,uniqueKey("c29-drift"),mapper.writeValueAsString(Map.of("input","说明文档","projectPlanDigest",digest)));
        assertEquals(412,response.getResponse().getStatus());assertEquals(count,runs.count());assertNull(threads.findById(tid).orElseThrow().getActiveTurnId());
        var current=mockMvc.perform(get("/api/v1/projects/"+projectId+"/verification-plan")).andReturn();
        var fresh=submit(tid,uniqueKey("c29-fresh"),mapper.writeValueAsString(Map.of("input","说明文档","projectPlanDigest",jsonField(current,"digest"))));
        assertEquals(202,fresh.getResponse().getStatus());
    }
    @Test void invalidPlanVersionRejectsPreviewAndDigestSubmissionWithoutResourceEffects() throws Exception {
        String tid=thread();var preview=mockMvc.perform(get("/api/v1/projects/"+projectId+"/verification-plan")).andReturn();
        String digest=jsonField(preview,"digest");long r=runs.count(),t=turns.count(),m=messages.count();
        Path file=root.resolve(".dsh/verify.yaml");String original=Files.readString(file);
        for(String value:java.util.List.of("a".repeat(65),"a".repeat(64)+" ","😀".repeat(65),"v\0x")){
            Files.writeString(file,original+"planVersion: "+mapper.writeValueAsString(value)+"\n");
            var rejectedPreview=mockMvc.perform(get("/api/v1/projects/"+projectId+"/verification-plan")).andReturn();
            assertEquals(400,rejectedPreview.getResponse().getStatus());assertEquals("VALIDATION_FAILED",jsonField(rejectedPreview,"code"));
            assertFalse(rejectedPreview.getResponse().getContentAsString().contains("a".repeat(65)));
            var rejected=submit(tid,uniqueKey("c33a2-invalid"),mapper.writeValueAsString(Map.of("input","验收","projectPlanDigest",digest)));
            assertEquals(400,rejected.getResponse().getStatus());assertEquals("VALIDATION_FAILED",jsonField(rejected,"code"));
            assertEquals(r,runs.count());assertEquals(t,turns.count());assertEquals(m,messages.count());
            assertNull(threads.findById(tid).orElseThrow().getActiveTurnId());
        }
    }
    @Test void previewPreservesUnicodeBoundaryAndOnlyVersionDriftRejectsSubmission() throws Exception {
        String tid=thread(),value="😀".repeat(64);Path file=root.resolve(".dsh/verify.yaml");String original=Files.readString(file);
        Files.writeString(file,original+"planVersion: "+mapper.writeValueAsString(value)+"\n");
        var preview=mockMvc.perform(get("/api/v1/projects/"+projectId+"/verification-plan")).andReturn();
        assertEquals(200,preview.getResponse().getStatus());assertEquals(value,jsonField(preview,"planVersion"));
        Files.writeString(file,original+"planVersion: "+mapper.writeValueAsString("😀".repeat(63)+"🙂")+"\n");
        long r=runs.count(),t=turns.count(),m=messages.count();
        var rejected=submit(tid,uniqueKey("c33a2-drift"),mapper.writeValueAsString(Map.of("input","验收","projectPlanDigest",jsonField(preview,"digest"))));
        assertEquals(412,rejected.getResponse().getStatus());assertEquals(r,runs.count());assertEquals(t,turns.count());assertEquals(m,messages.count());
        assertNull(threads.findById(tid).orElseThrow().getActiveTurnId());
    }
    @Test void reportDirectoriesAreDisplayedAndTheirOnlyChangeRejectsOldPreview() throws Exception {
        String tid=thread();Path file=root.resolve(".dsh/verify.yaml");String original=Files.readString(file);
        Files.writeString(file,original+"testReports:\n  format: MAVEN_SUREFIRE\n  directories: [target/surefire-reports]\n");
        var preview=mockMvc.perform(get("/api/v1/projects/"+projectId+"/verification-plan")).andReturn();
        assertEquals(200,preview.getResponse().getStatus());
        var view=mapper.readTree(preview.getResponse().getContentAsString());
        assertEquals("MAVEN_SUREFIRE",view.path("testReports").path("format").asText());
        assertEquals("target/surefire-reports",view.path("testReports").path("directories").get(0).asText());
        long count=runs.count();String digest=view.path("digest").asText();
        Files.writeString(file,Files.readString(file).replace("target/surefire-reports","module/target/surefire-reports"));
        var rejected=submit(tid,uniqueKey("c32-report-drift"),mapper.writeValueAsString(Map.of("input","验收","projectPlanDigest",digest)));
        assertEquals(412,rejected.getResponse().getStatus());assertEquals(count,runs.count());
        Files.writeString(file,original+"testReports:\n  format: UNKNOWN\n  directories: [build/test-results/test]\n");
        assertEquals(400,mockMvc.perform(get("/api/v1/projects/"+projectId+"/verification-plan")).andReturn().getResponse().getStatus());
    }
    @Test void gradleTaskDirectoriesArePreviewedAndFormatDriftRejectsSubmission() throws Exception {
        String tid=thread();Path file=root.resolve(".dsh/verify.yaml");String original=Files.readString(file);
        Files.writeString(file,original+"testReports:\n  format: GRADLE\n  directories: [build/test-results/test, build/test-results/integrationTest]\n");
        var preview=mockMvc.perform(get("/api/v1/projects/"+projectId+"/verification-plan")).andReturn();
        assertEquals(200,preview.getResponse().getStatus());var view=mapper.readTree(preview.getResponse().getContentAsString());
        assertEquals("GRADLE",view.path("testReports").path("format").asText());assertEquals(2,view.path("testReports").path("directories").size());
        Files.writeString(file,Files.readString(file).replace("format: GRADLE","format: MAVEN_SUREFIRE"));
        long count=runs.count();var rejected=submit(tid,uniqueKey("c32b-format-drift"),mapper.writeValueAsString(
                Map.of("input","format drift","projectPlanDigest",view.path("digest").asText())));
        assertEquals(412,rejected.getResponse().getStatus());assertEquals(count,runs.count());
    }
    @Test void idempotencyReplaysSameDefinitionAndRejectsDifferentDefinition() throws Exception {
        String tid=thread(),key=uniqueKey("c29-replay"),json=body(requirement());long count=runs.count();
        var first=submit(tid,key,json);assertEquals(202,first.getResponse().getStatus());
        var second=submit(tid,key,json);assertEquals(first.getResponse().getContentAsString(),second.getResponse().getContentAsString());
        var conflict=submit(tid,key,body(Map.of("purpose","GENERAL")));assertEquals(409,conflict.getResponse().getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT",jsonField(conflict,"code"));assertEquals(count+1,runs.count());
    }
    @Test void appendCannotReplaceRequirementAndWorkspaceConfigurationCannotDowngradeIt() throws Exception {
        String rid=accepted(thread(),requirement());var run=runs.findById(rid).orElseThrow();String original=run.getVerificationRequirement();
        long count=messages.count();
        var append=postJson("/api/v1/turns/"+run.getTurnId()+"/inputs",body(Map.of("purpose","GENERAL")),uniqueKey("c29-append"));
        assertEquals(400,append.getResponse().getStatus());assertEquals(count,messages.count());
        assertThrows(IllegalStateException.class,()->run.registerVerificationRequirement(RunVerificationRequirement.general()));
        var workspace=workspaces.resolveByRef(run.getWorkspaceRef()).dir();
        Files.createDirectories(workspace.resolve(".dsh"));Files.writeString(workspace.resolve(".dsh/verify.yaml"),"purpose: GENERAL\n");
        assertTrue(VerificationPlan.forRun(runs.findById(rid).orElseThrow(),root).defectFix());
        assertEquals(original,runs.findById(rid).orElseThrow().getVerificationRequirement());
    }
    @Test void sameProjectCanHoldIndependentDefectAndGeneralRuns() throws Exception {
        String a=accepted(thread(),requirement()),b=accepted(thread(),Map.of("purpose","GENERAL"));
        assertTrue(VerificationPlan.forRun(runs.findById(a).orElseThrow(),root).defectFix());
        assertFalse(VerificationPlan.forRun(runs.findById(b).orElseThrow(),root).defectFix());
    }
    @Test void registeredRootDoesNotImposeItsDefectGateOnWorkers() throws Exception {
        String rid=accepted(thread(),requirement());var parent=runs.findById(rid).orElseThrow();
        var parentThread=threads.findById(turns.findById(parent.getTurnId()).orElseThrow().getThreadId()).orElseThrow();
        String childId=com.dsh.common.IdGen.next("run_");
        children.create(childId,parent,parentThread,profiles.findById(profileId).orElseThrow(),"TESTER","仅整理测试说明","ws_unused",2,4,10000,Instant.now().plusSeconds(60));
        var child=runs.findById(childId).orElseThrow();
        assertEquals("GENERAL",RunVerificationRequirement.decode(child.getVerificationRequirement()).purpose());
        assertFalse(VerificationPlan.forRun(child,root).defectFix());assertTrue(VerificationPlan.forRun(parent,root).defectFix());
    }
    @Test void legacySubmissionStillUsesProjectConfigurationAndWorkersKeepLegacyNull() throws Exception {
        String tid=thread();Files.writeString(root.resolve(".dsh/verify.yaml"),"command: echo '1 passed'\npurpose: DEFECT_FIX\n");
        var response=submit(tid,uniqueKey("c29-legacy"),"{\"input\":\"旧请求\"}");assertEquals(202,response.getResponse().getStatus());
        var parent=runs.findById(jsonField(response,"rootRunId")).orElseThrow();assertNull(parent.getVerificationRequirement());
        assertTrue(VerificationPlan.forRun(parent,root).defectFix());assertNull(VerificationPlan.forRun(parent,root).reproduction());
        String childId=com.dsh.common.IdGen.next("run_");var parentThread=threads.findById(tid).orElseThrow();
        children.create(childId,parent,parentThread,profiles.findById(profileId).orElseThrow(),"CODER","旧子任务","ws_unused",2,4,10000,Instant.now().plusSeconds(60));
        assertNull(runs.findById(childId).orElseThrow().getVerificationRequirement());
    }
    @Test void noChangeAnswerCannotBypassRegisteredDefectVerification() throws Exception {
        thread();String answerProfile=createProfile("C29 answer","fake-answer");
        String defectThread=createThread(projectId,answerProfile);
        String rid=accepted(defectThread,requirement());var failed=runs.findById(rid).orElseThrow();
        assertEquals("FAILED",failed.getState());assertEquals("FAILED",failed.getVerificationStatus());
        assertNotEquals("NO_CHANGE",failed.getCompletionBasis());
        String generalThread=createThread(projectId,answerProfile);
        String general=accepted(generalThread,Map.of("purpose","GENERAL"));var completed=runs.findById(general).orElseThrow();
        assertEquals("COMPLETED",completed.getState());assertEquals("NOT_REQUIRED",completed.getVerificationStatus());
        assertEquals("NO_CHANGE",completed.getCompletionBasis());
    }
    @Test void losingFrozenWorkspaceCannotSelectANewBaselineOrCallAnotherModel() throws Exception {
        String rid=accepted(thread(),requirement());var before=runs.findById(rid).orElseThrow();
        Path workspace=workspaces.resolveByRef(before.getWorkspaceRef()).dir();
        Path lost=workspace.resolveSibling(workspace.getFileName()+"-lost");
        assertFalse(Files.exists(lost));Files.move(workspace,lost);
        var result=postJson("/api/v1/turns/"+before.getTurnId()+"/inputs","{\"input\":\"继续修复\"}",uniqueKey("c29-lost"));
        assertEquals(202,result.getResponse().getStatus());var after=runs.findById(rid).orElseThrow();
        assertEquals("FAILED",after.getState());assertEquals("ENVIRONMENT_ERROR",after.getStopReason());
        assertEquals(before.getBudgetModelRequestsUsed(),after.getBudgetModelRequestsUsed());assertFalse(Files.exists(workspace));
    }
    @Test void untrustedAndMissingProjectsCannotExposeVerificationSource() throws Exception {
        thread();String untrusted=createProject("untrusted",root.toString());
        assertEquals(403,mockMvc.perform(get("/api/v1/projects/"+untrusted+"/verification-plan")).andReturn().getResponse().getStatus());
        assertEquals(404,mockMvc.perform(get("/api/v1/projects/no_such_project/verification-plan")).andReturn().getResponse().getStatus());
        assertEquals(404,mockMvc.perform(get("/api/v1/runs/no_such_run/verification-plan")).andReturn().getResponse().getStatus());
    }
}
