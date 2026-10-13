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

@SpringBootTest(properties={"dsh.loop.completion-review-enabled=true","dsh.policy.require-for-side-effects=NONE"})
class CompletionReviewIntegrationTest {
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
    @Autowired ToolDispatcher dispatcher;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
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
    @Test void matchingCommandStartsReviewBeforeAnotherExploratoryRequestAndReusesVerification() throws Exception {
        var c=create(); var count=new AtomicInteger();
        doAnswer(i->{int n=count.incrementAndGet();var request=(ModelRequest)i.getArgument(0);
            if(n==1)return write(c,"fixed"); if(n==2)return command(COMMAND);
            assertEquals(true,steps.executionSettings().get("loop.completionReviewEnabled"));
            assertEquals(3,steps.executionSettings().get("loop.completionReviewMaxResponses"));
            assertEquals(3,n); assertTrue(request.messages().getFirst().content().contains("有界完成复核 v2"));
            assertTrue(request.tools().stream().allMatch(t->CompletionReviewService.READ_TOOLS.contains(t.name())));
            assertFalse(request.messages().getFirst().content().contains("完整构建日志"));
            return text(report(c,true).toString());
        }).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId()); assertCompleted(c,3);
        assertEquals("xx",Files.readString(workspace(c).resolve("checks.txt")));
        assertEquals(1,verifications.findByRunIdOrderByStartedAtDesc(c.runId(),org.springframework.data.domain.PageRequest.of(0,20)).getTotalElements());
    }
    @Test void normalFinalAnswerAlsoRequiresReviewAfterIndependentVerification() throws Exception {
        var c=create();var count=new AtomicInteger();
        doAnswer(i->{int n=count.incrementAndGet();if(n==1)return write(c,"fixed");if(n==2)return text("已经修改");
            assertTrue(review.active(c.runId()));return text(report(c,true).toString());
        }).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId());assertCompleted(c,3);assertEquals("x",Files.readString(workspace(c).resolve("checks.txt")));
    }
    @Test void unrelatedGreenOutputDoesNotTriggerIndependentVerificationEarly() throws Exception {
        var c=create();var count=new AtomicInteger();
        doAnswer(i->{int n=count.incrementAndGet();if(n==1)return write(c,"fixed");if(n==2)return command("echo '99 passed in 0.01s'");
            if(n==3){assertFalse(review.active(c.runId()));assertFalse(Files.exists(workspace(c).resolve("checks.txt")));return text("已修改");}
            return text(report(c,true).toString());
        }).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId());assertCompleted(c,4);
    }
    @Test void repeatedReadsConsumeReviewResponsesAndStopWithoutFakeCompletion() throws Exception {
        var c=create();var count=new AtomicInteger();
        doAnswer(i->{int n=count.incrementAndGet();if(n==1)return write(c,"fixed");if(n==2)return command(COMMAND);
            return tool("read_file","{\"path\":\"a.txt\"}");
        }).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId()); var run=runs.findById(c.runId()).orElseThrow();
        assertEquals(5,count.get());assertEquals("FAILED",run.getState());assertEquals("COMPLETION_REVIEW_LIMIT",run.getStopReason());
        assertEquals(3,review.state(c.runId()).used()); assertNull(run.getCompletionBasis());
        var stored=messages.listByThread(c.threadId());
        assertEquals(5,stored.stream().filter(m->"tool".equals(m.getRole())).count());
        assertEquals(5,stored.stream().filter(m->"assistant_tool".equals(m.getRole())).count());
    }
    @Test void guessedWriteToolIsDeniedAndCanBeFollowedByReady() throws Exception {
        var c=create();var count=new AtomicInteger();
        doAnswer(i->{int n=count.incrementAndGet();if(n==1)return write(c,"fixed");if(n==2)return command(COMMAND);
            if(n==3){var denied=dispatcher.dispatch(c.runId(),c.threadId(),c.turnId(),c.projectId(),Instant.now().plusSeconds(30),
                    "write_file","{\"path\":\"forbidden.txt\",\"content\":\"bad\"}","direct-test");
                assertEquals("DENIED",denied.status());assertEquals("COMPLETION_REVIEW_READ_ONLY",denied.errorCode());
                return write(c,"forbidden");}
            assertEquals("fixed",Files.readString(workspace(c).resolve("a.txt")));
            assertFalse(Files.exists(workspace(c).resolve("forbidden.txt")));
            assertTrue(messages.listByThread(c.threadId()).stream().anyMatch(m->m.getContent().contains("COMPLETION_REVIEW_READ_ONLY")));
            return text(report(c,true).toString());
        }).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId());assertCompleted(c,4);
    }
    @ParameterizedTest @ValueSource(strings={"missing-input","unknown-input","wrong-verification","contradictory-ready","plain-text","trailing-json","duplicate-key"})
    void unsupportedCompletionClaimsRequireCorrection(String damage) throws Exception {
        var c=create();var count=new AtomicInteger();
        doAnswer(i->{int n=count.incrementAndGet();if(n==1)return write(c,"fixed");if(n==2)return command(COMMAND);
            if(n==3){var report=report(c,true);
                switch(damage){
                    case "missing-input" -> report.putArray("coverage");
                    case "unknown-input" -> ((com.fasterxml.jackson.databind.node.ObjectNode)report.path("coverage").get(0)).put("messageId","msg_other");
                    case "wrong-verification" -> report.put("verificationId","ver_other");
                    case "contradictory-ready" -> {report=report(c,false);report.put("decision","READY");}
                    case "plain-text" -> {return text("全部完成");}
                    case "trailing-json" -> {return text(report+" {}");}
                    case "duplicate-key" -> {return text("{\"decision\":\"REWORK\","+report.toString().substring(1));}
                }
                return text(report.toString());
            }
            assertTrue(review.prompt(c.runId()).contains("复核JSON无效"));return text(report(c,true).toString());
        }).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId());assertCompleted(c,4);assertEquals(2,review.state(c.runId()).used());
    }
    @Test void reworkReopensOrdinaryToolsAndRequiresNewEvidenceWithoutResettingReviewCount() throws Exception {
        var c=create();var count=new AtomicInteger();
        doAnswer(i->{int n=count.incrementAndGet();if(n==1)return write(c,"candidate");if(n==2)return command(COMMAND);
            if(n==3)return text(report(c,false).toString());
            if(n==4){assertFalse(review.active(c.runId()));var request=(ModelRequest)i.getArgument(0);
                assertTrue(request.tools().stream().anyMatch(t->t.name().equals("write_file")));return write(c,"fixed");}
            if(n==5)return command(COMMAND);
            assertEquals(6,n);assertEquals(1,review.state(c.runId()).used());return text(report(c,true).toString());
        }).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId());assertCompleted(c,6);assertEquals(2,review.state(c.runId()).used());
        assertEquals("xxxx",Files.readString(workspace(c).resolve("checks.txt")));
        assertEquals(2,verifications.findByRunIdOrderByStartedAtDesc(c.runId(),org.springframework.data.domain.PageRequest.of(0,20)).getTotalElements());
    }
    @ParameterizedTest @ValueSource(strings={"goal","workspace","plan","log"})
    void inFlightReviewCannotFinishAfterEvidenceDrift(String change) throws Exception {
        var c=create();var count=new AtomicInteger();
        doAnswer(i->{int n=count.incrementAndGet();if(n==1)return write(c,"fixed");if(n==2)return command(COMMAND);
            if(n==3){String old=report(c,true).toString();
                switch(change){
                    case "goal" -> messages.save(new MessageEntity(IdGen.next("msg_"),c.threadId(),c.turnId(),"user","补充新约束",
                            messages.listByThread(c.threadId()).stream().mapToLong(MessageEntity::getSequence).max().orElseThrow()+1,Instant.now()));
                    case "workspace" -> Files.writeString(workspace(c).resolve("user.txt"),"external change");
                    case "log" -> {var v=verifications.findById(review.state(c.runId()).verificationId()).orElseThrow();
                        Path log=workspaces.resolveByRef(runs.findById(c.runId()).orElseThrow().getWorkspaceRef()).metaDir().resolve("artifacts").resolve(v.getLogArtifactIds());
                        byte[] bytes=Files.readAllBytes(log);bytes[0]='X';Files.write(log,bytes); }
                    case "plan" -> Files.writeString(root.resolve(".dsh/verify.yaml"),"command: \""+COMMAND+"\"\nexpectTests: true\ntimeoutSeconds: 31\n");
                }
                return text(old);
            }
            if(n==4){assertEquals("RUNNING",runs.findById(c.runId()).orElseThrow().getState());
                assertFalse(review.active(c.runId()));return text("按最新输入重新验证");}
            assertEquals(5,n);return text(report(c,true).toString());
        }).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId());assertCompleted(c,5);assertEquals(2,review.state(c.runId()).used());
        if(change.equals("goal"))assertEquals(2,review.state(c.runId()).inputIds().size());
    }
    @Test void resumeReconstructsConsumedResponsesFromSealedState() throws Exception {
        var c=create();steps.begin(c.runId());
        steps.apply(c.runId(),write(c,"fixed"),0);
        String verificationId=verification.run(c.runId());assertTrue(review.open(c.runId(),verificationId,"manual-trigger"));
        steps.apply(c.runId(),tool("read_file","{\"path\":\"a.txt\"}"),0);
        assertEquals(1,review.state(c.runId()).used());
        doAnswer(i->{assertTrue(review.prompt(c.runId()).contains("剩余复核响应数=2"));return text(report(c,true).toString());})
                .when(gateway).generate(any(),any());
        loop.resumeRootRun(c.runId());assertCompleted(c,3);assertEquals(2,review.state(c.runId()).used());
    }
    @Test void recoveryCannotCompletePassedEvidenceWithoutReadyReview() throws Exception {
        var c=create();steps.begin(c.runId());steps.apply(c.runId(),write(c,"fixed"),0);steps.apply(c.runId(),text("修改完成"),0);
        verification.run(c.runId());assertEquals("VERIFYING",runs.findById(c.runId()).orElseThrow().getState());
        recovery.recoverOne(c.runId());recovery.recoverOne(c.runId());
        var run=runs.findById(c.runId()).orElseThrow();assertEquals("FAILED",run.getState());
        assertEquals("COMPLETION_REVIEW_REQUIRED",run.getStopReason());assertEquals("x",Files.readString(workspace(c).resolve("checks.txt")));
    }
    @Test void corruptedLatestReviewStateCannotResetConsumedAllowance() throws Exception {
        var c=create();steps.begin(c.runId());steps.apply(c.runId(),write(c,"fixed"),0);
        assertTrue(review.open(c.runId(),verification.run(c.runId()),"manual-trigger"));
        var artifact=artifacts.findFirstByRunIdAndNameStartingWithOrderByNameDesc(c.runId(),"completion-review-state-v1-").orElseThrow();
        var meta=workspaces.resolveByRef(runs.findById(c.runId()).orElseThrow().getWorkspaceRef()).metaDir();
        Files.writeString(meta.resolve("artifacts").resolve(artifact.getId()),"{}");
        assertThrows(IllegalStateException.class,()->review.state(c.runId()));
        loop.resumeRootRun(c.runId());assertEquals("FAILED",runs.findById(c.runId()).orElseThrow().getState());
        assertEquals("ENVIRONMENT_ERROR",runs.findById(c.runId()).orElseThrow().getStopReason());
    }
    @ParameterizedTest @ValueSource(strings={"zero-exit-failed-report","nonzero-exit"})
    void unsuccessfulVerificationNeverStartsReadyReview(String kind) throws Exception {
        String bad=kind.equals("nonzero-exit")?"echo '1 failed in 0.01s'; exit 7":"echo '1 failed in 0.01s'";
        var c=create(); Files.writeString(root.resolve(".dsh/verify.yaml"),"command: \""+bad+"\"\nexpectTests: true\n");
        var count=new AtomicInteger();
        doAnswer(i->{int n=count.incrementAndGet();if(n==1)return write(c,"fixed");if(n==2)return command(bad);
            assertFalse(review.active(c.runId()));return text("候选修复请验收");
        }).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId());assertEquals(3,count.get());
        var run=runs.findById(c.runId()).orElseThrow();assertEquals("FAILED",run.getState());
        assertEquals("VERIFICATION_FAILED",run.getStopReason());assertFalse(review.satisfied(c.runId()));
    }
    @Test void sameCommandInSubdirectoryIsNotMistakenForEffectiveRootPlan() throws Exception {
        var c=create();Files.createDirectories(root.resolve("sub"));var count=new AtomicInteger();
        doAnswer(i->{int n=count.incrementAndGet();if(n==1)return write(c,"fixed");
            if(n==2)return tool("run_command",mapper.createObjectNode().put("command",COMMAND).put("workdir","sub").toString());
            if(n==3){assertFalse(review.active(c.runId()));return text("修改完成");}
            return text(report(c,true).toString());
        }).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId());assertCompleted(c,4);
        assertEquals("x",Files.readString(workspace(c).resolve("checks.txt")));
        assertEquals("x",Files.readString(workspace(c).resolve("sub/checks.txt")));
    }
    @Test void pureAnswerDoesNotCreateVerificationOrReview() throws Exception {
        var c=create();doAnswer(i->text("这是无需修改文件的解释")).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId());var run=runs.findById(c.runId()).orElseThrow();
        assertEquals("COMPLETED",run.getState());assertEquals("NO_CHANGE",run.getCompletionBasis());assertNull(review.state(c.runId()));
    }
    @Test void configurationRollbackCannotBypassPendingReviewOrChangeFrozenLimit() throws Exception {
        var c=create();steps.begin(c.runId());steps.apply(c.runId(),write(c,"fixed"),0);steps.apply(c.runId(),text("修改完成"),0);
        String verificationId=verification.run(c.runId());assertTrue(review.open(c.runId(),verificationId,null));
        assertTrue(steps.reopenForCompletionReview(c.runId()));
        var rollback=new CompletionReviewService(runs,turns,threads,projects,messages,artifacts,verifications,store,
                new com.dsh.application.VerificationEvidenceContext(turns,threads,projects,messages,artifacts,store,workspaces,mapper),mapper,false,1);
        assertFalse(rollback.satisfied(c.runId()));assertTrue(rollback.active(c.runId()));
        steps.apply(c.runId(),tool("read_file","{\"path\":\"a.txt\"}"),0);
        assertFalse(rollback.exhausted(c.runId()));assertEquals(3,rollback.state(c.runId()).maxResponses());
        assertTrue(rollback.prompt(c.runId()).contains("剩余复核响应数=2"));
        steps.apply(c.runId(),text(report(c,true).toString()),0);
        assertTrue(rollback.satisfied(c.runId()));verification.run(c.runId());
        recovery.recoverOne(c.runId());assertEquals("COMPLETED",runs.findById(c.runId()).orElseThrow().getState());
    }
    @Test void originalModelRequestBudgetCanStopReviewBeforeItsOwnLimit() throws Exception {
        var c=create(3);var count=new AtomicInteger();
        doAnswer(i->{int n=count.incrementAndGet();if(n==1)return write(c,"fixed");if(n==2)return command(COMMAND);
            return tool("read_file","{\"path\":\"a.txt\"}");
        }).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId());var run=runs.findById(c.runId()).orElseThrow();
        assertEquals(3,count.get());assertEquals("FAILED",run.getState());assertEquals("BUDGET_LIMIT",run.getStopReason());
        assertEquals(1,review.state(c.runId()).used());assertEquals(36,run.getBudgetSettledTokens());
    }

    @ParameterizedTest
    @ValueSource(strings={"missing-refs","empty-refs","unknown-id","duplicate-id","wrong-type","non-verification","cross-run","old-verification","legacy-report","bad-version"})
    void coverageMustCiteCurrentVerificationLogsBeforeReady(String damage) throws Exception {
        var c=create(); var count=new AtomicInteger(); var oldLog=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(i -> {
            int n=count.incrementAndGet();
            if(n==1) return write(c,"fixed");
            if(n==2) {
                if(damage.equals("old-verification")) oldLog.set(verifications.findById(verification.run(c.runId())).orElseThrow().getLogArtifactIds());
                return command(COMMAND);
            }
            if(n==3) {
                var invalid=report(c,true);
                var entry=(com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("coverage").get(0);
                String current=entry.path("logArtifactIds").get(0).asText();
                switch(damage) {
                    case "missing-refs" -> entry.remove("logArtifactIds");
                    case "empty-refs" -> entry.putArray("logArtifactIds");
                    case "unknown-id" -> entry.putArray("logArtifactIds").add("art_invented");
                    case "duplicate-id" -> entry.putArray("logArtifactIds").add(current).add(current);
                    case "wrong-type" -> entry.put("logArtifactIds",current);
                    case "non-verification", "cross-run" -> {
                        var artifact=store.save(damage.equals("cross-run")?null:c.runId(),c.projectId(),"other.log","text/plain",
                                "1 passed in 0.01s".getBytes(java.nio.charset.StandardCharsets.UTF_8),true);
                        entry.putArray("logArtifactIds").add(artifact.getId());
                    }
                    case "old-verification" -> entry.putArray("logArtifactIds").add(oldLog.get());
                    case "legacy-report" -> {invalid.remove("reportVersion");entry.remove("logArtifactIds");}
                    default -> invalid.put("reportVersion",4294967298L);
                }
                return text(invalid.toString());
            }
            assertEquals(4,n); assertEquals("ACTIVE",review.state(c.runId()).mode());
            assertFalse(review.satisfied(c.runId()));
            assertTrue(review.prompt(c.runId()).contains("复核JSON无效"));
            assertTrue(review.prompt(c.runId()).contains("logArtifactIds"));
            return text(report(c,true).toString());
        }).when(gateway).generate(any(),any());
        loop.startRootRun(c.runId()); assertCompleted(c,4);
        assertEquals(2,review.state(c.runId()).used());
    }

    @Test void historicalReadyCannotBypassNewCitationRulesOrResetAllowanceOnRollback() throws Exception {
        var c=create();steps.begin(c.runId());steps.apply(c.runId(),write(c,"fixed"),0);
        steps.apply(c.runId(),text("提交完成候选"),0);
        String verificationId=verification.run(c.runId());
        assertTrue(review.open(c.runId(),verificationId,null));assertTrue(steps.reopenForCompletionReview(c.runId()));
        var legacy=report(c,true);legacy.remove("reportVersion");
        legacy.path("coverage").forEach(item->((com.fasterxml.jackson.databind.node.ObjectNode)item).remove("logArtifactIds"));
        var old=review.state(c.runId());
        var state=new CompletionReviewService.State(old.revision()+1,c.runId(),"READY",1,old.maxResponses(),verificationId,
                old.candidateId(),old.inputIds(),"",legacy);
        store.save(c.runId(),c.projectId(),String.format(java.util.Locale.ROOT,"completion-review-state-v1-%08d.json",state.revision()),
                "application/json",mapper.writeValueAsBytes(state),false);
        var rollback=new CompletionReviewService(runs,turns,threads,projects,messages,artifacts,verifications,store,
                new com.dsh.application.VerificationEvidenceContext(turns,threads,projects,messages,artifacts,store,workspaces,mapper),mapper,false,1);
        assertFalse(review.satisfied(c.runId()));assertFalse(rollback.satisfied(c.runId()));
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> rollback.invalidateAtBoundary(c.runId()));
        assertEquals("DORMANT",review.state(c.runId()).mode());assertEquals(1,review.state(c.runId()).used());
        assertEquals(3,review.state(c.runId()).maxResponses());
        assertEquals(Boolean.TRUE,tx.execute(status -> rollback.open(c.runId(),verificationId,null)));
        steps.apply(c.runId(),text(report(c,true).toString()),0);
        assertTrue(rollback.satisfied(c.runId()));assertEquals(2,review.state(c.runId()).used());
    }
}
