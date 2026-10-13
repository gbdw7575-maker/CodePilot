package com.dsh.api;

import com.dsh.orchestration.acceptance.*;
import com.dsh.orchestration.task.TaskEventJournal;
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
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PG/confirmation/files/receipt/state/events; Runner is substituted, not a Docker check control. */
@SpringBootTest @AutoConfigureMockMvc
class TaskCheckCompletionTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){TaskCheckExecutionPreparationTest.properties(r);}
    @MockitoBean SandboxRunner runner;
    @MockitoBean com.dsh.orchestration.task.TaskSchedulingService scheduling;
    @MockitoSpyBean TaskCheckSelectionAssessment assessment;
    @MockitoSpyBean TaskEventJournal events;
    @Autowired ApplicationContext context;
    @Autowired TaskCheckCompletionService completion;
    @Autowired TaskCheckExecutionCoordinator coordinator;
    @Autowired JdbcTemplate jdbc;
    TaskCheckExecutionCoordinatorTest helper;
    boolean lastMutationApplied;
    @BeforeEach void setup(){helper=new TaskCheckExecutionCoordinatorTest();context.getAutowireCapableBeanFactory().autowireBean(helper);
        helper.runner=runner;helper.setup();}
    TaskCheckAttemptEvidenceTest.Fixture fixture(String... checks)throws Exception{
        var f=helper.fixture(checks);strong(f);return f;
    }
    void strong(TaskCheckAttemptEvidenceTest.Fixture f){
        // Explicit test DB mode fixture; this is not a claim that human opt-in/production dispatch ran.
        jdbc.update("INSERT INTO root_task_acceptance_modes(root_run_id,mode,frozen_at) VALUES(?,'EXECUTABLE_CHECKS',now())",f.confirmed().chain().run());
        jdbc.update("UPDATE threads SET active_turn_id=? WHERE id=?",f.confirmed().chain().turn(),f.confirmed().chain().thread());
    }
    TaskCheckExecutionCoordinator.Result observed(TaskCheckAttemptEvidenceTest.Fixture f)throws Exception{helper.success();var r=helper.execute(f);assertEquals("OBSERVED",r.state());return r;}
    void unchanged(TaskCheckAttemptEvidenceTest.Fixture f){helper.noTaskSuccess(f);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_check_completions WHERE attempt_id=?",Integer.class,f.attempt()));}
    void afterIo(Runnable mutation){doAnswer(call->{call.callRealMethod();
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        mutation.run();lastMutationApplied=true;return null;}).when(assessment).requireCurrent(any());}
    @ParameterizedTest @ValueSource(strings={"single","multiple"})
    void fullSelectionCommitsOneImmutableReceiptWithRealBytesAndSameTransactionEvents(String size)throws Exception{
        var f=size.equals("single")?fixture("slugify-semantic"):fixture("slugify-semantic","second-semantic");
        var r=observed(f);var committed=completion.commit(r);var o=r.checks().getFirst().prepared().observed();
        assertEquals(f.attempt(),committed.attemptId());assertEquals(r.executionId(),committed.executionId());
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT state FROM agent_tasks WHERE id=?",String.class,o.task().rowId()));
        var a=jdbc.queryForMap("SELECT state,lease_owner,lease_until FROM agent_task_attempts WHERE attempt_id=?",f.attempt());
        assertEquals("SUCCEEDED",a.get("state"));assertNull(a.get("lease_owner"));assertNull(a.get("lease_until"));
        assertEquals("COMMITTED",jdbc.queryForObject("SELECT state FROM task_check_executions WHERE id=?",String.class,r.executionId()));
        String json=jdbc.queryForObject("SELECT receipt_json FROM task_check_completions WHERE execution_id=?",String.class,r.executionId());
        assertArrayEquals(json.getBytes(java.nio.charset.StandardCharsets.UTF_8),helper.base.artifacts.readVerifiedContent(committed.artifactId(),2*1048576));
        assertEquals(CanonicalTaskJson.sha256(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)),committed.receiptDigest());
        var receipt=helper.base.mapper.readTree(json);assertEquals(r.checks().size(),receipt.path("checks").size());
        var row=helper.base.results.findById(f.attempt()).orElseThrow();assertEquals("SUCCEEDED",row.getState());
        assertNull(row.getVerificationRef());assertNull(row.getPatchArtifactId());assertEquals(receipt.path("deliveryDigest").asText(),row.getOutputDigest());
        assertTrue(helper.base.mapper.readTree(row.getArtifactRefsJson()).toString().contains(committed.artifactId()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM agent_task_events WHERE attempt_id=? AND state='SUCCEEDED'",Integer.class,f.attempt()));
        verify(runner,times(r.checks().size())).executeCheck(any(),any());
    }
    @Test void usageIsCopiedFromClosedChildRatherThanInventedZero()throws Exception{
        var f=fixture("slugify-semantic");var r=observed(f);
        jdbc.update("UPDATE runs SET budget_model_requests_used=7,budget_tool_calls_used=11,budget_settled_tokens=1234,updated_at=created_at+interval '2 seconds' WHERE id=?",f.child());
        completion.commit(r);var row=helper.base.results.findById(f.attempt()).orElseThrow();
        assertEquals(7,row.getModelRequestsUsed());assertEquals(11,row.getToolCallsUsed());assertEquals(1234,row.getSettledTokens());assertEquals(2000,row.getDurationMs());
    }
    @Test void repeatedResultNeverWritesSecondReceiptOrResultOrRunsAgain()throws Exception{
        var f=fixture("slugify-semantic");var r=observed(f);completion.commit(r);
        assertEquals(TaskCheckCompletionService.Reason.ALREADY_COMMITTED,assertThrows(TaskCheckCompletionService.Unavailable.class,()->completion.commit(r)).reason());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM task_check_completions WHERE attempt_id=?",Integer.class,f.attempt()));verify(runner,times(1)).executeCheck(any(),any());
    }
    @Test void simultaneousCommittersHaveExactlyOneWinner()throws Exception{
        var f=fixture("slugify-semantic");var r=observed(f);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{Callable<Boolean> call=()->{assertTrue(start.await(10,TimeUnit.SECONDS));try{completion.commit(r);return true;}catch(RuntimeException refused){return false;}};
            var one=pool.submit(call);var two=pool.submit(call);start.countDown();assertNotEquals(one.get(60,TimeUnit.SECONDS),two.get(60,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM task_check_completions WHERE attempt_id=?",Integer.class,f.attempt()));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM agent_task_results WHERE attempt_id=?",Integer.class,f.attempt()));
        }finally{pool.shutdownNow();}
    }
    @Test void oldCallbackCannotCreateOrChangeStrongSuccess()throws Exception{
        var f=fixture("slugify-semantic");var r=observed(f);completion.commit(r);
        var actual=context.getBean(com.dsh.orchestration.acceptance.TaskCheckLifecycleGuard.class);
        assertTrue(Boolean.TRUE.equals(new TransactionTemplate(helper.base.helper.transactionManager).execute(s->actual.blocksLegacy(f.attempt()))));
    }
    @Test void callerWriteTransactionCannotEncloseArtifactIo()throws Exception{
        var f=fixture("slugify-semantic");var r=observed(f);var tx=new TransactionTemplate(helper.base.helper.transactionManager);
        assertEquals(TaskCheckCompletionService.Reason.CALLER_TRANSACTION,assertThrows(TaskCheckCompletionService.Unavailable.class,()->tx.execute(s->completion.commit(r))).reason());unchanged(f);
    }
    @Test void nullInputIsRejected(){assertEquals(TaskCheckCompletionService.Reason.INVALID_INPUT,assertThrows(TaskCheckCompletionService.Unavailable.class,()->completion.commit(null)).reason());}
    @ParameterizedTest @ValueSource(strings={"failure","skip","timeout","unknown"})
    void failedOrUncertainExecutionCannotBeCommitted(String mode)throws Exception{
        var f=fixture("slugify-semantic");when(runner.executeCheck(any(),any())).thenAnswer(call->TaskCheckExecutionCoordinatorTest.outcome(call.getArgument(0),
            mode.equals("timeout")?TaskCheckExecutionOutcome.Termination.TIMEOUT:TaskCheckExecutionOutcome.Termination.COMPLETED,mode.equals("timeout")?null:0,
            mode.equals("unknown")?TaskCheckExecutionOutcome.Presence.UNKNOWN:TaskCheckExecutionOutcome.Presence.ABSENT,
            Map.of("target/surefire-reports/TEST-Trusted.xml",TaskCheckSelectionAssessmentTest.xml(1,mode.equals("failure")?1:0,mode.equals("skip")?1:0))));
        var r=helper.execute(f);assertNotEquals("OBSERVED",r.state());assertThrows(TaskCheckCompletionService.Unavailable.class,()->completion.commit(r));unchanged(f);
    }
    @ParameterizedTest @ValueSource(strings={"root-state","root-cancel","turn","active-key","thread-pointer","turn-input","message-thread","plan-revision","task-version","task-goal","task-state","fence","owner","lease","expired","deadline","child-state","child-cancel","input","requirement","registration","slot","launch","artifact","project-trust","project-path","project-version","other-project","project-insert","project-delete"})
    void lastIoObservationCannotAuthorizeChangedDatabaseBinding(String drift)throws Exception{
        var f=fixture("slugify-semantic");var c=f.confirmed().chain();
        String auxiliary="proj_aux_"+UUID.randomUUID().toString().replace("-","");
        if(Set.of("other-project","project-delete").contains(drift))helper.base.helper.projects.save(new com.dsh.storage.ProjectEntity(
                auxiliary,"auxiliary",f.lease().dir().toString(),false,java.time.Instant.now(),java.time.Instant.now()));
        var r=observed(f);
        afterIo(()->{switch(drift){
            case "root-state"->jdbc.update("UPDATE runs SET state='CANCELLING' WHERE id=?",c.run());
            case "root-cancel"->jdbc.update("UPDATE runs SET cancel_requested_at=now() WHERE id=?",c.run());
            case "turn"->jdbc.update("UPDATE turns SET state='CANCELLING' WHERE id=?",c.turn());
            case "active-key"->jdbc.update("UPDATE turns SET active_key=NULL WHERE id=?",c.turn());
            case "thread-pointer"->jdbc.update("UPDATE threads SET active_turn_id=NULL WHERE id=?",c.thread());
            case "turn-input"->jdbc.update("UPDATE turns SET input='changed' WHERE id=?",c.turn());
            case "message-thread"->jdbc.update("UPDATE messages SET thread_id='different-thread' WHERE turn_id=?",c.turn());
            case "plan-revision"->jdbc.update("UPDATE agent_plans SET revision=revision+1 WHERE id=?",c.plan());
            case "task-version"->jdbc.update("UPDATE agent_tasks SET task_version=2 WHERE plan_id=? AND task_id='A'",c.plan());
            case "task-goal"->jdbc.update("UPDATE agent_tasks SET goal='changed' WHERE plan_id=? AND task_id='A'",c.plan());
            case "task-state"->jdbc.update("UPDATE agent_tasks SET state='CANCELLING' WHERE plan_id=? AND task_id='A'",c.plan());
            case "fence"->jdbc.update("UPDATE agent_task_attempts SET fencing_token=3 WHERE attempt_id=?",f.attempt());
            case "owner"->jdbc.update("UPDATE agent_task_attempts SET lease_owner='other' WHERE attempt_id=?",f.attempt());
            case "lease"->jdbc.update("UPDATE agent_task_attempts SET lease_until=lease_until+interval '1 second' WHERE attempt_id=?",f.attempt());
            case "expired"->jdbc.update("UPDATE agent_task_attempts SET lease_until=now()-interval '1 second' WHERE attempt_id=?",f.attempt());
            case "deadline"->jdbc.update("UPDATE runs SET budget_deadline_at=budget_deadline_at+interval '1 second' WHERE id=?",c.run());
            case "child-state"->jdbc.update("UPDATE runs SET state='RUNNING' WHERE id=?",f.child());
            case "child-cancel"->jdbc.update("UPDATE runs SET cancel_requested_at=now() WHERE id=?",f.child());
            case "input"->jdbc.update("UPDATE agent_input_snapshots SET base_digest=? WHERE run_id=?","a".repeat(64),f.child());
            case "requirement"->jdbc.update("UPDATE messages SET content='changed' WHERE turn_id=? AND role='user'",c.turn());
            case "registration"->jdbc.update("UPDATE task_check_registrations SET snapshot_digest=? WHERE id=?","b".repeat(64),f.confirmed().registration());
            case "slot"->jdbc.update("UPDATE task_check_executions SET check_refs_json='[]' WHERE id=?",r.executionId());
            case "launch"->jdbc.update("UPDATE task_check_launches SET cancel_requested=true WHERE attempt_id=?",f.attempt());
            case "artifact"->jdbc.update("UPDATE artifacts SET sha256=? WHERE id=?","c".repeat(64),r.checks().getFirst().sealed().manifest().id());
            case "project-trust"->context.getBean(com.dsh.application.ProjectService.class).trust(c.project(),false);
            case "project-path"->jdbc.update("UPDATE projects SET root_path=? WHERE id=?",f.lease().dir().toString(),c.project());
            case "project-version"->jdbc.update("UPDATE projects SET version=version+1 WHERE id=?",c.project());
            case "other-project"->jdbc.update("UPDATE projects SET trusted=true WHERE id=?",auxiliary);
            case "project-insert"->helper.base.helper.projects.save(new com.dsh.storage.ProjectEntity(auxiliary,"late",f.lease().dir().toString(),false,java.time.Instant.now(),java.time.Instant.now()));
            case "project-delete"->jdbc.update("DELETE FROM projects WHERE id=?",auxiliary);
        }});
        assertThrows(RuntimeException.class,()->completion.commit(r));
        assertTrue(lastMutationApplied,"The intended DB drift must actually finish before CAS refusal");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM agent_task_results WHERE attempt_id=?",Integer.class,f.attempt()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_check_completions WHERE attempt_id=?",Integer.class,f.attempt()));
        assertNotEquals("SUCCEEDED",jdbc.queryForObject("SELECT state FROM agent_task_attempts WHERE attempt_id=?",String.class,f.attempt()));
    }
    @Test void projectRevocationCannotCrossTheFinalTransaction()throws Exception{
        var f=fixture("slugify-semantic");var r=observed(f);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        doAnswer(call->{var value=call.callRealMethod();entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return value;})
            .when(events).append(anyString(),anyString(),anyString(),anyInt(),eq(f.attempt()),anyString(),eq("VERIFYING"),eq("SUCCEEDED"),anyString());
        try{
            var committing=pool.submit(()->completion.commit(r));assertTrue(entered.await(30,TimeUnit.SECONDS));
            var revoking=pool.submit(()->context.getBean(com.dsh.application.ProjectService.class).trust(f.confirmed().chain().project(),false));
            boolean waiting=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(System.nanoTime()<until){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND lower(query) LIKE '%update projects%'",Integer.class)>0){waiting=true;break;}Thread.sleep(10);}
            assertTrue(waiting,"Revocation must really wait on the final transaction, not race through it");
            release.countDown();assertNotNull(committing.get(10,TimeUnit.SECONDS));assertNotNull(revoking.get(10,TimeUnit.SECONDS));
            assertEquals(Boolean.FALSE,jdbc.queryForObject("SELECT trusted FROM projects WHERE id=?",Boolean.class,f.confirmed().chain().project()));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM task_check_completions WHERE attempt_id=?",Integer.class,f.attempt()));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @ParameterizedTest @ValueSource(strings={"root","task"})
    void productionCancellationAfterFinalIoWinsBeforeSuccess(String kind)throws Exception{
        var f=fixture("slugify-semantic");var r=observed(f);
        afterIo(()->{if(kind.equals("root"))context.getBean(com.dsh.agent.control.RunControlService.class).cancel(f.confirmed().chain().run(),"cancel race");
            else context.getBean(com.dsh.orchestration.task.TaskCancelService.class).cancel(f.confirmed().chain().run(),List.of("A"),"cancel race");});
        assertThrows(RuntimeException.class,()->completion.commit(r));
        assertTrue(lastMutationApplied,"Production cancellation must actually complete before CAS refusal");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_check_completions WHERE attempt_id=?",Integer.class,f.attempt()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM agent_task_results WHERE attempt_id=?",Integer.class,f.attempt()));
        assertNotEquals("SUCCEEDED",jdbc.queryForObject("SELECT state FROM agent_task_attempts WHERE attempt_id=?",String.class,f.attempt()));
    }
    @ParameterizedTest @ValueSource(strings={"task","result","artifact","fence"})
    void upstreamDatabaseDriftAfterFinalIoStillRefuses(String mode)throws Exception{
        var u=helper.base.upstream();var f=u.fixture();strong(f);
        // The upstream fixture keeps the real catalog minimum (unlike helper.fixture's minimum=1).
        when(runner.executeCheck(any(),any())).thenAnswer(call->TaskCheckExecutionCoordinatorTest.outcome(call.getArgument(0),
                TaskCheckExecutionOutcome.Termination.COMPLETED,0,TaskCheckExecutionOutcome.Presence.ABSENT,
                Map.of("target/surefire-reports/TEST-Trusted.xml",TaskCheckSelectionAssessmentTest.xml(4,0,0))));
        var r=helper.execute(f);assertEquals("OBSERVED",r.state());var upstream=r.checks().getFirst().prepared().observed().upstream().getFirst();
        afterIo(()->{switch(mode){
            case "task"->jdbc.update("UPDATE agent_tasks SET state='FAILED' WHERE plan_id=? AND task_id='U'",f.confirmed().chain().plan());
            case "result"->jdbc.update("UPDATE agent_task_results SET output_digest=? WHERE attempt_id=?","d".repeat(64),upstream.attemptId());
            case "artifact"->jdbc.update("UPDATE artifacts SET sha256=? WHERE id=?","e".repeat(64),u.artifact().getId());
            case "fence"->jdbc.update("UPDATE agent_task_attempts SET fencing_token=9 WHERE attempt_id=?",upstream.attemptId());
        }});assertThrows(RuntimeException.class,()->completion.commit(r));assertTrue(lastMutationApplied);unchanged(f);
    }
    @ParameterizedTest @ValueSource(strings={"legacy","unfrozen"})
    void modeMustAlreadyBeExplicitlyStrong(String mode)throws Exception{
        var f=fixture("slugify-semantic");var r=observed(f);
        if(mode.equals("legacy"))jdbc.update("UPDATE root_task_acceptance_modes SET mode='LEGACY_COVERAGE_ONLY' WHERE root_run_id=?",f.confirmed().chain().run());
        else jdbc.update("DELETE FROM root_task_acceptance_modes WHERE root_run_id=?",f.confirmed().chain().run());
        assertEquals(TaskCheckCompletionService.Reason.NOT_STRONG,assertThrows(TaskCheckCompletionService.Unavailable.class,()->completion.commit(r)).reason());unchanged(f);
    }
    @Test void eventFailureRollsBackReceiptResultLeaseAndBothStates()throws Exception{
        var f=fixture("slugify-semantic");var r=observed(f);var lease=helper.lease(f);
        doThrow(new IllegalStateException("simulated event write failure")).when(events).append(anyString(),anyString(),anyString(),anyInt(),eq(f.attempt()),anyString(),eq("VERIFYING"),eq("SUCCEEDED"),anyString());
        assertThrows(RuntimeException.class,()->completion.commit(r));unchanged(f);assertEquals(lease,helper.lease(f));
        assertEquals("OBSERVED",jdbc.queryForObject("SELECT state FROM task_check_executions WHERE id=?",String.class,r.executionId()));
    }
    @Test void expiryDuringEventWritesStillRollsBackSuccess()throws Exception{
        var f=fixture("slugify-semantic");var r=observed(f);
        doAnswer(call->{var value=call.callRealMethod();jdbc.update("UPDATE task_check_executions SET lease_until=now()-interval '1 second' WHERE id=?",r.executionId());return value;})
            .when(events).append(anyString(),anyString(),anyString(),anyInt(),eq(f.attempt()),anyString(),eq("VERIFYING"),eq("SUCCEEDED"),anyString());
        assertThrows(RuntimeException.class,()->completion.commit(r));unchanged(f);
        assertEquals("OBSERVED",jdbc.queryForObject("SELECT state FROM task_check_executions WHERE id=?",String.class,r.executionId()));
    }
    @ParameterizedTest @ValueSource(strings={"report","manifest","delivery","candidate"})
    void actualBytesMustRemainCurrentBeforeTransaction(String part)throws Exception{
        var f=fixture("slugify-semantic");var r=observed(f);var sealed=r.checks().getFirst().sealed();
        if(part.equals("candidate"))Files.writeString(f.lease().dir().resolve("src/main/java/sample/Value.java"),"package sample; public class Value {}\n");
        else{var ref=part.equals("manifest")?sealed.manifest():sealed.members().stream().filter(a->a.mediaType().equals(part.equals("report")?"application/xml":"application/zip")).findFirst().orElseThrow();
            Files.writeString(f.lease().metaDir().resolve("artifacts").resolve(ref.id()),"corrupted");}
        assertThrows(RuntimeException.class,()->completion.commit(r));unchanged(f);
    }
}
