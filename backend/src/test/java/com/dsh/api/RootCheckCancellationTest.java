package com.dsh.api;

import com.dsh.agent.control.CancellationTokenRegistry;
import com.dsh.agent.control.RunControlService;
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

import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual PG/v2 confirmation/control services. Synthetic state mutations are not process cleanup evidence. */
@SpringBootTest
@AutoConfigureMockMvc
class RootCheckCancellationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        TaskCheckExecutionPreparationTest.properties(r);
        r.add("dsh.orchestration.recovery-scan-ms",()->"3600000");
    }
    @MockitoBean SandboxRunner runner;
    @MockitoBean com.dsh.orchestration.task.TaskSchedulingService scheduling;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskCheckLaunchJournal journal;
    @Autowired TaskCheckLifecycleGuard lifecycle;
    @Autowired RunControlService controls;
    @Autowired CancellationTokenRegistry tokens;
    TaskCheckAttemptEvidenceTest base;
    record Input(TaskCheckAttemptEvidenceTest.Fixture f,TaskCheckAttemptPreparation.Prepared p) {
        String root(){return f.confirmed().chain().run();}
        String turn(){return f.confirmed().chain().turn();}
        String thread(){return f.confirmed().chain().thread();}
    }
    @BeforeEach void setup() {
        base=new TaskCheckAttemptEvidenceTest();context.getAutowireCapableBeanFactory().autowireBean(base);
        base.runner=runner;base.setup();
    }
    Input input() throws Exception {
        var f=base.fixture();var p=base.prepare(f);
        jdbc.update("UPDATE threads SET active_turn_id=? WHERE id=?",f.confirmed().chain().turn(),f.confirmed().chain().thread());
        return new Input(f,p);
    }
    String launch(Input i,String state) {
        var row=journal.register(i.p());
        if(!"PENDING".equals(state)) jdbc.update("""
            UPDATE task_check_launches SET state=?,claim_owner=?,cancel_requested=?,manifest_json=? WHERE id=?
            """,state,state.equals("CANCELLED")?null:"old-controller",state.equals("CANCELLED"),
                state.equals("SEALED")?"{}":null,row.id());
        return row.id();
    }
    String launchRow(String id) {return jdbc.queryForObject("SELECT row_to_json(l)::text FROM task_check_launches l WHERE id=?",String.class,id);}
    void assertUnconfirmed(Input i)throws Exception {
        var root=base.runs.findById(i.root()).orElseThrow();
        assertEquals("BLOCKED",root.getState());assertEquals("RECOVERY_REQUIRED",root.getStopReason());
        assertEquals(Boolean.FALSE,root.isCleanupConfirmed());assertNotEquals("SETTLED",root.getBudgetUsageStatus());
        assertEquals(i.turn(),jdbc.queryForObject("SELECT active_turn_id FROM threads WHERE id=?",String.class,i.thread()));
        assertEquals(i.thread(),jdbc.queryForObject("SELECT active_key FROM turns WHERE id=?",String.class,i.turn()));
        assertEquals("BLOCKED",jdbc.queryForObject("SELECT state FROM turns WHERE id=?",String.class,i.turn()));
        base.helper.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/runs/{id}",i.root()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.state").value("BLOCKED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.stopReason").value("RECOVERY_REQUIRED"));
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","STARTING","UNKNOWN","SEALED","CANCELLED"})
    void rootCancellationPersistsIntentAndRefusesFalseCleanup(String state)throws Exception {
        var i=input();String id=launch(i,state);tokens.register(i.root());
        try {
            assertEquals("CANCELLING",controls.cancel(i.root(),"USER_CANCELLED").getState());
            assertTrue(tokens.get(i.root()).isCancelled());
            assertEquals(state.equals("PENDING")?"CANCELLED":state,jdbc.queryForObject("SELECT state FROM task_check_launches WHERE id=?",String.class,id));
            assertEquals(!state.equals("SEALED"),jdbc.queryForObject("SELECT cancel_requested FROM task_check_launches WHERE id=?",Boolean.class,id));
            String before=launchRow(id);controls.cancel(i.root(),"repeat");assertEquals(before,launchRow(id));
            controls.finalizeCancellation(i.root());assertUnconfirmed(i);
            assertThrows(ApiException.class,()->controls.resume(i.root()));
        }finally{tokens.release(i.root());}
        verifyNoInteractions(runner);
    }
    @ParameterizedTest @ValueSource(strings={"WAITING_INPUT","WAITING_APPROVAL"})
    void parkedRootCannotClaimUnknownCheckStopped(String state)throws Exception {
        var i=input();String id=launch(i,"UNKNOWN");
        jdbc.update("UPDATE runs SET state=? WHERE id=?",state,i.root());
        controls.cancel(i.root(),"USER_CANCELLED");assertUnconfirmed(i);
        assertTrue(jdbc.queryForObject("SELECT cancel_requested FROM task_check_launches WHERE id=?",Boolean.class,id));
    }
    @ParameterizedTest @ValueSource(strings={"RESERVED","OBSERVED","REJECTED","ABORTED","UNKNOWN"})
    void slotWithoutLaunchCannotServeAsCleanupProof(String state)throws Exception {
        var i=input();jdbc.update("""
            INSERT INTO task_check_executions (id,root_run_id,attempt_id,fencing_token,registration_id,
              check_refs_json,lease_until,root_deadline,state,created_at,updated_at)
            SELECT ?,?,attempt_id,fencing_token,?,'[]',lease_until,lease_until,?,now(),now()
              FROM agent_task_attempts WHERE attempt_id=?
            ""","checkexec_"+UUID.randomUUID(),i.root(),i.f().confirmed().registration(),state,i.f().attempt());
        jdbc.update("UPDATE runs SET state='WAITING_INPUT' WHERE id=?",i.root());
        controls.cancel(i.root(),"USER_CANCELLED");assertUnconfirmed(i);
    }
    @Test void cancellationIsScopedToRootIncludingOrphanedLaunches()throws Exception {
        var i=input();String own=launch(i,"UNKNOWN");var other=input();String foreign=launch(other,"PENDING");
        String unchanged=launchRow(foreign);
        jdbc.update("DELETE FROM agent_task_attempts WHERE attempt_id=?",i.f().attempt());
        controls.cancel(i.root(),"USER_CANCELLED");
        assertTrue(jdbc.queryForObject("SELECT cancel_requested FROM task_check_launches WHERE id=?",Boolean.class,own));
        assertEquals(unchanged,launchRow(foreign));
        new TransactionTemplate(base.helper.transactionManager).executeWithoutResult(tx->assertTrue(lifecycle.hasRootChecks(i.root())));
    }
    @Test void rootCancelIncludesOlderAttemptsAndTerminalTasks()throws Exception {
        var i=input();String old=launch(i,"UNKNOWN");
        jdbc.update("UPDATE agent_tasks SET state='SUCCEEDED' WHERE plan_id=?",i.f().confirmed().chain().plan());
        jdbc.update("INSERT INTO task_check_launches(id,root_run_id,attempt_id,fencing_token,check_id,operation_id,intent_digest,intent_json,state,cancel_requested,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'{}','PENDING',false,now(),now())",
                "checklaunch_"+UUID.randomUUID(),i.root(),"missing-old-attempt",1L,"older",UUID.randomUUID().toString().replace("-",""),"0".repeat(64));
        controls.cancel(i.root(),"USER_CANCELLED");
        assertTrue(jdbc.queryForObject("SELECT cancel_requested FROM task_check_launches WHERE id=?",Boolean.class,old));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM task_check_launches WHERE root_run_id=? AND cancel_requested",Integer.class,i.root()));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT state FROM agent_tasks WHERE plan_id=?",String.class,i.f().confirmed().chain().plan()));
    }
    @Test void terminalRootCancellationRepairsLateIntentIdempotently()throws Exception {
        var i=input();String id=launch(i,"PENDING");jdbc.update("UPDATE runs SET state='FAILED' WHERE id=?",i.root());
        assertEquals("FAILED",controls.cancel(i.root(),"USER_CANCELLED").getState());
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT state FROM task_check_launches WHERE id=?",String.class,id));
        String before=launchRow(id);controls.cancel(i.root(),"repeat");assertEquals(before,launchRow(id));
    }
    @Test void actualRootControlRollbackAlsoRollsBackLaunchCancellation()throws Exception {
        var i=input();String id=launch(i,"PENDING"),before=launchRow(id);
        String constraint="cancel_fault_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("ALTER TABLE runs ADD CONSTRAINT "+constraint+" CHECK (id<>'"+i.root()+"' OR state<>'CANCELLING')");
        try {assertThrows(RuntimeException.class,()->controls.cancel(i.root(),"USER_CANCELLED"));}
        finally {jdbc.execute("ALTER TABLE runs DROP CONSTRAINT "+constraint);}
        assertEquals(before,launchRow(id));assertEquals("RUNNING",base.runs.findById(i.root()).orElseThrow().getState());
        assertNull(base.runs.findById(i.root()).orElseThrow().getCancelRequestedAt());
    }
    @ParameterizedTest @ValueSource(strings={"root-control","cancel-flag","turn-state"})
    void registrationRechecksSourceAfterTransactionFreeObservation(String drift)throws Exception {
        var i=input();var checked=spy(base.preparation);
        doAnswer(call->{base.preparation.requireCurrent(i.p());
            switch(drift) {
                case "root-control"->controls.cancel(i.root(),"USER_CANCELLED");
                case "cancel-flag"->jdbc.update("UPDATE runs SET cancel_requested_at=now() WHERE id=?",i.root());
                default->jdbc.update("UPDATE turns SET state='CANCELLING' WHERE id=?",i.turn());
            }return null;
        }).when(checked).requireCurrent(i.p());
        var rebuilt=new TaskCheckLaunchJournal(jdbc,base.helper.transactionManager,checked,base.evidence,base.mapper,tokens);
        assertEquals(TaskCheckLaunchJournal.Reason.INPUTS_STALE,
                assertThrows(TaskCheckLaunchJournal.Unavailable.class,()->rebuilt.register(i.p())).reason());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_check_launches WHERE root_run_id=?",Integer.class,i.root()));
    }
    @Test void cancelledRootWithoutCheckFactsRetainsLegacyControl()throws Exception {
        var i=input();tokens.register(i.root());
        try {assertEquals("CANCELLING",controls.cancel(i.root(),"USER_CANCELLED").getState());
            assertEquals("CANCELLED",controls.finalizeCancellation(i.root()).getState());
            assertEquals(Boolean.TRUE,base.runs.findById(i.root()).orElseThrow().isCleanupConfirmed());
            assertNull(jdbc.queryForObject("SELECT active_turn_id FROM threads WHERE id=?",String.class,i.thread()));
        }finally{tokens.release(i.root());}
    }
    @Test void cancellingChildDoesNotCancelSiblingRootCheck()throws Exception {
        var i=input();String id=launch(i,"PENDING"),before=launchRow(id);
        jdbc.update("UPDATE runs SET state='WAITING_INPUT' WHERE id=?",i.f().child());
        assertEquals("CANCELLED",controls.cancel(i.f().child(),"USER_CANCELLED").getState());
        assertEquals(before,launchRow(id));
    }
    @Test void rootMethodsRequireCallerTransaction() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->lifecycle.cancelRootChecks("missing"));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->lifecycle.hasRootChecks("missing"));
    }
    void awaitThreadLock(Future<?> waiting)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
        while(System.nanoTime()<until) {
            blocked=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%threads%'",Integer.class)>0;
            if(blocked)break;Thread.sleep(20);
        }
        assertTrue(blocked,"Must wait for the shared thread source lock");assertFalse(waiting.isDone());
    }
    @Test void registrationWaitsForRootCancellationThenRefusesLateInsert()throws Exception {
        var i=input();var pool=Executors.newFixedThreadPool(2);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var writer=pool.submit(()->new TransactionTemplate(base.helper.transactionManager).executeWithoutResult(tx->{
                lifecycle.lockRoot(i.root());held.countDown();
                try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                lifecycle.cancelRootChecks(i.root());jdbc.update("UPDATE runs SET state='CANCELLING',cancel_requested_at=now() WHERE id=?",i.root());
            }));
            assertTrue(held.await(10,TimeUnit.SECONDS));
            var reader=pool.submit(()->assertThrows(TaskCheckLaunchJournal.Unavailable.class,()->journal.register(i.p())));
            awaitThreadLock(reader);release.countDown();writer.get(10,TimeUnit.SECONDS);
            assertEquals(TaskCheckLaunchJournal.Reason.INPUTS_STALE,reader.get(10,TimeUnit.SECONDS).reason());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_check_launches WHERE root_run_id=?",Integer.class,i.root()));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void rootControlRefreshesVersionAfterWaitingForSourceLock()throws Exception {
        var i=input();tokens.register(i.root());var pool=Executors.newFixedThreadPool(2);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var writer=pool.submit(()->new TransactionTemplate(base.helper.transactionManager).executeWithoutResult(tx->{
                jdbc.queryForList("SELECT id FROM threads WHERE id=? FOR UPDATE",String.class,i.thread());held.countDown();
                try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                jdbc.update("UPDATE runs SET version=version+1 WHERE id=?",i.root());
            }));
            assertTrue(held.await(10,TimeUnit.SECONDS));var reader=pool.submit(()->controls.cancel(i.root(),"USER_CANCELLED"));
            awaitThreadLock(reader);release.countDown();writer.get(10,TimeUnit.SECONDS);
            assertEquals("CANCELLING",reader.get(10,TimeUnit.SECONDS).getState());
            controls.finalizeCancellation(i.root());
        }finally{release.countDown();pool.shutdownNow();tokens.release(i.root());}
    }
}
