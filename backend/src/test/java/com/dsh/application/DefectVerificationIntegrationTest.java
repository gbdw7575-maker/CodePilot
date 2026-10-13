package com.dsh.application;

import com.dsh.agent.LoopSteps;
import com.dsh.common.IdGen;
import com.dsh.sandbox.*;
import com.dsh.storage.*;
import com.dsh.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
class DefectVerificationIntegrationTest {
    @Autowired RunRepository runs; @Autowired TurnRepository turns; @Autowired ThreadRepository threads;
    @Autowired ProjectRepository projects; @Autowired MessageRepository messages;
    @Autowired ArtifactRepository artifacts; @Autowired VerificationRepository verifications;
    @Autowired VerificationService service; @Autowired WorkspaceManager workspaces;
    @Autowired VerificationEvidenceContext context; @Autowired ArtifactStore store; @Autowired LoopSteps steps;
    @MockitoSpyBean SandboxRunner sandbox;
    @TempDir Path root;
    static final String SOURCE="if [ \"$(cat source.txt)\" = fixed ]; then\n"
            +"echo 'DSH_DEFECT_V1 PASS sample'; exit 0\nelse\necho 'DSH_DEFECT_V1 FAIL sample'; exit 1\nfi\n";
    record Fixture(String runId,String threadId,String turnId,Path workspace,Path meta) {}
    String yaml(String purpose,String source) {
        var config=new java.util.LinkedHashMap<String,Object>();
        config.put("command","echo '1 passed in 0.01s'");config.put("expectTests",true);
        config.put("timeoutSeconds",30);config.put("planVersion","c28-v1");config.put("purpose",purpose);
        if(source!=null)config.put("defectReproduction",Map.of("assertionId","sample","fileName","check.sh",
                "content",source,"command","sh \"$DSH_PROBE_FILE\""));
        return new org.yaml.snakeyaml.Yaml().dump(config);
    }
    Fixture create(String purpose,String source,boolean fixed) throws Exception {
        Files.createDirectories(root.resolve(".dsh"));Files.writeString(root.resolve(".dsh/verify.yaml"),yaml(purpose,source));
        Files.writeString(root.resolve("source.txt"),"broken");
        var now=Instant.now();String pid=IdGen.next("proj_"),tid=IdGen.next("thr_"),turn=IdGen.next("turn_"),rid=IdGen.next("run_");
        var project=projects.save(new ProjectEntity(pid,"C28",root.toString(),true,now,now));
        threads.save(new ThreadEntity(tid,pid,"C28","unused",now,now));
        turns.save(new TurnEntity(turn,tid,rid,"repair duplicated values","VERIFYING","PENDING",tid,now,now));
        var run=new RunEntity(rid,turn,null,"MAIN","VERIFYING","PENDING",1,IdGen.next("ws_"),"C28",24,80,200000,now.plusSeconds(120),now,now);
        runs.save(run);var lease=workspaces.prepare(rid,project);run.setWorkspaceRef(lease.workspaceId());runs.save(run);
        messages.save(new MessageEntity(IdGen.next("msg_"),tid,turn,"user","repair duplicated values",1L,now));
        if(fixed)Files.writeString(lease.dir().resolve("source.txt"),"fixed");
        return new Fixture(rid,tid,turn,lease.dir(),lease.metaDir());
    }
    VerificationEntity executeVerification(Fixture f) {return verifications.findById(service.run(f.runId())).orElseThrow();}
    @Test void actualBaselineFailureAndCandidatePassAllowFreshCompletion() throws Exception {
        var f=create("DEFECT_FIX",SOURCE,true);var before=WorkspaceManager.digestOf(f.workspace());var v=executeVerification(f);
        assertEquals("PASSED",v.getStatus());assertEquals(1,v.getTestsPassed());assertTrue(context.isFresh(runs.findById(f.runId()).orElseThrow(),v));
        assertEquals(4,VerificationEvidenceContext.logArtifactIds(v).size());assertEquals(before,WorkspaceManager.digestOf(f.workspace()));
        assertEquals("broken",Files.readString(workspaces.baselineContentDir(f.meta()).resolve("source.txt")));
        assertEquals(v.getId(),service.run(f.runId()));verify(sandbox,times(3)).execute(any(),any());
        assertTrue(steps.verificationPassed(f.runId()));steps.completeAfterVerification(f.runId());
        assertEquals("COMPLETED",runs.findById(f.runId()).orElseThrow().getState());
        try(var files=Files.list(f.meta())){assertFalse(files.anyMatch(p->p.getFileName().toString().startsWith("defect-check-")));}
    }
    @ParameterizedTest @ValueSource(strings={"unfixed","tests-only","always-green","definition-missing","environment-error"})
    void ordinaryGreenCannotReplaceAnUnprovenFix(String failure) throws Exception {
        String source=switch(failure) {
            case "definition-missing" -> null;
            case "always-green" -> "echo 'DSH_DEFECT_V1 PASS sample'; exit 0\n";
            case "environment-error" -> "cat missing-dependency\n";
            default -> SOURCE;
        };
        var f=create("DEFECT_FIX",source,false);
        if(failure.equals("tests-only"))Files.writeString(f.workspace().resolve("new-test.txt"),"test without source fix");
        var v=executeVerification(f);assertEquals(1,v.getTestsPassed());assertEquals(0,v.getExitCode());
        assertEquals("FAILED",v.getStatus());assertFalse(steps.verificationPassed(f.runId()));
        assertThrows(IllegalStateException.class,()->steps.completeAfterVerification(f.runId()));
        assertTrue(store.readTextPage(VerificationEvidenceContext.logArtifactIds(v).getFirst(),0,4096).text().contains("UNPROVEN"));
    }
    @Test void generalDocumentationTaskKeepsExistingVerificationWithoutSourceChange() throws Exception {
        var f=create("GENERAL",null,false);var v=executeVerification(f);
        assertEquals("PASSED",v.getStatus());assertEquals(1,VerificationEvidenceContext.logArtifactIds(v).size());
        verify(sandbox,times(1)).execute(any(),any());assertTrue(steps.verificationPassed(f.runId()));
    }
    @ParameterizedTest @ValueSource(strings={"definition","goal","candidate","baseline-manifest","source-artifact","baseline-log"})
    void evidenceDriftInvalidatesReuseAndCompletion(String damage) throws Exception {
        var f=create("DEFECT_FIX",SOURCE,true);var v=executeVerification(f);
        switch(damage) {
            case "definition" -> Files.writeString(root.resolve(".dsh/verify.yaml"),yaml("DEFECT_FIX",SOURCE+"# changed assertion identity\n"));
            case "goal" -> messages.save(new MessageEntity(IdGen.next("msg_"),f.threadId(),f.turnId(),"user","new constraint",2L,Instant.now()));
            case "candidate" -> Files.writeString(f.workspace().resolve("source.txt"),"changed-again");
            case "baseline-manifest" -> Files.delete(f.meta().resolve("baseline-manifest.json"));
            default -> {
                var ids=VerificationEvidenceContext.logArtifactIds(v);String id=ids.get(damage.equals("source-artifact")?1:2);
                Path artifact=f.meta().resolve("artifacts").resolve(id);byte[] bytes=Files.readAllBytes(artifact);bytes[0]='X';Files.write(artifact,bytes);
            }
        }
        assertFalse(steps.verificationPassed(f.runId()));
        assertThrows(IllegalStateException.class,()->steps.completeAfterVerification(f.runId()));
        if(damage.equals("definition")||damage.equals("goal")||damage.equals("source-artifact")||damage.equals("baseline-log")){
            assertNotEquals(v.getId(),service.runForCurrentWorkspace(f.runId()));
            verify(sandbox,times(6)).execute(any(),any());assertTrue(steps.verificationPassed(f.runId()));
        }
    }
    @Test void mutationOfOriginalCandidateDuringComparisonIsNotAccepted() throws Exception {
        var f=create("DEFECT_FIX",SOURCE,true);
        doAnswer(i->{Object value=i.callRealMethod();var lease=(WorkspaceLease)i.getArgument(1);
            if(lease.dir().getFileName().toString().equals("candidate"))Files.writeString(f.workspace().resolve("source.txt"),"changed");
            return value;}).when(sandbox).execute(any(),any());
        var v=executeVerification(f);assertEquals("FAILED",v.getStatus());assertFalse(steps.verificationPassed(f.runId()));
    }
    @Test void assertionCannotModifyTrackedFilesAndUseItsOwnMutationAsProof() throws Exception {
        var f=create("DEFECT_FIX","echo fixed > source.txt\necho 'DSH_DEFECT_V1 FAIL sample'; exit 1\n",true);
        var v=executeVerification(f);assertEquals("FAILED",v.getStatus());assertEquals("fixed",Files.readString(f.workspace().resolve("source.txt")));
        verify(sandbox,times(2)).execute(any(),any());
    }
}
