package com.dsh.application;

import com.dsh.agent.LoopSteps;
import com.dsh.common.IdGen;
import com.dsh.storage.*;
import com.dsh.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SurefireVerificationIntegrationTest {
    @Autowired RunRepository runs; @Autowired TurnRepository turns; @Autowired ThreadRepository threads;
    @Autowired ProjectRepository projects; @Autowired MessageRepository messages;
    @Autowired VerificationRepository verifications; @Autowired VerificationService service;
    @Autowired VerificationEvidenceContext context; @Autowired WorkspaceManager workspaces;
    @Autowired ArtifactStore store; @Autowired LoopSteps steps;
    @TempDir Path root;
    static final String DIR="target/surefire-reports";
    void configure(String command) throws Exception {
        Files.createDirectories(root.resolve(".dsh"));
        Files.writeString(root.resolve(".dsh/verify.yaml"),"command: |-\n  "+command.replace("\n","\n  ")
                +"\nexpectTests: true\ntimeoutSeconds: 30\ntestReports:\n  format: MAVEN_SUREFIRE\n  directories: ["+DIR+"]\n");
    }
    static String writeReport(String xml) { return "mkdir -p "+DIR+"\ncat > "+DIR+"/TEST-Sample.xml <<'C32_XML'\n"+xml+"\nC32_XML"; }
    RunEntity create(String command) throws Exception {
        Files.writeString(root.resolve("fixture-report.sh"), command+"\n");
        configure("sh fixture-report.sh");Files.writeString(root.resolve("source.txt"),"C32A");var now=Instant.now();
        String pid=IdGen.next("proj_"),tid=IdGen.next("thr_"),turn=IdGen.next("turn_"),rid=IdGen.next("run_");
        var project=projects.save(new ProjectEntity(pid,"C32A",root.toString(),true,now,now));
        threads.save(new ThreadEntity(tid,pid,"C32A","unused",now,now));
        turns.save(new TurnEntity(turn,tid,rid,"verify reports","VERIFYING","PENDING",tid,now,now));
        var run=new RunEntity(rid,turn,null,"MAIN","VERIFYING","PENDING",1,IdGen.next("ws_"),"C32A",24,80,200000,now.plusSeconds(120),now,now);
        runs.save(run);var lease=workspaces.prepare(rid,project);run.setWorkspaceRef(lease.workspaceId());runs.save(run);
        messages.save(new MessageEntity(IdGen.next("msg_"),tid,turn,"user","verify reports",1L,now));return runs.findById(rid).orElseThrow();
    }
    VerificationEntity verifyRun(RunEntity run) { return verifications.findById(service.run(run.getId())).orElseThrow(); }
    String log(VerificationEntity v) { return new String(store.readContent(VerificationEvidenceContext.logArtifactIds(v).getFirst()),java.nio.charset.StandardCharsets.UTF_8); }
    @Test void quietCommandPassesWithActualReportAndSealedBytesThenReusesEvidence() throws Exception {
        var run=create(writeReport(SurefireReportsTest.xml(2,0,0,1)));var v=verifyRun(run);
        assertEquals("PASSED",v.getStatus());assertEquals(3,v.getTestsDiscovered());assertEquals(2,v.getTestsPassed());assertEquals(1,v.getTestsSkipped());
        assertEquals(2,VerificationEvidenceContext.logArtifactIds(v).size());assertTrue(log(v).contains("TEST_REPORTS=VALID"));
        assertTrue(context.isFresh(run,v));assertEquals(v.getId(),service.run(run.getId()));
        var reports=new com.fasterxml.jackson.databind.ObjectMapper().readTree(store.readContent(VerificationEvidenceContext.logArtifactIds(v).getLast()));
        byte[] bytes=java.util.Base64.getDecoder().decode(reports.path("reports").get(0).path("base64").asText());
        assertArrayEquals(Files.readAllBytes(workspaces.resolveByRef(run.getWorkspaceRef()).dir().resolve(DIR+"/TEST-Sample.xml")),bytes);
        steps.completeAfterVerification(run.getId());assertEquals("COMPLETED",runs.findById(run.getId()).orElseThrow().getState());
    }
    @Test void xmlFailureAndErrorCannotBeHiddenByExitZeroOrPassingLog() throws Exception {
        var run=create(writeReport(SurefireReportsTest.xml(1,1,1,1))+"\necho '100 passed in 0.01s'");var v=verifyRun(run);
        assertEquals(0,v.getExitCode());assertEquals(4,v.getTestsDiscovered());assertEquals(1,v.getTestsPassed());assertEquals("FAILED",v.getStatus());
        assertThrows(IllegalStateException.class,()->steps.completeAfterVerification(run.getId()));
    }
    @Test void reportedLogFailureAlsoVetoesPassingXml() throws Exception {
        var run=create(writeReport(SurefireReportsTest.xml(1,0,0,0))+"\necho '1 failed in 0.01s'");assertEquals("FAILED",verifyRun(run).getStatus());
    }
    @Test void unchangedOldReportCannotBeRescuedByPassingLog() throws Exception {
        var run=create("echo '1 passed in 0.01s'");var workspace=workspaces.resolveByRef(run.getWorkspaceRef()).dir();
        Files.createDirectories(workspace.resolve(DIR));Files.writeString(workspace.resolve(DIR+"/TEST-Sample.xml"),SurefireReportsTest.xml(1,0,0,0));
        var v=verifyRun(run);assertEquals("FAILED",v.getStatus());assertTrue(log(v).contains("REPORT_UNCHANGED"));
    }
    @Test void missingXmlNeverFallsBackToPassingSummary() throws Exception {
        var run=create("echo '5 passed in 0.01s'");var v=verifyRun(run);assertEquals("FAILED",v.getStatus());assertTrue(log(v).contains("REPORT_DIRECTORY_MISSING"));
    }
    @Test void allSkippedFailsEvenWhenExpectTestsIsFalse() throws Exception {
        var run=create(writeReport(SurefireReportsTest.xml(0,0,0,2)));
        var file=root.resolve(".dsh/verify.yaml");Files.writeString(file,Files.readString(file).replace("expectTests: true","expectTests: false"));
        var v=verifyRun(run);assertEquals("FAILED",v.getStatus());assertEquals(2,v.getTestsSkipped());assertTrue(log(v).contains("REPORT_ALL_SKIPPED"));
    }
    @Test void sealedBundleTamperingInvalidatesFreshnessAndCompletion() throws Exception {
        var run=create(writeReport(SurefireReportsTest.xml(1,0,0,0)));var v=verifyRun(run);assertTrue(context.isFresh(run,v));
        String id=VerificationEvidenceContext.logArtifactIds(v).getLast();
        Files.writeString(workspaces.resolveByRef(run.getWorkspaceRef()).metaDir().resolve("artifacts").resolve(id),"{}");
        assertFalse(context.isFresh(run,v));assertThrows(IllegalStateException.class,()->steps.completeAfterVerification(run.getId()));
    }
    @Test void reportConfigurationDriftChangesBothDigestsAndRejectsOldEvidence() throws Exception {
        var run=create(writeReport(SurefireReportsTest.xml(1,0,0,0)));var plan=VerificationPlan.forRun(run,root);var before=context.capture(run,plan);
        var v=verifyRun(run);String oldDisplay=plan.displayDigest();
        var file=root.resolve(".dsh/verify.yaml");Files.writeString(file,Files.readString(file).replace(DIR,"other/surefire-reports"));
        var after=VerificationPlan.forRun(run,root);assertNotEquals(oldDisplay,after.displayDigest());assertNotEquals(before.planDigest(),context.capture(run,after).planDigest());
        assertFalse(context.isFresh(run,v));assertEquals("FAILED",verifyRun(run).getStatus());
    }
    @Test void workspaceYamlCannotRemoveDeclaredReportsAndRegisteredGeneralRetainsThem() throws Exception {
        var run=create("echo '1 passed in 0.01s'");run.registerVerificationRequirement(RunVerificationRequirement.general());runs.save(run);
        var workspace=workspaces.resolveByRef(run.getWorkspaceRef()).dir();
        Files.writeString(workspace.resolve(".dsh/verify.yaml"),"command: echo '1 passed in 0.01s'\n");
        assertNotNull(VerificationPlan.forRun(run,root).testReports());assertEquals("FAILED",verifyRun(run).getStatus());
    }
    @Test void successfulXmlCannotHideNonzeroCommandExit() throws Exception {
        var run=create(writeReport(SurefireReportsTest.xml(1,0,0,0))+"\nexit 7");var v=verifyRun(run);
        assertEquals(1,v.getTestsPassed());assertEquals(7,v.getExitCode());assertEquals("FAILED",v.getStatus());
    }
}
