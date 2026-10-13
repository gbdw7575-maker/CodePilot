package com.dsh.api;

import com.dsh.orchestration.acceptance.*;
import com.dsh.sandbox.*;
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
import java.time.Instant;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual PG/v2 confirmation/files/ArtifactStore. Runner substituted; no model or production success. */
@SpringBootTest @AutoConfigureMockMvc
class TaskCheckExecutionCoordinatorTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){TaskCheckExecutionPreparationTest.properties(r);}
    @MockitoBean SandboxRunner runner;
    @MockitoBean com.dsh.orchestration.task.TaskSchedulingService scheduling;
    @Autowired ApplicationContext context;
    @Autowired TaskCheckExecutionCoordinator coordinator;
    @Autowired TaskCheckLaunchJournal journal;
    @Autowired JdbcTemplate jdbc;
    TaskCheckSelectionAssessmentTest helper;
    TaskCheckAttemptEvidenceTest base;
    @BeforeEach void setup(){
        helper=new TaskCheckSelectionAssessmentTest();context.getAutowireCapableBeanFactory().autowireBean(helper);helper.setup();
        base=helper.base;base.runner=runner;
    }
    TaskCheckAttemptEvidenceTest.Fixture fixture(String... checks)throws Exception{return helper.fixture(1,checks);}
    TaskCheckExecutionCoordinator.Result execute(TaskCheckAttemptEvidenceTest.Fixture f){return coordinator.execute(f.confirmed().registration(),f.attempt(),f.child(),2);}
    Instant lease(TaskCheckAttemptEvidenceTest.Fixture f){return jdbc.queryForObject("SELECT lease_until FROM agent_task_attempts WHERE attempt_id=?",java.sql.Timestamp.class,f.attempt()).toInstant();}
    void shortLease(TaskCheckAttemptEvidenceTest.Fixture f){jdbc.update("UPDATE agent_task_attempts SET lease_until=? WHERE attempt_id=?",java.sql.Timestamp.from(Instant.now().plusSeconds(60)),f.attempt());}
    void success()throws Exception {
        when(runner.executeCheck(any(),any())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            TaskCheckExecutionRequest r=call.getArgument(0);
            return outcome(r,TaskCheckExecutionOutcome.Termination.COMPLETED,0,TaskCheckExecutionOutcome.Presence.ABSENT,
                Map.of("target/surefire-reports/TEST-Trusted.xml",TaskCheckSelectionAssessmentTest.xml(1,0,0)));
        });
    }
    static TaskCheckExecutionOutcome outcome(TaskCheckExecutionRequest r,TaskCheckExecutionOutcome.Termination term,Integer exit,TaskCheckExecutionOutcome.Presence presence,Map<String,byte[]> reports)throws Exception {
        var ctor=TaskCheckExecutionOutcome.class.getDeclaredConstructors()[0];ctor.setAccessible(true);
        return (TaskCheckExecutionOutcome)ctor.newInstance(r.operationId(),r.operationId().repeat(2),term,exit,"NONE",presence,
            new TaskCheckExecutionOutcome.Logs("stdout","stderr",12L,0L,false),25L,reports);
    }
    void noTaskSuccess(TaskCheckAttemptEvidenceTest.Fixture f){
        assertEquals("VERIFYING",jdbc.queryForObject("SELECT state FROM agent_tasks WHERE plan_id=? AND task_id='A'",String.class,f.confirmed().chain().plan()));
        assertEquals("RUNNING",jdbc.queryForObject("SELECT state FROM agent_task_attempts WHERE attempt_id=?",String.class,f.attempt()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM agent_task_results WHERE attempt_id=?",Integer.class,f.attempt()));
    }
    @Test void actualFullSelectionExecutesThroughJournalAndFreezesOneExtendedLease()throws Exception {
        var f=fixture("slugify-semantic","second-semantic");shortLease(f);Instant old=lease(f);success();var result=execute(f);
        assertEquals("OBSERVED",result.state());assertTrue(result.observation().isPresent());assertEquals(2,result.checks().size());
        assertFalse(result.successAuthority());assertFalse(result.observation().orElseThrow().successAuthority());
        Instant reserved=lease(f);assertTrue(reserved.isAfter(old));
        for(var pair:result.checks()){
            assertEquals(reserved,pair.prepared().observed().attempt().leaseUntil());assertTrue(pair.sealed().currentAtReturn());
            var manifest=base.mapper.readTree(base.evidence.verify(pair.sealed()));assertFalse(manifest.path("successAuthority").asBoolean(true));
        }
        var rows=journal.scanRoot(f.confirmed().chain().run(),null,128);assertEquals(2,rows.size());
        assertTrue(rows.stream().allMatch(r->r.state().equals("SEALED")&&r.claimOwner().equals(result.executionId())));
        assertEquals(2,base.mapper.readTree(jdbc.queryForObject("SELECT launch_ids_json FROM task_check_executions WHERE id=?",String.class,result.executionId())).size());
        verify(runner,times(2)).executeCheck(any(),any());noTaskSuccess(f);
    }
    @Test void leaseAlreadyAtRootDeadlineIsNotChanged()throws Exception {
        var f=fixture("slugify-semantic");jdbc.update("UPDATE agent_task_attempts SET lease_until=(SELECT budget_deadline_at FROM runs WHERE id=?) WHERE attempt_id=?",f.confirmed().chain().run(),f.attempt());Instant before=lease(f);success();assertEquals("OBSERVED",execute(f).state());assertEquals(before,lease(f));
    }
    @Test void rootBudgetCannotBeRaisedToFitVerification()throws Exception {
        var f=fixture("slugify-semantic");shortLease(f);Instant old=lease(f);
        jdbc.update("UPDATE runs SET budget_deadline_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().plusSeconds(90)),f.confirmed().chain().run());
        assertEquals(TaskCheckExecutionCoordinator.Reason.DEADLINE_INSUFFICIENT,assertThrows(TaskCheckExecutionCoordinator.Unavailable.class,()->execute(f)).reason());
        assertEquals(old,lease(f));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_check_executions WHERE attempt_id=?",Integer.class,f.attempt()));verifyNoInteractions(runner);
    }
    @Test void expiredLeaseCannotBeRevived()throws Exception {
        var f=fixture("slugify-semantic");jdbc.update("UPDATE agent_task_attempts SET lease_until=now()-interval '1 second' WHERE attempt_id=?",f.attempt());
        assertThrows(RuntimeException.class,()->execute(f));verifyNoInteractions(runner);noTaskSuccess(f);
    }
    @Test void completedExecutionCannotReplayEvenAfterServiceReconstruction()throws Exception {
        var f=fixture("slugify-semantic");success();var first=execute(f);
        var rebuilt=new TaskCheckExecutionCoordinator(jdbc,base.helper.transactionManager,base.preparation,journal,base.evidence,helper.assessment,base.mapper,
            context.getBean(com.dsh.agent.control.CancellationTokenRegistry.class));
        assertEquals(TaskCheckExecutionCoordinator.Reason.ALREADY_RESERVED,assertThrows(TaskCheckExecutionCoordinator.Unavailable.class,
            ()->rebuilt.execute(f.confirmed().registration(),f.attempt(),f.child(),2)).reason());
        assertEquals("OBSERVED",first.state());verify(runner,times(1)).executeCheck(any(),any());
    }
    @Test void concurrentControllersHaveOneExecutionWinner()throws Exception {
        var f=fixture("slugify-semantic");success();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            Callable<Boolean> code=()->{assertTrue(start.await(10,TimeUnit.SECONDS));try{return execute(f).state().equals("OBSERVED");}catch(RuntimeException refusal){return false;}};
            var a=pool.submit(code);var b=pool.submit(code);start.countDown();assertNotEquals(a.get(45,TimeUnit.SECONDS),b.get(45,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM task_check_executions WHERE attempt_id=?",Integer.class,f.attempt()));verify(runner,times(1)).executeCheck(any(),any());
        }finally{pool.shutdownNow();}
    }
    @ParameterizedTest @ValueSource(strings={"requirement","fence"})
    void durableOperationBlocksReplayBeforeReReadingChangedInputs(String drift)throws Exception {
        var f=fixture("slugify-semantic");success();assertEquals("OBSERVED",execute(f).state());
        if(drift.equals("requirement"))jdbc.update("UPDATE messages SET content='changed after evidence' WHERE turn_id=?",f.confirmed().chain().turn());
        else jdbc.update("UPDATE agent_task_attempts SET fencing_token=3 WHERE attempt_id=?",f.attempt());
        assertEquals(TaskCheckExecutionCoordinator.Reason.ALREADY_RESERVED,assertThrows(TaskCheckExecutionCoordinator.Unavailable.class,
            ()->coordinator.execute(f.confirmed().registration(),f.attempt(),f.child(),drift.equals("fence")?3:2)).reason());
        verify(runner,times(1)).executeCheck(any(),any());noTaskSuccess(f);
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","STARTING","UNKNOWN","SEALED","CANCELLED"})
    void anyPreviousLaunchPreventsNewLeaseAndExecution(String state)throws Exception {
        var f=fixture("slugify-semantic");var p=base.prepare(f);var row=journal.register(p);
        if(!state.equals("PENDING")&&!state.equals("CANCELLED"))journal.claim(row.id(),"earlier",p);
        if(state.equals("UNKNOWN"))journal.markUnknown(row.id(),"earlier");
        if(state.equals("SEALED")){base.success(p);var s=base.evidence.executeAndSeal(p,()->false);journal.recordSealed(row.id(),"earlier",p,s);clearInvocations(runner);}
        if(state.equals("CANCELLED"))journal.requestCancel(row.id());
        Instant before=lease(f);assertEquals(TaskCheckExecutionCoordinator.Reason.ALREADY_RESERVED,assertThrows(TaskCheckExecutionCoordinator.Unavailable.class,()->execute(f)).reason());
        assertEquals(before,lease(f));verifyNoInteractions(runner);
    }
    @Test void claimedExceptionBecomesUnknownAndCancelsUnclaimedRemainder()throws Exception {
        var f=fixture("slugify-semantic","second-semantic");when(runner.executeCheck(any(),any())).thenThrow(new IllegalStateException("simulated lost controller"));
        var result=execute(f);assertEquals("UNKNOWN",result.state());assertEquals(TaskCheckExecutionCoordinator.Reason.EXECUTION_UNKNOWN,result.reason());
        var rows=journal.scanRoot(f.confirmed().chain().run(),null,128);assertEquals(2,rows.size());
        assertEquals(1,rows.stream().filter(r->r.state().equals("UNKNOWN")&&r.cancelRequested()).count());
        assertEquals(1,rows.stream().filter(r->r.state().equals("CANCELLED")&&r.claimOwner()==null).count());
        assertThrows(RuntimeException.class,()->execute(f));verify(runner,times(1)).executeCheck(any(),any());noTaskSuccess(f);
    }
    @ParameterizedTest @ValueSource(strings={"UNKNOWN","PRESENT"})
    void uncertainCleanupSealsFactsButBlocksRemainingChecks(String presence)throws Exception {
        var f=fixture("slugify-semantic","second-semantic");when(runner.executeCheck(any(),any())).thenAnswer(call->outcome(call.getArgument(0),
            TaskCheckExecutionOutcome.Termination.COMPLETED,0,TaskCheckExecutionOutcome.Presence.valueOf(presence),Map.of("target/surefire-reports/TEST-Trusted.xml",TaskCheckSelectionAssessmentTest.xml(1,0,0))));
        var result=execute(f);assertEquals("REJECTED",result.state());assertEquals(1,result.checks().size());assertTrue(result.observation().isEmpty());
        var rows=journal.scanRoot(f.confirmed().chain().run(),null,128);assertEquals(1,rows.stream().filter(r->r.state().equals("SEALED")).count());
        assertEquals(1,rows.stream().filter(r->r.state().equals("CANCELLED")).count());verify(runner,times(1)).executeCheck(any(),any());noTaskSuccess(f);
    }
    @ParameterizedTest @ValueSource(strings={"failure","skip","missing","timeout"})
    void unsuccessfulActualReportsNeverCreateObservation(String mode)throws Exception {
        var f=fixture("slugify-semantic");when(runner.executeCheck(any(),any())).thenAnswer(call->outcome(call.getArgument(0),
            mode.equals("timeout")?TaskCheckExecutionOutcome.Termination.TIMEOUT:TaskCheckExecutionOutcome.Termination.COMPLETED,mode.equals("timeout")?null:0,
            TaskCheckExecutionOutcome.Presence.ABSENT,mode.equals("missing")?Map.of():Map.of("target/surefire-reports/TEST-Trusted.xml",
                TaskCheckSelectionAssessmentTest.xml(1,mode.equals("failure")?1:0,mode.equals("skip")?1:0))));
        var result=execute(f);assertEquals("REJECTED",result.state());assertTrue(result.observation().isEmpty());assertEquals("SEALED",journal.scanRoot(f.confirmed().chain().run(),null,128).getFirst().state());noTaskSuccess(f);
    }
    @ParameterizedTest @ValueSource(strings={"journal-cancel","root-cancel","deadline","lease","requirement","candidate","slot"})
    void midExecutionDriftRetainsFactsWithoutSuccess(String drift)throws Exception {
        var f=fixture("slugify-semantic");when(runner.executeCheck(any(),any())).thenAnswer(call->{
            switch(drift){
                case "journal-cancel"->journal.requestCancel(journal.scanRoot(f.confirmed().chain().run(),null,128).getFirst().id());
                case "root-cancel"->jdbc.update("UPDATE runs SET cancel_requested_at=now() WHERE id=?",f.confirmed().chain().run());
                case "deadline"->jdbc.update("UPDATE runs SET budget_deadline_at=budget_deadline_at+interval '1 second' WHERE id=?",f.confirmed().chain().run());
                case "lease"->jdbc.update("UPDATE agent_task_attempts SET lease_until=lease_until+interval '1 second' WHERE attempt_id=?",f.attempt());
                case "requirement"->jdbc.update("UPDATE messages SET content='changed' WHERE turn_id=?",f.confirmed().chain().turn());
                case "candidate"->Files.writeString(f.lease().dir().resolve("src/main/java/sample/Value.java"),"package sample; public class Value {}\n");
                case "slot"->jdbc.update("UPDATE task_check_executions SET state='UNKNOWN' WHERE attempt_id=?",f.attempt());
            }
            BooleanSupplier signal=call.getArgument(1);
            if(Set.of("journal-cancel","root-cancel","deadline","lease","slot").contains(drift))assertTrue(signal.getAsBoolean());
            return outcome(call.getArgument(0),TaskCheckExecutionOutcome.Termination.COMPLETED,0,TaskCheckExecutionOutcome.Presence.ABSENT,
                Map.of("target/surefire-reports/TEST-Trusted.xml",TaskCheckSelectionAssessmentTest.xml(1,0,0)));
        });
        // Slot externally changed is a database error, never an observation result.
        if(drift.equals("slot"))assertThrows(TaskCheckExecutionCoordinator.Unavailable.class,()->execute(f));
        else{var result=execute(f);assertEquals("REJECTED",result.state());assertTrue(result.observation().isEmpty());assertEquals(1,result.checks().size());}
        assertEquals("SEALED",journal.scanRoot(f.confirmed().chain().run(),null,128).getFirst().state());noTaskSuccess(f);
    }
    @Test void callerTransactionCannotEncloseFilesystemOrRunner()throws Exception {
        var f=fixture("slugify-semantic");var tx=new TransactionTemplate(base.helper.transactionManager);
        assertEquals(TaskCheckExecutionCoordinator.Reason.CALLER_TRANSACTION,assertThrows(TaskCheckExecutionCoordinator.Unavailable.class,()->tx.execute(s->execute(f))).reason());verifyNoInteractions(runner);
    }
    @Test void memoryCancellationRefusesBeforeReservation()throws Exception {
        var f=fixture("slugify-semantic");var tokens=context.getBean(com.dsh.agent.control.CancellationTokenRegistry.class);
        var token=tokens.register(f.confirmed().chain().run());token.cancel();
        assertThrows(RuntimeException.class,()->execute(f));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_check_executions WHERE attempt_id=?",Integer.class,f.attempt()));verifyNoInteractions(runner);
    }
    @Test void malformedRegistrationCannotSelectAnyCheck()throws Exception {
        var f=fixture("slugify-semantic");jdbc.update("UPDATE task_check_registrations SET snapshot_digest=? WHERE id=?","b".repeat(64),f.confirmed().registration());
        assertEquals(TaskCheckExecutionCoordinator.Reason.SELECTION_INVALID,assertThrows(TaskCheckExecutionCoordinator.Unavailable.class,()->execute(f)).reason());verifyNoInteractions(runner);
    }
    @ParameterizedTest @ValueSource(strings={"renewed","expired"})
    void oldPreparedCannotRegisterAcrossLeaseReservation(String mode)throws Exception {
        var f=fixture("slugify-semantic");var p=base.prepare(f);var preparation=spy(base.preparation);
        doAnswer(call->{base.preparation.requireCurrent(p);
            jdbc.update("UPDATE agent_task_attempts SET lease_until="+(mode.equals("renewed")?"lease_until+interval '1 second'":"now()-interval '1 second'")+" WHERE attempt_id=?",f.attempt());
            return null;}).when(preparation).requireCurrent(p);
        var boundJournal=new TaskCheckLaunchJournal(jdbc,base.helper.transactionManager,preparation,base.evidence,base.mapper,
            context.getBean(com.dsh.agent.control.CancellationTokenRegistry.class));
        assertEquals(TaskCheckLaunchJournal.Reason.INPUTS_STALE,assertThrows(TaskCheckLaunchJournal.Unavailable.class,()->boundJournal.register(p)).reason());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_check_launches WHERE attempt_id=?",Integer.class,f.attempt()));verifyNoInteractions(runner);
    }
}
