package com.dsh.application;

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
class GradleVerificationIntegrationTest {
    @Autowired RunRepository runs; @Autowired TurnRepository turns; @Autowired ThreadRepository threads;
    @Autowired ProjectRepository projects; @Autowired MessageRepository messages;
    @Autowired VerificationRepository verifications; @Autowired VerificationService service;
    @Autowired VerificationEvidenceContext context; @Autowired WorkspaceManager workspaces;
    @Autowired ArtifactStore store;
    @TempDir Path root;
    static final String DIR="build/test-results/test";
    RunEntity create(String script) throws Exception {
        Files.createDirectories(root.resolve(".dsh"));
        Files.writeString(root.resolve(".dsh/verify.yaml"),"command: sh report.sh\nexpectTests: true\ntimeoutSeconds: 30\ntestReports:\n  format: GRADLE\n  directories: ["+DIR+"]\n");
        Files.writeString(root.resolve("report.sh"),script+"\n");var now=Instant.now();
        String pid=IdGen.next("proj_"),tid=IdGen.next("thr_"),turn=IdGen.next("turn_"),rid=IdGen.next("run_");
        var p=projects.save(new ProjectEntity(pid,"C32B",root.toString(),true,now,now));
        threads.save(new ThreadEntity(tid,pid,"C32B","unused",now,now));
        turns.save(new TurnEntity(turn,tid,rid,"Gradle reports","VERIFYING","PENDING",tid,now,now));
        var run=new RunEntity(rid,turn,null,"MAIN","VERIFYING","PENDING",1,IdGen.next("ws_"),"C32B",24,80,200000,now.plusSeconds(120),now,now);
        runs.save(run);run.setWorkspaceRef(workspaces.prepare(rid,p).workspaceId());runs.save(run);
        messages.save(new MessageEntity(IdGen.next("msg_"),tid,turn,"user","Gradle reports",1L,now));return runs.findById(rid).orElseThrow();
    }
    static String script(String timestamp,int passed,int failed,int errors,int skipped) {
        return "mkdir -p "+DIR+"\ncat <<'XML' | sed \"s/C32B_NOW/$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)/\" > "+DIR+"/TEST-A.xml\n"
                +GradleReportsTest.xml(timestamp,passed,failed,errors,skipped)+"\nXML";
    }
    VerificationEntity verify(RunEntity run) {return verifications.findById(service.run(run.getId())).orElseThrow();}
    String log(VerificationEntity v) {return new String(store.readContent(VerificationEvidenceContext.logArtifactIds(v).getFirst()),java.nio.charset.StandardCharsets.UTF_8);}
    @Test void quietFreshReportPassesAndSealsGradleIdentity() throws Exception {
        var run=create(script("C32B_NOW",2,0,0,1));var v=verify(run);
        assertEquals("PASSED",v.getStatus());assertEquals(3,v.getTestsDiscovered());assertEquals(2,v.getTestsPassed());assertEquals(1,v.getTestsSkipped());
        assertTrue(context.isFresh(run,v));assertEquals(v.getId(),service.run(run.getId()));
        var seal=new com.fasterxml.jackson.databind.ObjectMapper().readTree(store.readContent(VerificationEvidenceContext.logArtifactIds(v).getLast()));
        assertEquals("GRADLE",seal.path("format").asText());assertEquals("gradle-reports-v1",seal.path("version").asText());
    }
    @Test void restoredOldExecutionWithNewFileAndPassingLogFails() throws Exception {
        var run=create(script("2000-01-01T00:00:00Z",1,0,0,0)+"\necho '100 passed in 0.01s'");
        var workspace=workspaces.resolveByRef(run.getWorkspaceRef()).dir();assertFalse(Files.exists(workspace.resolve(DIR)));
        var v=verify(run);assertEquals(0,v.getExitCode());assertEquals("FAILED",v.getStatus());assertTrue(log(v).contains("REPORT_EXECUTION_TIME_STALE"));
    }
    @Test void freshXmlFailureAndErrorCannotBeMaskedByExitZero() throws Exception {
        var run=create(script("C32B_NOW",1,1,1,1));var v=verify(run);
        assertEquals(0,v.getExitCode());assertEquals("FAILED",v.getStatus());assertEquals(4,v.getTestsDiscovered());assertEquals(1,v.getTestsPassed());
    }
    @Test void changingOnlyFormatInvalidatesEffectivePlanAndExistingSeal() throws Exception {
        var run=create(script("C32B_NOW",1,0,0,0));var v=verify(run);var before=context.capture(run,VerificationPlan.forRun(run,root));
        Path config=root.resolve(".dsh/verify.yaml");Files.writeString(config,Files.readString(config).replace("GRADLE","MAVEN_SUREFIRE"));
        var after=context.capture(run,VerificationPlan.forRun(run,root));assertNotEquals(before.planDigest(),after.planDigest());assertFalse(context.isFresh(run,v));
    }
}
