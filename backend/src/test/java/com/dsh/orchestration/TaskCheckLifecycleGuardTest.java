package com.dsh.orchestration;

import com.dsh.common.IdGen;
import com.dsh.orchestration.acceptance.TaskCheckLifecycleGuard;
import com.dsh.orchestration.acceptance.RootTaskAcceptanceModes;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.orchestration.plan.PlanService;
import com.dsh.orchestration.task.TaskCancelService;
import com.dsh.orchestration.task.TaskSchedulingService;
import com.dsh.orchestration.task.TaskRecoveryService;
import com.dsh.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PostgreSQL/services. Synthetic (even damaged) durable facts must block legacy authority. No Runner/model. */
@SpringBootTest
@TestPropertySource(properties={"dsh.policy.require-for-side-effects=NONE","dsh.orchestration.recovery-scan-ms=3600000"})
class TaskCheckLifecycleGuardTest {
    @Autowired TaskCheckLifecycleGuard guard;
    @Autowired RootTaskAcceptanceModes modes;
    @Autowired TaskSchedulingService scheduling;
    @Autowired TaskRecoveryService recovery;
    @Autowired TaskCancelService cancellation;
    @Autowired PlanService plans;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ProjectRepository projects;
    @Autowired ModelProfileRepository profiles;
    @Autowired ThreadRepository threads;
    @Autowired TurnRepository turns;
    @Autowired RunRepository runs;
    @Autowired TaskRepository tasks;
    @Autowired TaskAttemptRepository attempts;
    @TempDir Path projectRoot;

    record Fixture(String root,String plan,String attempt) { }
    Fixture fixture(String taskState,String attemptState) {
        Instant now=Instant.now();
        String project=IdGen.next("proj_lifecycle_"),profile=IdGen.next("prof_lifecycle_");
        projects.save(new ProjectEntity(project,"Lifecycle",projectRoot.toString(),true,now,now));
        profiles.save(new ModelProfileEntity(profile,"fake-fake-ma05","fake","fake-ma05","http://127.0.0.1:9",
                null,"disabled",null,4096,false,"ACTIVE",now,now));
        String thread=IdGen.next("thr_lifecycle_"),turn=IdGen.next("turn_lifecycle_"),root=IdGen.next("run_lifecycle_");
        threads.save(new ThreadEntity(thread,project,"Lifecycle",profile,now,now));
        turns.save(new TurnEntity(turn,thread,root,"Task","RUNNING","PENDING",thread,now,now));
        runs.save(new RunEntity(root,turn,null,"MAIN","RUNNING","PENDING",1,IdGen.next("ws_"),"digest",
                30,120,320000,now.plusSeconds(600),now,now));
        String plan=plans.saveDraft(root,new PlanDraft(null,null,"Lifecycle",List.of(
                new TaskDraft("A","Task",List.of(),List.of(),"Deliver",List.of("Check"),null,"DELEGATED",true)))).planId();
        var task=tasks.findByPlanIdAndTaskId(plan,"A").orElseThrow();
        task.setState(taskState);tasks.save(task);
        String attempt=IdGen.next("att_lifecycle_");
        attempts.save(new TaskAttemptEntity(attempt,plan,"A",task.getTaskVersion(),null,1,"old-worker",
                now.minusSeconds(60),9L,null,attemptState,now,now));
        return new Fixture(root,plan,attempt);
    }
    String launch(Fixture f,String state,long fence) {
        String id=IdGen.next("checklaunch_");
        jdbc.update("""
                INSERT INTO task_check_launches (id,root_run_id,attempt_id,fencing_token,check_id,operation_id,
                intent_digest,intent_json,state,claim_owner,cancel_requested,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'{}',?,'old-controller',false,now(),now())
                """,id,f.root(),f.attempt(),fence,"check",java.util.UUID.randomUUID().toString().replace("-",""),"0".repeat(64),state);
        return id;
    }
    void slot(Fixture f,String state) {
        jdbc.update("""
                INSERT INTO task_check_executions (id,root_run_id,attempt_id,fencing_token,registration_id,
                check_refs_json,lease_until,root_deadline,state,created_at,updated_at)
                VALUES (?,?,?,?,?,'[]',?,?,?,now(),now())
                """,IdGen.next("checkexec_"),f.root(),f.attempt(),1L,"old-reg",
                Timestamp.from(Instant.now().minusSeconds(60)),Timestamp.from(Instant.now().plusSeconds(600)),state);
    }
    String attemptRow(Fixture f) {
        return jdbc.queryForObject("SELECT row_to_json(a)::text FROM agent_task_attempts a WHERE attempt_id=?",String.class,f.attempt());
    }
    String taskRow(Fixture f) {
        return jdbc.queryForObject("SELECT row_to_json(t)::text FROM agent_tasks t WHERE plan_id=? AND task_id='A'",String.class,f.plan());
    }
    void refuseAllLegacy(Fixture f) {
        String before=attemptRow(f),taskBefore=taskRow(f);
        assertFalse(scheduling.claim(f.attempt(),"replacement",Instant.now().plusSeconds(600)).claimed());
        scheduling.requeue(f.attempt(),"CONCURRENCY_LIMIT");
        scheduling.requeue(f.attempt(),"ENVIRONMENT_ERROR");
        assertFalse(scheduling.reapExpiredLease(f.attempt(),Instant.now()));
        scheduling.complete(f.attempt(),new SubagentResult("late-child","CODER","COMPLETED",null,"Done",
                List.of(),null,"","",0,0,null,0));
        scheduling.complete(f.attempt(),new SubagentResult("late-child","CODER","CANCELLED","CANCELLED","Cancelled",
                List.of(),null,"","",0,0,null,0));
        assertEquals(before,attemptRow(f));assertEquals(taskBefore,taskRow(f));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM agent_task_results WHERE attempt_id=?",Integer.class,f.attempt()));
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","STARTING","UNKNOWN","SEALED","CANCELLED"})
    void launchHistoryBlocksEveryLegacyPathRegardlessOfCurrentFence(String state) {
        var f=fixture("VERIFYING","RUNNING");launch(f,state,1);refuseAllLegacy(f);
    }
    @ParameterizedTest @ValueSource(strings={"RESERVED","OBSERVED","REJECTED","ABORTED","UNKNOWN"})
    void executionSlotWithoutLaunchBlocksEveryLegacyPath(String state) {
        var f=fixture("VERIFYING","RUNNING");slot(f,state);refuseAllLegacy(f);
    }
    @Test void queuedAttemptCannotHideOldUnknownByChangingFence() {
        var f=fixture("READY","QUEUED");launch(f,"UNKNOWN",1);refuseAllLegacy(f);
    }
    @Test void expiryDoesNotClaimUnknownProcessCancellationComplete() {
        var f=fixture("CANCELLING","RUNNING");launch(f,"UNKNOWN",1);refuseAllLegacy(f);
    }
    @Test void frozenStrongRootWithoutFactsCannotUseLegacyPaths() {
        var f=fixture("RUNNING","RUNNING");
        jdbc.update("INSERT INTO root_task_acceptance_modes(root_run_id,mode,frozen_at) VALUES (?,'EXECUTABLE_CHECKS',now())",f.root());
        refuseAllLegacy(f);
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","STARTING","UNKNOWN","SEALED","CANCELLED"})
    void taskCancellationDurablyCancelsIntentsWithoutClaimingProcessStopped(String state) {
        var f=fixture("VERIFYING","RUNNING");String id=launch(f,state,1);
        var result=cancellation.cancel(f.root(),List.of("A"),"user");
        assertTrue(result.getFirst().accepted());assertEquals("CANCELLING",result.getFirst().state());
        boolean active=List.of("PENDING","STARTING","UNKNOWN").contains(state);
        String expected=state.equals("PENDING")?"CANCELLED":state;
        assertEquals(expected,jdbc.queryForObject("SELECT state FROM task_check_launches WHERE id=?",String.class,id));
        assertEquals(active,jdbc.queryForObject("SELECT cancel_requested FROM task_check_launches WHERE id=?",Boolean.class,id));
        String row=jdbc.queryForObject("SELECT row_to_json(l)::text FROM task_check_launches l WHERE id=?",String.class,id);
        cancellation.cancel(f.root(),List.of("A"),"repeat");
        assertEquals(row,jdbc.queryForObject("SELECT row_to_json(l)::text FROM task_check_launches l WHERE id=?",String.class,id));
        refuseAllLegacy(f);
    }
    @Test void cancellationIncludesOldAttemptGeneration() {
        var f=fixture("VERIFYING","RUNNING");String old=launch(f,"UNKNOWN",1);
        var current=attempts.findById(f.attempt()).orElseThrow();
        var newer=new TaskAttemptEntity(IdGen.next("att_new_"),f.plan(),"A",current.getTaskVersion(),null,2,
                "new-worker",Instant.now().plusSeconds(600),10L,null,"RUNNING",Instant.now(),Instant.now());
        attempts.save(newer);String pending=launch(new Fixture(f.root(),f.plan(),newer.getAttemptId()),"PENDING",10);
        cancellation.cancel(f.root(),List.of("A"),"user");
        assertTrue(jdbc.queryForObject("SELECT cancel_requested FROM task_check_launches WHERE id=?",Boolean.class,old));
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT state FROM task_check_launches WHERE id=?",String.class,old));
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT state FROM task_check_launches WHERE id=?",String.class,pending));
    }
    @Test void cancellationRollbackDoesNotLeaveDurableFlags() {
        var f=fixture("VERIFYING","RUNNING");String id=launch(f,"PENDING",1),before=taskRow(f);
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            cancellation.cancel(f.root(),List.of("A"),"rollback");
            assertTrue(jdbc.queryForObject("SELECT cancel_requested FROM task_check_launches WHERE id=?",Boolean.class,id));
            tx.setRollbackOnly();
        });
        assertEquals(before,taskRow(f));assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM task_check_launches WHERE id=?",String.class,id));
        assertFalse(jdbc.queryForObject("SELECT cancel_requested FROM task_check_launches WHERE id=?",Boolean.class,id));
    }
    @Test void queuedStateCannotPretendAnOlderUnknownCheckStopped() {
        var f=fixture("READY","QUEUED");String id=launch(f,"UNKNOWN",1),before=attemptRow(f);
        assertEquals("CANCELLING",cancellation.cancel(f.root(),List.of("A"),"user").getFirst().state());
        assertEquals(before,attemptRow(f));assertEquals("UNKNOWN",jdbc.queryForObject("SELECT state FROM task_check_launches WHERE id=?",String.class,id));
        assertTrue(jdbc.queryForObject("SELECT cancel_requested FROM task_check_launches WHERE id=?",Boolean.class,id));
        refuseAllLegacy(f);
    }
    @Test void slotWithoutLaunchCannotPretendCancellationCompleted() {
        var f=fixture("READY","QUEUED");slot(f,"RESERVED");String before=attemptRow(f);
        assertEquals("CANCELLING",cancellation.cancel(f.root(),List.of("A"),"user").getFirst().state());
        assertEquals(before,attemptRow(f));refuseAllLegacy(f);
    }
    @Test void guardsRequireCallerTransaction() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->guard.blocksLegacy("missing"));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->guard.cancelChecks("missing"));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->guard.lockRoot("missing"));
    }
    @Test void orphanedPlanCannotHideDurableCheckHistory() {
        var f=fixture("VERIFYING","RUNNING");launch(f,"UNKNOWN",1);
        jdbc.update("DELETE FROM agent_plans WHERE id=?",f.plan());refuseAllLegacy(f);
    }
    @Test void taskFenceIgnoresUnrelatedTaskButTracksReplacement() {
        var f=fixture("RUNNING","RUNNING");var current=attempts.findById(f.attempt()).orElseThrow();
        launch(f,"UNKNOWN",1);
        attempts.save(new TaskAttemptEntity(IdGen.next("att_other_"),f.plan(),"B",current.getTaskVersion(),null,1,
                "other-worker",Instant.now().plusSeconds(600),10L,null,"RUNNING",Instant.now(),Instant.now()));
        assertEquals(10,attempts.maxFencingToken(List.of(f.plan())));
        assertEquals(9,attempts.maxTaskFencingToken(f.plan(),"A"));
        attempts.save(new TaskAttemptEntity(IdGen.next("att_replace_"),f.plan(),"A",current.getTaskVersion(),null,2,
                "replacement",Instant.now().plusSeconds(600),11L,null,"RUNNING",Instant.now(),Instant.now()));
        assertEquals(11,attempts.maxTaskFencingToken(f.plan(),"A"));assertEquals(10,attempts.maxTaskFencingToken(f.plan(),"B"));
    }
    @Test void concurrentRootVersionChangeBeforeSourceLockDoesNotStrandLegacyRetry() throws Exception {
        var f=fixture("RUNNING","RUNNING");var pool=Executors.newFixedThreadPool(2);
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        String thread=jdbc.queryForObject("SELECT u.thread_id FROM turns u JOIN runs r ON r.turn_id=u.id WHERE r.id=?",String.class,f.root());
        try {
            var writer=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                jdbc.queryForList("SELECT id FROM threads WHERE id=? FOR UPDATE",String.class,thread);held.countDown();
                try {assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                jdbc.update("UPDATE runs SET version=version+1 WHERE id=?",f.root());
            }));
            assertTrue(held.await(10,TimeUnit.SECONDS));
            var reader=pool.submit(()->scheduling.requeue(f.attempt(),"CONCURRENCY_LIMIT"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
            while(System.nanoTime()<deadline) {
                blocked=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%threads%'",Integer.class)>0;
                if(blocked)break;Thread.sleep(20);
            }
            assertTrue(blocked);release.countDown();writer.get(10,TimeUnit.SECONDS);reader.get(10,TimeUnit.SECONDS);
            assertEquals("QUEUED",attempts.findById(f.attempt()).orElseThrow().getState());
            assertEquals("READY",tasks.findByPlanIdAndTaskId(f.plan(),"A").orElseThrow().getState());
            // Keep this synthetic retry out of later tests that explicitly invoke startup recovery.
            jdbc.update("UPDATE agent_task_attempts SET lease_until=? WHERE attempt_id=?",
                    Timestamp.from(Instant.now().plusSeconds(600)),f.attempt());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void startupRecoveryCannotCancelQueuedUnknownOnTerminalRoot() {
        var f=fixture("READY","QUEUED");launch(f,"UNKNOWN",1);
        jdbc.update("UPDATE runs SET state='FAILED' WHERE id=?",f.root());
        String before=attemptRow(f),taskBefore=taskRow(f);
        recovery.recover();
        assertEquals(before,attemptRow(f));assertEquals(taskBefore,taskRow(f));
        assertEquals(0,recovery.cancelQueuedIfWon(attempts.findById(f.attempt()).orElseThrow(),f.root()));
    }
    @Test void startupPendingRecoveryCannotInventUnknownProcessAbsence() {
        var f=fixture("PENDING","RUNNING");launch(f,"UNKNOWN",1);
        jdbc.update("UPDATE runs SET state='FAILED' WHERE id=?",f.root());
        String before=attemptRow(f),taskBefore=taskRow(f);
        recovery.recover();recovery.cancelPendingTask(tasks.findByPlanIdAndTaskId(f.plan(),"A").orElseThrow(),f.root());
        assertEquals(before,attemptRow(f));assertEquals(taskBefore,taskRow(f));
    }
    @Test void recoveryWaitsForConcurrentFactRegistrationThenRefuses() throws Exception {
        var f=fixture("VERIFYING","RUNNING");var pool=Executors.newFixedThreadPool(2);
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var writer=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                jdbc.queryForList("SELECT attempt_id FROM agent_task_attempts WHERE attempt_id=? FOR UPDATE",String.class,f.attempt());
                launch(f,"UNKNOWN",1);held.countDown();
                try {assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
            }));
            assertTrue(held.await(10,TimeUnit.SECONDS));
            var reader=pool.submit(()->scheduling.reapExpiredLease(f.attempt(),Instant.now()));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
            while(System.nanoTime()<deadline) {
                blocked=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%agent_task_attempts%'",Integer.class)>0;
                if(blocked)break;Thread.sleep(20);
            }
            assertTrue(blocked,"Actual PG row lock must serialize registration with recovery");assertFalse(reader.isDone());
            release.countDown();writer.get(10,TimeUnit.SECONDS);assertFalse(reader.get(10,TimeUnit.SECONDS));
            assertEquals("RUNNING",attempts.findById(f.attempt()).orElseThrow().getState());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void recoveryLocksBusinessSourceBeforeAttemptAvoidingCancellationDeadlock() throws Exception {
        var f=fixture("RUNNING","RUNNING");var pool=Executors.newFixedThreadPool(2);
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var writer=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                modes.lockSource(f.root());held.countDown();
                try {assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                jdbc.execute("SET LOCAL lock_timeout='2s'");
                jdbc.queryForList("SELECT attempt_id FROM agent_task_attempts WHERE attempt_id=? FOR UPDATE",String.class,f.attempt());
                launch(f,"UNKNOWN",1);
            }));
            assertTrue(held.await(10,TimeUnit.SECONDS));
            var reader=pool.submit(()->scheduling.reapExpiredLease(f.attempt(),Instant.now()));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
            while(System.nanoTime()<deadline) {
                blocked=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%threads%'",Integer.class)>0;
                if(blocked)break;Thread.sleep(20);
            }
            assertTrue(blocked,"Recovery must wait for thread before owning attempt");assertFalse(reader.isDone());
            release.countDown();writer.get(10,TimeUnit.SECONDS);assertFalse(reader.get(10,TimeUnit.SECONDS));
        } finally {release.countDown();pool.shutdownNow();}
    }
}
