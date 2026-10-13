package com.dsh.application;

import com.dsh.agent.LoopSteps;
import com.dsh.common.IdGen;
import com.dsh.sandbox.SandboxRunner;
import com.dsh.storage.*;
import com.dsh.workspace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class RunVerificationIntegrationTest {
    @Autowired RunRepository runs; @Autowired TurnRepository turns; @Autowired ThreadRepository threads;
    @Autowired ProjectRepository projects; @Autowired MessageRepository messages;
    @Autowired VerificationRepository verifications; @Autowired VerificationService service;
    @Autowired VerificationEvidenceContext context; @Autowired WorkspaceManager workspaces;
    @Autowired LoopSteps steps;
    @MockitoSpyBean SandboxRunner sandbox;
    @TempDir Path root;
    static final String SOURCE="if [ \"$(cat source.txt)\" = fixed ]; then\necho 'DSH_DEFECT_V1 PASS sample'; exit 0\nelse\necho 'DSH_DEFECT_V1 FAIL sample'; exit 1\nfi\n";
    RunEntity create(boolean fixed) throws Exception {
        Files.createDirectories(root.resolve(".dsh"));Files.writeString(root.resolve(".dsh/verify.yaml"),"command: echo '1 passed in 0.01s'\nexpectTests: true\ntimeoutSeconds: 30\n");
        Files.writeString(root.resolve("source.txt"),"broken");var now=Instant.now();
        String pid=IdGen.next("proj_"),tid=IdGen.next("thr_"),turn=IdGen.next("turn_"),rid=IdGen.next("run_");
        var project=projects.save(new ProjectEntity(pid,"C29",root.toString(),true,now,now));
        threads.save(new ThreadEntity(tid,pid,"C29","unused",now,now));
        turns.save(new TurnEntity(turn,tid,rid,"repair source","VERIFYING","PENDING",tid,now,now));
        var run=new RunEntity(rid,turn,null,"MAIN","VERIFYING","PENDING",1,IdGen.next("ws_"),"C29",24,80,200000,now.plusSeconds(120),now,now);
        var requirement=new ObjectMapper().valueToTree(Map.of("purpose","DEFECT_FIX","acceptanceDescription","source must equal fixed",
                "defectReproduction",Map.of("assertionId","sample","fileName","check.sh","content",SOURCE,"command","sh \"$DSH_PROBE_FILE\"")));
        run.registerVerificationRequirement(RunVerificationRequirement.parse(requirement).encode());runs.save(run);
        var lease=workspaces.prepare(rid,project);run.setWorkspaceRef(lease.workspaceId());runs.save(run);
        messages.save(new MessageEntity(IdGen.next("msg_"),tid,turn,"user","repair source",1L,now));
        if(fixed)Files.writeString(lease.dir().resolve("source.txt"),"fixed");return run;
    }
    VerificationEntity verifyRun(RunEntity run) { return verifications.findById(service.run(run.getId())).orElseThrow(); }
    @Test void scopedDefinitionRunsBothViewsAndSupportsFreshCompletion() throws Exception {
        var run=create(true);var v=verifyRun(run);assertEquals("PASSED",v.getStatus());
        assertTrue(context.isFresh(run,v));assertEquals(4,VerificationEvidenceContext.logArtifactIds(v).size());
        verify(sandbox,times(3)).execute(any(),any());assertEquals(v.getId(),service.run(run.getId()));
        steps.completeAfterVerification(run.getId());assertEquals("COMPLETED",runs.findById(run.getId()).orElseThrow().getState());
    }
    @Test void unfixedScopedDefectFailsDespiteOrdinaryTestsPassing() throws Exception {
        var run=create(false);var v=verifyRun(run);assertEquals(1,v.getTestsPassed());assertEquals(0,v.getExitCode());
        assertEquals("FAILED",v.getStatus());assertFalse(steps.verificationPassed(run.getId()));
        assertThrows(IllegalStateException.class,()->steps.completeAfterVerification(run.getId()));
    }
    @Test void originalRegressionConfigurationChangeInvalidatesEvidenceButNotFrozenDefinition() throws Exception {
        var run=create(true);var v=verifyRun(run);String registration=run.getVerificationRequirement();
        Files.writeString(root.resolve(".dsh/verify.yaml"),"command: echo '2 passed in 0.01s'\nexpectTests: true\ntimeoutSeconds: 30\n");
        assertFalse(context.isFresh(run,v));assertFalse(steps.verificationPassed(run.getId()));
        assertNotEquals(v.getId(),service.runForCurrentWorkspace(run.getId()));
        assertEquals(registration,runs.findById(run.getId()).orElseThrow().getVerificationRequirement());
    }
    @Test void newHumanConstraintInvalidatesRegisteredEvidence() throws Exception {
        var run=create(true);var v=verifyRun(run);var turn=turns.findById(run.getTurnId()).orElseThrow();
        messages.save(new MessageEntity(IdGen.next("msg_"),turn.getThreadId(),turn.getId(),"user","also preserve existing encoding",2L,Instant.now()));
        assertFalse(context.isFresh(run,v));assertThrows(IllegalStateException.class,()->steps.completeAfterVerification(run.getId()));
    }
    @Test void workspaceYamlCannotReplaceOriginalRegressionOrRegisteredReproduction() throws Exception {
        var run=create(true);var workspace=workspaces.resolveByRef(run.getWorkspaceRef()).dir();
        Files.writeString(workspace.resolve(".dsh/verify.yaml"),"command: false\npurpose: GENERAL\n");
        var v=verifyRun(run);assertEquals("PASSED",v.getStatus());verify(sandbox,times(3)).execute(any(),any());
        assertEquals("DEFECT_FIX",VerificationPlan.forRun(run,root).purpose());
    }
}
