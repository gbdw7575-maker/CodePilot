package com.dsh.agent;
import com.dsh.agent.model.*;
import com.dsh.agent.tool.*;
import com.dsh.application.ArtifactStore;
import com.dsh.common.IdGen;
import com.dsh.storage.*;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"dsh.loop.history-compaction-mode=WINDOW_LASTUSER","dsh.loop.replay-protected-tail-units=2","dsh.budget.context-window-tokens=120000"})
class ToolOutputReplayIntegrationTest {
    @Autowired LoopSteps steps; @Autowired RunRepository runs; @Autowired TurnRepository turns;
    @Autowired ThreadRepository threads; @Autowired ProjectRepository projects; @Autowired ModelProfileRepository profiles;
    @Autowired MessageRepository messages; @Autowired ArtifactStore artifacts; @Autowired ArtifactRepository artifactRepository;
    @Autowired ReadArtifactTool read; @Autowired WorkspaceManager workspaces; @Autowired ObjectMapper mapper;
    @TempDir Path root;
    record Chain(String run,String turn,String thread,String project,List<String> artifacts){}
    Chain create(){
        Instant now=Instant.now();String p=IdGen.next("proj_"),profile=IdGen.next("prof_"),t=IdGen.next("thr_"),turn=IdGen.next("turn_"),run=IdGen.next("run_");
        ProjectEntity project=projects.save(new ProjectEntity(p,"C23",root.toString(),true,now,now));
        profiles.save(new ModelProfileEntity(profile,"fake-c23","fake","fake-c23","http://127.0.0.1:9",null,"disabled",null,64,false,"ACTIVE",now,now));
        threads.save(new ThreadEntity(t,p,"C23",profile,now,now));turns.save(new TurnEntity(turn,t,run,"目标原文：修复缺陷并保持接口", "QUEUED","PENDING",t,now,now));
        var lease=workspaces.prepare(run,project);runs.save(new RunEntity(run,turn,null,"MAIN","QUEUED","PENDING",1,lease.workspaceId(),lease.baselineDigest(),24,40,200000,now.plusSeconds(120),now,now));
        messages.save(new MessageEntity(IdGen.next("msg_"),t,turn,"user","目标原文：修复缺陷并保持接口",1L,now));var ids=new ArrayList<String>();
        for(int i=0;i<6;i++){
            String call="c"+i,text="BUILD_NOISE "+"x".repeat(8000)+"\nERROR unique diagnostic "+i;
            var artifact=artifacts.save(run,p,"tool-output-"+call+".txt","text/plain",text.getBytes(StandardCharsets.UTF_8),true);ids.add(artifact.getId());
            var batch=mapper.createObjectNode();batch.putArray("calls").addObject().put("callId",call).put("name","run_command").put("arguments","{\"command\":\"unchanged\"}");batch.put("reasoningContent","LIVE_REASONING_"+i);
            messages.save(new MessageEntity(IdGen.next("msg_"),t,turn,"assistant_tool",batch.toString(),i*2+2L,now));
            var raw=mapper.createObjectNode().put("callId",call).put("name","run_command").put("status","FAILED").put("isError",true).put("exitCode",1).put("errorCode","COMMAND_FAILED").put("summary","构建失败").put("fullOutputArtifactId",artifact.getId()).put("fullOutputBytes",text.length()).put("captureTruncated",true).put("outputDroppedBytes",42);
            raw.putArray("content").addObject().put("type","text").put("text",text);raw.withArray("content").addObject().put("type","artifact").put("artifactId","art_link_"+i).put("mimeType","image/png").put("description","原始附件");raw.putObject("structuredContent").put("workspaceDigest","digest-"+i).put("termination","COMPLETED").put("testsDiscovered",17).put("customObservation","inline-value-"+i);
            messages.save(new MessageEntity(IdGen.next("msg_"),t,turn,"tool",raw.toString(),i*2+3L,now));
        }
        steps.begin(run);return new Chain(run,turn,t,p,ids);
    }
    ModelRequest assemble(Chain c){return steps.assemble(c.run(),steps.snapshot(c.run()));}
    ModelMessage tool(ModelRequest r,int i){return r.messages().stream().filter(m->("c"+i).equals(m.toolCallId())).findFirst().orElseThrow();}
    @Test void foldsBeforeWindowOrWatermarkAndCanRecoverWithoutTranscriptMutation() throws Exception {
        Chain c=create();var before=messages.listByThread(c.thread()).stream().map(MessageEntity::getContent).toList();long count=artifactRepository.count();var prepared=steps.prepare(c.run(),steps.snapshot(c.run()));var a=prepared.request();var b=assemble(c);
        var metric=prepared.contextMetrics();assertNotNull(metric);assertEquals(4,metric.replayFoldedResults());
        assertEquals(2,metric.replayProtectedTailUnits());assertTrue(metric.historyRawTokensAfterReplay()<metric.historyRawTokensBeforeReplay());
        assertEquals(a.estimatedInputTokens(),metric.estimatedInputTokens());
        assertEquals(metric.historyRawTokensFinal(),metric.toolCallsRawTokensFinal()+metric.toolResultsRawTokensFinal()+metric.otherHistoryRawTokensFinal());
        assertFalse(metric.windowPressure());assertFalse(metric.watermarkPressure());assertFalse(metric.cumulativePressure());
        assertEquals(a.messages(),b.messages());assertEquals(count,artifactRepository.count());assertEquals(before,messages.listByThread(c.thread()).stream().map(MessageEntity::getContent).toList());
        for(int i=0;i<4;i++){var m=tool(a,i);assertTrue(m.content().contains("工具输出已压缩"));assertTrue(m.content().contains("ERROR unique diagnostic "+i));assertTrue(m.content().contains("exitCode: 1"));assertTrue(m.content().contains("COMMAND_FAILED"));assertTrue(m.content().contains("digest-"+i));assertTrue(m.content().contains("inline-value-"+i));assertTrue(m.content().contains("\"testsDiscovered\":17"));assertTrue(m.content().contains("art_link_"+i));assertTrue(m.content().contains("42"));assertTrue(m.content().contains("缺失部分无法回读"));}
        for(int i=4;i<6;i++)assertTrue(tool(a,i).content().contains("x".repeat(7000)));
        assertTrue(a.messages().stream().anyMatch(m->"user".equals(m.role())&&m.content().equals("目标原文：修复缺陷并保持接口")));
        assertEquals(6,a.messages().stream().filter(m->m.toolCalls()!=null&&!m.toolCalls().isEmpty()).count());assertEquals(6,a.messages().stream().filter(m->"tool".equals(m.role())).count());assertEquals("LIVE_REASONING_0",a.messages().get(2).reasoningContent());
        StringBuilder recovered=new StringBuilder();long offset=0;do{var outcome=read.execute(mapper.createObjectNode().put("artifactId",c.artifacts().getFirst()).put("byteOffset",offset),new ToolExecutionContext(c.run(),c.thread(),c.turn(),"recover",c.project(),null,Instant.now().plusSeconds(30),null,null,mapper));assertEquals("SUCCEEDED",outcome.status());recovered.append(outcome.text());offset=outcome.structuredContent().path("nextOffset").asLong();if(!outcome.structuredContent().path("hasMore").asBoolean())break;}while(true);
        assertTrue(recovered.toString().contains("ERROR unique diagnostic 0"));assertEquals(0,runs.findById(c.run()).orElseThrow().getBudgetModelRequestsUsed());
        assertEquals(2,steps.executionSettings().get("loop.replayProtectedTailUnits"));
        ModelRequest baseline;
        org.springframework.test.util.ReflectionTestUtils.setField(steps,"replayProtectedTailUnits",0);
        try {var off=steps.prepare(c.run(),steps.snapshot(c.run()));baseline=off.request();assertEquals(0,off.contextMetrics().replayFoldedResults());assertEquals(off.contextMetrics().historyRawTokensBeforeReplay(),off.contextMetrics().historyRawTokensAfterReplay());} finally {org.springframework.test.util.ReflectionTestUtils.setField(steps,"replayProtectedTailUnits",2);}
        assertTrue(tool(baseline,0).content().contains("x".repeat(7000)));
        assertTrue(a.estimatedInputTokens()<baseline.estimatedInputTokens());
        assertEquals(baseline.messages().getFirst(),a.messages().getFirst());
        System.out.println("C23_INPUT_ESTIMATE baseline="+baseline.estimatedInputTokens()+" folded="+a.estimatedInputTokens()+" replay10_saved="+(baseline.estimatedInputTokens()-a.estimatedInputTokens())*10);
    }
    @ParameterizedTest @ValueSource(strings={"missing","tampered","cross-run","cross-project","private","binary","too-large","symlink","no-reference","missing-structured","cross-run-structured"}) void unusableEvidenceKeepsFullObservation(String fault) throws Exception {
        Chain c=create();String id=c.artifacts().getFirst();var a=artifacts.get(id);Path file=workspaces.require(c.run()).metaDir().resolve("artifacts").resolve(id);
        if(fault.endsWith("structured")){var linked=artifacts.save(c.run(),c.project(),"structured.json","application/json","{\"observed\":true}".getBytes(StandardCharsets.UTF_8),true);var m=messages.listByThread(c.thread()).stream().filter(x->x.getSequence()==3).findFirst().orElseThrow();var raw=(ObjectNode)mapper.readTree(m.getContent());raw.put("structuredArtifactId",linked.getId());messages.save(new MessageEntity(m.getId(),m.getThreadId(),m.getTurnId(),m.getRole(),raw.toString(),m.getSequence(),m.getCreatedAt()));if(fault.equals("missing-structured"))Files.delete(file.getParent().resolve(linked.getId()));else artifactRepository.save(new ArtifactEntity(linked.getId(),"other",linked.getProjectId(),linked.getName(),linked.getMediaType(),linked.getSizeBytes(),linked.getSha256(),linked.getCreatedAt(),true));}
        switch(fault){case "missing-structured","cross-run-structured"->{}case "missing"->Files.delete(file);case "tampered"->{byte[] bytes=Files.readAllBytes(file);bytes[0]='!';Files.write(file,bytes);}case "symlink"->{Path other=root.resolve("other.log");Files.copy(file,other);Files.delete(file);Files.createSymbolicLink(file,other);}case "no-reference"->{var m=messages.listByThread(c.thread()).stream().filter(x->x.getSequence()==3).findFirst().orElseThrow();var raw=(ObjectNode)mapper.readTree(m.getContent());raw.remove("fullOutputArtifactId");messages.save(new MessageEntity(m.getId(),m.getThreadId(),m.getTurnId(),m.getRole(),raw.toString(),m.getSequence(),m.getCreatedAt()));}default->artifactRepository.save(new ArtifactEntity(id,fault.equals("cross-run")?"other":a.getRunId(),fault.equals("cross-project")?"other":a.getProjectId(),a.getName(),fault.equals("binary")?"application/octet-stream":a.getMediaType(),fault.equals("too-large")?3*1024*1024:a.getSizeBytes(),a.getSha256(),a.getCreatedAt(),!fault.equals("private")));}
        var request=assemble(c);assertTrue(tool(request,0).content().contains("x".repeat(7000)));assertFalse(tool(request,0).content().contains("工具输出已压缩"));assertTrue(tool(request,1).content().contains("工具输出已压缩"));
    }
}
