package com.dsh.evals;

import com.dsh.agent.*;
import com.dsh.agent.tool.*;
import com.dsh.application.DshProperties;
import com.dsh.evals.model.*;
import com.dsh.orchestration.ChildRunLifecycle;
import com.dsh.orchestration.task.TaskVerificationService;
import com.dsh.rules.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvalManifestServiceTest {
    @TempDir Path root;
    EvalManifestService service;EvalConfig config;EvalDataset dataset;
    LoopSteps steps=mock(LoopSteps.class);AgentLoop loop=mock(AgentLoop.class);ToolRegistry tools=mock(ToolRegistry.class);
    ChildRunLifecycle children=mock(ChildRunLifecycle.class);RuleLoader rules=mock(RuleLoader.class);MockEnvironment env=new MockEnvironment();
    @BeforeEach void setup() throws Exception {
        var p=new DshProperties(null,null,null,null,null,null,null,null,null,null,null,null,null,null,null);
        @SuppressWarnings("unchecked") ObjectProvider<TaskVerificationService> gate=mock(ObjectProvider.class);
        service=new EvalManifestService(p,steps,loop,tools,children,gate,env,rules);
        when(steps.executionSettings()).thenReturn(Map.of("loop.historyCompactionMode","WINDOW_LASTUSER","loop.compactionWatermarkPercent",70,"loop.compactionProtectedTailUnits",5));
        when(children.executionApprovalTimeoutSeconds()).thenReturn(60L);
        when(tools.mountedDefinitions()).thenReturn(List.of(new ToolDefinition("read_file","read","1","CORE","READ_ONLY",new ObjectMapper().createObjectNode())));
        when(tools.extensionTools()).thenReturn(List.of());
        when(rules.load(anyString(),anyString())).thenReturn(new RuleLoader.LoadedRules(List.of(),"empty"));
        Files.writeString(root.resolve("source.txt"),"baseline");
        dataset=new EvalDataset("d","v1",List.of(new EvalSample("s","goal",EvalCategory.SIMPLE,new EvalGraderSpec("true",".",5,false,0))),Set.of("s"),Set.of());
        var budget=new EvalBudget(4,10,10000,1);String digest=EvalConfig.computeDigest("d","v1","dev","model",budget,"image",1,null);
        config=new EvalConfig("d","v1","dev",Set.of(),"profile","model",budget,null,"image",1,digest);
    }
    EvalExecutionManifest capture(){return service.capture(config,dataset,root,"project");}
    @Test void capturesActualSettingsAndMarksUnfrozenStatesWithoutReadingCredentials() {
        env.setProperty("dsh.orchestration.acceptance-coverage-mode"," identifier ");
        var m=capture();assertEquals("IDENTIFIER",m.factors().path("orchestration.acceptanceCoverageMode").asText());
        assertEquals("WINDOW_LASTUSER",m.factors().path("loop.historyCompactionMode").asText());assertEquals(60,m.factors().path("orchestration.childApprovalTimeoutSeconds").asInt());
        assertTrue(m.limitations().contains("SEMANTIC_MEMORY_STATE_UNFROZEN"));assertTrue(m.limitations().contains("EPISODIC_DATABASE_STATE_UNFROZEN"));
        assertTrue(m.factors().has("workspace.settings"));
        assertFalse(m.factors().toString().contains("credentialRef"));assertFalse(m.factors().toString().contains("baseUrl"));
        assertEquals(capture().executionDigest(),m.executionDigest());
    }
    @Test void changingCoverageCompactionOrToolDefinitionChangesDigestWhileLegacyConfigIsFixed() {
        var before=capture();String legacy=config.configDigest();env.setProperty("dsh.orchestration.acceptance-coverage-mode","IDENTIFIER");
        assertNotEquals(before.executionDigest(),capture().executionDigest());assertEquals(legacy,config.configDigest());
        env.setProperty("dsh.orchestration.acceptance-coverage-mode","KEYWORD");
        when(steps.executionSettings()).thenReturn(Map.of("loop.historyCompactionMode","BUDGET_WATERMARK"));assertNotEquals(before.executionDigest(),capture().executionDigest());
        when(tools.mountedDefinitions()).thenReturn(List.of());assertNotEquals(before.factors().path("tools.coreDefinitionsSha256"),capture().factors().path("tools.coreDefinitionsSha256"));
    }
    @Test void sameDatasetVersionWithChangedInputOrGraderIsDetected() {
        String before=EvalManifestService.datasetDigest(dataset);
        var changed=new EvalDataset("d","v1",List.of(new EvalSample("s","different goal",EvalCategory.SIMPLE,new EvalGraderSpec("false",".",5,false,0))),Set.of("s"),Set.of());
        assertNotEquals(before,EvalManifestService.datasetDigest(changed));
    }
    @Test void sourceDriftRefusesReportPublicationButIgnoredCachesAreExplicitlyOutOfScope() throws Exception {
        var initial=capture();Files.createDirectories(root.resolve("target"));Files.writeString(root.resolve("target/generated"),"cache");
        service.assertUnchanged(initial,config,dataset,root,"project");
        Files.writeString(root.resolve("source.txt"),"drift");assertThrows(IllegalStateException.class,()->service.assertUnchanged(initial,config,dataset,root,"project"));
        assertTrue(initial.limitations().contains("BASELINE_BUILD_CACHE_CONTENTS_EXCLUDED"));
    }
    @Test void memoryDirectoryWritesDoNotMasqueradeAsFrozenSourceChanges() throws Exception {
        var initial=capture();Files.createDirectories(root.resolve(".myagent/memory/p"));Files.writeString(root.resolve(".myagent/memory/p/new.md"),"mutable");
        service.assertUnchanged(initial,config,dataset,root,"project");assertTrue(initial.limitations().contains("SEMANTIC_MEMORY_STATE_UNFROZEN"));
    }
    @Test void boundNullMemoryDirectoryMatchesActualProviderPlacementAndWarns() throws Exception {
        var p = new DshProperties(null,null,null,null,null,null,null,null,
                new DshProperties.Memory(true,true,true,"AGENTS.md",200,25000,null,0.15),null,null,null,null,null,null);
        @SuppressWarnings("unchecked") ObjectProvider<TaskVerificationService> gate = mock(ObjectProvider.class);
        service = new EvalManifestService(p,steps,loop,tools,children,gate,env,rules);
        var frozen = capture();
        assertTrue(frozen.limitations().contains("SEMANTIC_MEMORY_DIRECTORY_NULL"));
        Files.createDirectories(root.resolve("null/project"));Files.writeString(root.resolve("null/project/state"),"mutable");
        service.assertUnchanged(frozen,config,dataset,root,"project");
    }
    @Test void missingSupportFileFailsBeforeAnyRunAndChangedSupportBytesAreDetected() throws Exception {
        Path support=root.resolveSibling(root.getFileName()+"-grader.java");
        try {
            dataset=new EvalDataset("d","v1",List.of(new EvalSample("s","goal",EvalCategory.SIMPLE,new EvalGraderSpec("true",".",5,false,0,support.toString()))),Set.of("s"),Set.of());
            assertThrows(java.io.UncheckedIOException.class,this::capture);Files.writeString(support,"first");var frozen=capture();
            Files.writeString(support,"changed");assertThrows(IllegalStateException.class,()->service.assertUnchanged(frozen,config,dataset,root,"project"));
        } finally {Files.deleteIfExists(support);}
    }
    @Test void symlinkFilesAndLinkedParentDirectoriesAreRejected() throws Exception {
        Path target=Files.writeString(root.resolve("target.txt"),"safe");Path link=root.resolve("linked");Files.createSymbolicLink(link,target);
        assertThrows(java.io.IOException.class,()->EvalManifestService.fileDigest(link));
        Path dir=Files.createDirectory(root.resolve("directory"));Files.writeString(dir.resolve("data"),"safe");Path dl=root.resolve("dirlink");Files.createSymbolicLink(dl,dir);
        assertThrows(java.io.IOException.class,()->EvalManifestService.fileDigest(dl.resolve("data")));
        assertThrows(java.io.IOException.class,()->EvalManifestService.directoryFingerprint(root,true,Set.of()));
    }
    @Test void actualNormalizedRetryValueIsCapturedRatherThanEnvironmentString() {
        when(loop.executionVerificationRetryLimit()).thenReturn(2);env.setProperty("dsh.loop.verification-retry-limit","999");
        assertEquals(2,capture().factors().path("loop.verificationRetryLimit").asInt());
    }
    @Test void replayTailChangesExecutionDigestUsingNormalizedRuntimeValue() {
        when(steps.executionSettings()).thenReturn(Map.of("loop.replayProtectedTailUnits",0));
        var before=capture();env.setProperty("dsh.loop.replay-protected-tail-units","999");
        assertEquals(0,capture().factors().path("loop.replayProtectedTailUnits").asInt());
        when(steps.executionSettings()).thenReturn(Map.of("loop.replayProtectedTailUnits",5));
        assertEquals(5,capture().factors().path("loop.replayProtectedTailUnits").asInt());
        assertNotEquals(before.executionDigest(),capture().executionDigest());
    }
    @Test void completionReviewModeAndNormalizedLimitAreIndependentExecutionFactors() {
        when(steps.executionSettings()).thenReturn(Map.of("loop.completionReviewEnabled",false,"loop.completionReviewMaxResponses",3));
        var before=capture();
        env.setProperty("dsh.loop.completion-review-max-responses","999");
        assertEquals(3,capture().factors().path("loop.completionReviewMaxResponses").asInt());
        when(steps.executionSettings()).thenReturn(Map.of("loop.completionReviewEnabled",true,"loop.completionReviewMaxResponses",3));
        assertNotEquals(before.executionDigest(),capture().executionDigest());
        when(steps.executionSettings()).thenReturn(Map.of("loop.completionReviewEnabled",false,"loop.completionReviewMaxResponses",2));
        assertNotEquals(before.executionDigest(),capture().executionDigest());
    }
}
