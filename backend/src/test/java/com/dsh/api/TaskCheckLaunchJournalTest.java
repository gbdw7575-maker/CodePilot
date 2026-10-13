package com.dsh.api;

import com.dsh.orchestration.acceptance.*;
import com.dsh.sandbox.SandboxRunner;
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
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PG/v2 confirmation/files/ArtifactStore; no production worker, real check process or model call. */
@SpringBootTest
@AutoConfigureMockMvc
class TaskCheckLaunchJournalTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){TaskCheckExecutionPreparationTest.properties(r);}
    @MockitoBean SandboxRunner runner;
    @MockitoBean com.dsh.orchestration.task.TaskSchedulingService scheduling;
    @Autowired ApplicationContext context;
    @Autowired TaskCheckLaunchJournal journal;
    @Autowired JdbcTemplate jdbc;
    TaskCheckAttemptEvidenceTest base;
    @BeforeEach void setup(){base=new TaskCheckAttemptEvidenceTest();context.getAutowireCapableBeanFactory().autowireBean(base);base.runner=runner;base.setup();}
    record Input(TaskCheckAttemptEvidenceTest.Fixture f,TaskCheckAttemptPreparation.Prepared p) { }
    Input input()throws Exception {var f=base.fixture();return new Input(f,base.prepare(f));}
    TaskCheckLaunchJournal.Entry register(Input i){return journal.register(i.p());}
    void refused(TaskCheckLaunchJournal.Reason reason,org.junit.jupiter.api.function.Executable code) {
        assertEquals(reason,assertThrows(TaskCheckLaunchJournal.Unavailable.class,code).reason());
    }
    @Test void persistsActualFullBindingAndDoesNotRunOrComplete()throws Exception {
        var i=input();var row=register(i);var tree=base.mapper.readTree(row.intentJson());
        assertEquals("PENDING",row.state());assertNull(row.claimOwner());assertFalse(row.cancelRequested());
        assertEquals(i.f().attempt(),row.attemptId());assertEquals(i.p().request().operationId(),row.operationId());
        assertEquals(i.f().child(),tree.path("binding").path("attemptObservation").path("child").path("id").asText());
        assertEquals("  用户原文\n约束  ",tree.path("binding").path("source").path("requirements").get(0).path("content").asText());
        assertTrue(tree.path("binding").path("executionDefinition").isObject());assertTrue(tree.path("binding").path("cacheFiles").isArray());
        assertEquals(64,tree.path("delivery").path("zipDigest").asText().length());
        assertEquals(row,journal.inspect(row.id()));assertFalse(row.launchAuthority());assertFalse(row.successAuthority());
        assertEquals("VERIFYING",base.helper.tasks.findByPlanIdAndTaskId(i.f().confirmed().chain().plan(),"A").orElseThrow().getState());
        assertEquals("RUNNING",base.attempts.findById(i.f().attempt()).orElseThrow().getState());verifyNoInteractions(runner);
    }
    @Test void sameOperationReplayDoesNotReplaceRecord()throws Exception {
        var i=input();var first=register(i);assertEquals(first,register(i));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM task_check_launches WHERE attempt_id=?",Integer.class,i.f().attempt()));
    }
    @Test void freshPreparationCannotSilentlyOverwriteRecordedOperation()throws Exception {
        var i=input();var row=register(i);var second=base.prepare(i.f());assertNotEquals(i.p().request().operationId(),second.request().operationId());
        refused(TaskCheckLaunchJournal.Reason.CONFLICT,()->journal.register(second));assertEquals(row,journal.inspect(row.id()));
    }
    @Test void pendingRecordSurvivesServiceReconstruction()throws Exception {
        var i=input();var row=register(i);var rebuilt=new TaskCheckLaunchJournal(jdbc,base.helper.transactionManager,base.preparation,base.evidence,
            base.mapper,context.getBean(com.dsh.agent.control.CancellationTokenRegistry.class));
        assertEquals(row,rebuilt.inspect(row.id()));assertEquals(List.of(row),rebuilt.scanRoot(row.rootRunId(),null,128));
    }
    @Test void concurrentRegistrationHasOneWinnerAndStableId()throws Exception {
        var i=input();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<TaskCheckLaunchJournal.Entry> call=()->{assertTrue(start.await(10,TimeUnit.SECONDS));return register(i);};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();assertEquals(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM task_check_launches WHERE attempt_id=?",Integer.class,i.f().attempt()));
        }finally{pool.shutdownNow();}
    }
    @Test void concurrentClaimGrantsExactlyOneJournalWinner()throws Exception {
        var i=input();var row=register(i);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return journal.claim(row.id(),"worker-a",i.p());});
            var b=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return journal.claim(row.id(),"worker-b",i.p());});
            start.countDown();var ra=a.get(30,TimeUnit.SECONDS);var rb=b.get(30,TimeUnit.SECONDS);
            assertNotEquals(ra.isPresent(),rb.isPresent());var claimed=journal.inspect(row.id());assertEquals("STARTING",claimed.state());
            assertFalse(claimed.launchAuthority());verifyNoInteractions(runner);
        }finally{pool.shutdownNow();}
    }
    @Test void cancellationBeforeClaimPreventsClaim()throws Exception {
        var i=input();var row=register(i);var cancelled=journal.requestCancel(row.id());
        assertEquals("CANCELLED",cancelled.state());assertTrue(cancelled.cancelRequested());assertNull(cancelled.claimOwner());
        assertTrue(journal.claim(row.id(),"worker",i.p()).isEmpty());assertEquals(cancelled,journal.requestCancel(row.id()));
    }
    @Test void cancellationAfterClaimDoesNotInventTermination()throws Exception {
        var i=input();var row=register(i);assertTrue(journal.claim(row.id(),"worker",i.p()).isPresent());
        var cancelled=journal.requestCancel(row.id());assertEquals("STARTING",cancelled.state());assertTrue(cancelled.cancelRequested());
        assertEquals("worker",cancelled.claimOwner());assertNull(cancelled.manifestJson());assertTrue(journal.claim(row.id(),"worker-b",i.p()).isEmpty());
    }
    @Test void concurrentCancellationAndClaimHaveConsistentOutcome()throws Exception {
        var i=input();var row=register(i);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var claim=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return journal.claim(row.id(),"worker",i.p());});
            var cancel=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return journal.requestCancel(row.id());});
            start.countDown();var winner=claim.get(30,TimeUnit.SECONDS);cancel.get(30,TimeUnit.SECONDS);var actual=journal.inspect(row.id());
            assertTrue(actual.cancelRequested());assertEquals(winner.isPresent()?"STARTING":"CANCELLED",actual.state());
            assertEquals(winner.isPresent()?"worker":null,actual.claimOwner());
        }finally{pool.shutdownNow();}
    }
    @Test void unknownOperationCannotBeReclaimedAfterRestartOrLeaseExpiry()throws Exception {
        var i=input();var row=register(i);journal.claim(row.id(),"worker",i.p()).orElseThrow();
        var unknown=journal.markUnknown(row.id(),"worker");assertEquals("UNKNOWN",unknown.state());
        assertEquals(unknown,journal.markUnknown(row.id(),"worker"));assertTrue(journal.claim(row.id(),"other",i.p()).isEmpty());
        jdbc.update("UPDATE agent_task_attempts SET lease_until=now()-interval '1 second' WHERE attempt_id=?",i.f().attempt());
        refused(TaskCheckLaunchJournal.Reason.INPUTS_STALE,()->journal.claim(row.id(),"other",i.p()));assertEquals(unknown,journal.inspect(row.id()));
    }
    @Test void anotherOwnerCannotMarkOrSealUnknown()throws Exception {
        var i=input();var row=register(i);journal.claim(row.id(),"worker",i.p());
        refused(TaskCheckLaunchJournal.Reason.CONFLICT,()->journal.markUnknown(row.id(),"other"));
        base.success(i.p());var sealed=base.evidence.executeAndSeal(i.p(),()->false);
        refused(TaskCheckLaunchJournal.Reason.CONFLICT,()->journal.recordSealed(row.id(),"other",i.p(),sealed));
        assertEquals("STARTING",journal.inspect(row.id()).state());
    }
    @Test void sealedFactReplaysAndPreservesActualArtifactIdentity()throws Exception {
        var i=input();var row=register(i);journal.claim(row.id(),"worker",i.p());base.success(i.p());
        var sealed=base.evidence.executeAndSeal(i.p(),()->false);var recorded=journal.recordSealed(row.id(),"worker",i.p(),sealed);
        assertEquals("SEALED",recorded.state());assertEquals(sealed.manifest().id(),base.mapper.readTree(recorded.manifestJson()).path("id").asText());
        assertEquals(recorded,journal.recordSealed(row.id(),"worker",i.p(),sealed));assertFalse(recorded.successAuthority());
        assertTrue(journal.claim(row.id(),"other",i.p()).isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM agent_task_results WHERE attempt_id=?",Integer.class,i.f().attempt()));
    }
    @Test void unknownCanAcceptLateActualEvidenceWithoutReplay()throws Exception {
        var i=input();var row=register(i);journal.claim(row.id(),"worker",i.p());journal.markUnknown(row.id(),"worker");
        base.success(i.p());var sealed=base.evidence.executeAndSeal(i.p(),()->false);
        assertEquals("SEALED",journal.recordSealed(row.id(),"worker",i.p(),sealed).state());
    }
    @Test void pendingOperationCannotRecordASealedResult()throws Exception {
        var i=input();var row=register(i);base.success(i.p());var sealed=base.evidence.executeAndSeal(i.p(),()->false);
        refused(TaskCheckLaunchJournal.Reason.CONFLICT,()->journal.recordSealed(row.id(),"worker",i.p(),sealed));
    }
    @Test void anotherRealPreparedOrManifestCannotReplaceTheRecordedOperation()throws Exception {
        var i=input();var row=register(i);journal.claim(row.id(),"worker",i.p());var second=base.prepare(i.f());base.success(second);
        var sealed=base.evidence.executeAndSeal(second,()->false);
        refused(TaskCheckLaunchJournal.Reason.CONFLICT,()->journal.recordSealed(row.id(),"worker",second,sealed));
        refused(TaskCheckLaunchJournal.Reason.EVIDENCE_INVALID,()->journal.recordSealed(row.id(),"worker",i.p(),sealed));
    }
    @Test void twoActualManifestsForOneOperationCannotReplaceTheFirst()throws Exception {
        var i=input();var row=register(i);journal.claim(row.id(),"worker",i.p());base.success(i.p());
        var a=base.evidence.executeAndSeal(i.p(),()->false);var b=base.evidence.executeAndSeal(i.p(),()->false);
        var recorded=journal.recordSealed(row.id(),"worker",i.p(),a);
        refused(TaskCheckLaunchJournal.Reason.CONFLICT,()->journal.recordSealed(row.id(),"worker",i.p(),b));assertEquals(recorded,journal.inspect(row.id()));
    }
    @Test void cancellationAndInputDriftStillAllowPreservingAlreadySealedFacts()throws Exception {
        var i=input();var row=register(i);journal.claim(row.id(),"worker",i.p());journal.requestCancel(row.id());
        base.success(i.p());var sealed=base.evidence.executeAndSeal(i.p(),()->false);base.drift(i.f(),"requirement");
        var result=journal.recordSealed(row.id(),"worker",i.p(),sealed);assertEquals("SEALED",result.state());assertTrue(result.cancelRequested());assertFalse(result.successAuthority());
    }
    @ParameterizedTest @ValueSource(strings={"TIMEOUT","CANCELLED","LOST","exit","unknown","failure"})
    void unsuccessfulEvidenceRemainsAnAuditFactRatherThanSuccess(String mode)throws Exception {
        var i=input();var row=register(i);journal.claim(row.id(),"worker",i.p());
        var termination=Set.of("TIMEOUT","CANCELLED","LOST").contains(mode)
            ?com.dsh.sandbox.TaskCheckExecutionOutcome.Termination.valueOf(mode):com.dsh.sandbox.TaskCheckExecutionOutcome.Termination.COMPLETED;
        Integer exit=termination==com.dsh.sandbox.TaskCheckExecutionOutcome.Termination.COMPLETED?(mode.equals("exit")?1:0):null;
        var cleanup=mode.equals("unknown")?com.dsh.sandbox.TaskCheckExecutionOutcome.Presence.UNKNOWN:com.dsh.sandbox.TaskCheckExecutionOutcome.Presence.ABSENT;
        var xml=mode.equals("failure")?TaskCheckSelectionAssessmentTest.xml(1,1,0):TaskCheckAttemptEvidenceTest.xml("works");
        when(runner.executeCheck(same(i.p().request()),any())).thenReturn(TaskCheckAttemptEvidenceTest.outcome(i.p().request(),termination,exit,cleanup,
            Map.of("target/surefire-reports/TEST-Trusted.xml",xml)));
        var sealed=base.evidence.executeAndSeal(i.p(),()->false);var recorded=journal.recordSealed(row.id(),"worker",i.p(),sealed);
        assertEquals("SEALED",recorded.state());assertFalse(recorded.successAuthority());
    }
    @ParameterizedTest @ValueSource(strings={"manifest","report","delivery","ownership"})
    void actualArtifactCorruptionCannotBeRecorded(String mode)throws Exception {
        var i=input();var row=register(i);journal.claim(row.id(),"worker",i.p());base.success(i.p());var sealed=base.evidence.executeAndSeal(i.p(),()->false);
        var ref=mode.equals("manifest")?sealed.manifest():sealed.members().stream().filter(r->r.mediaType().equals(mode.equals("delivery")?"application/zip":"application/xml")).findFirst().orElseThrow();
        if(mode.equals("ownership"))jdbc.update("UPDATE artifacts SET run_id=? WHERE id=?",i.f().confirmed().chain().run(),ref.id());
        else Files.writeString(i.f().lease().metaDir().resolve("artifacts").resolve(ref.id()),"broken");
        refused(TaskCheckLaunchJournal.Reason.EVIDENCE_INVALID,()->journal.recordSealed(row.id(),"worker",i.p(),sealed));assertEquals("STARTING",journal.inspect(row.id()).state());
    }
    @ParameterizedTest @ValueSource(strings={"candidate","requirement","cache","baseline","fencing","cancel","lease","renewed"})
    void stalePreparationCannotRegisterOrClaim(String mode)throws Exception {
        var i=input();var row=register(i);
        if(mode.equals("lease"))jdbc.update("UPDATE agent_task_attempts SET lease_until=now()-interval '1 second' WHERE attempt_id=?",i.f().attempt());
        else if(mode.equals("renewed"))jdbc.update("UPDATE agent_task_attempts SET lease_until=lease_until+interval '1 second' WHERE attempt_id=?",i.f().attempt());
        else base.drift(i.f(),mode);
        refused(TaskCheckLaunchJournal.Reason.INPUTS_STALE,()->register(i));refused(TaskCheckLaunchJournal.Reason.INPUTS_STALE,()->journal.claim(row.id(),"worker",i.p()));
        assertEquals(row,journal.inspect(row.id()));verifyNoInteractions(runner);
    }
    @Test void memoryCancellationAndInterruptCannotClaim()throws Exception {
        var i=input();var row=register(i);var tokens=context.getBean(com.dsh.agent.control.CancellationTokenRegistry.class);var root=row.rootRunId();
        try{tokens.register(root);assertTrue(tokens.requestCancel(root));refused(TaskCheckLaunchJournal.Reason.INPUTS_STALE,()->journal.claim(row.id(),"worker",i.p()));}
        finally{tokens.release(root);}
        try{Thread.currentThread().interrupt();refused(TaskCheckLaunchJournal.Reason.INPUTS_STALE,()->journal.claim(row.id(),"worker",i.p()));}
        finally{Thread.interrupted();}
    }
    @ParameterizedTest @ValueSource(strings={"root-cancel","task-state","fencing","lease","turn-state","plan-revision","child-state"})
    void databaseChangeAfterFileValidationStillLosesFinalClaimPredicate(String mode)throws Exception {
        var i=input();var row=register(i);var checked=mock(TaskCheckAttemptPreparation.class);
        doAnswer(call->{
            base.preparation.requireCurrent(i.p());
            switch(mode){
                case "root-cancel"->jdbc.update("UPDATE runs SET cancel_requested_at=now() WHERE id=?",row.rootRunId());
                case "task-state"->jdbc.update("UPDATE agent_tasks SET state='CANCELLING' WHERE plan_id=? AND task_id='A'",i.f().confirmed().chain().plan());
                case "fencing"->jdbc.update("UPDATE agent_task_attempts SET fencing_token=fencing_token+1 WHERE attempt_id=?",i.f().attempt());
                case "lease"->jdbc.update("UPDATE agent_task_attempts SET lease_until=now()-interval '1 second' WHERE attempt_id=?",i.f().attempt());
                case "turn-state"->jdbc.update("UPDATE turns SET state='CANCELLING' WHERE id=?",i.f().confirmed().chain().turn());
                case "plan-revision"->jdbc.update("UPDATE agent_plans SET revision=revision+1 WHERE id=?",i.f().confirmed().chain().plan());
                case "child-state"->jdbc.update("UPDATE runs SET state='CANCELLING' WHERE id=?",i.f().child());
            }
            return null;
        }).when(checked).requireCurrent(i.p());
        var controlled=new TaskCheckLaunchJournal(jdbc,base.helper.transactionManager,checked,base.evidence,base.mapper,
            context.getBean(com.dsh.agent.control.CancellationTokenRegistry.class));
        assertTrue(controlled.claim(row.id(),"worker",i.p()).isEmpty());assertEquals(row,journal.inspect(row.id()));verifyNoInteractions(runner);
    }
    @ParameterizedTest @ValueSource(strings={"digest","json","operation","root","owner","state","cancel","manifest"})
    void corruptPersistedFactsAreRejectedOnRecovery(String mode)throws Exception {
        var i=input();var row=register(i);
        String sql=switch(mode){case "digest"->"intent_digest='"+"a".repeat(64)+"'";case "json"->"intent_json='{}'";
            case "operation"->"operation_id='"+"b".repeat(32)+"'";case "root"->"root_run_id='other'";
            case "owner"->"claim_owner='worker'";case "state"->"state='SUCCESS'";case "cancel"->"cancel_requested=true";default->"manifest_json='{}'";};
        jdbc.update("UPDATE task_check_launches SET "+sql+" WHERE id=?",row.id());
        refused(TaskCheckLaunchJournal.Reason.CORRUPT,()->journal.inspect(row.id()));
    }
    @Test void boundedRecoveryQueryDoesNotLeakAnotherRootAndSupportsPages()throws Exception {
        var i=input();var a=register(i);var j=input();var b=register(j);
        assertEquals(List.of(a),journal.scanRoot(a.rootRunId(),null,1));assertEquals(List.of(),journal.scanRoot(a.rootRunId(),a.id(),1));
        assertEquals(List.of(b),journal.scanRoot(b.rootRunId(),null,128));assertEquals(List.of(),journal.scanRoot("absent-root",null,128));
        refused(TaskCheckLaunchJournal.Reason.INVALID_INPUT,()->journal.scanRoot(a.rootRunId(),null,129));
        refused(TaskCheckLaunchJournal.Reason.INVALID_INPUT,()->journal.scanRoot(a.rootRunId(),null,0));
    }
    @Test void differentChecksHaveSeparateSlotsAndCanBeRecoveredAcrossPages()throws Exception {
        var helper=new TaskCheckSelectionAssessmentTest();context.getAutowireCapableBeanFactory().autowireBean(helper);helper.setup();
        var f=helper.fixture(3,"slugify-semantic","second-semantic");
        var a=journal.register(base.preparation.prepare(f.confirmed().registration(),"slugify-semantic",f.attempt(),f.child(),2));
        var b=journal.register(base.preparation.prepare(f.confirmed().registration(),"second-semantic",f.attempt(),f.child(),2));
        var first=journal.scanRoot(a.rootRunId(),null,1);assertEquals(1,first.size());
        var second=journal.scanRoot(a.rootRunId(),first.getFirst().id(),1);assertEquals(1,second.size());
        assertEquals(Set.of(a.id(),b.id()),Set.of(first.getFirst().id(),second.getFirst().id()));
        assertEquals(List.of(),journal.scanRoot(a.rootRunId(),second.getFirst().id(),1));
    }
    @Test void callerTransactionsAndInvalidArgumentsAreRefused()throws Exception {
        var i=input();var row=register(i);
        new TransactionTemplate(base.helper.transactionManager).execute(s->{
            refused(TaskCheckLaunchJournal.Reason.CALLER_TRANSACTION,()->register(i));
            refused(TaskCheckLaunchJournal.Reason.CALLER_TRANSACTION,()->journal.claim(row.id(),"worker",i.p()));
            refused(TaskCheckLaunchJournal.Reason.CALLER_TRANSACTION,()->journal.inspect(row.id()));
            refused(TaskCheckLaunchJournal.Reason.CALLER_TRANSACTION,()->journal.requestCancel(row.id()));return null;});
        refused(TaskCheckLaunchJournal.Reason.INVALID_INPUT,()->journal.register(null));
        refused(TaskCheckLaunchJournal.Reason.INVALID_INPUT,()->journal.claim(row.id()," ",i.p()));
        refused(TaskCheckLaunchJournal.Reason.INVALID_INPUT,()->journal.inspect(null));
        refused(TaskCheckLaunchJournal.Reason.MISSING,()->journal.inspect("absent"));
    }
}
