package com.dsh.evals;

import com.dsh.agent.model.FakeModelGateway;
import com.dsh.api.dto.*;
import com.dsh.application.ModelProfileService;
import com.dsh.evals.model.*;
import com.dsh.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties={"dsh.policy.require-for-side-effects=NONE","dsh.orchestration.enabled=false","dsh.memory.enabled=false","dsh.episodic.enabled=false"})
class EvalManifestIntegrationTest {
    @Autowired EvalRunner runner;
    @Autowired EvalDatasetLoader loader;
    @Autowired ModelProfileService profiles;
    @Autowired ModelProfileRepository profileRows;
    @Autowired RunRepository runs;
    @MockitoSpyBean FakeModelGateway gateway;
    @TempDir Path root;
    EvalDataset dataset;EvalConfig config;Path baseline,reports;
    @BeforeEach void setup()throws Exception {
        baseline=Files.createDirectory(root.resolve("baseline"));Files.writeString(baseline.resolve("source.txt"),"baseline");
        reports=root.resolve("reports");
        var profile=profiles.create(new ModelProfileWrite("e07-fake",Provider.fake,"fake","http://127.0.0.1:9",null,new ModelOptions(ModelOptions.Thinking.disabled,null,4096,false)));
        String modelDigest=ModelProfileService.configDigest(profileRows.findById(profile.id()).orElseThrow());
        var budget=new EvalBudget(24,80,200000,2);var grader=new EvalGraderSpec("true",".",5,false,0);
        dataset=new EvalDataset("e07","v1",List.of(new EvalSample("s","解释术语",EvalCategory.SIMPLE,grader)),Set.of("s"),Set.of());
        String digest=EvalConfig.computeDigest("e07","v1","dev",modelDigest,budget,"local",1,null);
        config=new EvalConfig("e07","v1","dev",Set.of(),profile.id(),modelDigest,budget,null,"local",1,digest);
    }
    @Test void manifestIsSealedBeforeFirstModelCallAndNewReportRoundTrips()throws Exception {
        doAnswer(i->{assertTrue(Files.isDirectory(reports.resolve(".execution-manifests")));
            try(var files=Files.list(reports.resolve(".execution-manifests"))){assertEquals(1,files.count());}
            return i.callRealMethod();}).when(gateway).generate(any(),any());
        var report=runner.evaluate(config,dataset,baseline,reports);assertNotNull(report.executionManifest());
        report.executionManifest().validateFor(config);assertEquals(config.configDigest(),report.configDigest());
        Path file;try(var files=Files.list(reports)){file=files.filter(p->p.toString().endsWith(".json")).findFirst().orElseThrow();}
        assertEquals(report,loader.loadReport(file));assertFalse(report.executionManifest().limitations().contains("SEMANTIC_MEMORY_STATE_UNFROZEN"));
        assertFalse(report.executionManifest().limitations().contains("EPISODIC_DATABASE_STATE_UNFROZEN"));
    }
    @Test void baselineDriftRejectsFinalReportAndKeepsPreRunEvidence()throws Exception {
        doAnswer(i->{Files.writeString(baseline.resolve("source.txt"),"external drift");return i.callRealMethod();}).when(gateway).generate(any(),any());
        assertThrows(IllegalStateException.class,()->runner.evaluate(config,dataset,baseline,reports));
        assertTrue(Files.isDirectory(reports.resolve(".execution-manifests")));
        try(var files=Files.list(reports)){assertEquals(0,files.filter(p->p.toString().endsWith(".json")).count());}
    }
    @Test void linkedReportParentCannotExposePrivateManifestToBaseline() throws Exception {
        Path alias = root.resolve("alias");Files.createSymbolicLink(alias, baseline);
        long before = runs.count();
        assertThrows(IllegalArgumentException.class, () -> runner.evaluate(config, dataset, baseline, alias.resolve("reports")));
        assertEquals(before, runs.count());assertFalse(Files.exists(baseline.resolve("reports")));
    }
    @Test void reportsInsideBaselineAreRejectedBeforeModelExecution() {
        long before=runs.count();assertThrows(IllegalArgumentException.class,()->runner.evaluate(config,dataset,baseline,baseline.resolve("reports")));
        assertEquals(before,runs.count());
    }
}
