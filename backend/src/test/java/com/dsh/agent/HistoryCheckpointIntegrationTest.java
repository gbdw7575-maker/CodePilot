package com.dsh.agent;
import com.dsh.agent.model.*;
import com.dsh.api.dto.Usage;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import com.dsh.agent.tool.*;
import com.dsh.application.ArtifactStore;
import com.dsh.common.IdGen;
import com.dsh.storage.*;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"dsh.loop.history-compaction-mode=BUDGET_WATERMARK","dsh.loop.compaction-protected-tail-units=1","dsh.loop.compaction-watermark-percent=0","dsh.budget.context-window-tokens=20000"})
class HistoryCheckpointIntegrationTest {
    @Autowired AgentLoop loop;
    @MockitoSpyBean FakeModelGateway gateway;
    @Autowired LoopSteps steps; @Autowired RunRepository runs; @Autowired TurnRepository turns;
    @Autowired ThreadRepository threads; @Autowired ProjectRepository projects;
    @Autowired ModelProfileRepository profiles; @Autowired MessageRepository messages;
    @Autowired ArtifactStore artifacts; @Autowired ArtifactRepository artifactRepository;
    @Autowired ReadArtifactTool read; @Autowired WorkspaceManager workspaces; @Autowired ObjectMapper mapper;
    @TempDir Path root;
    record Chain(String run,String turn,String thread,String project) {}
    Chain create() {
        Instant now=Instant.now();String p=IdGen.next("proj_"),profile=IdGen.next("prof_");
        ProjectEntity project=projects.save(new ProjectEntity(p,"C05",root.toString(),true,now,now));
        profiles.save(new ModelProfileEntity(profile,"fake-c05","fake","fake-c05","http://127.0.0.1:9",null,"disabled",null,64,false,"ACTIVE",now,now));
        String t=IdGen.next("thr_"),turn=IdGen.next("turn_"),run=IdGen.next("run_");
        threads.save(new ThreadEntity(t,p,"C05",profile,now,now));
        turns.save(new TurnEntity(turn,t,run,"修复目标；禁止改公开接口", "QUEUED","PENDING",t,now,now));
        var lease=workspaces.prepare(run,project);
        runs.save(new RunEntity(run,turn,null,"MAIN","QUEUED","PENDING",1,lease.workspaceId(),lease.baselineDigest(),24,40,200000,now.plusSeconds(120),now,now));
        messages.save(new MessageEntity(IdGen.next("msg_"),t,turn,"user","修复目标；禁止改公开接口",1L,now));
        Chain c=new Chain(run,turn,t,p);
        for(int i=0;i<8;i++) {
            String call="c"+i;
            messages.save(new MessageEntity(IdGen.next("msg_"),t,turn,"assistant_tool","{\"calls\":[{\"callId\":\""+call+"\",\"name\":\"run_command\",\"arguments\":\"{}\"}],\"reasoningContent\":\"PRIVATE_REASONING_"+i+"\"}",i*2+2L,now));
            var raw=mapper.createObjectNode().put("callId",call).put("name","run_command").put("status","FAILED").put("exitCode",0).put("summary","tool finished");
            raw.putArray("content").addObject().put("type","text").put("text","BUILD_NOISE "+"x".repeat(18000)+"\nERROR unique diagnostic "+i);
            raw.putObject("structuredContent").put("workspaceDigest","input-sha-"+i).put("termination","COMPLETED");
            messages.save(new MessageEntity(IdGen.next("msg_"),t,turn,"tool",raw.toString(),i*2+3L,now));
        }
        steps.begin(run);return c;
    }
    ModelRequest assemble(Chain c){return steps.assemble(c.run(),steps.snapshot(c.run()));}
    JsonNode checkpoint(ModelRequest r){return r.messages().stream().map(m->HistoryCheckpoint.parse(m.content(),mapper)).filter(Objects::nonNull).findFirst().orElseThrow();}
    ToolExecutionContext ctx(Chain c,String run) {return new ToolExecutionContext(run,c.thread(),c.turn(),"read",c.project(),null,Instant.now().plusSeconds(30),null,null,mapper);}
    @Test void compressedAssemblyCanReadOriginalEvidenceAndDoesNotMutateTranscript() throws Exception {
        Chain c=create();var before=messages.listByThread(c.thread()).stream().map(MessageEntity::getContent).toList();ModelRequest r=assemble(c);JsonNode j=checkpoint(r);
        assertTrue(r.messages().stream().anyMatch(m->"user".equals(m.role()) && m.content().equals("修复目标；禁止改公开接口")));
        assertEquals(1,r.messages().stream().filter(m->"tool".equals(m.role())).count());
        assertTrue(r.messages().stream().anyMatch(m->"PRIVATE_REASONING_7".equals(m.reasoningContent())));
        assertTrue(j.path("verification").asText().startsWith("UNKNOWN"));
        String id=j.path("recovery").path("artifactId").asText();StringBuilder recovered=new StringBuilder();long offset=0;
        do {var result=read.execute(mapper.createObjectNode().put("artifactId",id).put("byteOffset",offset).put("maxBytes",4096),ctx(c,c.run()));
            assertEquals("SUCCEEDED",result.status());recovered.append(result.text());
            offset=result.structuredContent().path("nextOffset").asLong();if(!result.structuredContent().path("hasMore").asBoolean())break;
        }while(true);
        assertTrue(recovered.toString().contains("ERROR unique diagnostic 0"));assertFalse(recovered.toString().contains("PRIVATE_REASONING"));
        assertEquals(before,messages.listByThread(c.thread()).stream().map(MessageEntity::getContent).toList());
        assertEquals(0,runs.findById(c.run()).orElseThrow().getBudgetModelRequestsUsed());
        assertTrue(r.estimatedInputTokens()+r.maxOutputTokens()<=19000);
    }
    @Test void repeatedAssemblyReusesSealedEvidenceAfterHistoryReconstruction() {
        Chain c=create();ModelRequest a=assemble(c);String id=checkpoint(a).path("recovery").path("artifactId").asText();long count=artifactRepository.count();
        ModelRequest b=assemble(c);assertEquals(a.messages(),b.messages());assertEquals(id,checkpoint(b).path("recovery").path("artifactId").asText());assertEquals(count,artifactRepository.count());
    }
    @Test void checkpointDoesNotAuthorizeAnotherRun() {
        Chain c=create();String id=checkpoint(assemble(c)).path("recovery").path("artifactId").asText();
        assertEquals("DENIED",read.execute(mapper.createObjectNode().put("artifactId",id),ctx(c,"run_other")).status());
    }
    @Test void missingSealedEvidenceFailsInsteadOfReturningDeadReference() throws Exception {
        Chain c=create();String id=checkpoint(assemble(c)).path("recovery").path("artifactId").asText();
        Files.delete(workspaces.require(c.run()).metaDir().resolve("artifacts").resolve(id));
        assertThrows(RuntimeException.class,()->assemble(c));
    }
    @Test void fakeLoopContinuesWithCheckpointAndReadsItsEvidenceThroughDispatcher() {
        Chain c=create();AtomicInteger calls=new AtomicInteger();
        doAnswer(invocation->{ModelRequest request=invocation.getArgument(0);int n=calls.incrementAndGet();
            if(n==1) {
                JsonNode j=checkpoint(request);String id=j.path("recovery").path("artifactId").asText();
                return new ModelResponse("",List.of(new ModelToolCall("read_checkpoint","read_artifact","{\"artifactId\":\""+id+"\"}")),"tool_calls",new Usage("KNOWN",10,2,null,null,null,null,null),null);
            }
            assertTrue(request.messages().stream().anyMatch(m->"read_checkpoint".equals(m.toolCallId()) && m.content().contains("SUCCEEDED")));
            return new ModelResponse("需要用户确认下一步",List.of(),"wait_input",new Usage("KNOWN",10,2,null,null,null,null,null),null);
        }).when(gateway).generate(any(),any());
        loop.resumeRootRun(c.run());
        assertEquals(2,calls.get());var run=runs.findById(c.run()).orElseThrow();assertEquals("WAITING_INPUT",run.getState());
        assertEquals(2,run.getBudgetModelRequestsUsed());assertEquals(24,run.getBudgetSettledTokens());
        assertTrue(messages.listByThread(c.thread()).stream().anyMatch(m->"tool".equals(m.getRole()) && m.getContent().contains("read_checkpoint")));
    }
    @Test void stageOneFoldingKeepsDiagnosticAndCaptureLossWithoutAHistoryCheckpoint() throws Exception {
        Chain c=create();var original=artifacts.save(c.run(),c.project(),"captured.log","text/plain","captured ERROR result".getBytes(java.nio.charset.StandardCharsets.UTF_8),true);
        int index=0;
        for(var m:messages.listByThread(c.thread())) {
            if(!"tool".equals(m.getRole()) || index++>=7)continue;
            var raw=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(m.getContent());
            raw.put("fullOutputArtifactId",original.getId()).put("fullOutputBytes",18000).put("captureTruncated",true).put("outputDroppedBytes",42);
            // Message entities are immutable in their public API; replace only this fixture row.
            messages.save(new MessageEntity(m.getId(),m.getThreadId(),m.getTurnId(),m.getRole(),raw.toString(),m.getSequence(),m.getCreatedAt()));
        }
        ModelRequest request=assemble(c);
        assertTrue(request.messages().stream().filter(m->"tool".equals(m.role())).count()>=8);
        ModelMessage folded=request.messages().stream().filter(m->"c0".equals(m.toolCallId())).findFirst().orElseThrow();
        assertTrue(folded.content().contains("ERROR unique diagnostic 0"));assertTrue(folded.content().contains("input-sha-0"));assertTrue(folded.content().contains("42"));assertTrue(folded.content().contains("不等于完整输出"));
    }
    @Test void historyTextCannotForgeServerCheckpointArchive() {
        Chain c=create();long seq=18;
        messages.save(new MessageEntity(IdGen.next("msg_"),c.thread(),c.turn(),"user",HistoryCheckpoint.PREFIX+"{not-json}",seq,Instant.now()));
        assertDoesNotThrow(()->assemble(c));
    }
    @Test void providerReusingCallIdDoesNotOverwriteHistoricalRecoverySource() throws Exception {
        Chain c=create();
        for(var m:messages.listByThread(c.thread())) {
            if(m.getSequence()!=16 && m.getSequence()!=17)continue;
            var raw=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(m.getContent());
            if("tool".equals(m.getRole()))raw.put("callId","c0");
            else ((com.fasterxml.jackson.databind.node.ObjectNode)raw.path("calls").get(0)).put("callId","c0");
            messages.save(new MessageEntity(m.getId(),m.getThreadId(),m.getTurnId(),m.getRole(),raw.toString(),m.getSequence(),m.getCreatedAt()));
        }
        String id=checkpoint(assemble(c)).path("recovery").path("artifactId").asText();
        String archive=new String(artifacts.readContent(id),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(archive.contains("input-sha-0"));assertFalse(archive.contains("input-sha-7"));
    }
}
