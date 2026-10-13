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

/** Actual confirmation, PG, Host inputs and owned workspace; no process/model execution. */
@SpringBootTest
@AutoConfigureMockMvc
class TaskCheckExecutionPreparationTest {
    static final Path base=createRoot();
    static Path createRoot(){try{Path root=Files.createTempDirectory("m1d2c1-api-");Files.createDirectory(root.resolve("control"));Files.createDirectory(root.resolve("workspaces"));Files.createDirectory(root.resolve("projects"));return root;}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("dsh.orchestration.task-checks.control-root",()->base.resolve("control").toString());registry.add("dsh.workspace.root",()->base.resolve("workspaces").toString());registry.add("dsh.orchestration.enabled",()->"true");registry.add("dsh.sandbox.mode",()->"docker");}
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
    @Autowired com.dsh.orchestration.acceptance.TaskCheckExecutionRequestFactory factory;
    @Autowired com.dsh.workspace.WorkspaceManager workspaces;
    @Autowired com.dsh.sandbox.SandboxRunner runner;
    @Autowired com.dsh.application.DshProperties properties;
    record Prepared(Chain chain,String registration,com.dsh.workspace.WorkspaceLease lease) { }
    Prepared confirmed(boolean complete)throws Exception {
        var c=executionChain();Path project=Path.of(projects.findById(c.project).orElseThrow().getRootPath());
        Files.createDirectories(project.resolve("src/main/java/sample"));
        Files.writeString(project.resolve("src/main/java/sample/Value.java"),"package sample; public class Value { public int value(){return 1;} }\n");
        var lease=workspaces.prepare(c.run,projects.findById(c.project).orElseThrow());
        var run=runs.findById(c.run).orElseThrow();run.setWorkspaceRef(lease.workspaceId());runs.save(run);
        String expected=complete?executionTag(c):selectionTag(c,"slugify-semantic");
        var result=confirm(c,expected,"preparation-register",payload("slugify-semantic"));
        assertEquals(200,result.getResponse().getStatus());
        return new Prepared(c,body(result).path("registrationId").asText(),lease);
    }
    com.dsh.orchestration.acceptance.TaskCheckExecutionRequest request(Prepared p){return factory.prepare(p.registration,"slugify-semantic");}
    Path javaFile(Prepared p){return p.lease.dir().resolve("src/main/java/sample/Value.java");}
    com.dsh.orchestration.acceptance.TaskCheckExecutionRequestFactory.Reason refusal(Runnable action){
        return assertThrows(com.dsh.orchestration.acceptance.TaskCheckExecutionRequestFactory.Unavailable.class,action::run).reason();
    }
    @Test void actualV2RegistrationProducesImmutableOwnedInputsWithoutExecuting()throws Exception {
        var p=confirmed(true);var r=request(p);factory.requireCurrent(r);
        assertEquals(p.registration,r.registrationId());assertEquals(p.chain.project,r.projectId());assertEquals(p.chain.run,r.rootRunId());
        assertEquals(p.lease.dir(),r.candidateRoot());assertEquals(p.lease.workspaceId(),r.workspaceId());
        assertEquals("A",r.taskId());assertEquals(1,r.taskVersion());assertEquals(1,r.candidateFiles().size());
        assertEquals("src/main/java/sample/Value.java",r.candidateFiles().getFirst().path());
        assertEquals(com.dsh.orchestration.acceptance.CanonicalTaskJson.sha256(Files.readAllBytes(javaFile(p))),r.candidateFiles().getFirst().sha256());
        assertEquals(64,r.candidateDigest().length());assertEquals(List.of("pom.xml","run-check.sh","tests/TrustedTest.java"),r.verifierPaths());
        byte[] bytes=r.verifierBytes("pom.xml");bytes[0]^=1;assertNotEquals(bytes[0],r.verifierBytes("pom.xml")[0]);
        assertThrows(UnsupportedOperationException.class,()->r.candidateFiles().clear());
        assertThrows(UnsupportedOperationException.class,()->r.check().argv().clear());
        assertThrows(UnsupportedOperationException.class,()->r.verifierPaths().clear());
        assertThrows(IllegalArgumentException.class,()->r.verifierBytes("check_slugify.py"));
        assertEquals("EXECUTION_NOT_AVAILABLE",gateDiagnostic(p.chain));assertEquals("DRAFT",tasks.findByPlanIdAndTaskId(p.chain.plan,"A").orElseThrow().getState());
        assertTrue(attempts.findByPlanIdAndTaskIdOrderByAttemptNoDesc(p.chain.plan,"A").isEmpty());
        assertEquals(1,count(p.chain));assertFalse(request(p).operationId().equals(r.operationId()));
    }
    @Test void legacyRegistrationCannotBeUpgradedIntoRequest()throws Exception {
        var p=confirmed(false);assertEquals("LEGACY_REGISTRATION",refusal(()->request(p)).name());
    }
    @Test void availableButUnselectedCheckCannotBePrepared()throws Exception {
        var p=confirmed(true);assertEquals("CHECK_NOT_SELECTED",refusal(()->factory.prepare(p.registration,"second-semantic")).name());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings={"../checkreg_x","checkreg_ABC","checkreg_123"})
    void malformedRegistrationReferenceIsRejected(String id){assertEquals("INVALID_INPUT",refusal(()->factory.prepare(id,"slugify-semantic")).name());}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings={"../slugify","sh /tmp/check.sh","x\n"})
    void malformedCheckReferenceIsRejected(String id){assertEquals("INVALID_INPUT",refusal(()->factory.prepare("checkreg_"+"0".repeat(32),id)).name());}
    @Test void unknownRegistrationIsNotARequest(){assertEquals("MISSING_REGISTRATION",refusal(()->factory.prepare("checkreg_"+"0".repeat(32),"slugify-semantic")).name());}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"binding_digest","binding_json","snapshot_digest","snapshot_json","observed_plan_revision","task_version"})
    void damagedActualStoredIdentityIsRejected(String field)throws Exception {
        var p=confirmed(true);
        switch(field){case "observed_plan_revision","task_version"->jdbc.update("UPDATE task_check_registrations SET "+field+"="+field+"+1 WHERE id=?",p.registration);
            case "binding_digest","snapshot_digest"->jdbc.update("UPDATE task_check_registrations SET "+field+"=? WHERE id=?","0".repeat(64),p.registration);
            default->jdbc.update("UPDATE task_check_registrations SET "+field+"=? WHERE id=?","{}",p.registration);}
        assertEquals("INVALID_REGISTRATION",refusal(()->request(p)).name());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"goal","requirement","cancel","trust","verifier","descriptor","cache","extra-cache"})
    void actualCurrentInputsMustStillMatchConfirmation(String change)throws Exception {
        var p=confirmed(true);var r=request(p);var c=p.chain;
        switch(change){case "goal"->jdbc.update("UPDATE agent_tasks SET goal=? WHERE plan_id=?","changed",c.plan);
            case "requirement"->jdbc.update("UPDATE messages SET content=? WHERE turn_id=? AND role='user'","new requirement",c.turn);
            case "cancel"->jdbc.update("UPDATE runs SET state='CANCELLED' WHERE id=?",c.run);
            case "trust"->jdbc.update("UPDATE projects SET trusted=false WHERE id=?",c.project);
            case "verifier"->Files.writeString(c.catalog.getParent().resolve("pom.xml"),"changed verifier");
            case "descriptor"->Files.writeString(descriptor(c),Files.readString(descriptor(c))+"\n");
            case "cache"->Files.writeString(dependency(c),"damaged");
            case "extra-cache"->Files.writeString(dependency(c).resolveSibling("extra.jar"),"not frozen");}
        assertEquals("STALE_INPUTS",refusal(()->factory.requireCurrent(r)).name());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"released","wrong-ref","wrong-project","outside","symlink-root"})
    void workspaceMustBeActualOwnedLiveLease(String change)throws Exception {
        var p=confirmed(true);var r=request(p);
        switch(change){case "released"->workspaces.release(p.chain.run);
            case "wrong-ref"->jdbc.update("UPDATE runs SET workspace_ref=? WHERE id=?","ws_"+"0".repeat(32),p.chain.run);
            case "wrong-project","outside"->{
                workspaces.release(p.chain.run);var existing=projects.findById(p.chain.project).orElseThrow();
                if(change.equals("wrong-project")){var now=Instant.now();String other="proj_"+UUID.randomUUID();var root=Files.createDirectory(base.resolve("projects").resolve(other));
                    existing=projects.save(new ProjectEntity(other,"other",root.toString(),true,now,now));}
                var other=workspaces.prepare(p.chain.run,existing);jdbc.update("UPDATE runs SET workspace_ref=? WHERE id=?",other.workspaceId(),p.chain.run);
                if(change.equals("outside")){Files.move(other.dir(),other.dir().resolveSibling(other.workspaceId()+"-moved"));}}
            case "symlink-root"->{Path moved=p.lease.dir().resolveSibling(p.lease.workspaceId()+"-old");Files.move(p.lease.dir(),moved);Files.createSymbolicLink(p.lease.dir(),moved);}}
        assertNotNull(refusal(()->factory.requireCurrent(r)));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"changed","added","deleted","oversize","file-link","directory-link","empty"})
    void candidateJavaSourceIsBoundedAndCannotDrift(String change)throws Exception {
        var p=confirmed(true);var r=request(p);Path file=javaFile(p);
        switch(change){case "changed"->Files.writeString(file,"class Changed {}\n");
            case "added"->Files.writeString(file.resolveSibling("Added.java"),"class Added {}\n");
            case "deleted","empty"->Files.delete(file);
            case "oversize"->Files.write(file,new byte[1048577]);
            case "file-link"->{Files.delete(file);Files.createSymbolicLink(file,p.chain.catalog.getParent().resolve("pom.xml"));}
            case "directory-link"->Files.createSymbolicLink(file.getParent().resolve("linked"),p.chain.catalog.getParent());}
        assertNotNull(refusal(()->factory.requireCurrent(r)));
    }
    @Test void candidateBuildTestsAndOldReportsNeverBecomeVerifierInputs()throws Exception {
        var p=confirmed(true);var r=request(p);
        Files.writeString(p.lease.dir().resolve("pom.xml"),"candidate pom should be ignored");
        Files.createDirectories(p.lease.dir().resolve("target/surefire-reports"));Files.writeString(p.lease.dir().resolve("target/surefire-reports/TEST-fake.xml"),"fake pass");
        Files.createDirectories(p.lease.dir().resolve("src/test/java"));Files.writeString(p.lease.dir().resolve("src/test/java/FakeTest.java"),"fake pass");
        factory.requireCurrent(r);assertFalse(new String(r.verifierBytes("pom.xml")).contains("candidate pom"));assertEquals(1,r.candidateFiles().size());
    }
    @Test void callerTransactionIsRejectedBeforeSlowFileReads()throws Exception {
        var p=confirmed(true);var actual=new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(s->refusal(()->request(p)));
        assertEquals("CALLER_TRANSACTION",actual.name());
    }
    @Test void isolationPlanHasFixedConfirmedPolicyAndNoFreeCommandOrEnvironment()throws Exception {
        var p=confirmed(true);var r=request(p);var plan=runner.planCheck(r);var options=plan.createOptions();
        assertEquals("docker",runner.mode());assertEquals("PREPARATION_ONLY",plan.status());assertSame(r,plan.inputs());
        assertEquals("sha256:"+r.execution().imageDigest(),plan.image());assertEquals("dsh-check-"+r.operationId(),plan.containerName());
        assertEquals(List.of("sh","/verifier/run-check.sh","normal"),plan.argv());
        assertEquals(List.of("/candidate","/verifier","/cache"),plan.readOnlyMountTargets());
        for(var entry:Map.of("--network","none","--user","1000:1000","--memory","805306368","--cpus","1","--pids-limit","128","--cap-drop","ALL","--security-opt","no-new-privileges").entrySet())
            assertEquals(entry.getValue(),options.get(options.indexOf(entry.getKey())+1));
        assertTrue(options.contains("--read-only"));assertTrue(options.contains("/tmp:rw,exec,nosuid,nodev,size=268435456,mode=1777"));
        assertFalse(options.contains("--rm"));assertFalse(options.contains("--env"));assertFalse(options.contains("-e"));assertFalse(options.contains("--privileged"));
        assertFalse(options.contains("docker"));assertFalse(options.contains("create"));assertFalse(options.contains("--mount"));
        assertThrows(UnsupportedOperationException.class,()->options.clear());assertThrows(UnsupportedOperationException.class,()->plan.argv().clear());
        assertThrows(UnsupportedOperationException.class,()->new com.dsh.sandbox.LocalProcessSandboxRunner(properties).planCheck(r));
        assertEquals("EXECUTION_NOT_AVAILABLE",gateDiagnostic(p.chain));
    }
    @Test void requestHasNoPublicConstructorAndNullCannotProduceAPlan(){
        assertEquals(0,com.dsh.orchestration.acceptance.TaskCheckExecutionRequest.class.getConstructors().length);
        assertThrows(NullPointerException.class,()->runner.planCheck(null));
        assertEquals("INVALID_INPUT",refusal(()->factory.requireCurrent(null)).name());
    }
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.dsh.orchestration.acceptance.TaskCheckPreviewSource observedSources;
    @Test void sourceMutationDuringPreparationIsRejectedByFreshFinalObservation()throws Exception {
        var p=confirmed(true);var calls=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation->{var snapshot=invocation.callRealMethod();
            if(calls.incrementAndGet()==1){var writer=new org.springframework.transaction.support.TransactionTemplate(transactionManager);writer.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);writer.executeWithoutResult(s->jdbc.update("UPDATE agent_tasks SET goal=? WHERE plan_id=?","changed during IO",p.chain.plan));}
            return snapshot;
        }).when(observedSources).read(p.chain.run,p.chain.plan,"A");
        assertEquals("STALE_INPUTS",refusal(()->request(p)).name());assertEquals(2,calls.get());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"files","total-bytes","depth","directories","case-alias","unsupported-name"})
    void sourceTreeResourceAndPathLimitsAreEnforced(String limit)throws Exception {
        var p=confirmed(true);Path dir=javaFile(p).getParent();
        switch(limit){case "files"->{for(int i=0;i<4096;i++)Files.writeString(dir.resolve("File"+i+".java"),"class Fixture {}\n");}
            case "total-bytes"->{byte[] bytes=new byte[1048576];for(int i=0;i<32;i++)Files.write(dir.resolve("Large"+i+".java"),bytes);}
            case "depth"->{Path nested=dir;for(int i=0;i<33;i++)nested=Files.createDirectory(nested.resolve("nested"));Files.writeString(nested.resolve("Deep.java"),"class Deep {}\n");}
            case "directories"->{for(int i=0;i<8192;i++)Files.createDirectory(dir.resolve("dir"+i));}
            case "case-alias"->Files.writeString(dir.resolve("value.java"),"class Alias {}\n");
            case "unsupported-name"->Files.writeString(dir.resolve("中文.java"),"class Unsupported {}\n");}
        assertEquals("INVALID_SOURCE",refusal(()->request(p)).name());
    }}
