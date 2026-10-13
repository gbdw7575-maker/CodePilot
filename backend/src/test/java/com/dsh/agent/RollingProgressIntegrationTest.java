package com.dsh.agent;

import com.dsh.agent.model.*;
import com.dsh.api.dto.Usage;
import com.dsh.common.IdGen;
import com.dsh.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties="dsh.policy.require-for-side-effects=NONE")
class RollingProgressIntegrationTest {
    @Autowired AgentLoop loop;
    @Autowired LoopSteps steps;
    @Autowired RunRepository runs;
    @Autowired TurnRepository turns;
    @Autowired ThreadRepository threads;
    @Autowired ProjectRepository projects;
    @Autowired ModelProfileRepository profiles;
    @Autowired MessageRepository messages;
    @MockitoSpyBean FakeModelGateway gateway;
    @TempDir Path root;
    record Chain(String runId,String turnId,String threadId) {}
    private Chain create() throws Exception {
        Files.writeString(root.resolve("a.txt"),"known input\n");
        Files.createDirectories(root.resolve(".dsh"));
        Files.writeString(root.resolve(".dsh/verify.yaml"),"command: echo '1 passed in 0.01s'\nexpectTests: true\n");
        Instant now=Instant.now();String p=IdGen.next("proj_"),profile=IdGen.next("prof_");
        projects.save(new ProjectEntity(p,"P04",root.toString(),true,now,now));
        profiles.save(new ModelProfileEntity(profile,"fake-p04","fake","fake-p04","http://127.0.0.1:9",null,"disabled",null,4096,false,"ACTIVE",now,now));
        String t=IdGen.next("thr_"),turn=IdGen.next("turn_"),run=IdGen.next("run_");
        threads.save(new ThreadEntity(t,p,"P04",profile,now,now));
        turns.save(new TurnEntity(turn,t,run,"说明并验证", "QUEUED","PENDING",t,now,now));
        runs.save(new RunEntity(run,turn,null,"MAIN","QUEUED","PENDING",1,IdGen.next("ws_"),"p04",24,40,200000,now.plusSeconds(120),now,now));
        messages.save(new MessageEntity(IdGen.next("msg_"),t,turn,"user","说明并验证",1L,now));
        return new Chain(run,turn,t);
    }
    private ModelResponse response(String tool,String args) {
        return new ModelResponse(tool==null?"完成":"",tool==null?List.of():List.of(new ModelToolCall(IdGen.next("call_"),tool,args)),
                tool==null?"stop":"tool_calls",new Usage("KNOWN",10,2,null,null,null,null,null),null);
    }
    @Test void temporaryWriteCannotBlindTheLoopDuringRepeatedReads() throws Exception {
        Chain c=create();AtomicInteger calls=new AtomicInteger();AtomicReference<ModelRequest> last=new AtomicReference<>();
        doAnswer(i->{int n=calls.incrementAndGet();last.set(i.getArgument(0));
            if(n==1)return response("write_file","{\"path\":\"probe.txt\",\"content\":\"temporary\"}");
            if(n<=10)return response("read_file","{\"path\":\"a.txt\"}");
            assertTrue(last.get().messages().getFirst().content().contains("近期未观察到新的修改候选"));
            assertEquals(10,last.get().messages().stream().filter(m->"tool".equals(m.role())).count());
            return response(null,null);
        }).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId());
        assertEquals(11,calls.get());assertEquals("COMPLETED",runs.findById(c.runId()).orElseThrow().getState());
        assertEquals(11,runs.findById(c.runId()).orElseThrow().getBudgetModelRequestsUsed());
        assertEquals(132,runs.findById(c.runId()).orElseThrow().getBudgetSettledTokens());
    }
    @Test void repeatedAssemblyRebuildsHintFromCommittedHistoryWithoutAddingMessages() throws Exception {
        Chain c=create();steps.begin(c.runId());
        for(int i=0;i<9;i++) {
            messages.save(new MessageEntity(IdGen.next("msg_"),c.threadId(),c.turnId(),"assistant_tool",
                    "{\"calls\":[{\"callId\":\"echo"+i+"\",\"name\":\"echo\",\"arguments\":\"{}\"}]}",i*2+2L,Instant.now()));
            messages.save(new MessageEntity(IdGen.next("msg_"),c.threadId(),c.turnId(),"tool",
                    "{\"callId\":\"echo"+i+"\",\"name\":\"echo\",\"status\":\"SUCCEEDED\",\"content\":[],\"structuredContent\":{}}",i*2+3L,Instant.now()));
        }
        ModelRequest a=steps.assemble(c.runId(),steps.snapshot(c.runId()));
        ModelRequest b=steps.assemble(c.runId(),steps.snapshot(c.runId()));
        assertTrue(a.messages().getFirst().content().contains("最近 8 次工具活动"));
        assertEquals(a.messages(),b.messages());assertEquals(a.prefixHash(),b.prefixHash());
        assertEquals(19,messages.listByThread(c.threadId()).size());
        assertEquals(9,a.messages().stream().filter(m->"tool".equals(m.role())).count());
        assertEquals(9,a.messages().stream().filter(m->m.toolCalls()!=null && !m.toolCalls().isEmpty()).count());
        assertTrue(a.estimatedInputTokens()>0);
    }
}
