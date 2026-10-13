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

@SpringBootTest(properties={"dsh.policy.require-for-side-effects=EXECUTE","dsh.policy.approval-timeout-minutes=5"})
class RunProbeApprovalIntegrationTest {
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
    @Autowired com.dsh.agent.control.ApprovalService approvals;
    @Autowired ApprovalRepository approvalRows;
    @Autowired ArtifactRepository artifacts;
    @Autowired com.dsh.workspace.WorkspaceManager workspaces;
    private com.dsh.storage.ApprovalEntity pause(Chain c) {
        AtomicInteger count=new AtomicInteger();
        doAnswer(i->count.incrementAndGet()==1?response("run_probe","{\"fileName\":\"test_probe.sh\",\"content\":\"echo fact\\n\",\"command\":\"sh \\\"$DSH_PROBE_FILE\\\"\"}"):response(null,null)).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId());
        assertEquals("WAITING_APPROVAL",runs.findById(c.runId()).orElseThrow().getState());
        var a=approvalRows.findByRunIdAndStatus(c.runId(),"PENDING").getFirst();
        assertEquals("run_probe",a.getToolName());
        assertTrue(artifacts.findAll().stream().noneMatch(e->c.runId().equals(e.getRunId())&&e.getName().startsWith("probe-")));
        return a;
    }
    @Test void approvalDefersRegistrationAndChargesExistingToolBudget() throws Exception {
        Chain c=create();var a=pause(c);
        approvals.decide(a.getId(),com.dsh.api.dto.ApprovalDecision.Decision.APPROVE,a.getParametersDigest(),a.getWorkspaceDigest());
        loop.resumeFromApproval(c.turnId(),c.runId());
        var run=runs.findById(c.runId()).orElseThrow();assertEquals("COMPLETED",run.getState(),run.getStopReason());
        assertEquals(1,run.getBudgetToolCallsUsed());assertEquals(2,run.getBudgetModelRequestsUsed());
        assertEquals("CONSUMED",approvalRows.findById(a.getId()).orElseThrow().getStatus());
        assertEquals(1,artifacts.findAll().stream().filter(e->c.runId().equals(e.getRunId())&&e.getName().startsWith("probe-")).count());
        assertFalse(Files.exists(workspaces.resolveByRef(run.getWorkspaceRef()).dir().resolve("test_probe.sh")));
    }
    @Test void denialNeverRegistersOrExecutesProbe() throws Exception {
        Chain c=create();var a=pause(c);
        approvals.decide(a.getId(),com.dsh.api.dto.ApprovalDecision.Decision.DENY,a.getParametersDigest(),a.getWorkspaceDigest());
        loop.resumeFromApproval(c.turnId(),c.runId());
        assertTrue(artifacts.findAll().stream().noneMatch(e->c.runId().equals(e.getRunId())&&e.getName().startsWith("probe-")));
        assertNotEquals("PENDING",approvalRows.findById(a.getId()).orElseThrow().getStatus());
    }
}
