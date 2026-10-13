package com.dsh.evals;

import com.dsh.agent.model.FakeModelGateway;
import com.dsh.api.dto.*;
import com.dsh.application.*;
import com.dsh.evals.model.*;
import com.dsh.storage.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest(properties={"dsh.orchestration.enabled=false","dsh.memory.enabled=false","dsh.episodic.enabled=false"})
class EvalPreflightIntegrationTest {
    @Autowired EvalRunner runner;@Autowired ModelProfileService profiles;@Autowired ModelProfileRepository profileRows;
    @Autowired RunRepository runs;@Autowired EvalLaunchGate gate;
    @MockitoSpyBean FakeModelGateway gateway;@MockitoSpyBean EvalGrader grader;
    @MockitoSpyBean TurnService turns;@MockitoSpyBean EvalReportWriter writer;
    @TempDir Path root;
    EvalConfig config;EvalDataset dataset;Path baseline,reports;
    @BeforeEach void setup()throws Exception{
        baseline=Files.createDirectory(root.resolve("baseline"));Files.writeString(baseline.resolve("README.md"),"fixture source\n");
        Files.writeString(baseline.resolve("AGENTS.md"),"Use readable code.\n");reports=root.resolve("reports");
        var profile=profiles.create(new ModelProfileWrite("c15-fake",Provider.fake,"fake","http://127.0.0.1:9",null,new ModelOptions(ModelOptions.Thinking.disabled,null,4096,false)));
        String digest=ModelProfileService.configDigest(profileRows.findById(profile.id()).orElseThrow());
        var budget=new EvalBudget(4,4,20000,2);
        String hash=EvalConfig.computeDigest("c15","v1","dev",digest,budget,"local",1,null);
        config=new EvalConfig("c15","v1","dev",Set.of(),profile.id(),digest,budget,null,"local",1,hash);
        dataset=new EvalDataset("c15","v1",List.of(new EvalSample("s","解释术语",EvalCategory.SIMPLE,new EvalGraderSpec("true",".",5,false,0))),Set.of("s"),Set.of());
        clearInvocations(gateway,grader,turns,writer);
    }
    void noExecution(long before){assertEquals(before,runs.count());verifyNoInteractions(gateway,grader,turns);}
    @Test void previewSealsActualRulesWithoutRunOrModelOrGrader()throws Exception{
        long before=runs.count();var result=runner.preflight(config,dataset,baseline,reports);
        noExecution(before);assertTrue(Files.isRegularFile(result.manifestPath()));
        assertEquals(result.manifest(),gate.load(result.manifestPath(),config));
        assertTrue(result.manifest().factors().path("prompt.initialRulesBySampleSha256").has("s"));
        assertFalse(Files.exists(baseline.resolve(".execution-manifests")));
        try(var files=Files.list(reports)){assertEquals(0,files.filter(p->p.toString().endsWith(".json")).count());}
    }
    @Test void matchingPreviewAllowsNormalFakeRunAndBindsFinalManifest()throws Exception{
        var pre=runner.preflight(config,dataset,baseline,reports);long before=runs.count();
        var report=runner.evaluateGuarded(config,dataset,baseline,reports,pre.manifestPath());
        assertEquals(before+1,runs.count());assertEquals("COMPLETED",report.trials().get(0).terminalState());
        assertEquals(pre.manifest().executionDigest(),report.executionManifest().executionDigest());
        verify(gateway,atLeastOnce()).generate(any(),any());
    }
    @Test void baselineChangedAfterPreviewStopsBeforeSubmittingRun()throws Exception{
        var pre=runner.preflight(config,dataset,baseline,reports);long before=runs.count();
        Files.writeString(baseline.resolve("README.md"),"changed\n");
        assertThrows(IllegalStateException.class,()->runner.evaluateGuarded(config,dataset,baseline,reports,pre.manifestPath()));noExecution(before);
    }
    @Test void newRulesAfterPreviewStopBeforeRun()throws Exception{
        var pre=runner.preflight(config,dataset,baseline,reports);long before=runs.count();
        Files.writeString(baseline.resolve("AGENTS.md"),"New constraints\n");
        assertThrows(IllegalStateException.class,()->runner.evaluateGuarded(config,dataset,baseline,reports,pre.manifestPath()));noExecution(before);
    }
    @Test void oppositeReviewArmWithValidDigestIsRejected()throws Exception{
        var pre=runner.preflight(config,dataset,baseline,reports);long before=runs.count();
        var f=pre.manifest().factors();f.put("loop.completionReviewEnabled",!f.path("loop.completionReviewEnabled").asBoolean());
        var other=EvalExecutionManifest.create(f,pre.manifest().limitations());Path file=root.resolve("opposite.json");
        new ObjectMapper().writeValue(file.toFile(),other);
        assertThrows(IllegalStateException.class,()->runner.evaluateGuarded(config,dataset,baseline,reports,file));noExecution(before);
    }
    @Test void driftAfterExecutionEvidenceWriteStillCannotStartFirstRun()throws Exception{
        var pre=runner.preflight(config,dataset,baseline,reports);long before=runs.count();
        doAnswer(i->{Object result=i.callRealMethod();Files.writeString(baseline.resolve("README.md"),"drift after write\n");return result;})
            .when(writer).writeExecutionManifest(any(),any());
        assertThrows(IllegalStateException.class,()->runner.evaluateGuarded(config,dataset,baseline,reports,pre.manifestPath()));noExecution(before);
    }
    @Test void driftDuringFirstAttemptBlocksSecondAttempt()throws Exception{
        var budget=config.budget();String hash=EvalConfig.computeDigest("c15","v1","dev",config.modelProfileConfigDigest(),budget,"local",2,null);
        config=new EvalConfig("c15","v1","dev",Set.of(),config.modelProfileId(),config.modelProfileConfigDigest(),budget,null,"local",2,hash);
        var pre=runner.preflight(config,dataset,baseline,reports);long before=runs.count();
        doAnswer(i->{Files.writeString(baseline.resolve("README.md"),"first run drift\n");return i.callRealMethod();}).when(gateway).generate(any(),any());
        assertThrows(IllegalStateException.class,()->runner.evaluateGuarded(config,dataset,baseline,reports,pre.manifestPath()));
        assertEquals(before+1,runs.count());
    }
    @Test void missingOutputDirectoryCannotPreview(){long before=runs.count();assertThrows(IllegalArgumentException.class,()->runner.preflight(config,dataset,baseline,null));noExecution(before);}
    @Test void reportsInsideBaselineCannotPreview(){long before=runs.count();assertThrows(IllegalArgumentException.class,()->runner.preflight(config,dataset,baseline,baseline.resolve("reports")));noExecution(before);}
    @Test void expectedManifestInsideBaselineCannotExecute()throws Exception{
        var pre=runner.preflight(config,dataset,baseline,reports);Path inside=baseline.resolve("expected.json");Files.copy(pre.manifestPath(),inside);
        long before=runs.count();assertThrows(IllegalArgumentException.class,()->runner.evaluateGuarded(config,dataset,baseline,reports,inside));noExecution(before);
    }
    @Test void missingExpectedDoesNotFallbackToLegacyExecution(){long before=runs.count();assertThrows(IllegalArgumentException.class,()->runner.evaluateGuarded(config,dataset,baseline,reports,null));noExecution(before);}
    @Test void staleModelConfigCannotPreview(){
        var budget=config.budget();String hash=EvalConfig.computeDigest("c15","v1","dev","other",budget,"local",1,null);
        var stale=new EvalConfig("c15","v1","dev",Set.of(),config.modelProfileId(),"other",budget,null,"local",1,hash);
        long before=runs.count();assertThrows(IllegalArgumentException.class,()->runner.preflight(stale,dataset,baseline,reports));noExecution(before);
    }
}
