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
class TaskCheckExecutionConfirmationApiTest {
    static final Path base=createRoot();
    static Path createRoot(){try{Path root=Files.createTempDirectory("m1d2b-api-");Files.createDirectory(root.resolve("control"));Files.createDirectory(root.resolve("workspaces"));Files.createDirectory(root.resolve("projects"));return root;}catch(Exception e){throw new IllegalStateException(e);}}
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

    @Autowired com.dsh.orchestration.acceptance.TaskCheckDispatchGate gate;
    Path descriptor(Chain c){return c.catalog.getParent().resolve("task-execution.json");}
    Path dependency(Chain c){return c.catalog.getParent().resolve("runtime-cache/dep.jar");}
    Chain executionChain()throws Exception {
        var c=chain();var dir=c.catalog.getParent();Files.createDirectory(dir.resolve("tests"));
        var verifier=mapper.createArrayNode();
        for(String name:List.of("pom.xml","run-check.sh","tests/TrustedTest.java")){
            var file=dir.resolve(name);Files.writeString(file,"synthetic verifier bytes: no Runner execution "+name);
            verifier.addObject().put("path",name).put("sha256",com.dsh.orchestration.acceptance.CanonicalTaskJson.sha256(Files.readAllBytes(file)));
        }
        var catalog=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Files.readAllBytes(c.catalog));
        for(var check:catalog.path("checks")){
            var value=(com.fasterxml.jackson.databind.node.ObjectNode)check;
            value.putArray("argv").add("sh").add("/verifier/run-check.sh").add("normal");value.set("verifiers",verifier.deepCopy());
        }
        Files.write(c.catalog,mapper.writeValueAsBytes(catalog));Files.createDirectory(dir.resolve("runtime-cache"));Files.writeString(dependency(c),"synthetic cache, not runnable Maven");
        var spec=mapper.createObjectNode().put("version","dsh-task-execution-v1").put("projectId",c.project)
                .put("registryDigest",com.dsh.orchestration.acceptance.CanonicalTaskJson.sha256(Files.readAllBytes(c.catalog)))
                .put("imageDigest",catalog.path("imageDigest").asText()).put("adapterId","MAVEN_JUNIT_SOURCE_V1");
        spec.set("executionPolicy",catalog.path("executionPolicy").deepCopy());
        spec.putObject("limits").put("cpuMillis",1000).put("memoryBytes",805306368).put("pids",128).put("tmpfsBytes",268435456)
                .put("logBytes",1048576).put("reportFiles",64).put("reportFileBytes",1048576).put("reportTotalBytes",4194304);
        var report=spec.putObject("report").put("format","MAVEN_SUREFIRE").put("policy","surefire-reports-v1")
                .put("transport","BOUNDED_DOCKER_STDOUT_BYTES_V1").put("lifecycle","MAVEN_EXIT_CAPTURE_RELEASE_V1");
        report.putArray("directories").add("target/surefire-reports");
        spec.putArray("cacheManifest").addObject().put("path","dep.jar").put("bytes",Files.size(dependency(c)))
                .put("sha256",com.dsh.orchestration.acceptance.CanonicalTaskJson.sha256(Files.readAllBytes(dependency(c))));
        spec.putArray("checkRefs").add("second-semantic").add("slugify-semantic");Files.write(descriptor(c),mapper.writeValueAsBytes(spec));return c;
    }
    org.springframework.test.web.servlet.MvcResult execution(Chain c,String expected,String... refs)throws Exception {
        var request=get(url(c).replace("acceptance-preview","acceptance-execution-preview")).header("Host","localhost");
        if(expected!=null)request.header("If-Match",expected);if(refs.length>0)request.queryParam("checkRef",refs);return mvc.perform(request).andReturn();
    }
    String executionTag(Chain c)throws Exception {var result=execution(c,tag(c),"slugify-semantic");assertEquals(200,result.getResponse().getStatus());return result.getResponse().getHeader("ETag");}
    String gateDiagnostic(Chain c){return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(s->gate.inspect(c.run,c.plan,List.of(tasks.findByPlanIdAndTaskId(c.plan,"A").orElseThrow())).diagnostic());}
    @Test void actualFullPreviewAndConfirmationBindAllExecutionBytesWithoutDispatch()throws Exception {
        var c=executionChain();var result=execution(c,tag(c),"slugify-semantic");assertEquals(200,result.getResponse().getStatus());
        var view=body(result);assertTrue(result.getResponse().getHeader("ETag").startsWith("\"task-check-selection-v2-"));
        assertEquals(2,view.path("execution").path("checks").size());assertFalse(view.toString().contains(base.toString()));
        var def=mapper.readTree(view.path("execution").path("canonicalDefinition").asText());assertEquals(1000,def.path("limits").path("cpuMillis").asInt());assertEquals(1,def.path("cacheManifest").size());
        var saved=confirm(c,result.getResponse().getHeader("ETag"),"complete-register",payload("slugify-semantic"));assertEquals(200,saved.getResponse().getStatus());
        Files.writeString(Path.of("target/m1d2b-execution-preview.json"),view.toString());
        Files.writeString(Path.of("target/m1d2b-execution-registration.json"),body(saved).toString());
        assertEquals(view.path("execution").path("executionSpecDigest"),body(saved).path("executionSpecDigest"));assertTrue(saved.getResponse().getHeader("ETag").startsWith("\"task-check-registration-v2-"));
        var stored=mapper.readTree(jdbc.queryForObject("SELECT binding_json FROM task_check_registrations WHERE plan_id=?",String.class,c.plan));
        assertEquals("task-check-registration-v2",stored.path("version").asText());assertEquals(view.path("execution"),stored.path("execution"));
        assertEquals("DRAFT",tasks.findByPlanIdAndTaskId(c.plan,"A").orElseThrow().getState());assertEquals("EXECUTION_NOT_AVAILABLE",gateDiagnostic(c));
        assertTrue(attempts.findByPlanIdAndTaskIdOrderByAttemptNoDesc(c.plan,"A").isEmpty());
    }
    @Test void v1WireAndBindingRemainUnchangedAndCannotAutomaticallyUpgrade()throws Exception {
        var c=executionChain();var old=select(c,tag(c),"slugify-semantic");assertFalse(body(old).has("execution"));
        var saved=confirm(c,old.getResponse().getHeader("ETag"),"old-register",payload("slugify-semantic"));assertEquals(200,saved.getResponse().getStatus());assertFalse(body(saved).has("executionSpecDigest"));
        assertEquals(409,confirm(c,executionTag(c),"upgrade-rejected",payload("slugify-semantic")).getResponse().getStatus());assertEquals(1,count(c));
        assertEquals("task-check-registration-v1",mapper.readTree(jdbc.queryForObject("SELECT binding_json FROM task_check_registrations WHERE plan_id=?",String.class,c.plan)).path("version").asText());
    }
    @Test void completeRegistrationCannotBeDowngradedOrOverwritten()throws Exception {
        var c=executionChain();assertEquals(200,confirm(c,executionTag(c),"v2-register",payload("slugify-semantic")).getResponse().getStatus());
        assertEquals(409,confirm(c,selectionTag(c,"slugify-semantic"),"downgrade-rejected",payload("slugify-semantic")).getResponse().getStatus());assertEquals(1,count(c));
    }
    @Test void snapshotLargeCanonicalDefinitionCanRoundTripThroughActualStorage()throws Exception {
        var c=executionChain();var spec=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Files.readAllBytes(descriptor(c)));var manifest=spec.putArray("cacheManifest");
        for(int i=0;i<1000;i++){String name=String.format(java.util.Locale.ROOT,"cache-%04d.jar",i);Files.write(c.catalog.getParent().resolve("runtime-cache").resolve(name),new byte[0]);manifest.addObject().put("path",name).put("bytes",0).put("sha256",com.dsh.orchestration.acceptance.CanonicalTaskJson.sha256(new byte[0]));}
        manifest.addObject().put("path","dep.jar").put("bytes",Files.size(dependency(c))).put("sha256",com.dsh.orchestration.acceptance.CanonicalTaskJson.sha256(Files.readAllBytes(dependency(c))));Files.write(descriptor(c),mapper.writeValueAsBytes(spec));
        var preview=execution(c,tag(c),"slugify-semantic");assertEquals(200,preview.getResponse().getStatus());assertTrue(body(preview).path("execution").path("canonicalDefinition").asText().length()>100000);
        assertEquals(200,confirm(c,preview.getResponse().getHeader("ETag"),"large-first",payload("slugify-semantic")).getResponse().getStatus());
        assertEquals(200,confirm(c,preview.getResponse().getHeader("ETag"),"large-replay-new-key",payload("slugify-semantic")).getResponse().getStatus());assertEquals("EXECUTION_NOT_AVAILABLE",gateDiagnostic(c));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"limits","report","whitespace","cache"})
    void descriptorOrValidCacheIdentityDriftRequiresNewConfirmation(String change)throws Exception {
        var c=executionChain();String old=executionTag(c);var spec=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Files.readAllBytes(descriptor(c)));
        switch(change){case "limits"->((com.fasterxml.jackson.databind.node.ObjectNode)spec.path("limits")).put("cpuMillis",1500);
            case "report"->((com.fasterxml.jackson.databind.node.ObjectNode)spec.path("limits")).put("reportFiles",32);
            case "cache"->{Files.writeString(dependency(c),"new valid cache bytes");var row=(com.fasterxml.jackson.databind.node.ObjectNode)spec.path("cacheManifest").get(0);row.put("bytes",Files.size(dependency(c))).put("sha256",com.dsh.orchestration.acceptance.CanonicalTaskJson.sha256(Files.readAllBytes(dependency(c))));}}
        Files.write(descriptor(c),mapper.writeValueAsBytes(spec));if(change.equals("whitespace"))Files.writeString(descriptor(c),Files.readString(descriptor(c))+"\n");
        assertEquals(412,confirm(c,old,"stale-"+change,payload("slugify-semantic")).getResponse().getStatus());assertEquals(0,count(c));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"missing","extra-cache","damaged-cache","project-untrusted","other-project-overlap"})
    void unavailableActualInputsNeverFallBackToLegacyPreview(String change)throws Exception {
        var c=executionChain();String before=tag(c);
        switch(change){case "missing"->Files.delete(descriptor(c));case "extra-cache"->Files.writeString(dependency(c).resolveSibling("extra.jar"),"extra");case "damaged-cache"->Files.writeString(dependency(c),"corrupt");
            case "project-untrusted"->jdbc.update("UPDATE projects SET trusted=false WHERE id=?",c.project);
            case "other-project-overlap"->{var now=Instant.now();projects.save(new ProjectEntity("outside_"+UUID.randomUUID(),"overlap",base.resolve("control").toString(),false,now,now));}}
        try{assertTrue(execution(c,before,"slugify-semantic").getResponse().getStatus()>=400);assertEquals(0,count(c));}
        finally{if(change.equals("other-project-overlap"))jdbc.update("DELETE FROM projects WHERE root_path=?",base.resolve("control").toString());}
    }
    @Test void wrongEtagsUnknownParametersAndUnselectedRefsRefuse()throws Exception {
        var c=executionChain();String old=selectionTag(c,"slugify-semantic"),complete=executionTag(c);
        assertEquals(428,execution(c,null,"slugify-semantic").getResponse().getStatus());assertEquals(400,execution(c,old,"slugify-semantic").getResponse().getStatus());
        assertEquals(412,confirm(c,complete.replace("-v2-","-v1-"),"wrong-v1",payload("slugify-semantic")).getResponse().getStatus());assertEquals(412,confirm(c,old.replace("-v1-","-v2-"),"wrong-v2",payload("slugify-semantic")).getResponse().getStatus());
        assertEquals(400,execution(c,tag(c),"slugify-semantic,second-semantic").getResponse().getStatus());
        assertEquals(400,mvc.perform(get(url(c).replace("acceptance-preview","acceptance-execution-preview")).header("Host","localhost").header("If-Match",tag(c)).queryParam("command","echo passed").queryParam("checkRef","slugify-semantic")).andReturn().getResponse().getStatus());assertEquals(0,count(c));
    }
    @Test void completeHistoricalReplayDoesNotCertifyCurrentCache()throws Exception {
        var c=executionChain();String tag=executionTag(c),input=payload("slugify-semantic");var first=confirm(c,tag,"history-key",input);assertEquals(200,first.getResponse().getStatus());
        Files.writeString(dependency(c),"damaged");assertEquals(body(first),body(confirm(c,tag,"history-key",input)));assertTrue(confirm(c,tag,"new-current-key",input).getResponse().getStatus()>=400);assertEquals("STALE_BINDING",gateDiagnostic(c));assertEquals(1,count(c));
    }
    @Test void changedDescriptionBeforeActualTransactionCommitRollsBack()throws Exception {
        var c=executionChain();String tag=executionTag(c);var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThrows(ApiException.class,()->tx.execute(s->{confirmations.confirm(c.run,c.plan,"A",tag,input());try{Files.writeString(descriptor(c),Files.readString(descriptor(c))+"\n");}catch(Exception e){throw new IllegalStateException(e);}return null;}));assertEquals(0,count(c));
    }
    @Test void confirmedPolicyDriftIsStaleAtProductionDispatchGate()throws Exception {
        var c=executionChain();assertEquals(200,confirm(c,executionTag(c),"before-drift",payload("slugify-semantic")).getResponse().getStatus());
        Files.writeString(descriptor(c),Files.readString(descriptor(c))+"\n");assertEquals("STALE_BINDING",gateDiagnostic(c));
    }
    @Test void requirementAndVersionChangesRejectCompleteConfirmation()throws Exception {
        var c=executionChain();String old=executionTag(c);messages.save(new MessageEntity("added_"+UUID.randomUUID(),c.thread,c.turn,"user","新增需求",9007199254740994L,Instant.now()));
        assertEquals(412,confirm(c,old,"source-drift",payload("slugify-semantic")).getResponse().getStatus());
        String current=executionTag(c);jdbc.update("UPDATE agent_tasks SET task_version=2 WHERE plan_id=? AND task_id='A'",c.plan);assertEquals(412,confirm(c,current,"version-drift",payload("slugify-semantic")).getResponse().getStatus());assertEquals(0,count(c));
    }
}
