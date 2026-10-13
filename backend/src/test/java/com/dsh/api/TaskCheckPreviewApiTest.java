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
class TaskCheckPreviewApiTest {
    static final Path base=createRoot();
    static Path createRoot(){try{Path root=Files.createTempDirectory("m1c2b-api-");Files.createDirectory(root.resolve("control"));Files.createDirectory(root.resolve("workspaces"));Files.createDirectory(root.resolve("projects"));return root;}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("dsh.orchestration.task-checks.control-root",()->base.resolve("control").toString());registry.add("dsh.workspace.root",()->base.resolve("workspaces").toString());registry.add("dsh.orchestration.enabled",()->"false");}
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
        Path file=privateDir.resolve("task-checks.json");Files.write(file,mapper.writeValueAsBytes(catalog));Files.writeString(privateDir.resolve("check_slugify.py"),"assert 2 + 2 == 4\n");
        return new Chain(project,thread,turn,run,plan,file);
    }
    private String url(Chain c){return "/api/v1/runs/"+c.run+"/plans/"+c.plan+"/tasks/A/acceptance-preview";}
    private JsonNode getPreview(Chain c,int status)throws Exception{var result=mvc.perform(get(url(c)).header("Host","localhost")).andReturn();assertEquals(status,result.getResponse().getStatus(),result.getResponse().getContentAsString());return mapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
    @Test void actualGetIsUncachedPreviewAndDoesNotCreateAnything()throws Exception{
        var c=chain();long taskCount=tasks.count(),planCount=plans.count(),attemptCount=attempts.count(),artifactCount=artifacts.count(),runCount=runs.count();
        var response=mvc.perform(get(url(c)).header("Host","localhost")).andReturn();assertEquals(200,response.getResponse().getStatus());var body=mapper.readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("PREVIEW_ONLY",body.path("status").asText());assertEquals("  用户原文\n约束  ",body.path("requirements").get(0).path("content").asText());assertEquals("9007199254740993",body.path("requirements").get(0).path("sequence").asText());
        assertEquals("\"task-check-preview-v1-"+body.path("previewDigest").asText()+"\"",response.getResponse().getHeader("ETag"));assertEquals("no-store",response.getResponse().getHeader("Cache-Control"));assertFalse(body.has("registrationId"));
        assertEquals(1,body.path("availableChecks").size());assertEquals("DRAFT",tasks.findByPlanIdAndTaskId(c.plan,"A").orElseThrow().getState());
        assertEquals(taskCount,tasks.count());assertEquals(planCount,plans.count());assertEquals(attemptCount,attempts.count());assertEquals(artifactCount,artifacts.count());assertEquals(runCount,runs.count());
    }
    @Test void crossRootPlanAndChildRoutesCannotReadDefinitions()throws Exception{
        var c=chain();var other=chain();var cross=mvc.perform(get(url(c).replace(c.plan,other.plan)).header("Host","localhost")).andReturn();assertEquals(404,cross.getResponse().getStatus());
        var now=Instant.now();String child="child_"+UUID.randomUUID();runs.save(new RunEntity(child,c.turn,c.run,"CODER","RUNNING","PENDING",1,"ws_child","digest",1,1,100,now,now,now));
        assertEquals(404,mvc.perform(get(url(c).replace(c.run,child)).header("Host","localhost")).andReturn().getResponse().getStatus());
    }
    @Test void revokedTrustAndInvalidCatalogAreSafeErrors()throws Exception{
        var c=chain();var project=projects.findById(c.project).orElseThrow();project.setTrusted(false);projects.save(project);assertEquals("PROJECT_NOT_TRUSTED",getPreview(c,403).path("code").asText());
        project=projects.findById(c.project).orElseThrow();project.setTrusted(true);projects.save(project);Files.writeString(c.catalog,"not JSON");var error=getPreview(c,400);assertEquals("VALIDATION_FAILED",error.path("code").asText());assertFalse(error.toString().contains(base.toString()));
    }
    @Test void storedAcceptanceDamageCannotBeDisplayedAsAnEmptySet()throws Exception{
        var c=chain();var old=tasks.findByPlanIdAndTaskId(c.plan,"A").orElseThrow();tasks.delete(old);tasks.flush();var now=Instant.now();tasks.save(new TaskEntity("corrupt_"+UUID.randomUUID(),c.plan,"A",1,"修复","[]","[]","交付","[3]",null,"DELEGATED",true,"DRAFT",now,now));
        assertEquals("VALIDATION_FAILED",getPreview(c,400).path("code").asText());
    }
    @Test void actualInternalOriginIsExcludedButSamePrefixHumanInputIsKept()throws Exception{
        var c=chain();var now=Instant.now();var internal=new MessageEntity("internal_"+UUID.randomUUID(),c.thread,c.turn,"user","[verification-fix:internal] 运行反馈",9007199254740994L,now);messages.save(internal);origins.markInternalFeedback(runs.findById(c.run).orElseThrow(),internal,c.project);
        var human=new MessageEntity("human_"+UUID.randomUUID(),c.thread,c.turn,"user","[verification-fix:human] 真正补充要求",9007199254740995L,now);messages.save(human);
        var requirements=getPreview(c,200).path("requirements");assertEquals(2,requirements.size());assertEquals(human.getContent(),requirements.get(1).path("content").asText());
    }
    @Test void newHumanRequirementsAndCatalogWhitespaceProduceFreshIdentities()throws Exception{
        var c=chain();var first=getPreview(c,200);messages.save(new MessageEntity("extra_"+UUID.randomUUID(),c.thread,c.turn,"user","额外要求",9007199254740994L,Instant.now()));var next=getPreview(c,200);
        assertNotEquals(first.path("requirementsDigest"),next.path("requirementsDigest"));assertNotEquals(first.path("previewDigest"),next.path("previewDigest"));assertEquals(first.path("taskDefinitionDigest"),next.path("taskDefinitionDigest"));
        Files.writeString(c.catalog,Files.readString(c.catalog)+"\n");var changed=getPreview(c,200);assertNotEquals(next.path("registryDigest"),changed.path("registryDigest"));assertNotEquals(next.path("previewDigest"),changed.path("previewDigest"));
    }
    @Test void loopbackGuardAndUnsupportedWritesDoNotCreateAConfirmation()throws Exception{
        var c=chain();assertEquals(403,mvc.perform(get(url(c)).header("Host","evil.example")).andReturn().getResponse().getStatus());
        assertEquals(405,mvc.perform(post(url(c)).header("Host","localhost").header("X-DSH-Client","local-ui").header("Idempotency-Key","read-only-test").contentType("application/json").content("{}" )).andReturn().getResponse().getStatus());
        assertEquals("DRAFT",tasks.findByPlanIdAndTaskId(c.plan,"A").orElseThrow().getState());
    }
}
