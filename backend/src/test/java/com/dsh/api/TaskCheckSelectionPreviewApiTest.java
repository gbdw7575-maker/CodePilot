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
class TaskCheckSelectionPreviewApiTest {
    static final Path base=createRoot();
    static Path createRoot(){try{Path root=Files.createTempDirectory("m1c2c-api-");Files.createDirectory(root.resolve("control"));Files.createDirectory(root.resolve("workspaces"));Files.createDirectory(root.resolve("projects"));return root;}catch(Exception e){throw new IllegalStateException(e);}}
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
        var second=catalog.path("checks").get(0).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)second).put("id","second-semantic");((com.fasterxml.jackson.databind.node.ArrayNode)catalog.path("checks")).add(second);
        Path file=privateDir.resolve("task-checks.json");Files.write(file,mapper.writeValueAsBytes(catalog));Files.writeString(privateDir.resolve("check_slugify.py"),"assert 2 + 2 == 4\n");
        return new Chain(project,thread,turn,run,plan,file);
    }
    private String url(Chain c){return "/api/v1/runs/"+c.run+"/plans/"+c.plan+"/tasks/A/acceptance-preview";}
    private String selectedUrl(Chain c){return url(c).replace("acceptance-preview","acceptance-selection-preview");}
    private String tag(Chain c)throws Exception{var result=mvc.perform(get(url(c)).header("Host","localhost")).andReturn();assertEquals(200,result.getResponse().getStatus());return result.getResponse().getHeader("ETag");}
    private org.springframework.test.web.servlet.MvcResult select(Chain c,String tag,String... refs)throws Exception{var request=get(selectedUrl(c)).header("Host","localhost");if(tag!=null)request.header("If-Match",tag);if(refs.length>0)request.queryParam("checkRef",refs);return mvc.perform(request).andReturn();}
    private JsonNode body(org.springframework.test.web.servlet.MvcResult result)throws Exception{return mapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
    @Test void actualSelectionHasBoundDefinitionsNoStoreAndNoBusinessWrites()throws Exception{
        var c=chain();String tag=tag(c);long taskCount=tasks.count(),planCount=plans.count(),attemptCount=attempts.count(),artifactCount=artifacts.count(),runCount=runs.count(),messageCount=messages.count();
        var result=select(c,tag,"slugify-semantic","second-semantic");assertEquals(200,result.getResponse().getStatus());var selected=body(result);
        assertEquals("SELECTED_PREVIEW_ONLY",selected.path("status").asText());assertEquals("second-semantic",selected.path("checkRefs").get(0).asText());assertEquals(2,selected.path("selectedChecks").size());
        assertEquals("  用户原文\n约束  ",selected.path("taskPreview").path("requirements").get(0).path("content").asText());assertEquals("9007199254740993",selected.path("taskPreview").path("requirements").get(0).path("sequence").asText());
        assertEquals("no-store",result.getResponse().getHeader("Cache-Control"));assertEquals("\"task-check-selection-v1-"+selected.path("selectionDigest").asText()+"\"",result.getResponse().getHeader("ETag"));
        assertFalse(selected.has("registrationId"));assertEquals("DRAFT",tasks.findByPlanIdAndTaskId(c.plan,"A").orElseThrow().getState());
        assertEquals(taskCount,tasks.count());assertEquals(planCount,plans.count());assertEquals(attemptCount,attempts.count());assertEquals(artifactCount,artifacts.count());assertEquals(runCount,runs.count());assertEquals(messageCount,messages.count());
        assertEquals(selected.path("selectionDigest"),body(select(c,tag,"second-semantic","slugify-semantic")).path("selectionDigest"));
        assertNotEquals(selected.path("selectionDigest"),body(select(c,tag,"slugify-semantic")).path("selectionDigest"));
    }
    @Test void exactHeaderAndRawRepeatedQueryValuesAreRequired()throws Exception{
        var c=chain();String tag=tag(c);assertEquals(428,select(c,null,"slugify-semantic").getResponse().getStatus());assertEquals(400,select(c,"*","slugify-semantic").getResponse().getStatus());
        for(String[] refs:new String[][]{{},{"unknown"},{"slugify-semantic","slugify-semantic"},{"slugify-semantic,second-semantic"},{" slugify-semantic"},{"../check.py"},{"echo passed"}})assertEquals(400,select(c,tag,refs).getResponse().getStatus());
        assertEquals(400,mvc.perform(get(selectedUrl(c)).header("Host","localhost").header("If-Match",tag).queryParam("checkRef","slugify-semantic").queryParam("command","echo passed")).andReturn().getResponse().getStatus());
        assertEquals(412,select(c,"\"task-check-preview-v1-"+"0".repeat(64)+"\"","slugify-semantic").getResponse().getStatus());
    }
    @Test void appendedHumanInputAndSameVersionTaskMutationInvalidateSelection()throws Exception{
        var c=chain();String initial=tag(c);messages.save(new MessageEntity("extra_"+UUID.randomUUID(),c.thread,c.turn,"user","新增约束",9007199254740994L,Instant.now()));assertEquals(412,select(c,initial,"slugify-semantic").getResponse().getStatus());
        String newer=tag(c);var task=tasks.findByPlanIdAndTaskId(c.plan,"A").orElseThrow();tasks.delete(task);tasks.flush();var now=Instant.now();tasks.save(new TaskEntity("recreated_"+UUID.randomUUID(),c.plan,"A",1,"同版本新目标","[]","[]","交付","[\"正确行为\"]",null,"DELEGATED",true,"DRAFT",now,now));
        assertEquals(412,select(c,newer,"slugify-semantic").getResponse().getStatus());assertEquals(200,select(c,tag(c),"slugify-semantic").getResponse().getStatus());
    }
    @Test void wholeCatalogDriftRequiresNewFullPreviewEvenForUnchangedSelectedCheck()throws Exception{
        var c=chain();String initial=tag(c);Files.writeString(c.catalog,Files.readString(c.catalog)+"\n");assertEquals(412,select(c,initial,"slugify-semantic").getResponse().getStatus());assertEquals(200,select(c,tag(c),"slugify-semantic").getResponse().getStatus());
    }
    @Test void projectTrustRootMembershipAndLoopbackStillApply()throws Exception{
        var c=chain();String initial=tag(c);var other=chain();assertEquals(404,mvc.perform(get(selectedUrl(c).replace(c.plan,other.plan)).header("Host","localhost").header("If-Match",initial).queryParam("checkRef","slugify-semantic")).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get(selectedUrl(c)).header("Host","evil.example").header("If-Match",initial).queryParam("checkRef","slugify-semantic")).andReturn().getResponse().getStatus());
        var project=projects.findById(c.project).orElseThrow();project.setTrusted(false);projects.save(project);var result=select(c,initial,"slugify-semantic");assertEquals(403,result.getResponse().getStatus());assertFalse(body(result).toString().contains(base.toString()));
    }
    @Test void writeRequestCannotConfirmOrDispatchASelection()throws Exception{
        var c=chain();assertEquals(405,mvc.perform(post(selectedUrl(c)).header("Host","localhost").header("X-DSH-Client","local-ui").header("Idempotency-Key","selection-only").contentType("application/json").content("{}" )).andReturn().getResponse().getStatus());assertEquals("DRAFT",tasks.findByPlanIdAndTaskId(c.plan,"A").orElseThrow().getState());
    }
}
