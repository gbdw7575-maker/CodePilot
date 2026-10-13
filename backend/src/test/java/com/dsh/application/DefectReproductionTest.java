package com.dsh.application;

import com.dsh.sandbox.*;
import com.dsh.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefectReproductionTest {
    @TempDir Path root;
    static final DefectReproductionPlan PLAN=new DefectReproductionPlan("dup_location","check.sh","echo example","sh \"$DSH_PROBE_FILE\"");
    CommandOutcome outcome(Integer exit,String out,String err,boolean truncated,boolean clean,CommandSpec.Termination t) {
        return new CommandOutcome("op",exit,t,out,err,truncated,1,clean);
    }
    @Test void explicitAssertionFailureAndPassAreDistinct() {
        assertTrue(DefectReproduction.valid(outcome(1,PLAN.marker(false),"",false,true,CommandSpec.Termination.COMPLETED),PLAN,false));
        assertTrue(DefectReproduction.valid(outcome(0,PLAN.marker(true),"",false,true,CommandSpec.Termination.COMPLETED),PLAN,true));
        assertFalse(DefectReproduction.valid(outcome(0,PLAN.marker(true),"",false,true,CommandSpec.Termination.COMPLETED),PLAN,false));
    }
    @ParameterizedTest @ValueSource(strings={"no-marker","wrong-id","duplicate","late-output","stderr","cleanup","truncated","unknown-exit","timeout","skipped","zero-tests","zero-decorated","zero-maven"})
    void incompleteOrEnvironmentalFailureIsNotReproduction(String type) {
        Integer code=type.equals("unknown-exit")?null:1;
        String out=switch(type) {
            case "no-marker" -> "Traceback: missing dependency";
            case "wrong-id" -> "DSH_DEFECT_V1 FAIL other";
            case "duplicate" -> PLAN.marker(false)+"\n"+PLAN.marker(false);
            case "late-output" -> PLAN.marker(false)+"\nmore output";
            case "zero-decorated" -> "===== no tests ran in 0.01s =====\n"+PLAN.marker(false);
            case "zero-maven" -> "Tests run: 0, Failures: 0, Errors: 0, Skipped: 0\n"+PLAN.marker(false);
            case "zero-tests" -> "no tests ran in 0.01s\n"+PLAN.marker(false);
            case "skipped" -> "1 skipped in 0.01s\n"+PLAN.marker(false);
            default -> PLAN.marker(false);
        };
        var t=type.equals("timeout")?CommandSpec.Termination.TIMEOUT:CommandSpec.Termination.COMPLETED;
        assertFalse(DefectReproduction.valid(outcome(code,out,type.equals("stderr")?"ImportError":"",type.equals("truncated"),!type.equals("cleanup"),t),PLAN,false));
    }
    @Test void unchangedSnapshotRunsSameCommandWithoutTouchingOriginals() throws Exception {
        Path candidate=root.resolve("workspace"),meta=root.resolve("meta"),base=meta.resolve("baseline-content");
        Files.createDirectories(candidate);Files.createDirectories(base);
        Files.writeString(candidate.resolve("source.txt"),"fixed");Files.writeString(base.resolve("source.txt"),"broken");
        Files.writeString(meta.resolve("baseline-manifest.json"),"{}");
        var manager=mock(WorkspaceManager.class);var sandbox=mock(SandboxRunner.class);
        when(manager.readBaselineManifest(meta)).thenReturn(WorkspaceManager.fileHashes(base));
        when(manager.baselineContentDir(meta)).thenReturn(base);
        when(sandbox.execute(any(),any())).thenAnswer(i->{
            var lease=(WorkspaceLease)i.getArgument(1);
            assertNotEquals(candidate,lease.dir());assertNotEquals(base,lease.dir());
            boolean fixed=Files.readString(lease.dir().resolve("source.txt")).equals("fixed");
            return outcome(fixed?0:1,PLAN.marker(fixed),"",false,true,CommandSpec.Termination.COMPLETED);
        });
        var lease=new WorkspaceLease("ws", "run","proj",root,candidate,meta,"baseline",Instant.now());
        var result=new DefectReproduction(manager,sandbox).execute(lease,PLAN,30);
        assertTrue(result.proven());assertEquals(2,result.observations().size());
        var captor=org.mockito.ArgumentCaptor.forClass(CommandSpec.class);
        verify(sandbox,times(2)).execute(captor.capture(),any());
        assertEquals(captor.getAllValues().get(0).command(),captor.getAllValues().get(1).command());
        assertEquals("fixed",Files.readString(candidate.resolve("source.txt")));
        assertEquals("broken",Files.readString(base.resolve("source.txt")));
        try(var paths=Files.list(meta)){assertFalse(paths.anyMatch(p->p.getFileName().toString().startsWith("defect-check-")));}
    }
    @ParameterizedTest @ValueSource(strings={"missing-content","damaged-content","oversize","escape","symlink"})
    void incompleteBaselineFailsBeforeAnyExecution(String damage) throws Exception {
        Path candidate=root.resolve("workspace"),meta=root.resolve("meta"),base=meta.resolve("baseline-content");
        Files.createDirectories(candidate);Files.createDirectories(base);
        Files.writeString(candidate.resolve("source.txt"),"fixed");Files.writeString(base.resolve("source.txt"),"broken");
        Files.writeString(meta.resolve("baseline-manifest.json"),"{}");
        Map<String,String> hashes=WorkspaceManager.fileHashes(base);
        switch(damage) {
            case "missing-content" -> Files.delete(base.resolve("source.txt"));
            case "damaged-content" -> Files.writeString(base.resolve("source.txt"),"tampered");
            case "oversize" -> Files.writeString(base.resolve("source.txt"),"a".repeat(1024*1024+1));
            case "escape" -> hashes=Map.of("../outside","0".repeat(64));
            case "symlink" -> {Files.delete(base.resolve("source.txt"));Files.createSymbolicLink(base.resolve("source.txt"),candidate.resolve("source.txt"));}
        }
        var manager=mock(WorkspaceManager.class);var sandbox=mock(SandboxRunner.class);
        when(manager.readBaselineManifest(meta)).thenReturn(hashes);when(manager.baselineContentDir(meta)).thenReturn(base);
        var lease=new WorkspaceLease("ws","run","proj",root,candidate,meta,"base",Instant.now());
        assertFalse(new DefectReproduction(manager,sandbox).execute(lease,PLAN,30).proven());
        verifyNoInteractions(sandbox);
    }
    @Test void expiredTimeBudgetDoesNotLaunchAnyAssertion() {
        var sandbox=mock(SandboxRunner.class);
        var result=new DefectReproduction(mock(WorkspaceManager.class),sandbox).execute(null,PLAN,0);
        assertEquals("TIME_BUDGET_EXPIRED",result.reason());assertFalse(result.proven());verifyNoInteractions(sandbox);
    }
    @Test void explicitPurposeDoesNotSilentlyDowngradeMissingDefinition() throws Exception {
        Files.createDirectories(root.resolve(".dsh"));
        Files.writeString(root.resolve(".dsh/verify.yaml"),"purpose: DEFECT_FIX\ncommand: echo fine\n");
        var plan=VerificationPlan.load(root);assertTrue(plan.defectFix());assertNull(plan.reproduction());
        assertFalse(new DefectReproduction(mock(WorkspaceManager.class),mock(SandboxRunner.class)).execute(null,null,30).proven());
    }
    @ParameterizedTest @ValueSource(strings={"invalid-purpose","general-with-definition","extra-field","bad-id","bad-file","too-large","empty-command"})
    void invalidDefectConfigurationCannotFallBackToGeneral(String error) throws Exception {
        Files.createDirectories(root.resolve(".dsh"));
        var def=new java.util.LinkedHashMap<String,Object>(Map.of("assertionId","dup_location","fileName","check.sh","content","echo fine","command","sh \"$DSH_PROBE_FILE\""));
        String purpose="DEFECT_FIX";
        switch(error) {
            case "invalid-purpose" -> purpose="AUTO";
            case "general-with-definition" -> purpose="GENERAL";
            case "extra-field" -> def.put("extra",true);
            case "bad-id" -> def.put("assertionId","invalid id");
            case "bad-file" -> def.put("fileName","../check.sh");
            case "too-large" -> def.put("content","x".repeat(16385));
            case "empty-command" -> def.put("command","");
        }
        String yaml=new org.yaml.snakeyaml.Yaml().dump(Map.of("purpose",purpose,"defectReproduction",def));
        Files.writeString(root.resolve(".dsh/verify.yaml"),yaml);
        assertThrows(IllegalArgumentException.class,()->VerificationPlan.load(root));
    }
}
