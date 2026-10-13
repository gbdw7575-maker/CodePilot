package com.dsh.api;

import com.dsh.application.ArtifactStore;
import com.dsh.orchestration.acceptance.*;
import com.dsh.sandbox.*;
import com.dsh.storage.*;
import com.dsh.workspace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual v2 confirmation/PG/files/storage. Runner substitution is explicitly not a Docker control. */
@SpringBootTest
@AutoConfigureMockMvc
class TaskCheckAttemptEvidenceTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){TaskCheckExecutionPreparationTest.properties(registry);}
    @MockitoBean SandboxRunner runner;
    @MockitoBean com.dsh.orchestration.task.TaskSchedulingService scheduling;
    @Autowired ApplicationContext context;@Autowired WorkspaceManager workspaces;@Autowired RunRepository runs;
    @Autowired TaskAttemptRepository attempts;@Autowired InputSnapshotRepository inputs;
    @Autowired TaskResultRepository results;
    @Autowired TaskCheckAttemptPreparation preparation;@Autowired TaskCheckEvidenceService evidence;
    @Autowired ArtifactStore artifacts;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper mapper;
    TaskCheckExecutionPreparationTest helper;
    record Fixture(TaskCheckExecutionPreparationTest.Prepared confirmed,String child,String attempt,WorkspaceLease lease) { }
    @BeforeEach void setup(){helper=new TaskCheckExecutionPreparationTest();context.getAutowireCapableBeanFactory().autowireBean(helper);}
    Fixture fixture()throws Exception {
        return fixture(helper.confirmed(true),"[]");
    }
    Fixture fixture(TaskCheckExecutionPreparationTest.Prepared confirmed,String artifactJson)throws Exception {
        var c=confirmed.chain();String child="sub_run_"+UUID.randomUUID().toString().replace("-","");
        String attempt="att_"+UUID.randomUUID().toString().replace("-","");var now=Instant.now();
        var lease=workspaces.prepare(child,helper.projects.findById(c.project()).orElseThrow());
        Files.writeString(lease.dir().resolve("src/main/java/sample/Value.java"),"package sample; public class Value { public int value(){return 2;} }\n");
        runs.save(new RunEntity(child,c.turn(),c.run(),"CODER","COMPLETED","PENDING",1,lease.workspaceId(),"digest",24,80,200000,now.plusSeconds(600),now,now));
        jdbc.update("UPDATE agent_tasks SET state='VERIFYING' WHERE plan_id=? AND task_id='A'",c.plan());
        attempts.save(new TaskAttemptEntity(attempt,c.plan(),"A",1,child,1,"worker-pool",now.plusSeconds(300),2L,null,"RUNNING",now,now));
        inputs.save(new InputSnapshotEntity("snap_"+UUID.randomUUID(),child,c.run(),c.project(),lease.workspaceId(),"",artifactJson,null,now));
        workspaces.release(child);return new Fixture(confirmed,child,attempt,lease);
    }
    record UpstreamFixture(Fixture fixture,ArtifactEntity artifact,Path content) { }
    UpstreamFixture upstream()throws Exception {
        return upstream(null);
    }
    UpstreamFixture upstream(String requirement)throws Exception {
        var c=helper.executionChain();var project=helper.projects.findById(c.project()).orElseThrow();Path projectPath=Path.of(project.getRootPath());
        if(requirement!=null){
            jdbc.update("UPDATE messages SET content=? WHERE turn_id=? AND role='user'",requirement,c.turn());
            jdbc.update("UPDATE turns SET input=? WHERE id=?",requirement,c.turn());
        }
        Files.createDirectories(projectPath.resolve("src/main/java/sample"));Files.writeString(projectPath.resolve("src/main/java/sample/Value.java"),"package sample; public class Value {}\n");
        var root=workspaces.prepare(c.run(),project);var run=runs.findById(c.run()).orElseThrow();run.setWorkspaceRef(root.workspaceId());runs.save(run);
        var now=Instant.now();String upstream="sub_run_"+UUID.randomUUID().toString().replace("-","");var lease=workspaces.prepare(upstream,project);
        runs.save(new RunEntity(upstream,c.turn(),c.run(),"CODER","COMPLETED","PENDING",1,lease.workspaceId(),"digest",24,80,200000,now.plusSeconds(600),now,now));
        helper.tasks.save(new TaskEntity("task_"+UUID.randomUUID(),c.plan(),"U",1,"上游","[]","[]","交付","[\"正确\"]",null,"DELEGATED",true,"SUCCEEDED",now,now));
        jdbc.update("UPDATE agent_tasks SET depends_on='[\"U\"]',input_refs='[{\"taskId\":\"U\",\"output\":\"patch\"}]' WHERE plan_id=? AND task_id='A'",c.plan());
        String attempt="att_"+UUID.randomUUID().toString().replace("-","");attempts.save(new TaskAttemptEntity(attempt,c.plan(),"U",1,upstream,1,null,null,1L,null,"SUCCEEDED",now,now));
        var artifact=artifacts.save(upstream,c.project(),"upstream.txt","text/plain","actual upstream bytes".getBytes(StandardCharsets.UTF_8),true);
        results.save(new TaskResultEntity(attempt,c.plan(),"U",1,upstream,"SUCCEEDED",null,null,mapper.writeValueAsString(List.of(artifact.getId())),artifact.getId(),"a".repeat(64),null,null,0,0,null,1,now));
        workspaces.release(upstream);
        var response=helper.confirm(c,helper.executionTag(c),"upstream-confirm",helper.payload("slugify-semantic"));assertEquals(200,response.getResponse().getStatus());
        String reg=mapper.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("registrationId").asText();
        String refs=mapper.writeValueAsString(List.of(Map.of("artifactId",artifact.getId(),"mediaType",artifact.getMediaType(),"sha256",artifact.getSha256())));
        return new UpstreamFixture(fixture(new TaskCheckExecutionPreparationTest.Prepared(c,reg,root),refs),artifact,lease.metaDir().resolve("artifacts").resolve(artifact.getId()));
    }
    @Test void upstreamRealBytesAreBoundAndRecorded()throws Exception {
        var u=upstream();var p=prepare(u.fixture());success(p);var sealed=evidence.executeAndSeal(p,()->false);
        assertEquals(u.artifact().getSha256(),mapper.readTree(evidence.verify(sealed)).path("binding").path("attemptObservation").path("upstream").get(0).path("artifacts").get(0).path("sha256").asText());
    }
    @Test void completeLongRequirementCanBeSealedAndReadWithoutTruncation()throws Exception {
        String text="需求".repeat(5000);var u=upstream(text);var p=prepare(u.fixture());success(p);
        var sealed=evidence.executeAndSeal(p,()->false);
        assertEquals(text,mapper.readTree(evidence.verify(sealed)).path("binding").path("source").path("requirements").get(0).path("content").asText());
    }
    @ParameterizedTest @ValueSource(strings={"bytes","missing","symlink","metadata"})
    void upstreamActualContentMustStillMatchFrozenIdentity(String mode)throws Exception {
        var u=upstream();switch(mode){case "bytes"->Files.writeString(u.content(),"corrupted");case "missing"->Files.delete(u.content());
            case "symlink"->{Files.delete(u.content());Files.createSymbolicLink(u.content(),helper.javaFile(u.fixture().confirmed()));}
            case "metadata"->jdbc.update("UPDATE artifacts SET sha256=? WHERE id=?","b".repeat(64),u.artifact().getId());}
        assertThrows(RuntimeException.class,()->prepare(u.fixture()));verifyNoInteractions(runner);
    }
    @Test void upstreamMutationDuringCheckCannotHaveCurrentEvidence()throws Exception {
        var u=upstream();var p=prepare(u.fixture());when(runner.executeCheck(same(p.request()),any())).thenAnswer(call->{Files.writeString(u.content(),"corrupted");
            return outcome(p.request(),TaskCheckExecutionOutcome.Termination.COMPLETED,0,TaskCheckExecutionOutcome.Presence.ABSENT,Map.of("target/surefire-reports/TEST-Trusted.xml",xml("works")));});
        assertFalse(evidence.executeAndSeal(p,()->false).currentAtReturn());
    }
    TaskCheckAttemptPreparation.Prepared prepare(Fixture f){return preparation.prepare(f.confirmed().registration(),"slugify-semantic",f.attempt(),f.child(),2);}
    static byte[] xml(String name){return ("<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase classname=\"Trusted\" name=\""+name+"\"/></testsuite>").getBytes(StandardCharsets.UTF_8);}
    static TaskCheckExecutionOutcome outcome(TaskCheckExecutionRequest r,TaskCheckExecutionOutcome.Termination termination,Integer exit,
            TaskCheckExecutionOutcome.Presence cleanup,Map<String,byte[]> reports)throws Exception {
        var ctor=TaskCheckExecutionOutcome.class.getDeclaredConstructors()[0];ctor.setAccessible(true);
        return (TaskCheckExecutionOutcome)ctor.newInstance(r.operationId(),"a".repeat(64),termination,exit,"NONE",cleanup,
                new TaskCheckExecutionOutcome.Logs("stdout\n","stderr\n",14L,0L,false),25L,reports);
    }
    void success(TaskCheckAttemptPreparation.Prepared p)throws Exception {
        when(runner.executeCheck(same(p.request()),any())).thenReturn(outcome(p.request(),TaskCheckExecutionOutcome.Termination.COMPLETED,0,
                TaskCheckExecutionOutcome.Presence.ABSENT,Map.of("target/surefire-reports/TEST-Trusted.xml",xml("works"))));
    }
    @Test void actualChildBytesReportsAndOwnershipAreSealedWithoutSuccess()throws Exception {
        var f=fixture();var p=prepare(f);success(p);var sealed=evidence.executeAndSeal(p,()->false);
        assertTrue(sealed.currentAtReturn());assertEquals(f.child(),sealed.manifest().runId());assertEquals(4,sealed.members().size());
        var tree=mapper.readTree(evidence.verify(sealed));assertFalse(tree.path("successAuthority").asBoolean(true));
        assertEquals(f.lease().workspaceId(),tree.path("binding").path("workspaceRef").asText());
        assertEquals("  用户原文\n约束  ",tree.path("binding").path("source").path("requirements").get(0).path("content").asText());
        assertEquals(1,tree.path("parsedReports").path("counts").path("passed").asInt());
        assertEquals("VERIFYING",helper.tasks.findByPlanIdAndTaskId(f.confirmed().chain().plan(),"A").orElseThrow().getState());
        assertEquals("RUNNING",attempts.findById(f.attempt()).orElseThrow().getState());
        var zip=sealed.members().stream().filter(a->a.mediaType().equals("application/zip")).findFirst().orElseThrow();
        try(var input=new ZipInputStream(new ByteArrayInputStream(artifacts.readVerifiedContent(zip.id(),64L*1048576)))){
            var entry=input.getNextEntry();assertEquals("src/main/java/sample/Value.java",entry.getName());
            assertTrue(new String(input.readAllBytes(),StandardCharsets.UTF_8).contains("return 2"));assertNull(input.getNextEntry());
        }
        assertFalse(new String(evidence.verify(sealed),StandardCharsets.UTF_8).contains(TaskCheckExecutionPreparationTest.base.toString()));
    }
    @ParameterizedTest @ValueSource(strings={"candidate","requirement","cache","baseline","fencing","cancel"})
    void driftBeforeExecutionRefusesAndNeverCallsRunner(String mode)throws Exception {
        var f=fixture();var p=prepare(f);drift(f,mode);assertThrows(RuntimeException.class,()->evidence.executeAndSeal(p,()->false));verifyNoInteractions(runner);
    }
    void drift(Fixture f,String mode)throws Exception {
        switch(mode){
            case "candidate"->Files.writeString(f.lease().dir().resolve("src/main/java/sample/Value.java"),"changed");
            case "baseline"->Files.writeString(f.lease().metaDir().resolve("baseline-manifest.json"),"{}");
            case "requirement"->jdbc.update("UPDATE messages SET content='changed' WHERE turn_id=? AND role='user'",f.confirmed().chain().turn());
            case "cache"->Files.writeString(helper.dependency(f.confirmed().chain()),"changed");
            case "fencing"->jdbc.update("UPDATE agent_task_attempts SET fencing_token=3 WHERE attempt_id=?",f.attempt());
            case "cancel"->jdbc.update("UPDATE runs SET cancel_requested_at=now() WHERE id=?",f.confirmed().chain().run());
        }
    }
    @ParameterizedTest @ValueSource(strings={"candidate","requirement","cache","baseline","fencing","cancel"})
    void driftDuringExecutionPreservesActualExitAndMarksEvidenceStale(String mode)throws Exception {
        var f=fixture();var p=prepare(f);
        when(runner.executeCheck(same(p.request()),any())).thenAnswer(call->{drift(f,mode);return outcome(p.request(),TaskCheckExecutionOutcome.Termination.COMPLETED,0,
            TaskCheckExecutionOutcome.Presence.ABSENT,Map.of("target/surefire-reports/TEST-Trusted.xml",xml("works")));});
        var sealed=evidence.executeAndSeal(p,()->false);assertFalse(sealed.currentAtReturn());var tree=mapper.readTree(evidence.verify(sealed));
        assertEquals("COMPLETED",tree.path("execution").path("termination").asText());assertEquals(0,tree.path("execution").path("exitCode").asInt(-1));
        assertFalse(tree.path("currentAtSeal").asBoolean(true));
    }
    @ParameterizedTest @ValueSource(strings={"TIMEOUT","CANCELLED","LAUNCH_FAILED","LOST","exit-1","cleanup-unknown","malformed","missing","duplicates"})
    void failedAndIncompleteOutcomesRemainInspectableWithoutSuccess(String mode)throws Exception {
        var f=fixture();var p=prepare(f);var termination=TaskCheckExecutionOutcome.Termination.COMPLETED;Integer exit=0;
        var cleanup=TaskCheckExecutionOutcome.Presence.ABSENT;Map<String,byte[]> reports=Map.of("target/surefire-reports/TEST-Trusted.xml",xml("works"));
        switch(mode){case "TIMEOUT","CANCELLED","LAUNCH_FAILED","LOST"->{termination=TaskCheckExecutionOutcome.Termination.valueOf(mode);exit=null;reports=Map.of();}
            case "exit-1"->exit=1;case "cleanup-unknown"->cleanup=TaskCheckExecutionOutcome.Presence.UNKNOWN;
            case "malformed"->reports=Map.of("target/surefire-reports/TEST-Trusted.xml","broken xml".getBytes(StandardCharsets.UTF_8));
            case "missing"->reports=Map.of();case "duplicates"->reports=Map.of("target/surefire-reports/TEST-Trusted.xml",xml("same"),"target/surefire-reports/TEST-Other.xml",xml("same"));}
        var raw=outcome(p.request(),termination,exit,cleanup,reports);when(runner.executeCheck(same(p.request()),any())).thenReturn(raw);
        var sealed=evidence.executeAndSeal(p,()->false);var tree=mapper.readTree(evidence.verify(sealed));
        assertEquals(termination.name(),tree.path("execution").path("termination").asText());assertEquals(cleanup.name(),tree.path("execution").path("cleanup").asText());
        assertFalse(tree.path("successAuthority").asBoolean(true));
        if(mode.equals("malformed")||mode.equals("missing")||mode.equals("duplicates"))assertTrue(tree.path("parsedReports").path("error").asText().startsWith("REPORT_"));
        if(mode.equals("malformed"))assertArrayEquals(reports.values().iterator().next(),artifacts.readVerifiedContent(sealed.members().get(1).id(),1048576));
    }
    @Test void externalCancellationIsStickyAndDatabaseInvalidationIsObserved()throws Exception {
        var f=fixture();var p=prepare(f);var external=new java.util.concurrent.atomic.AtomicBoolean();
        when(runner.executeCheck(same(p.request()),any())).thenAnswer(call->{BooleanSupplier cancel=call.getArgument(1);
            assertFalse(cancel.getAsBoolean());external.set(true);assertTrue(cancel.getAsBoolean());external.set(false);assertTrue(cancel.getAsBoolean());
            return outcome(p.request(),TaskCheckExecutionOutcome.Termination.CANCELLED,null,TaskCheckExecutionOutcome.Presence.ABSENT,Map.of());});
        assertTrue(mapper.readTree(evidence.verify(evidence.executeAndSeal(p,external::get))).path("cancellationObserved").asBoolean());
    }
    @ParameterizedTest @ValueSource(strings={"pom.xml","README.md","src/main/resources/config.yml"})
    void unsupportedChangedFilesCannotMasqueradeAsVerifiedDelivery(String name)throws Exception {
        var f=fixture();Path file=f.lease().dir().resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,"unchecked");
        assertEquals("INVALID_DELIVERY",assertThrows(IllegalStateException.class,()->prepare(f)).getMessage());verifyNoInteractions(runner);
    }
    @Test void sharedChildWorkspaceRefIsRefused()throws Exception {
        var f=fixture();jdbc.update("UPDATE runs SET workspace_ref=? WHERE id=?",f.lease().workspaceId(),f.confirmed().chain().run());
        assertThrows(RuntimeException.class,()->prepare(f));
    }
    @ParameterizedTest @ValueSource(strings={"bytes","metadata","symlink"})
    void sealedActualArtifactTamperingIsRefused(String mode)throws Exception {
        var f=fixture();var p=prepare(f);success(p);var sealed=evidence.executeAndSeal(p,()->false);var ref=sealed.members().get(1);
        Path file=f.lease().metaDir().resolve("artifacts").resolve(ref.id());
        switch(mode){case "bytes"->Files.writeString(file,"changed");case "metadata"->jdbc.update("UPDATE artifacts SET media_type='text/plain' WHERE id=?",ref.id());
            case "symlink"->{Files.delete(file);Files.createSymbolicLink(file,f.lease().dir().resolve("src/main/java/sample/Value.java"));}}
        assertThrows(RuntimeException.class,()->evidence.verify(sealed));
    }
    @Test void storageRedirectRefusesCompleteEvidenceAndDoesNotWriteThroughLink()throws Exception {
        var f=fixture();var p=prepare(f);success(p);Path outside=Files.createTempDirectory("evidence-outside-");
        Files.createSymbolicLink(f.lease().metaDir().resolve("artifacts"),outside);
        assertThrows(RuntimeException.class,()->evidence.executeAndSeal(p,()->false));
        try(var files=Files.list(outside)){assertEquals(0,files.count());}
    }
    @Test void candidateRedirectRefusesBeforeExecution()throws Exception {
        var f=fixture();Path file=f.lease().dir().resolve("src/main/java/sample/Value.java");Files.delete(file);Files.createSymbolicLink(file,helper.javaFile(f.confirmed()));
        assertThrows(RuntimeException.class,()->prepare(f));verifyNoInteractions(runner);
    }
    @ParameterizedTest @ValueSource(strings={"path","size","alias","operation","exit","container","logs"})
    void invalidRunnerProtocolNeverProducesCompleteEvidence(String mode)throws Exception {
        var f=fixture();var p=prepare(f);Map<String,byte[]> reports=Map.of("target/surefire-reports/TEST-Trusted.xml",xml("works"));
        String operation=p.request().operationId(),container="a".repeat(64);Integer exit=0;var logs=new TaskCheckExecutionOutcome.Logs("","",0,0,false);
        switch(mode){case "path"->reports=Map.of("../outside.xml",xml("works"));case "size"->reports=Map.of("target/surefire-reports/TEST-Trusted.xml",new byte[1048577]);
            case "alias"->reports=Map.of("target/surefire-reports/TEST-Trusted.xml",xml("one"),"target/surefire-reports/TEST-trusted.xml",xml("two"));
            case "operation"->operation="other";case "exit"->exit=256;case "container"->container="unsafe";
            case "logs"->logs=new TaskCheckExecutionOutcome.Logs("","",1,2,true);}
        var ctor=TaskCheckExecutionOutcome.class.getDeclaredConstructors()[0];ctor.setAccessible(true);
        var raw=(TaskCheckExecutionOutcome)ctor.newInstance(operation,container,TaskCheckExecutionOutcome.Termination.COMPLETED,exit,"NONE",TaskCheckExecutionOutcome.Presence.ABSENT,logs,25L,reports);
        when(runner.executeCheck(same(p.request()),any())).thenReturn(raw);
        assertEquals("CHECK_OUTCOME_INVALID",assertThrows(IllegalStateException.class,()->evidence.executeAndSeal(p,()->false)).getMessage());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE run_id=?",Integer.class,f.child()));
    }
    @ParameterizedTest @ValueSource(strings={"owner","workspace","name","type"})
    void evidenceStoreRequiresExplicitOwnerAndSafeParameters(String mode)throws Exception {
        var f=fixture();String project=f.confirmed().chain().project(),ref=f.lease().workspaceId(),name="check-test.bin",type="application/octet-stream";
        switch(mode){case "owner"->project="proj_other";case "workspace"->ref=f.confirmed().lease().workspaceId();case "name"->name="check-../escape";case "type"->type=null;}
        String finalProject=project,finalRef=ref,finalName=name,finalType=type;
        assertThrows(RuntimeException.class,()->artifacts.saveCheckEvidence(f.child(),finalProject,finalRef,finalName,finalType,new byte[1]));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE run_id=?",Integer.class,f.child()));
    }
    @Test void executionIsForbiddenWithinCallerTransaction()throws Exception {
        var f=fixture();var p=prepare(f);var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        tx.execute(status->{assertThrows(RuntimeException.class,()->evidence.executeAndSeal(p,()->false));return null;});verifyNoInteractions(runner);
    }
}
