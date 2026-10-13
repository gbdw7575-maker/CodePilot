package com.dsh.api;

import com.dsh.application.VerificationEvidenceContext;
import com.dsh.orchestration.plan.PlanDtos.*;
import com.dsh.orchestration.plan.PlanService;
import com.dsh.storage.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Actual PG repositories, source-origin artifact bytes, Host files and HTTP response. No model. */


abstract class TaskAcceptanceModeFixtures {
    static final Path base=createRoot();
    static Path createRoot(){try{Path root=Files.createTempDirectory("m1c2d2b-api-");Files.createDirectory(root.resolve("control"));Files.createDirectory(root.resolve("workspaces"));Files.createDirectory(root.resolve("projects"));return root;}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("dsh.orchestration.task-checks.control-root",()->base.resolve("control").toString());registry.add("dsh.workspace.root",()->base.resolve("workspaces").toString());registry.add("dsh.orchestration.enabled",()->"true");}
    @Autowired ProjectRepository projects;@Autowired ModelProfileRepository profiles;@Autowired ThreadRepository threads;
    @Autowired TurnRepository turns;@Autowired RunRepository runs;@Autowired MessageRepository messages;
    @Autowired TaskRepository tasks;@Autowired PlanRepository plans;@Autowired TaskAttemptRepository attempts;
    @Autowired ArtifactRepository artifacts;@Autowired VerificationEvidenceContext origins;@Autowired PlanService planService;
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;
    record Chain(String project,String thread,String turn,String run,String plan,Path catalog){ }
    Chain chain() throws Exception {
        var now=Instant.now();String suffix=UUID.randomUUID().toString();String project="proj_"+suffix,thread="thr_"+suffix,turn="turn_"+suffix,run="run_"+suffix,profile="prof_"+suffix;
        Path root=Files.createDirectory(base.resolve("projects").resolve(project));projects.save(new ProjectEntity(project,"preview",root.toString(),true,now,now));
        profiles.save(new ModelProfileEntity(profile,"fake","fake","fake","http://127.0.0.1:9",null,"disabled",null,4096,false,"ACTIVE",now,now));
        threads.save(new ThreadEntity(thread,project,"preview",profile,now,now));turns.save(new TurnEntity(turn,thread,run,"  用户原文\n约束  ","RUNNING","PENDING",thread,now,now));
        runs.save(new RunEntity(run,turn,null,"MAIN","RUNNING","PENDING",1,"ws_"+suffix,"digest",24,80,200000,now.plusSeconds(600),now,now));
        messages.save(new MessageEntity("msg_"+suffix,thread,turn,"user","  用户原文\n约束  ",9007199254740993L,now));
        String plan=planService.saveDraft(run,new PlanDraft(null,null,"规划",List.of(new TaskDraft("A","修复",List.of(),List.of(),"交付",List.of("正确行为"),null,"DELEGATED",true)))).planId();
        Path privateDir=Files.createDirectory(base.resolve("control").resolve(project));JsonNode catalog;
        try(var stream=getClass().getResourceAsStream("/m1c1/catalog.json")){catalog=mapper.readTree(stream);}((com.fasterxml.jackson.databind.node.ObjectNode)catalog).put("projectId",project);
        var second=catalog.path("checks").get(0).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)second).put("id","second-semantic");((com.fasterxml.jackson.databind.node.ArrayNode)catalog.path("checks")).add(second);
        Path file=privateDir.resolve("task-checks.json");Files.write(file,mapper.writeValueAsBytes(catalog));Files.writeString(privateDir.resolve("check_slugify.py"),"assert 2 + 2 == 4\n");
        return new Chain(project,thread,turn,run,plan,file);
    }
    String url(Chain c){return "/api/v1/runs/"+c.run()+"/plans/"+c.plan()+"/tasks/A/acceptance-preview";}
    private String selectedUrl(Chain c){return url(c).replace("acceptance-preview","acceptance-selection-preview");}
    private String tag(Chain c)throws Exception{var result=mvc.perform(get(url(c)).header("Host","localhost")).andReturn();assertEquals(200,result.getResponse().getStatus());return result.getResponse().getHeader("ETag");}
    private org.springframework.test.web.servlet.MvcResult select(Chain c,String tag,String... refs)throws Exception{var request=get(selectedUrl(c)).header("Host","localhost");if(tag!=null)request.header("If-Match",tag);if(refs.length>0)request.queryParam("checkRef",refs);return mvc.perform(request).andReturn();}
    JsonNode body(org.springframework.test.web.servlet.MvcResult result)throws Exception{return mapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.dsh.orchestration.acceptance.TaskCheckConfirmationService confirmations;
    @Autowired com.dsh.application.TurnService turnService;
    @Autowired com.dsh.orchestration.task.TaskDispatchService dispatch;
    @Autowired com.dsh.orchestration.task.TaskSchedulingService scheduling;
    String selectionTag(Chain c,String... refs)throws Exception{var result=select(c,tag(c),refs);assertEquals(200,result.getResponse().getStatus());return result.getResponse().getHeader("ETag");}
    String registerUrl(Chain c){return url(c).replace("acceptance-preview","acceptance-registration");}
    String payload(String... refs)throws Exception{return mapper.writeValueAsString(Map.of("taskVersion",1,"confirmed",true,"checkRefs",List.of(refs)));}
    org.springframework.test.web.servlet.MvcResult confirm(Chain c,String tag,String key,String json)throws Exception{
        var req=post(registerUrl(c)).header("Host","localhost").header("X-DSH-Client","local-ui").contentType("application/json").content(json);
        if(tag!=null)req.header("If-Match",tag);if(key!=null)req.header("Idempotency-Key",key);return mvc.perform(req).andReturn();
    }
    int count(Chain c){return jdbc.queryForObject("SELECT count(*) FROM task_check_registrations WHERE plan_id=?",Integer.class,c.plan());}
    com.dsh.api.dto.ConfirmTaskChecks input(){return new com.dsh.api.dto.ConfirmTaskChecks(1,List.of("slugify-semantic"),true);}
    @Autowired com.dsh.orchestration.acceptance.RootTaskAcceptanceModes modes;
    @Autowired com.dsh.orchestration.task.TaskVerificationService verifier;
    @Autowired com.dsh.orchestration.task.TaskQueryService query;
    @Autowired com.dsh.storage.VerificationRepository verifications;
    String modeUrl(Chain c){return "/api/v1/runs/"+c.run()+"/task-acceptance-mode";}
    org.springframework.test.web.servlet.MvcResult enable(Chain c,String key,String json)throws Exception{return mvc.perform(post(modeUrl(c)).header("Host","localhost").header("X-DSH-Client","local-ui").header("Idempotency-Key",key).contentType("application/json").content(json)).andReturn();}
    String modeBody(){return "{\"mode\":\"EXECUTABLE_CHECKS\",\"confirmed\":true}";}
    void strong(Chain c)throws Exception{assertEquals(200,enable(c,"mode-"+UUID.randomUUID(),modeBody()).getResponse().getStatus());}
    void register(Chain c)throws Exception{assertEquals(200,confirm(c,selectionTag(c,"slugify-semantic"),"reg-"+UUID.randomUUID(),payload("slugify-semantic")).getResponse().getStatus());}
    com.dsh.orchestration.task.TaskDispatchService.DispatchResult send(Chain c,int revision,String... taskIds){return dispatch.register(c.run(),c.plan(),revision,List.of(taskIds),"send-"+UUID.randomUUID());}
    void refused(Chain c,int revision,String diagnostic,String... ids){var r=send(c,revision,ids);assertFalse(r.accepted());assertEquals("EVIDENCE_MISSING",r.errorCode());assertTrue(r.errorMessage().startsWith(diagnostic+":"),r.errorMessage());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM agent_task_dispatches WHERE root_run_id=?",Integer.class,c.run()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM agent_task_attempts WHERE plan_id=?",Integer.class,c.plan()));assertTrue(tasks.findByPlanIdOrderByTaskIdAsc(c.plan()).stream().allMatch(t->t.getState().equals("DRAFT")));org.mockito.Mockito.verify(scheduling,org.mockito.Mockito.never()).promoteAndPump(c.run());}
    static void await(java.util.concurrent.CountDownLatch latch){try{assertTrue(latch.await(10,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}
}
@SpringBootTest @AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties="dsh.orchestration.executable-checks-enabled=true")
class TaskAcceptanceModeApiTest extends TaskAcceptanceModeFixtures {
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.dsh.orchestration.task.TaskSchedulingService controlledScheduling;
    @Test void explicitHttpFreezesStrongWithExactReplayAndNoExecution()throws Exception{
        var c=chain();assertEquals("UNFROZEN",body(mvc.perform(get(modeUrl(c)).header("Host","localhost")).andReturn()).path("mode").asText());
        var first=enable(c,"exact-mode",modeBody());assertEquals(200,first.getResponse().getStatus());assertEquals("EXECUTABLE_CHECKS",body(first).path("mode").asText());assertFalse(body(first).path("executionAvailable").asBoolean());
        assertEquals(body(first),body(enable(c,"exact-mode",modeBody())));assertEquals(body(first),body(enable(c,"another-mode",modeBody())));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM root_task_acceptance_modes WHERE root_run_id=?",Integer.class,c.run()));refused(c,1,"MISSING_REGISTRATION","A");
    }
    @Test void strictJsonAndConsentRejectDowngradesOrCommands()throws Exception{
        var c=chain();for(String invalid:List.of("{}","[]",modeBody().replace("true","false"),modeBody().replace("true","\"true\""),modeBody().replace("EXECUTABLE_CHECKS","LEGACY_COVERAGE_ONLY"),modeBody()+" {}",modeBody().replace("}",",\"confirmed\":true}"),modeBody().replace("}",",\"command\":\"echo passed\"}")))assertEquals(400,enable(c,"bad-"+UUID.randomUUID(),invalid).getResponse().getStatus());
        assertFalse(modes.view(c.run()).frozen());
    }
    @Test void localGuardRunsBeforeHistoricalReplay()throws Exception{
        var c=chain();assertEquals(200,enable(c,"guard-mode",modeBody()).getResponse().getStatus());
        assertEquals(403,mvc.perform(post(modeUrl(c)).header("Host","evil.example").header("X-DSH-Client","local-ui").header("Idempotency-Key","guard-mode").contentType("application/json").content(modeBody())).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(post(modeUrl(c)).header("Host","localhost").contentType("application/json").content(modeBody())).andReturn().getResponse().getStatus());
    }
    @Test void registeredCurrentTaskStillRefusedUntilM1D()throws Exception{var c=chain();register(c);strong(c);refused(c,1,"EXECUTION_NOT_AVAILABLE","A");}
    @Test void sameVersionGoalDriftCannotReuseRegistration()throws Exception{var c=chain();register(c);strong(c);jdbc.update("UPDATE agent_tasks SET goal='new goal' WHERE plan_id=?",c.plan());refused(c,1,"STALE_BINDING","A");}
    @Test void actualRequirementAppendCannotReuseRegistration()throws Exception{var c=chain();register(c);strong(c);new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(s->turnService.appendInput(c.turn(),new com.dsh.api.dto.SubmitTurn("追加要求")));refused(c,1,"STALE_BINDING","A");}
    @Test void fullCatalogByteDriftCannotReuseRegistration()throws Exception{var c=chain();register(c);strong(c);Files.writeString(c.catalog(),Files.readString(c.catalog())+"\n");refused(c,1,"STALE_BINDING","A");}
    @Test void verifierDependencyByteDriftCannotReuseRegistration()throws Exception{var c=chain();register(c);strong(c);Files.writeString(c.catalog().getParent().resolve("check_slugify.py"),"assert False\n");refused(c,1,"STALE_BINDING","A");}
    @Test void revokedProjectCannotReuseRegistration()throws Exception{var c=chain();register(c);strong(c);var project=projects.findById(c.project()).orElseThrow();project.setTrusted(false);projects.saveAndFlush(project);refused(c,1,"STALE_BINDING","A");}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"binding_digest","snapshot_digest","binding_json","snapshot_json","project_id"})
    void corruptFrozenRowsNeverApprove(String column)throws Exception{var c=chain();register(c);strong(c);jdbc.update("UPDATE task_check_registrations SET "+column+"='damaged' WHERE plan_id=?",c.plan());refused(c,1,"INVALID_EVIDENCE","A");}
    @Test void unrelatedDraftRevisionKeepsStableBindingButNewTaskRequiresRegistration()throws Exception{
        var c=chain();register(c);strong(c);
        planService.saveDraft(c.run(),new PlanDraft(c.plan(),1,"规划",List.of(new TaskDraft("A","修复",List.of(),List.of(),"交付",List.of("正确行为"),null,"DELEGATED",true),new TaskDraft("B","另一个",List.of(),List.of(),"交付",List.of("正确行为"),null,"DELEGATED",true))));
        refused(c,2,"EXECUTION_NOT_AVAILABLE","A");refused(c,2,"MISSING_REGISTRATION","A","B");
    }
    @Test void actualTaskGenerationNeedsNewConfirmation()throws Exception{
        var c=chain();register(c);strong(c);planService.saveDraft(c.run(),new PlanDraft(c.plan(),1,"规划",List.of(new TaskDraft("A","新目标",List.of(),List.of(),"交付",List.of("正确行为"),null,"DELEGATED",true))));refused(c,2,"MISSING_REGISTRATION","A");
    }
    @Test void legacyDispatchFreezesAndCannotUpgrade()throws Exception{var c=chain();assertTrue(send(c,1,"A").accepted());assertEquals("LEGACY_COVERAGE_ONLY",modes.view(c.run()).mode());assertEquals(409,enable(c,"no-upgrade",modeBody()).getResponse().getStatus());}
    @Test void existingAttemptWithoutModeCannotUpgrade()throws Exception{var c=chain();var now=Instant.now();attempts.saveAndFlush(new TaskAttemptEntity("old_"+UUID.randomUUID(),c.plan(),"A",1,null,1,null,null,null,null,"FAILED",now,now));assertEquals(409,enable(c,"old-attempt",modeBody()).getResponse().getStatus());assertFalse(modes.view(c.run()).frozen());}
    @Test void inactiveRootCannotOptIn()throws Exception{var c=chain();var root=runs.findById(c.run()).orElseThrow();root.setState("CANCELLED");runs.saveAndFlush(root);assertEquals(409,enable(c,"inactive",modeBody()).getResponse().getStatus());}
    @Test void unknownRootDoesNotCreateMode()throws Exception{var c=chain();assertEquals(404,mvc.perform(get(modeUrl(c).replace(c.run(),"run_missing")).header("Host","localhost")).andReturn().getResponse().getStatus());}
    @Test void closedConfigAndNewServiceStillObservePersistentStrongMode()throws Exception{
        var c=chain();strong(c);var closed=new com.dsh.orchestration.acceptance.RootTaskAcceptanceModes(jdbc,runs,turns,threads,messages,false);
        assertTrue(closed.isStrong(c.run()));assertFalse(closed.legacyAllowed(c.plan()));assertFalse(closed.view(c.run()).optInAvailable());refused(c,1,"MISSING_REGISTRATION","A");
    }
    @Test void oldPatchOrPassedCannotValidateStrongTask()throws Exception{
        var c=chain();strong(c);var task=tasks.findByPlanIdAndTaskId(c.plan(),"A").orElseThrow();
        var result=new com.dsh.orchestration.SubagentResult("child_test","CODER","COMPLETED",null,"正确行为",List.of(),"patch_test",null,null,0,0,0,0L);
        assertFalse(verifier.verify(task,result).passed());assertEquals("EVIDENCE_MISSING",verifier.verify(task,result).reasonCode());
        var now=Instant.now();verifications.saveAndFlush(new VerificationEntity("passed_"+UUID.randomUUID(),"child_test","v1","digest","pre","image","正确行为","PASSED",0,1,1,0,false,"[]",now,now));
        assertFalse(verifier.verify(task,result).passed());
    }
    @Test void optInRollbackLeavesNoMode()throws Exception{var c=chain();new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(s->{modes.enable(c.run());s.setRollbackOnly();return null;});assertFalse(modes.view(c.run()).frozen());}
    @Test void invalidLegacyDispatchDoesNotFreezeAndCallerRollbackIsAtomic()throws Exception{
        var c=chain();assertFalse(send(c,999,"A").accepted());assertFalse(modes.view(c.run()).frozen());
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(s->{assertTrue(send(c,1,"A").accepted());s.setRollbackOnly();return null;});
        assertFalse(modes.view(c.run()).frozen());assertEquals("DRAFT",tasks.findByPlanIdAndTaskId(c.plan(),"A").orElseThrow().getState());
        org.mockito.Mockito.verify(scheduling,org.mockito.Mockito.never()).promoteAndPump(c.run());
    }
    @Test void strictCurrentSourceDamageIsRefusedWithoutPoisoningTransaction()throws Exception{
        var c=chain();register(c);strong(c);jdbc.update("UPDATE agent_tasks SET acceptance='[]' WHERE plan_id=?",c.plan());refused(c,1,"STALE_BINDING","A");
    }
    @Test void modeHttpIdempotencyRejectsDifferentValidBytes()throws Exception{
        var c=chain();assertEquals(200,enable(c,"same-bytes",modeBody()).getResponse().getStatus());
        assertEquals(409,enable(c,"same-bytes","{\"confirmed\":true,\"mode\":\"EXECUTABLE_CHECKS\"}").getResponse().getStatus());
    }
    @Test void legacyDispatchWinningLockRacePreventsOptIn()throws Exception{
        var c=chain();var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try{var owner=pool.submit(()->tx.execute(s->{var sent=send(c,1,"A");held.countDown();await(release);return sent;}));assertTrue(held.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var started=new java.util.concurrent.CountDownLatch(1);var selector=pool.submit(()->{started.countDown();return enable(c,"losing-opt-in",modeBody());});assertTrue(started.await(10,java.util.concurrent.TimeUnit.SECONDS));assertThrows(java.util.concurrent.TimeoutException.class,()->selector.get(150,java.util.concurrent.TimeUnit.MILLISECONDS));release.countDown();assertTrue(owner.get(15,java.util.concurrent.TimeUnit.SECONDS).accepted());assertEquals(409,selector.get(15,java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus());assertEquals("LEGACY_COVERAGE_ONLY",modes.view(c.run()).mode());
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void concurrentOptInAndDispatchCannotMixModes()throws Exception{
        var c=chain();var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try{var owner=pool.submit(()->tx.execute(s->{var mode=modes.enable(c.run());held.countDown();await(release);return mode;}));assertTrue(held.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var started=new java.util.concurrent.CountDownLatch(1);var sender=pool.submit(()->{started.countDown();return send(c,1,"A");});assertTrue(started.await(10,java.util.concurrent.TimeUnit.SECONDS));assertThrows(java.util.concurrent.TimeoutException.class,()->sender.get(150,java.util.concurrent.TimeUnit.MILLISECONDS));release.countDown();owner.get(15,java.util.concurrent.TimeUnit.SECONDS);assertFalse(sender.get(15,java.util.concurrent.TimeUnit.SECONDS).accepted());assertEquals("EXECUTABLE_CHECKS",modes.view(c.run()).mode());
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
}
@SpringBootTest @AutoConfigureMockMvc
class TaskAcceptanceModeDisabledTest extends TaskAcceptanceModeFixtures {
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.dsh.orchestration.task.TaskSchedulingService controlledScheduling;
    @Test void defaultClosedDoesNotCreateStrongPolicy()throws Exception{var c=chain();var view=modes.view(c.run());assertFalse(view.optInAvailable());assertEquals("UNFROZEN",view.mode());assertEquals(503,enable(c,"default-off",modeBody()).getResponse().getStatus());assertFalse(modes.view(c.run()).frozen());}
}
@SpringBootTest @AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties="dsh.orchestration.executable-checks-enabled=true")
class TaskAcceptanceModeRuntimeGuardTest extends TaskAcceptanceModeFixtures {
    @Autowired com.dsh.agent.LoopSteps loopSteps;
    @Autowired com.dsh.agent.control.RunControlService controls;
    @Autowired com.dsh.agent.control.CancellationTokenRegistry tokens;
    @Test void legacyDispatchDoesNotLockRunningUpstreamWhileWaitingForEventCursor()throws Exception{
        var c=chain();
        planService.saveDraft(c.run(),new PlanDraft(c.plan(),1,"规划",List.of(
            new TaskDraft("A","修复",List.of(),List.of(),"交付",List.of("正确行为"),null,"DELEGATED",true),
            new TaskDraft("B","后续",List.of("A"),List.of(),"交付",List.of("正确行为"),null,"DELEGATED",true))));
        jdbc.update("UPDATE agent_tasks SET state='RUNNING' WHERE plan_id=? AND task_id='A'",c.plan());
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var pid=new java.util.concurrent.atomic.AtomicInteger();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try{
            var worker=pool.submit(()->tx.execute(s->{
                jdbc.queryForObject("SELECT last_sequence FROM event_cursors WHERE thread_id=? FOR UPDATE",Long.class,c.thread());
                held.countDown();await(release);
                // Model the worker's post-event flush. An unrelated dispatch must not own A.
                return jdbc.queryForObject("SELECT task_id FROM agent_tasks WHERE plan_id=? AND task_id='A' FOR UPDATE NOWAIT",String.class,c.plan());
            }));
            assertTrue(held.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var sender=pool.submit(()->tx.execute(s->{pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));return send(c,2,"B");}));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);boolean waiting=false;
            while(System.nanoTime()<deadline){
                waiting=pid.get()!=0 && jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock' AND query LIKE '%event_cursors%'",Integer.class,pid.get())==1;
                if(waiting)break;Thread.sleep(25);
            }
            assertTrue(waiting,"Observe the actual event-cursor wait before testing upstream availability");
            release.countDown();assertEquals("A",worker.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(sender.get(15,java.util.concurrent.TimeUnit.SECONDS).accepted());
            assertEquals("RUNNING",tasks.findByPlanIdAndTaskId(c.plan(),"A").orElseThrow().getState());
            assertEquals("PENDING",tasks.findByPlanIdAndTaskId(c.plan(),"B").orElseThrow().getState());
            assertEquals("LEGACY_COVERAGE_ONLY",modes.view(c.run()).mode());
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void actualRecoveryPromotionAndClaimDoNotStartStrongWorkers()throws Exception{
        var c=chain();strong(c);var task=tasks.findByPlanIdAndTaskId(c.plan(),"A").orElseThrow();task.setState("PENDING");tasks.saveAndFlush(task);
        scheduling.promote(c.run());assertTrue(attempts.findByPlanIdAndTaskIdOrderByAttemptNoDesc(c.plan(),"A").isEmpty());
        var now=Instant.now();String id="queued_"+UUID.randomUUID();attempts.saveAndFlush(new TaskAttemptEntity(id,c.plan(),"A",1,null,1,null,null,null,null,"QUEUED",now,now));
        task.setState("READY");tasks.saveAndFlush(task);assertFalse(scheduling.claim(id,"owner",now.plusSeconds(60)).claimed());
        assertEquals("QUEUED",attempts.findById(id).orElseThrow().getState());assertEquals("READY",tasks.findByPlanIdAndTaskId(c.plan(),"A").orElseThrow().getState());
    }
    @Test void oldSucceededRowIsNotCurrentStrongEvidence()throws Exception{
        var c=chain();strong(c);var task=tasks.findByPlanIdAndTaskId(c.plan(),"A").orElseThrow();
        var old=new TaskResultEntity("old",c.plan(),"A",1,"child","SUCCEEDED",null,null,"[]","patch","digest",null,"passed",0,0,0,0L,Instant.now());
        assertFalse(query.isEvidenceValid(task,old));
    }
    @Test void actualRootAnswerCannotBypassStrongMode()throws Exception{
        var c=chain();strong(c);loopSteps.apply(c.run(),new com.dsh.agent.model.ModelResponse("已完成",List.of(),"stop",null,"fake-response"),1,0);
        assertEquals("FAILED",runs.findById(c.run()).orElseThrow().getState());assertEquals("EVIDENCE_MISSING",runs.findById(c.run()).orElseThrow().getStopReason());
        assertEquals("FAILED",turns.findById(c.turn()).orElseThrow().getState());assertEquals("DRAFT",tasks.findByPlanIdAndTaskId(c.plan(),"A").orElseThrow().getState());
    }
    @Test void oldRootPassedCannotCompleteStrongMode()throws Exception{
        var c=chain();strong(c);var root=runs.findById(c.run()).orElseThrow();root.setState("VERIFYING");root.setVerificationStatus("PASSED");runs.saveAndFlush(root);
        assertFalse(loopSteps.verificationPassed(c.run()));loopSteps.completeAfterVerification(c.run());assertEquals("FAILED",runs.findById(c.run()).orElseThrow().getState());
    }
    @Test void cancelledStrongRootIsNotRevivedByLateAnswerOrOldCompletion()throws Exception{
        var c=chain();strong(c);var root=runs.findById(c.run()).orElseThrow();root.setState("CANCELLED");runs.saveAndFlush(root);
        loopSteps.apply(c.run(),new com.dsh.agent.model.ModelResponse("已完成",List.of(),"stop",null,"late"),1,0);assertEquals("CANCELLED",runs.findById(c.run()).orElseThrow().getState());
        assertThrows(IllegalStateException.class,()->loopSteps.completeAfterVerification(c.run()));assertEquals("CANCELLED",runs.findById(c.run()).orElseThrow().getState());
    }
    @Test void responseWaitsForOptInAndCannotFinishAsLegacy()throws Exception{
        var c=chain();var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var started=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try{var owner=pool.submit(()->tx.execute(s->{modes.enable(c.run());held.countDown();await(release);return null;}));assertTrue(held.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var answer=pool.submit(()->{started.countDown();return loopSteps.apply(c.run(),new com.dsh.agent.model.ModelResponse("完成",List.of(),"stop",null,"fake"),1,0);});assertTrue(started.await(10,java.util.concurrent.TimeUnit.SECONDS));assertThrows(java.util.concurrent.TimeoutException.class,()->answer.get(150,java.util.concurrent.TimeUnit.MILLISECONDS));release.countDown();owner.get(15,java.util.concurrent.TimeUnit.SECONDS);answer.get(15,java.util.concurrent.TimeUnit.SECONDS);assertEquals("FAILED",runs.findById(c.run()).orElseThrow().getState());
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void actualMainCancelWaitsForModeSourceWithoutReversedLocks()throws Exception{
        var c=chain();tokens.register(c.run());var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var started=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try{var owner=pool.submit(()->tx.execute(s->{threads.findByIdForUpdate(c.thread()).orElseThrow();held.countDown();await(release);modes.enable(c.run());return null;}));assertTrue(held.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var cancel=pool.submit(()->{started.countDown();return controls.cancel(c.run(),"user");});assertTrue(started.await(10,java.util.concurrent.TimeUnit.SECONDS));assertThrows(java.util.concurrent.TimeoutException.class,()->cancel.get(150,java.util.concurrent.TimeUnit.MILLISECONDS));release.countDown();owner.get(15,java.util.concurrent.TimeUnit.SECONDS);assertEquals("CANCELLING",cancel.get(15,java.util.concurrent.TimeUnit.SECONDS).getState());
            assertEquals("CANCELLED",controls.finalizeCancellation(c.run()).getState());assertTrue(modes.isStrong(c.run()));
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));tokens.release(c.run());}
    }
    @Test void actualPausedStrongCancellationFinalizesAndKeepsMode()throws Exception{
        var c=chain();strong(c);var root=runs.findById(c.run()).orElseThrow();root.setState("WAITING_INPUT");runs.saveAndFlush(root);
        assertEquals("CANCELLED",controls.cancel(c.run(),"paused-user").getState());assertTrue(modes.isStrong(c.run()));assertEquals("CANCELLED",turns.findById(c.turn()).orElseThrow().getState());
    }
}
