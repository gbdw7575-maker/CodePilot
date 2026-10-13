package com.dsh.orchestration.plan;

import com.dsh.orchestration.plan.PlanDtos.*;
import com.dsh.orchestration.task.TaskDispatchService;
import com.dsh.orchestration.task.TaskSchedulingService;
import com.dsh.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real repositories/transactions; mock scheduling prevents actual worker/model starts. */
@SpringBootTest
class PlanTaskRevisionIntegrityTest {
    @Autowired PlanService service; @Autowired PlanRepository plans; @Autowired TaskRepository tasks;
    @Autowired TaskAttemptRepository attempts; @Autowired ProjectRepository projects; @Autowired ThreadRepository threads;
    @Autowired TurnRepository turns; @Autowired RunRepository runs; @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager; @Autowired TaskDispatchService dispatch;
    @MockitoBean TaskSchedulingService scheduling;
    String run,plan; TransactionTemplate tx;
    @BeforeEach void setup(){
        var now=Instant.now();String suffix=UUID.randomUUID().toString(),project="project_"+suffix,thread="thread_"+suffix,turn="turn_"+suffix;
        run="run_"+suffix;tx=new TransactionTemplate(manager);
        projects.save(new ProjectEntity(project,"fixture","/tmp/project-"+suffix,true,now,now));
        threads.save(new ThreadEntity(thread,project,"fixture","profile_unused",now,now));
        turns.save(new TurnEntity(turn,thread,run,"用户原需求","RUNNING","PENDING",thread,now,now));
        runs.save(new RunEntity(run,turn,null,"MAIN","RUNNING","PENDING",1,"ws_fixture","digest",24,80,200000,now.plusSeconds(600),now,now));
        plan=service.saveDraft(run,new PlanDraft(null,null,"初始规划",List.of(draft("A",true),draft("B",false)))).planId();
    }
    TaskDraft draft(String id,boolean required){return new TaskDraft(id,"目标-"+id,List.of(),List.of(),"交付-"+id,List.of("验收-"+id),null,"DELEGATED",required);}
    TaskDraft goal(String id,String goal,boolean required){var t=draft(id,required);return new TaskDraft(id,goal,t.dependsOn(),t.inputRefs(),t.deliverable(),t.acceptance(),t.resourceScope(),t.executionMode(),required);}
    SavedPlan edit(int revision,TaskDraft... rows){return service.saveDraft(run,new PlanDraft(plan,revision,"编辑规划",List.of(rows)));}
    TaskEntity task(String id){return tasks.findByPlanIdAndTaskId(plan,id).orElseThrow();}
    String identity(TaskEntity t){return String.join("|",t.getId(),""+t.getTaskVersion(),t.getState(),t.getCreatedAt().toString(),t.getUpdatedAt().toString());}
    void refused(String reason,Runnable action){assertEquals(reason,assertThrows(PlanServiceException.class,action::run).reasonCode());}
    @Test void unchangedTasksKeepIdentityVersionAndBothTimestamps(){
        String a=identity(task("A")),b=identity(task("B"));edit(1,draft("A",true),draft("B",false));
        assertEquals(a,identity(task("A")));assertEquals(b,identity(task("B")));assertEquals(2,plans.findById(plan).orElseThrow().getRevision());
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","READY","RUNNING","VERIFYING","WAITING_APPROVAL","CANCELLING","SUCCEEDED"})
    void editingAnotherDraftPreservesDispatchedAndSuccessfulTask(String state){
        var a=task("A");a.setState(state);tasks.saveAndFlush(a);String prior=identity(task("A"));
        edit(1,draft("A",true),goal("B","新目标-B",false));
        assertEquals(prior,identity(task("A")));assertTrue(task("B").getTaskVersion()>1);assertEquals("DRAFT",task("B").getState());
    }
    @ParameterizedTest @ValueSource(strings={"goal","deliverable","acceptance","dependsOn","inputRefs","resourceScope","executionMode","required"})
    void anyDefinitionChangeGetsFreshVersionWithoutRecreatingOtherTask(String field){
        var original=draft("A",true);var changed=new TaskDraft("A",field.equals("goal")?" 新目标 ":original.goal(),
                (field.equals("dependsOn")||field.equals("inputRefs"))?List.of("B"):original.dependsOn(),field.equals("inputRefs")?List.of(new InputRef("B","deliverable")):original.inputRefs(),
                field.equals("deliverable")?" 新交付 ":original.deliverable(),field.equals("acceptance")?List.of("新的断言"):original.acceptance(),
                field.equals("resourceScope")?new ResourceScope(List.of("src/"),List.of()):null,field.equals("executionMode")?"INLINE":"DELEGATED",!field.equals("required"));
        String priorA=task("A").getId(),priorB=identity(task("B"));edit(1,changed,draft("B",false));
        assertNotEquals(priorA,task("A").getId());assertTrue(task("A").getTaskVersion()>1);assertEquals(priorB,identity(task("B")));
    }
    @Test void normalizedTextAndJsonObjectKeyOrderAreEquivalent(){
        var scoped=new TaskDraft("A","目标-A",List.of(),List.of(),"交付-A",List.of("验收-A"),new ResourceScope(List.of("src"),List.of("out")),"DELEGATED",true);
        edit(1,scoped,draft("B",false));
        jdbc.update("UPDATE agent_tasks SET resource_scope=? WHERE plan_id=? AND task_id='A'","{ \"write\" : [\"out\"], \"read\" : [\"src\"] }",plan);
        var prior=identity(task("A"));edit(2,new TaskDraft("A"," 目标-A ",List.of(),List.of()," 交付-A ",List.of("验收-A"),scoped.resourceScope(),"DELEGATED",true),draft("B",false));
        assertEquals(prior,identity(task("A")));
    }
    @Test void removingAndReaddingOptionalTaskNeverReusesOldVersion(){
        int first=task("B").getTaskVersion();edit(1,draft("A",true));assertTrue(tasks.findByPlanIdAndTaskId(plan,"B").isEmpty());
        edit(2,draft("A",true),draft("B",false));assertTrue(task("B").getTaskVersion()>first);int second=task("B").getTaskVersion();
        edit(3,draft("A",true));edit(4,draft("A",true),draft("B",false));assertTrue(task("B").getTaskVersion()>second);
    }
    @Test void pendingTaskCannotBeChangedOrRemoved(){
        var b=task("B");b.setState("PENDING");tasks.saveAndFlush(b);String before=identity(task("B"));
        refused("TASK_LOCKED",()->edit(1,draft("A",true),goal("B","新目标",false)));refused("TASK_LOCKED",()->edit(1,draft("A",true)));
        assertEquals(before,identity(task("B")));assertEquals(1,plans.findById(plan).orElseThrow().getRevision());
    }
    @ParameterizedTest @ValueSource(strings={"QUEUED","RUNNING","WAITING_APPROVAL","SUCCEEDED","FAILED","CANCELLED"})
    void anyHistoricalAttemptPreventsOrdinaryDefinitionReplacementAndDeletion(String state){
        var now=Instant.now();var attempt=attempts.saveAndFlush(new TaskAttemptEntity("attempt_"+UUID.randomUUID(),plan,"B",1,null,1,null,null,null,null,state,now,now));
        String before=identity(task("B"));refused("TASK_LOCKED",()->edit(1,draft("A",true),goal("B","新定义",false)));
        refused("TASK_LOCKED",()->edit(1,draft("A",true)));assertEquals(before,identity(task("B")));
        assertEquals(state,attempts.findById(attempt.getAttemptId()).orElseThrow().getState());assertEquals(1,plans.findById(plan).orElseThrow().getRevision());
    }
    @Test void cancelledRequiredTaskWithoutAttemptCanBeRemovedButNotVersionReused(){
        var a=task("A");a.setState("CANCELLED");tasks.saveAndFlush(a);edit(1,draft("B",false));
        edit(2,draft("A",true),draft("B",false));assertTrue(task("A").getTaskVersion()>1);
    }
    @Test void legacyPlanUsesCurrentAndHistoricalVersionUpperBounds(){
        var current=plans.findById(plan).orElseThrow();current.setRevision(7);plans.saveAndFlush(current);
        jdbc.update("UPDATE agent_plans SET task_version_high_water=NULL WHERE id=?",plan);
        var now=Instant.now();attempts.saveAndFlush(new TaskAttemptEntity("historic_"+UUID.randomUUID(),plan,"removed_legacy_task",40,null,1,null,null,null,null,"FAILED",now,now));
        edit(7,goal("A","新目标",true),draft("B",false));assertTrue(task("A").getTaskVersion()>40);
    }
    @Test void revisionOverflowRefusesWithoutPartialReplacement(){
        var current=plans.findById(plan).orElseThrow();current.setRevision(Integer.MAX_VALUE);plans.saveAndFlush(current);String before=identity(task("A"));
        refused("VALIDATION_FAILED",()->edit(Integer.MAX_VALUE,goal("A","新目标",true),draft("B",false)));
        assertEquals(before,identity(task("A")));assertEquals(Integer.MAX_VALUE,plans.findById(plan).orElseThrow().getRevision());
    }
    @ParameterizedTest @ValueSource(ints={-1,Integer.MAX_VALUE})
    void corruptOrExhaustedGenerationUpperBoundRefusesWithoutAnyWrites(int value){
        jdbc.update("UPDATE agent_plans SET task_version_high_water=? WHERE id=?",value,plan);String before=identity(task("A"));
        refused("VALIDATION_FAILED",()->edit(1,goal("A","新目标",true),draft("B",false)));
        assertEquals(before,identity(task("A")));assertEquals(1,plans.findById(plan).orElseThrow().getRevision());
        assertEquals(value,jdbc.queryForObject("SELECT task_version_high_water FROM agent_plans WHERE id=?",Integer.class,plan));
    }
    @Test void callerRollbackRestoresRowsRevisionAndNextVersion(){
        String before=identity(task("A"));tx.execute(s->{edit(1,goal("A","待回滚",true),draft("B",false));s.setRollbackOnly();return null;});
        assertEquals(before,identity(task("A")));assertEquals(1,plans.findById(plan).orElseThrow().getRevision());
        edit(1,goal("A","提交目标",true),draft("B",false));assertEquals(2,task("A").getTaskVersion());
    }
    @Test void twoEditorsWithSameRevisionHaveExactlyOneWinner() throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var futures=new ArrayList<Future<String>>();for(String value:List.of("目标一","目标二"))futures.add(pool.submit(()->{
            assertTrue(start.await(10,TimeUnit.SECONDS));try{edit(1,goal("A",value,true),draft("B",false));return "WIN";}catch(PlanServiceException rejected){return rejected.reasonCode();}}));
            start.countDown();var results=List.of(futures.get(0).get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter("WIN"::equals).count());assertEquals(1,results.stream().filter("REVISION_MISMATCH"::equals).count());
            assertEquals(2,plans.findById(plan).orElseThrow().getRevision());assertEquals(2,task("A").getTaskVersion());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void staleCallerPersistenceContextIsRefreshedBeforeRevisionCheck() throws Exception {
        var cached=new CountDownLatch(1);var edited=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(1);
        try{var stale=pool.submit(()->tx.execute(s->{assertEquals(1,plans.findById(plan).orElseThrow().getRevision());cached.countDown();
            try{assertTrue(edited.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}
            try{edit(1,goal("A","陈旧覆盖",true),draft("B",false));return "WIN";}catch(PlanServiceException rejected){return rejected.reasonCode();}}));
            assertTrue(cached.await(10,TimeUnit.SECONDS));edit(1,goal("A","真实新目标",true),draft("B",false));edited.countDown();
            assertEquals("REVISION_MISMATCH",stale.get(20,TimeUnit.SECONDS));assertEquals("真实新目标",task("A").getGoal());
        }finally{edited.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void dispatchWaitsForPlanEditAndThenRejectsStaleRevision() throws Exception {
        var holding=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var editor=pool.submit(()->tx.execute(s->{jdbc.queryForObject("SELECT revision FROM agent_plans WHERE id=? FOR UPDATE",Integer.class,plan);
            jdbc.update("UPDATE agent_plans SET revision=2 WHERE id=?",plan);holding.countDown();
            try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return null;}));
            assertTrue(holding.await(10,TimeUnit.SECONDS));var sender=pool.submit(()->{started.countDown();return dispatch.register(run,plan,1,List.of("A"),"key_"+UUID.randomUUID());});
            assertTrue(started.await(10,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->sender.get(150,TimeUnit.MILLISECONDS));
            release.countDown();editor.get(15,TimeUnit.SECONDS);var result=sender.get(15,TimeUnit.SECONDS);
            assertFalse(result.accepted());assertEquals("REVISION_MISMATCH",result.errorCode());assertEquals("DRAFT",task("A").getState());
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void committedDispatchPreventsWaitingEditorFromResettingPendingTask() throws Exception {
        var holding=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var sender=pool.submit(()->tx.execute(s->{var accepted=dispatch.register(run,plan,1,List.of("A"),"key_"+UUID.randomUUID());assertTrue(accepted.accepted());
            holding.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return accepted;}));
            assertTrue(holding.await(10,TimeUnit.SECONDS));var editor=pool.submit(()->{started.countDown();try{edit(1,goal("A","企图覆盖",true),draft("B",false));return "WIN";}catch(PlanServiceException rejected){return rejected.reasonCode();}});
            assertTrue(started.await(10,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->editor.get(150,TimeUnit.MILLISECONDS));
            release.countDown();sender.get(15,TimeUnit.SECONDS);assertEquals("TASK_LOCKED",editor.get(15,TimeUnit.SECONDS));
            assertEquals("PENDING",task("A").getState());assertEquals("目标-A",task("A").getGoal());assertEquals(1,plans.findById(plan).orElseThrow().getRevision());
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
