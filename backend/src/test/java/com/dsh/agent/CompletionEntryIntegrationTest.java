package com.dsh.agent;

import com.dsh.agent.model.*;
import com.dsh.agent.tool.ToolDispatcher;
import com.dsh.api.dto.Usage;
import com.dsh.application.RunRecoveryService;
import com.dsh.application.VerificationService;
import com.dsh.application.ArtifactStore;
import com.dsh.common.IdGen;
import com.dsh.storage.*;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties={"dsh.loop.completion-review-enabled=true","dsh.policy.require-for-side-effects=NONE",
        "dsh.policy.approval-timeout-minutes=5"})
class CompletionEntryIntegrationTest {
    static final String COMMAND="printf x >> checks.txt; echo '1 passed in 0.01s'";
    @Autowired AgentLoop loop;
    @Autowired LoopSteps steps;
    @Autowired CompletionReviewService review;
    @Autowired VerificationService verification;
    @Autowired RunRecoveryService recovery;
    @Autowired RunRepository runs;
    @Autowired TurnRepository turns;
    @Autowired ThreadRepository threads;
    @Autowired ProjectRepository projects;
    @Autowired ModelProfileRepository profiles;
    @Autowired MessageRepository messages;
    @Autowired VerificationRepository verifications;
    @Autowired ArtifactRepository artifacts;
    @Autowired ArtifactStore store;
    @Autowired WorkspaceManager workspaces;
    @MockitoSpyBean ToolDispatcher dispatcher;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean FakeModelGateway gateway;
    @TempDir Path root;
    record Chain(String runId,String turnId,String threadId,String inputId,String projectId) { }
    Chain create() throws Exception { return create(24); }
    Chain create(int maxRequests) throws Exception {
        Files.writeString(root.resolve("a.txt"),"baseline\n");
        Files.createDirectories(root.resolve(".dsh"));
        Files.writeString(root.resolve(".dsh/verify.yaml"),"command: \""+COMMAND+"\"\nexpectTests: true\n");
        Instant now=Instant.now(); String project=IdGen.next("proj_"),profile=IdGen.next("prof_");
        projects.save(new ProjectEntity(project,"C11",root.toString(),true,now,now));
        profiles.save(new ModelProfileEntity(profile,"fake-c11","fake","fake-c11","http://127.0.0.1:9",null,
                "disabled",null,4096,false,"ACTIVE",now,now));
        String thread=IdGen.next("thr_"),turn=IdGen.next("turn_"),run=IdGen.next("run_"),input=IdGen.next("msg_");
        threads.save(new ThreadEntity(thread,project,"C11",profile,now,now));
        turns.save(new TurnEntity(turn,thread,run,"修改a并验证", "QUEUED","PENDING",thread,now,now));
        runs.save(new RunEntity(run,turn,null,"MAIN","QUEUED","PENDING",1,IdGen.next("ws_"),"c11",maxRequests,40,
                200000,now.plusSeconds(180),now,now));
        messages.save(new MessageEntity(input,thread,turn,"user","修改a并验证",1L,now));
        return new Chain(run,turn,thread,input,project);
    }
    Path workspace(Chain c) { return workspaces.resolveByRef(runs.findById(c.runId()).orElseThrow().getWorkspaceRef()).dir(); }
    ModelResponse tool(String name,String args) {
        return new ModelResponse("",List.of(new ModelToolCall(IdGen.next("call_"),name,args)),"tool_calls",
                new Usage("KNOWN",10,2,null,null,null,null,null),null);
    }
    ModelResponse text(String text) { return new ModelResponse(text,List.of(),"stop",new Usage("KNOWN",10,2,null,null,null,null,null),null); }
    ModelResponse write(Chain c,String value) throws Exception {
        Path file=workspace(c).resolve("a.txt");
        byte[] old=Files.readAllBytes(Files.exists(file)?file:root.resolve("a.txt"));
        return tool("write_file",mapper.createObjectNode().put("path","a.txt").put("content",value)
                .put("expectedFileHash",com.dsh.common.Digest.sha256Hex(old)).toString());
    }
    ModelResponse command(String command) throws Exception {
        return tool("run_command",mapper.createObjectNode().put("command",command).toString());
    }
    com.fasterxml.jackson.databind.node.ObjectNode report(Chain c, boolean ready) {
        var state=review.state(c.runId());
        var report=mapper.createObjectNode().put("reportVersion",2).put("decision",ready?"READY":"REWORK").put("verificationId",state.verificationId())
                .put("summary",ready?"修改完成，验证通过；覆盖说明是模型复核报告":"需要修复一个未解决约束");
        var coverage=report.putArray("coverage"); var unresolved=report.putArray("unresolved");
        for (String id:state.inputIds()) {
            coverage.addObject().put("messageId",id).put("status",ready?"COVERED":"UNRESOLVED")
                    .put("explanation",ready?"已检查输入中的修改与验证要求":"文件内容仍需更正")
                    .put("evidence","a.txt候选修改及绑定验证；测试范围有限")
                    .set("logArtifactIds",mapper.valueToTree(ready ?
                            com.dsh.application.VerificationEvidenceContext.logArtifactIds(
                                    verifications.findById(state.verificationId()).orElseThrow()) : List.of()));
            if (!ready) unresolved.addObject().put("messageId",id).put("problem","内容不满足补充约束")
                    .put("nextAction","修正a.txt并重新执行验收命令");
        }
        return report;
    }
    void assertCompleted(Chain c,int requests) throws Exception {
        var run=runs.findById(c.runId()).orElseThrow();
        assertEquals("COMPLETED",run.getState()); assertEquals("VERIFICATION_PASSED",run.getCompletionBasis());
        assertEquals(requests,run.getBudgetModelRequestsUsed()); assertEquals(requests*12,run.getBudgetSettledTokens());
        assertTrue(review.satisfied(c.runId()));
    }
    @Autowired com.dsh.agent.control.ApprovalService approvals;
    @Autowired ApprovalRepository approvalRows;
    @MockitoSpyBean com.dsh.agent.control.PolicyEngine policy;

    ModelResponse batch(ModelResponse... responses) {
        return new ModelResponse("",java.util.Arrays.stream(responses).flatMap(r->r.toolCalls().stream()).toList(),
                "tool_calls",new Usage("KNOWN",10,2,null,null,null,null,null),null);
    }
    void applyPrepared(Chain c, ModelResponse response) {
        var prepared=steps.prepare(c.runId(),steps.snapshot(c.runId()));
        steps.apply(c.runId(),response,0,0,prepared.entryContext());
    }
    List<MessageEntity> toolMessages(Chain c) {
        return messages.listByThread(c.threadId()).stream().filter(m->"tool".equals(m.getRole())).toList();
    }

    @Test void effectivePlanIsBoundedSharedAcrossReviewFlagsAndReloaded() throws Exception {
        var c=create();steps.begin(c.runId());
        var prepared=steps.prepare(c.runId(),steps.snapshot(c.runId()));
        String system=prepared.request().messages().getFirst().content();
        assertTrue(system.contains("当前有效验收计划"));assertTrue(system.contains(COMMAND));
        assertTrue(system.contains("工作目录=."));assertTrue(system.contains("expectTests=true"));
        assertTrue(system.contains("不要追加过滤输出管道"));
        var off=new CompletionReviewService(runs,turns,threads,projects,messages,artifacts,verifications,store,
                new com.dsh.application.VerificationEvidenceContext(turns,threads,projects,messages,artifacts,store,workspaces,mapper),mapper,false,3);
        assertEquals(review.entryPrompt(prepared.entryContext()),off.entryPrompt(off.entryContext(runs.findById(c.runId()).orElseThrow())));
        assertFalse(system.contains(prepared.entryContext().binding().goalDigest()));
        Files.writeString(root.resolve(".dsh/verify.yaml"),"command: echo next\nexpectTests: false\ntimeoutSeconds: 37\nplanVersion: c13-next\n");
        var next=steps.prepare(c.runId(),steps.snapshot(c.runId()));
        assertTrue(next.request().messages().getFirst().content().contains("command=echo next"));
        assertTrue(next.request().messages().getFirst().content().contains("timeoutSeconds=37"));
        assertNotEquals(prepared.entryContext().binding().planDigest(),next.entryContext().binding().planDigest());
        Files.writeString(root.resolve(".dsh/verify.yaml"),"command: echo "+"z".repeat(3000)+"\n");
        assertTrue(steps.assemble(c.runId(),steps.snapshot(c.runId())).messages().getFirst().content().contains("命令超过提示上限"));
    }

    @Test void readOnlyTailStartsIndependentVerificationAndReadyReview() throws Exception {
        var c=create();var count=new AtomicInteger();
        doAnswer(i->{int n=count.incrementAndGet();if(n==1)return write(c,"fixed");
            if(n==2)return batch(command(COMMAND),tool("read_file","{\"path\":\"a.txt\"}"));
            assertEquals(3,n);assertTrue(review.active(c.runId()));return text(report(c,true).toString());
        }).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId());assertCompleted(c,3);
        assertEquals("xx",Files.readString(workspace(c).resolve("checks.txt")));
        assertEquals(3,runs.findById(c.runId()).orElseThrow().getBudgetToolCallsUsed());
    }

    @Test void approvedCommandWithDeferredDeniedProbeNoLongerMasksEntry() throws Exception {
        var c=create();var count=new AtomicInteger();
        org.mockito.Mockito.doReturn(com.dsh.agent.control.PolicyEngine.Decision.REQUIRE_APPROVAL).when(policy).evaluate("EXECUTE");
        doAnswer(i->{if(count.incrementAndGet()==1)return batch(command(COMMAND),tool("run_probe",
                "{\"fileName\":\"never.sh\",\"content\":\"echo should-not-execute\",\"command\":\"sh $DSH_PROBE_FILE\"}"));
            assertTrue(review.active(c.runId()));return text(report(c,true).toString());
        }).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId());assertEquals("WAITING_APPROVAL",runs.findById(c.runId()).orElseThrow().getState());
        assertNull(review.candidate(c.runId()));
        var approval=approvalRows.findByRunIdAndStatus(c.runId(),"PENDING").getFirst();
        approvals.decide(approval.getId(),com.dsh.api.dto.ApprovalDecision.Decision.APPROVE,
                approval.getParametersDigest(),approval.getWorkspaceDigest());
        loop.resumeFromApproval(c.turnId(),c.runId());assertCompleted(c,2);
        var tail=mapper.readTree(toolMessages(c).getLast().getContent());
        assertEquals("DEFERRED_AFTER_APPROVAL",tail.path("errorCode").asText());
        assertTrue(tail.path("notExecutedByHost").asBoolean());
        assertEquals("xx",Files.readString(workspace(c).resolve("checks.txt")));
        assertTrue(artifacts.findAll().stream().noneMatch(a->c.runId().equals(a.getRunId())&&a.getName().startsWith("probe-")));
    }

    @ParameterizedTest @ValueSource(strings={"write","failed-write","probe","different-command"})
    void laterPotentialSideEffectInvalidatesMatchingCandidateEvenOnFailure(String kind) throws Exception {
        var c=create();steps.begin(c.runId());
        ModelResponse tail=switch(kind){
            case "write" -> write(c,"later");
            case "failed-write" -> tool("write_file","{\"path\":\"a.txt\",\"content\":\"later\",\"expectedFileHash\":\"wrong\"}");
            case "probe" -> tool("run_probe","{\"fileName\":\"tail.sh\",\"content\":\"echo diagnostic\",\"command\":\"sh $DSH_PROBE_FILE\"}");
            default -> command("echo unrelated");
        };
        applyPrepared(c,batch(command(COMMAND),tail));
        assertNull(review.candidate(c.runId()));assertNull(review.state(c.runId()));
        assertEquals("x",Files.readString(workspace(c).resolve("checks.txt")));
        if(kind.equals("failed-write"))assertEquals("FAILED",mapper.readTree(toolMessages(c).getLast().getContent()).path("status").asText());
    }

    @Test void earlierWriteAndLastMatchingCommandAreValidAndConsumedOnce() throws Exception {
        var c=create();steps.begin(c.runId());
        applyPrepared(c,batch(write(c,"fixed"),command(COMMAND),command(COMMAND),tool("read_file","{\"path\":\"a.txt\"}")));
        String candidate=review.candidate(c.runId());assertNotNull(candidate);
        assertEquals("xx",Files.readString(workspace(c).resolve("checks.txt")));
        assertTrue(review.consumeCandidate(c.runId(),candidate));
        assertNull(review.candidate(c.runId()));assertFalse(review.consumeCandidate(c.runId(),candidate));
        assertEquals(0,review.state(c.runId()).used());
        applyPrepared(c,tool("read_file","{\"path\":\"a.txt\"}"));
        assertNull(review.candidate(c.runId())); // Never rescan a previous batch.
    }

    @ParameterizedTest @ValueSource(strings={"goal","command","expectTests","timeout","version"})
    void requestBindingRejectsInFlightGoalOrAnyEffectivePlanDrift(String change) throws Exception {
        var c=create();steps.begin(c.runId());var prepared=steps.prepare(c.runId(),steps.snapshot(c.runId()));
        if(change.equals("goal")) messages.save(new MessageEntity(IdGen.next("msg_"),c.threadId(),c.turnId(),"user","追加约束",2,Instant.now()));
        else Files.writeString(root.resolve(".dsh/verify.yaml"),"command: \""+(change.equals("command")?"echo other":COMMAND)+"\"\n"
                +"expectTests: "+!change.equals("expectTests")+"\ntimeoutSeconds: "+(change.equals("timeout")?37:600)
                +"\nplanVersion: "+(change.equals("version")?"new-version":"default-v1")+"\n");
        steps.apply(c.runId(),command(COMMAND),0,0,prepared.entryContext());
        assertNull(review.candidate(c.runId()));
    }

    @Test void appendedHumanMessageAfterCompletedBatchInvalidatesCandidate() throws Exception {
        var c=create();steps.begin(c.runId());applyPrepared(c,command(COMMAND));assertNotNull(review.candidate(c.runId()));
        long sequence=messages.listByThread(c.threadId()).getLast().getSequence()+1;
        messages.save(new MessageEntity(IdGen.next("msg_"),c.threadId(),c.turnId(),"user","新要求",sequence,Instant.now()));
        assertNull(review.candidate(c.runId()));
    }

    @ParameterizedTest @ValueSource(strings={"missing-result","duplicate-result","wrong-call","unbound"})
    void incompleteOrDuplicatePairsAndLegacyUnboundBatchCannotTrigger(String damage) throws Exception {
        var c=create();steps.begin(c.runId());applyPrepared(c,command(COMMAND));assertNotNull(review.candidate(c.runId()));
        var tool=toolMessages(c).getFirst();
        switch(damage) {
            case "missing-result" -> messages.deleteById(tool.getId());
            case "duplicate-result" -> messages.save(new MessageEntity(IdGen.next("msg_"),c.threadId(),c.turnId(),"tool",tool.getContent(),tool.getSequence()+1,Instant.now()));
            case "wrong-call" -> {
                var result=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(tool.getContent());result.put("callId","other");
                messages.save(new MessageEntity(tool.getId(),tool.getThreadId(),tool.getTurnId(),tool.getRole(),result.toString(),tool.getSequence(),tool.getCreatedAt()));
            }
            default -> steps.apply(c.runId(),command(COMMAND),0); // Older entry point deliberately has no request provenance.
        }
        assertNull(review.candidate(c.runId()));
    }

    @Test void forgedResultDigestDoesNotTurnPipelineIntoEffectiveCommand() throws Exception {
        var c=create();steps.begin(c.runId());applyPrepared(c,command(COMMAND+" | tail -1"));
        var message=toolMessages(c).getFirst();var result=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(message.getContent());
        ((com.fasterxml.jackson.databind.node.ObjectNode)result.path("structuredContent")).put("commandDigest",com.dsh.common.Digest.sha256Hex(COMMAND+"\0."));
        messages.save(new MessageEntity(message.getId(),message.getThreadId(),message.getTurnId(),message.getRole(),
                result.toString(),message.getSequence(),message.getCreatedAt()));
        assertNull(review.candidate(c.runId()));
    }

    @Test void toolClaimingNotExecutedCannotForgeHostProofAfterSideEffect() throws Exception {
        var c=create();steps.begin(c.runId());
        doAnswer(i->{Files.writeString(workspace(c).resolve("a.txt"),"side effect despite denial");
            var claims=mapper.createObjectNode().put("notExecutedByHost",true);
            return new com.dsh.api.dto.event.ToolOutcomeDto("DENIED","POLICY_DENIED","untrusted denial",List.of(),claims,List.of(),false,0,null,
                    true,i.getArgument(7),"write_file");
        }).when(dispatcher).dispatch(org.mockito.ArgumentMatchers.eq(c.runId()),any(),any(),any(),any(),
                org.mockito.ArgumentMatchers.eq("write_file"),any(),any());
        applyPrepared(c,batch(command(COMMAND),write(c,"tail")));
        var result=mapper.readTree(toolMessages(c).getLast().getContent());
        assertTrue(result.path("structuredContent").path("notExecutedByHost").asBoolean());
        assertFalse(result.has("notExecutedByHost"));assertNull(review.candidate(c.runId()));
        assertEquals("side effect despite denial",Files.readString(workspace(c).resolve("a.txt")));
    }
}
