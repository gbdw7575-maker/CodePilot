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
@SpringBootTest
@AutoConfigureMockMvc
class TaskCheckConfirmationApiTest {
    static final Path base=createRoot();
    static Path createRoot(){try{Path root=Files.createTempDirectory("m1c2d2b-api-");Files.createDirectory(root.resolve("control"));Files.createDirectory(root.resolve("workspaces"));Files.createDirectory(root.resolve("projects"));return root;}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("dsh.orchestration.task-checks.control-root",()->base.resolve("control").toString());registry.add("dsh.workspace.root",()->base.resolve("workspaces").toString());registry.add("dsh.orchestration.enabled",()->"true");}
    @Autowired ProjectRepository projects;@Autowired ModelProfileRepository profiles;@Autowired ThreadRepository threads;
    @Autowired TurnRepository turns;@Autowired RunRepository runs;@Autowired MessageRepository messages;
    @Autowired TaskRepository tasks;@Autowired PlanRepository plans;@Autowired TaskAttemptRepository attempts;
    @Autowired ArtifactRepository artifacts;@Autowired VerificationEvidenceContext origins;@Autowired PlanService planService;
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;
    record Chain(String project,String thread,String turn,String run,String plan,Path catalog){ }
    private Chain chain() throws Exception {
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
    private String url(Chain c){return "/api/v1/runs/"+c.run+"/plans/"+c.plan+"/tasks/A/acceptance-preview";}
    private String selectedUrl(Chain c){return url(c).replace("acceptance-preview","acceptance-selection-preview");}
    private String tag(Chain c)throws Exception{var result=mvc.perform(get(url(c)).header("Host","localhost")).andReturn();assertEquals(200,result.getResponse().getStatus());return result.getResponse().getHeader("ETag");}
    private org.springframework.test.web.servlet.MvcResult select(Chain c,String tag,String... refs)throws Exception{var request=get(selectedUrl(c)).header("Host","localhost");if(tag!=null)request.header("If-Match",tag);if(refs.length>0)request.queryParam("checkRef",refs);return mvc.perform(request).andReturn();}
    private JsonNode body(org.springframework.test.web.servlet.MvcResult result)throws Exception{return mapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.dsh.orchestration.acceptance.TaskCheckConfirmationService confirmations;
    @Autowired com.dsh.application.TurnService turnService;
    @Autowired com.dsh.orchestration.task.TaskDispatchService dispatch;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.dsh.orchestration.task.TaskSchedulingService scheduling;
    String selectionTag(Chain c,String... refs)throws Exception{var result=select(c,tag(c),refs);assertEquals(200,result.getResponse().getStatus());return result.getResponse().getHeader("ETag");}
    String registerUrl(Chain c){return url(c).replace("acceptance-preview","acceptance-registration");}
    String payload(String... refs)throws Exception{return mapper.writeValueAsString(Map.of("taskVersion",1,"confirmed",true,"checkRefs",List.of(refs)));}
    org.springframework.test.web.servlet.MvcResult confirm(Chain c,String tag,String key,String json)throws Exception{
        var req=post(registerUrl(c)).header("Host","localhost").header("X-DSH-Client","local-ui").contentType("application/json").content(json);
        if(tag!=null)req.header("If-Match",tag);if(key!=null)req.header("Idempotency-Key",key);return mvc.perform(req).andReturn();
    }
    int count(Chain c){return jdbc.queryForObject("SELECT count(*) FROM task_check_registrations WHERE plan_id=?",Integer.class,c.plan);}
    com.dsh.api.dto.ConfirmTaskChecks input(){return new com.dsh.api.dto.ConfirmTaskChecks(1,List.of("slugify-semantic"),true);}
    @Test void actualHttpWritesExactBindingButDoesNotDispatch()throws Exception{
        var c=chain();String selected=selectionTag(c,"second-semantic","slugify-semantic");var result=confirm(c,selected,"confirm-exact",payload("slugify-semantic","second-semantic"));
        assertEquals(200,result.getResponse().getStatus());var saved=body(result);assertEquals("REGISTERED",saved.path("status").asText());
        assertEquals(List.of("second-semantic","slugify-semantic"),mapper.convertValue(saved.get("checkRefs"),List.class));assertEquals(1,count(c));
        assertEquals(saved.path("bindingDigest").asText(),jdbc.queryForObject("SELECT binding_digest FROM task_check_registrations WHERE plan_id=?",String.class,c.plan));
        assertEquals("DRAFT",tasks.findByPlanIdAndTaskId(c.plan,"A").orElseThrow().getState());assertTrue(attempts.findByPlanIdAndTaskIdOrderByAttemptNoDesc(c.plan,"A").isEmpty());
        assertTrue(result.getResponse().getHeader("ETag").contains(saved.path("snapshotDigest").asText()));
    }
    @Test void httpReplayAndNewKeySameBindingKeepFirstIdentityAndTimestamp()throws Exception{
        var c=chain();String tag=selectionTag(c,"slugify-semantic"),json=payload("slugify-semantic");var first=confirm(c,tag,"same-confirm",json);assertEquals(200,first.getResponse().getStatus());
        assertEquals(body(first),body(confirm(c,tag,"same-confirm",json)));assertEquals(body(first),body(confirm(c,tag,"new-confirm-key",json)));assertEquals(1,count(c));
        assertEquals(409,confirm(c,tag,"same-confirm",payload("second-semantic")).getResponse().getStatus());
        var root=runs.findById(c.run).orElseThrow();root.setState("CANCELLED");runs.saveAndFlush(root);
        assertEquals(body(first),body(confirm(c,tag,"same-confirm",json)));assertEquals(409,confirm(c,tag,"another-new-key",json).getResponse().getStatus());
    }
    @Test void sameTaskVersionDifferentSelectedChecksCannotOverwrite()throws Exception{
        var c=chain();var first=confirm(c,selectionTag(c,"slugify-semantic"),"first-register",payload("slugify-semantic"));assertEquals(200,first.getResponse().getStatus());
        var other=confirm(c,selectionTag(c,"second-semantic"),"other-register",payload("second-semantic"));assertEquals(409,other.getResponse().getStatus());assertEquals("WORKSPACE_CONFLICT",body(other).path("code").asText());assertEquals(1,count(c));
    }
    @Test void headersExplicitConsentStrictJsonAndSelectionAreRequired()throws Exception{
        var c=chain();String tag=selectionTag(c,"slugify-semantic"),valid=payload("slugify-semantic");
        assertEquals(428,confirm(c,null,"header-missing",valid).getResponse().getStatus());assertEquals(400,confirm(c,"*","header-invalid",valid).getResponse().getStatus());
        assertEquals(400,confirm(c,tag,null,valid).getResponse().getStatus());
        for(String json:List.of("{}","[]",valid.replace("true","false"),valid.replace("true","\"true\""),valid.replace("1","\"1\""),valid.replace("1","1.5"),valid.replace("1","0"),
                "{\"taskVersion\":1,\"confirmed\":true,\"checkRefs\":[]}",valid.replace("slugify-semantic","unknown"),
                valid.substring(0,valid.length()-1)+",\"command\":\"echo passed\"}",valid+" {}",valid.substring(0,valid.length()-1)+",\"confirmed\":false}"))
            assertEquals(400,confirm(c,tag,"strict-"+UUID.randomUUID(),json).getResponse().getStatus(),json);
        assertEquals(0,count(c));
    }
    @Test void localSourceGuardRunsBeforeReplayAndWrongRootIsNotFound()throws Exception{
        var c=chain();String tag=selectionTag(c,"slugify-semantic"),json=payload("slugify-semantic");assertEquals(200,confirm(c,tag,"local-guard-key",json).getResponse().getStatus());
        for(String header:List.of("Host","Origin"))assertEquals(403,mvc.perform(post(registerUrl(c)).header("X-DSH-Client","local-ui").header("Idempotency-Key","local-guard-key").header("If-Match",tag).header(header,"http://evil.example").contentType("application/json").content(json)).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(post(registerUrl(c)).header("Host","localhost").contentType("application/json").content(json)).andReturn().getResponse().getStatus());
        var other=chain();assertEquals(404,mvc.perform(post(registerUrl(c).replace(c.plan,other.plan)).header("Host","localhost").header("X-DSH-Client","local-ui").header("Idempotency-Key","wrong-root-key").header("If-Match",tag).contentType("application/json").content(json)).andReturn().getResponse().getStatus());
    }
    @Test void requirementTaskVersionAndCatalogDriftRefuseWithoutWriting()throws Exception{
        var c=chain();String old=selectionTag(c,"slugify-semantic");messages.save(new MessageEntity("added_"+UUID.randomUUID(),c.thread,c.turn,"user","新增要求",9007199254740994L,Instant.now()));
        assertEquals(412,confirm(c,old,"requirement-drift",payload("slugify-semantic")).getResponse().getStatus());
        String newer=selectionTag(c,"slugify-semantic");Files.writeString(c.catalog,Files.readString(c.catalog)+"\n");assertEquals(412,confirm(c,newer,"catalog-drift",payload("slugify-semantic")).getResponse().getStatus());
        newer=selectionTag(c,"slugify-semantic");jdbc.update("UPDATE agent_tasks SET task_version=2 WHERE plan_id=? AND task_id='A'",c.plan);
        assertEquals(412,confirm(c,newer,"task-version-drift",payload("slugify-semantic")).getResponse().getStatus());assertEquals(0,count(c));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"PENDING","RUNNING","SUCCEEDED","CANCELLED","FAILED"})
    void nonDraftTasksCannotBeConfirmed(String state)throws Exception{
        var c=chain();String tag=selectionTag(c,"slugify-semantic");var task=tasks.findByPlanIdAndTaskId(c.plan,"A").orElseThrow();task.setState(state);tasks.saveAndFlush(task);
        assertEquals(409,confirm(c,tag,"state-"+UUID.randomUUID(),payload("slugify-semantic")).getResponse().getStatus());assertEquals(0,count(c));
    }
    @Test void attemptAndRevokedProjectRefuse()throws Exception{
        var c=chain();String tag=selectionTag(c,"slugify-semantic");var now=Instant.now();attempts.saveAndFlush(new TaskAttemptEntity("attempt_"+UUID.randomUUID(),c.plan,"A",1,null,1,null,null,null,null,"FAILED",now,now));
        assertEquals(409,confirm(c,tag,"attempt-existing",payload("slugify-semantic")).getResponse().getStatus());
        var d=chain();tag=selectionTag(d,"slugify-semantic");var project=projects.findById(d.project).orElseThrow();project.setTrusted(false);projects.saveAndFlush(project);
        assertEquals(403,confirm(d,tag,"trust-revoked",payload("slugify-semantic")).getResponse().getStatus());assertEquals(0,count(d));
    }
    @Test void callerRollbackAndCatalogChangeBeforeCommitLeaveNoRegistration()throws Exception{
        var c=chain();String tag=selectionTag(c,"slugify-semantic");var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.execute(s->{confirmations.confirm(c.run,c.plan,"A",tag,input());s.setRollbackOnly();return null;});assertEquals(0,count(c));
        assertThrows(ApiException.class,()->tx.execute(s->{confirmations.confirm(c.run,c.plan,"A",tag,input());try{Files.writeString(c.catalog,Files.readString(c.catalog)+"\n");}catch(Exception e){throw new IllegalStateException(e);}return null;}));assertEquals(0,count(c));
    }
    @Test void appendWaitsForConfirmationThreadLockThenFreshConfirmationDetectsIt()throws Exception{
        var c=chain();String tag=selectionTag(c,"slugify-semantic");var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var started=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var owner=pool.submit(()->tx.execute(s->{var saved=confirmations.confirm(c.run,c.plan,"A",tag,input());held.countDown();await(release);return saved;}));assertTrue(held.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var writer=pool.submit(()->tx.execute(s->{started.countDown();return turnService.appendInput(c.turn,new com.dsh.api.dto.SubmitTurn("追加用户要求"));}));
            assertTrue(started.await(10,java.util.concurrent.TimeUnit.SECONDS));assertThrows(java.util.concurrent.TimeoutException.class,()->writer.get(150,java.util.concurrent.TimeUnit.MILLISECONDS));release.countDown();owner.get(15,java.util.concurrent.TimeUnit.SECONDS);writer.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(412,confirm(c,tag,"after-real-append",payload("slugify-semantic")).getResponse().getStatus());assertEquals(1,count(c));
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void actualDispatchWaitsForConfirmationPlanLock()throws Exception{
        var c=chain();String tag=selectionTag(c,"slugify-semantic");var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var started=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var owner=pool.submit(()->tx.execute(s->{var saved=confirmations.confirm(c.run,c.plan,"A",tag,input());held.countDown();await(release);return saved;}));assertTrue(held.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var sender=pool.submit(()->{started.countDown();return dispatch.register(c.run,c.plan,1,List.of("A"),"dispatch-"+UUID.randomUUID());});
            assertTrue(started.await(10,java.util.concurrent.TimeUnit.SECONDS));assertThrows(java.util.concurrent.TimeoutException.class,()->sender.get(150,java.util.concurrent.TimeUnit.MILLISECONDS));release.countDown();owner.get(15,java.util.concurrent.TimeUnit.SECONDS);assertTrue(sender.get(15,java.util.concurrent.TimeUnit.SECONDS).accepted());
            assertEquals("PENDING",tasks.findByPlanIdAndTaskId(c.plan,"A").orElseThrow().getState());assertEquals(1,count(c));
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void sameVersionDefinitionMutationAndFailedKeyDoNotApproveStaleData()throws Exception{
        var c=chain();String old=selectionTag(c,"slugify-semantic");jdbc.update("UPDATE agent_tasks SET goal='当前真实新目标' WHERE plan_id=? AND task_id='A'",c.plan);
        assertEquals(412,confirm(c,old,"failure-key-retry",payload("slugify-semantic")).getResponse().getStatus());assertEquals(0,count(c));
        assertEquals(200,confirm(c,selectionTag(c,"slugify-semantic"),"failure-key-retry",payload("slugify-semantic")).getResponse().getStatus());assertEquals(1,count(c));
    }
    @Test void simultaneousHttpSameKeyReturnsOneOriginalRegistration()throws Exception{
        var c=chain();String tag=selectionTag(c,"slugify-semantic"),json=payload("slugify-semantic");var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var first=pool.submit(()->{await(start);return confirm(c,tag,"concurrent-http-key",json);});var second=pool.submit(()->{await(start);return confirm(c,tag,"concurrent-http-key",json);});start.countDown();
            var a=first.get(20,java.util.concurrent.TimeUnit.SECONDS);var b=second.get(20,java.util.concurrent.TimeUnit.SECONDS);assertEquals(200,a.getResponse().getStatus());assertEquals(200,b.getResponse().getStatus());assertEquals(body(a),body(b));assertEquals(1,count(c));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void staleManagedTaskAndPlanCannotBypassActualCommittedEdit()throws Exception{
        var c=chain();String tag=selectionTag(c,"slugify-semantic");var cached=new java.util.concurrent.CountDownLatch(1);var changed=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try{var caller=pool.submit(()->tx.execute(s->{assertEquals(1,plans.findById(c.plan).orElseThrow().getRevision());assertEquals(1,tasks.findByPlanIdAndTaskId(c.plan,"A").orElseThrow().getTaskVersion());cached.countDown();await(changed);
                var rejected=assertThrows(ApiException.class,()->confirmations.confirm(c.run,c.plan,"A",tag,input())).code();s.setRollbackOnly();return rejected;}));assertTrue(cached.await(10,java.util.concurrent.TimeUnit.SECONDS));
            planService.saveDraft(c.run,new PlanDraft(c.plan,1,"新规划",List.of(new TaskDraft("A","编辑新目标",List.of(),List.of(),"交付",List.of("正确行为"),null,"DELEGATED",true))));changed.countDown();
            assertEquals(com.dsh.api.dto.ErrorCode.VERSION_CONFLICT,caller.get(15,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,count(c));
        }finally{changed.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void actualPlanEditWaitsForConfirmationAndCreatesAnotherUnconfirmedGeneration()throws Exception{
        var c=chain();String tag=selectionTag(c,"slugify-semantic");var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var started=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var owner=pool.submit(()->tx.execute(s->{var saved=confirmations.confirm(c.run,c.plan,"A",tag,input());held.countDown();await(release);return saved;}));assertTrue(held.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var editor=pool.submit(()->{started.countDown();return planService.saveDraft(c.run,new PlanDraft(c.plan,1,"新规划",List.of(new TaskDraft("A","新目标",List.of(),List.of(),"交付",List.of("正确行为"),null,"DELEGATED",true))));});
            assertTrue(started.await(10,java.util.concurrent.TimeUnit.SECONDS));assertThrows(java.util.concurrent.TimeoutException.class,()->editor.get(150,java.util.concurrent.TimeUnit.MILLISECONDS));release.countDown();owner.get(15,java.util.concurrent.TimeUnit.SECONDS);editor.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(tasks.findByPlanIdAndTaskId(c.plan,"A").orElseThrow().getTaskVersion()>1);assertEquals(1,count(c));assertEquals(1,jdbc.queryForObject("SELECT task_version FROM task_check_registrations WHERE plan_id=?",Integer.class,c.plan));
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
    static void await(java.util.concurrent.CountDownLatch latch){try{assertTrue(latch.await(10,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}
}
