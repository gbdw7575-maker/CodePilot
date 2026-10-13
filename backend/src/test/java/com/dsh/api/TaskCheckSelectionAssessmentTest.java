package com.dsh.api;

import com.dsh.application.ArtifactStore;
import com.dsh.orchestration.acceptance.*;
import com.dsh.sandbox.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual PG/v2 confirmation/workspaces/ArtifactStore. Stub Runner is not a Docker execution control. */
@SpringBootTest
@AutoConfigureMockMvc
class TaskCheckSelectionAssessmentTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){TaskCheckExecutionPreparationTest.properties(registry);}
    @MockitoBean SandboxRunner runner;
    @MockitoBean com.dsh.orchestration.task.TaskSchedulingService scheduling;
    @Autowired ApplicationContext context;
    @Autowired TaskCheckSelectionAssessment assessment;
    @Autowired TaskCheckAttemptPreparation preparation;
    @Autowired TaskCheckEvidenceService evidence;
    @Autowired ArtifactStore artifacts;
    @Autowired JdbcTemplate jdbc;
    TaskCheckAttemptEvidenceTest base;
    @BeforeEach void setup(){base=new TaskCheckAttemptEvidenceTest();context.getAutowireCapableBeanFactory().autowireBean(base);base.setup();}
    TaskCheckAttemptEvidenceTest.Fixture fixture(int minimum,String... refs)throws Exception {
        var h=base.helper;var c=h.executionChain();
        var catalog=h.mapper.readTree(Files.readAllBytes(c.catalog()));
        for(var check:catalog.path("checks"))((ObjectNode)check).put("minPassed",minimum);
        Files.write(c.catalog(),h.mapper.writeValueAsBytes(catalog));
        var spec=(ObjectNode)h.mapper.readTree(Files.readAllBytes(h.descriptor(c)));
        spec.put("registryDigest",CanonicalTaskJson.sha256(Files.readAllBytes(c.catalog())));
        Files.write(h.descriptor(c),h.mapper.writeValueAsBytes(spec));
        var project=h.projects.findById(c.project()).orElseThrow();Path root=Path.of(project.getRootPath());
        Files.createDirectories(root.resolve("src/main/java/sample"));Files.writeString(root.resolve("src/main/java/sample/Value.java"),"package sample; public class Value {}\n");
        var lease=h.workspaces.prepare(c.run(),project);var run=h.runs.findById(c.run()).orElseThrow();run.setWorkspaceRef(lease.workspaceId());h.runs.save(run);
        // Use the real preview service to obtain the current task-preview digest, then actual HTTP confirmation.
        var preview=context.getBean(TaskCheckPreviewService.class).preview(c.run(),c.plan(),"A");
        var selected=context.getBean(TaskCheckSelectionPreviewService.class).previewExecution(c.run(),c.plan(),"A",
                "\"task-check-preview-v1-"+preview.previewDigest()+"\"",List.of(refs));
        var response=h.confirm(c,"\"task-check-selection-v2-"+selected.selectionDigest()+"\"","assessment",h.payload(refs));
        assertEquals(200,response.getResponse().getStatus());
        String registration=h.mapper.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("registrationId").asText();
        return base.fixture(new TaskCheckExecutionPreparationTest.Prepared(c,registration,lease),"[]");
    }
    TaskCheckSelectionAssessment.CheckEvidence seal(TaskCheckAttemptEvidenceTest.Fixture f,String check,String mode)throws Exception {
        var p=preparation.prepare(f.confirmed().registration(),check,f.attempt(),f.child(),2);
        var termination=TaskCheckExecutionOutcome.Termination.COMPLETED;Integer exit=0;
        var cleanup=TaskCheckExecutionOutcome.Presence.ABSENT;
        Map<String,byte[]> reports=Map.of("target/surefire-reports/TEST-Trusted.xml",xml(3,0,0));
        switch(mode){
            case "TIMEOUT","CANCELLED","LAUNCH_FAILED","LOST"->{termination=TaskCheckExecutionOutcome.Termination.valueOf(mode);exit=null;}
            case "exit"->exit=1;case "unknown"->cleanup=TaskCheckExecutionOutcome.Presence.UNKNOWN;case "present"->cleanup=TaskCheckExecutionOutcome.Presence.PRESENT;
            case "missing"->reports=Map.of();case "malformed"->reports=Map.of("target/surefire-reports/TEST-Trusted.xml","bad xml".getBytes(StandardCharsets.UTF_8));
            case "duplicates"->reports=Map.of("target/surefire-reports/TEST-A.xml",xml(1,0,0),"target/surefire-reports/TEST-B.xml",xml(1,0,0));
            case "zero"->reports=Map.of("target/surefire-reports/TEST-Trusted.xml",xml(0,0,0));
            case "skip"->reports=Map.of("target/surefire-reports/TEST-Trusted.xml",xml(3,0,3));
            case "failure"->reports=Map.of("target/surefire-reports/TEST-Trusted.xml",xml(3,1,0));
        }
        var ctor=TaskCheckExecutionOutcome.class.getDeclaredConstructors()[0];ctor.setAccessible(true);
        var outcome=(TaskCheckExecutionOutcome)ctor.newInstance(p.request().operationId(),mode.equals("same-container")?"a".repeat(64):p.request().operationId().repeat(2),termination,exit,"NONE",cleanup,
                new TaskCheckExecutionOutcome.Logs("stdout","stderr",12L,0L,false),25L,reports);
        final String variant=mode;
        when(runner.executeCheck(same(p.request()),any())).thenAnswer(call->{
            if(variant.equals("cancel-observed")){java.util.function.BooleanSupplier signal=call.getArgument(1);assertTrue(signal.getAsBoolean());}
            return outcome;
        });
        return new TaskCheckSelectionAssessment.CheckEvidence(p,evidence.executeAndSeal(p,()->variant.equals("cancel-observed")));
    }
    static byte[] xml(int count,int failed,int skipped){
        var text=new StringBuilder("<testsuite tests=\"").append(count).append("\" failures=\"").append(failed).append("\" errors=\"0\" skipped=\"").append(skipped).append("\">");
        for(int i=0;i<count;i++){text.append("<testcase classname=\"Trusted\" name=\"case").append(i).append("\">");if(i<failed)text.append("<failure/>");else if(i<failed+skipped)text.append("<skipped/>");text.append("</testcase>");}
        return text.append("</testsuite>").toString().getBytes(StandardCharsets.UTF_8);
    }
    void refused(TaskCheckSelectionAssessment.Reason reason,List<TaskCheckSelectionAssessment.CheckEvidence> pairs){
        assertEquals(reason,assertThrows(TaskCheckSelectionAssessment.Unavailable.class,()->assessment.assess(pairs)).reason());
    }
    @Test void completeSingleCheckIsAnImmutableObservationWithoutStateWriteOrLaunch()throws Exception {
        var f=fixture(3,"slugify-semantic");var pair=seal(f,"slugify-semantic","good");clearInvocations(runner);
        var observed=assessment.assess(List.of(pair));assertFalse(observed.successAuthority());assertEquals(3,observed.checks().getFirst().counts().passed());
        assertNotNull(observed.observedAt());assertEquals(64,observed.digest().length());assertThrows(UnsupportedOperationException.class,()->observed.checks().clear());
        assessment.requireCurrent(observed);verifyNoInteractions(runner);
        assertEquals("VERIFYING",base.helper.tasks.findByPlanIdAndTaskId(f.confirmed().chain().plan(),"A").orElseThrow().getState());
        assertEquals("RUNNING",base.attempts.findById(f.attempt()).orElseThrow().getState());
    }
    @Test void completeTwoSelectedChecksAreSortedAndOrderIndependent()throws Exception {
        var f=fixture(3,"slugify-semantic","second-semantic");var a=seal(f,"slugify-semantic","good");var b=seal(f,"second-semantic","good");
        var one=assessment.assess(List.of(a,b));var two=assessment.assess(List.of(b,a));
        assertEquals(List.of("second-semantic","slugify-semantic"),one.checks().stream().map(TaskCheckSelectionAssessment.CheckObservation::checkId).toList());
        assertEquals(one.digest(),two.digest());assertFalse(one.successAuthority());
    }
    @Test void passingSubsetCannotReplaceFullSelection()throws Exception {
        var f=fixture(3,"slugify-semantic","second-semantic");refused(TaskCheckSelectionAssessment.Reason.SELECTION_MISMATCH,List.of(seal(f,"slugify-semantic","good")));
    }
    @Test void duplicateSelectedCheckCannotCountTwice()throws Exception {
        var f=fixture(3,"slugify-semantic");var pair=seal(f,"slugify-semantic","good");refused(TaskCheckSelectionAssessment.Reason.SELECTION_MISMATCH,List.of(pair,pair));
    }
    @Test void checksFromTwoRealRegistrationsCannotBeCombined()throws Exception {
        var a=fixture(3,"slugify-semantic","second-semantic");var b=fixture(3,"second-semantic");
        refused(TaskCheckSelectionAssessment.Reason.INPUTS_MISMATCH,List.of(seal(a,"slugify-semantic","good"),seal(b,"second-semantic","good")));
    }
    @Test void checksForDifferentCandidatesCannotBeCombined()throws Exception {
        var f=fixture(3,"slugify-semantic","second-semantic");var a=seal(f,"slugify-semantic","good");
        Files.writeString(f.lease().dir().resolve("src/main/java/sample/Value.java"),"package sample; public class Value { int v=3; }\n");
        var b=seal(f,"second-semantic","good");assertThrows(TaskCheckSelectionAssessment.Unavailable.class,()->assessment.assess(List.of(a,b)));
    }
    @Test void aValidManifestCannotBePairedWithAnotherRealCheckRequest()throws Exception {
        var f=fixture(3,"slugify-semantic","second-semantic");var a=seal(f,"slugify-semantic","good");var b=seal(f,"second-semantic","good");
        refused(TaskCheckSelectionAssessment.Reason.INPUTS_MISMATCH,List.of(a,new TaskCheckSelectionAssessment.CheckEvidence(b.prepared(),a.sealed())));
    }
    @Test void oneFailedCheckRejectsOtherwiseCompleteSelection()throws Exception {
        var f=fixture(3,"slugify-semantic","second-semantic");refused(TaskCheckSelectionAssessment.Reason.CHECK_FAILED,List.of(seal(f,"slugify-semantic","good"),seal(f,"second-semantic","exit")));
    }
    @Test void frozenMinimumCannotBeReplacedByPositiveTestCount()throws Exception {
        var f=fixture(4,"slugify-semantic");refused(TaskCheckSelectionAssessment.Reason.CHECK_FAILED,List.of(seal(f,"slugify-semantic","good")));
    }
    @Test void skippedTestsDoNotCountTowardMinimumButPermittedSkipsDoNotEraseRealPasses()throws Exception {
        var f=fixture(2,"slugify-semantic");var p=preparation.prepare(f.confirmed().registration(),"slugify-semantic",f.attempt(),f.child(),2);
        when(runner.executeCheck(same(p.request()),any())).thenReturn(TaskCheckAttemptEvidenceTest.outcome(p.request(),TaskCheckExecutionOutcome.Termination.COMPLETED,0,
                TaskCheckExecutionOutcome.Presence.ABSENT,Map.of("target/surefire-reports/TEST-Trusted.xml",xml(3,0,1))));
        var result=assessment.assess(List.of(new TaskCheckSelectionAssessment.CheckEvidence(p,evidence.executeAndSeal(p,()->false))));
        assertEquals(2,result.checks().getFirst().counts().passed());assertEquals(1,result.checks().getFirst().counts().skipped());
    }
    @ParameterizedTest @ValueSource(strings={"TIMEOUT","CANCELLED","LAUNCH_FAILED","LOST","exit","unknown","present","missing","malformed","duplicates","zero","skip","failure","cancel-observed"})
    void unsuccessfulFactsCannotCreateCompleteObservation(String mode)throws Exception {
        var f=fixture(3,"slugify-semantic");refused(TaskCheckSelectionAssessment.Reason.CHECK_FAILED,List.of(seal(f,"slugify-semantic",mode)));
    }
    @ParameterizedTest @ValueSource(strings={"candidate","requirement","cache","baseline","fencing","cancel","lease","registration"})
    void currentInputsMustBeReobservedAfterSealing(String mode)throws Exception {
        var f=fixture(3,"slugify-semantic");var pair=seal(f,"slugify-semantic","good");
        if(mode.equals("lease"))jdbc.update("UPDATE agent_task_attempts SET lease_until=now()-interval '1 second' WHERE attempt_id=?",f.attempt());
        else if(mode.equals("registration"))jdbc.update("UPDATE task_check_registrations SET snapshot_digest=? WHERE id=?","a".repeat(64),f.confirmed().registration());
        else base.drift(f,mode);
        assertThrows(TaskCheckSelectionAssessment.Unavailable.class,()->assessment.assess(List.of(pair)));
    }
    @ParameterizedTest @ValueSource(strings={"manifest","report","zip","ownership","missing"})
    void actualSealedBytesAndOwnershipAreReadAgain(String mode)throws Exception {
        var f=fixture(3,"slugify-semantic");var pair=seal(f,"slugify-semantic","good");
        var ref=mode.equals("manifest")?pair.sealed().manifest():pair.sealed().members().stream().filter(r->mode.equals("zip")?r.mediaType().equals("application/zip"):r.mediaType().equals("application/xml")).findFirst().orElseThrow();
        Path file=f.lease().metaDir().resolve("artifacts").resolve(ref.id());
        if(mode.equals("ownership"))jdbc.update("UPDATE artifacts SET run_id=? WHERE id=?",f.confirmed().chain().run(),ref.id());
        else if(mode.equals("missing"))Files.delete(file);else Files.writeString(file,"corrupted");
        refused(TaskCheckSelectionAssessment.Reason.EVIDENCE_INVALID,List.of(pair));
    }
    @Test void previouslyValidObservationIsNotPermanentPermission()throws Exception {
        var f=fixture(3,"slugify-semantic");var observed=assessment.assess(List.of(seal(f,"slugify-semantic","good")));base.drift(f,"cancel");
        assertThrows(TaskCheckSelectionAssessment.Unavailable.class,()->assessment.requireCurrent(observed));assertFalse(observed.successAuthority());
    }
    @Test void upstreamFileDriftCannotUseOldSuccessMetadata()throws Exception {
        var u=base.upstream();var pair=seal(u.fixture(),"slugify-semantic","good");Files.writeString(u.content(),"corrupted");
        refused(TaskCheckSelectionAssessment.Reason.INPUTS_STALE,List.of(pair));
    }
    @Test void callerTransactionIsRefusedBeforeFileReads()throws Exception {
        var f=fixture(3,"slugify-semantic");var pair=seal(f,"slugify-semantic","good");
        new org.springframework.transaction.support.TransactionTemplate(base.helper.transactionManager).execute(s->{refused(TaskCheckSelectionAssessment.Reason.CALLER_TRANSACTION,List.of(pair));return null;});
    }
    @Test void invalidInputCannotCreateObservation() {
        refused(TaskCheckSelectionAssessment.Reason.INVALID_INPUT,null);refused(TaskCheckSelectionAssessment.Reason.INVALID_INPUT,List.of());
        refused(TaskCheckSelectionAssessment.Reason.INVALID_INPUT,Collections.singletonList(null));
        refused(TaskCheckSelectionAssessment.Reason.INVALID_INPUT,List.of(new TaskCheckSelectionAssessment.CheckEvidence(null,null)));
        assertThrows(TaskCheckSelectionAssessment.Unavailable.class,()->assessment.requireCurrent(null));
    }
    @Test void moreThanEightInputsIsRefusedBeforeReads()throws Exception {
        var f=fixture(3,"slugify-semantic");var pair=seal(f,"slugify-semantic","good");
        refused(TaskCheckSelectionAssessment.Reason.INVALID_INPUT,Collections.nCopies(9,pair));
    }
    @Test void memoryCancellationCannotUseOtherwiseCurrentDatabaseState()throws Exception {
        var f=fixture(3,"slugify-semantic");var pair=seal(f,"slugify-semantic","good");
        var cancellations=context.getBean(com.dsh.agent.control.CancellationTokenRegistry.class);var root=f.confirmed().chain().run();
        try{cancellations.register(root);assertTrue(cancellations.requestCancel(root));refused(TaskCheckSelectionAssessment.Reason.INPUTS_STALE,List.of(pair));}
        finally{cancellations.release(root);}
    }
    @Test void interruptedObserverCannotProduceAnObservation()throws Exception {
        var f=fixture(3,"slugify-semantic");var pair=seal(f,"slugify-semantic","good");
        try{Thread.currentThread().interrupt();refused(TaskCheckSelectionAssessment.Reason.INPUTS_STALE,List.of(pair));}
        finally{Thread.interrupted();}
    }
    @Test void oneContainerCannotCountAsTwoIndependentChecks()throws Exception {
        var f=fixture(3,"slugify-semantic","second-semantic");
        refused(TaskCheckSelectionAssessment.Reason.EVIDENCE_INVALID,List.of(seal(f,"slugify-semantic","same-container"),seal(f,"second-semantic","same-container")));
    }
}
