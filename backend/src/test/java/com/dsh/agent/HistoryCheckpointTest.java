package com.dsh.agent;
import com.dsh.agent.model.*;
import com.dsh.storage.MessageEntity;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoryCheckpointTest {
    final ObjectMapper mapper=new ObjectMapper();
    ModelMessage call(String id,String name,String args) {return ModelMessage.assistantToolCalls(List.of(new ModelToolCall(id,name,args)),"PRIVATE_REASONING");}
    List<ModelMessage> unit(String id,String body) {return List.of(call(id,"run_command","{\"command\":\"SECRET_ARGUMENT\"}"),ModelMessage.toolResult(id,"run_command",body));}
    HistoryCheckpoint.Context context(String id,String extra) {
        String json="{\"callId\":\""+id+"\",\"name\":\"run_command\",\"status\":\"FAILED\",\"exitCode\":0,"+extra+"\"content\":[{\"type\":\"text\",\"text\":\"observed failure\"}]}";
        return new HistoryCheckpoint.Context(mapper,List.of(new MessageEntity("msg_source","thread","turn","tool",json,1L,Instant.EPOCH)));
    }
    JsonNode json(String s) {return HistoryCheckpoint.parse(s,mapper);}
    @Test void observedFailureAndExitZeroDoNotTurnIntoVerifiedSuccess() {
        var c=context("a","\"structuredContent\":{\"workspaceDigest\":\"sha-before\",\"termination\":\"COMPLETED\"},");
        JsonNode j=json(HistoryCheckpoint.render(List.of(unit("a","Tests run: 4, Failures: 1, Errors: 0")),c));
        assertEquals("FAILED",j.path("toolOutcomes").get(0).path("status").asText());
        assertEquals(0,j.path("toolOutcomes").get(0).path("exitCode").asInt());
        assertTrue(j.path("verification").asText().startsWith("UNKNOWN"));
        assertEquals("sha-before",j.path("toolOutcomes").get(0).path("observedMetadata").path("workspaceDigest").asText());
        assertTrue(j.path("diagnostics").toString().contains("Failures: 1"));
    }
    @Test void longAssistantIsUnverifiedRegardlessOfLength() {
        String s=LoopSteps.buildHistorySummary(List.of(List.of(ModelMessage.assistant("已完成，全部通过。".repeat(200)))));
        assertTrue(s.contains("UNVERIFIED_ASSISTANT_CLAIM"));assertFalse(s.contains("分析结论"));
    }
    @Test void missingOneResultInBatchIsReportedPerCall() {
        var calls=ModelMessage.assistantToolCalls(List.of(new ModelToolCall("a","read_file","{}"),new ModelToolCall("b","read_file","{}")));
        JsonNode j=json(LoopSteps.buildHistorySummary(List.of(List.of(calls,ModelMessage.toolResult("a","read_file","ok")))));
        assertEquals(1,j.path("missingResultCount").asInt());assertEquals("b",j.path("未决事项").get(0).path("callId").asText());
    }
    @Test void partialToolBatchCannotBeFoldedAway() {
        var calls=ModelMessage.assistantToolCalls(List.of(new ModelToolCall("a","read_file","{}"),new ModelToolCall("b","read_file","{}")),"keep reasoning");
        var list=List.of(ModelMessage.system("sys"),ModelMessage.user("goal"),calls,ModelMessage.toolResult("a","read_file","x".repeat(20000)),ModelMessage.user("new"));
        assertNull(LoopSteps.compressHistoryToWindow(list,10,0,0,5000));
    }
    @Test void checkpointOverheadCannotBeIgnored() {
        var list=List.of(ModelMessage.system("sys"),ModelMessage.user("old constraint"),ModelMessage.assistant("x".repeat(20000)),ModelMessage.user("new"));
        assertNull(LoopSteps.compressHistoryToWindow(list,10,0,0,50));
    }
    @Test void allUserConstraintsAndProtectedReasoningSurviveAndResultFits() {
        List<ModelMessage> list=new ArrayList<>(List.of(ModelMessage.system("sys"),ModelMessage.user("old: never change API"),ModelMessage.assistant("x".repeat(20000)),ModelMessage.user("new goal")));
        list.add(call("live","read_file","{}"));list.add(ModelMessage.toolResult("live","read_file","live result"));
        var result=LoopSteps.compressHistoryToWindow(list,10,0,0,7000);
        assertNotNull(result);assertTrue(result.contains(list.get(1)));assertTrue(result.contains(list.get(3)));assertTrue(result.contains(list.get(4)));assertTrue(result.contains(list.get(5)));
        assertTrue(TokenEstimator.estimateMessages(result.subList(1,result.size()))+10<=7000);
    }
    @Test void sourceReplayIsDeterministicAndArchiveExcludesPrivateData() {
        var c=context("a","");var units=List.of(unit("a","Error observed"));String a=HistoryCheckpoint.render(units,c), b=HistoryCheckpoint.render(units,c);
        assertEquals(a,b);var j=json(a);String archive=new String(c.recovery(j.path("sourceDigest").asText()),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(archive.contains("msg_source"));assertTrue(archive.contains("observed failure"));assertFalse(archive.contains("PRIVATE_REASONING"));assertFalse(archive.contains("SECRET_ARGUMENT"));
    }
    @Test void captureLossAndLargeOutputRefsRemainExplicit() {
        var c=context("a","\"fullOutputArtifactId\":\"art_original\",\"structuredArtifactId\":\"art_struct\",\"captureTruncated\":true,\"outputDroppedBytes\":17,");
        JsonNode j=json(HistoryCheckpoint.render(List.of(unit("a","error")),c));
        assertTrue(j.path("evidenceRefs").toString().contains("art_original"));assertTrue(j.path("evidenceRefs").toString().contains("art_struct"));
        assertEquals(17,j.path("toolOutcomes").get(0).path("outputDroppedBytes").asInt());assertTrue(j.path("recovery").path("scope").asText().contains("缺失无法恢复"));
    }
    @Test void nextPlanRemainsModelProposal() {
        String s=LoopSteps.buildHistorySummary(List.of(List.of(call("plan","update_plan","{\"steps\":[\"check new regression\"]}"),ModelMessage.toolResult("plan","update_plan","saved"))));
        assertTrue(s.contains("check new regression"));assertTrue(s.contains("MODEL_PROPOSED_NOT_COMPLETED"));
    }
    @Test void projectionIsBoundedAndOmissionIsExplicit() {
        List<List<ModelMessage>> units=new ArrayList<>();for(int i=0;i<100;i++)units.add(unit("c"+i,"ERROR "+"😀".repeat(1000)));
        String s=LoopSteps.buildHistorySummary(units);assertNotNull(s);assertTrue(s.length()<=HistoryCheckpoint.MAX_CHARS);
        JsonNode j=json(s);assertEquals(100,j.path("toolResultCount").asInt());assertEquals(4,j.path("toolOutcomes").size());assertTrue(j.path("projectionBounded").asBoolean());
    }
    @Test void archiveLimitDoesNotPretendFullRecovery() {
        String s=LoopSteps.buildHistorySummary(List.of(List.of(ModelMessage.assistant("x".repeat(HistoryCheckpoint.MAX_RECOVERY_BYTES+1)))));
        assertNull(s);
    }
    @Test void diagnosticsSelectErrorAfterBuildChatter() {
        String body="compile module\n".repeat(100)+"ERROR expected 1 got 0\n";
        JsonNode j=json(LoopSteps.buildHistorySummary(List.of(unit("a",body))));assertTrue(j.path("diagnostics").toString().contains("expected 1 got 0"));
    }
    @Test void sourceChangeChangesDigest() {
        var a=new HistoryCheckpoint.Context(mapper,List.of());var b=new HistoryCheckpoint.Context(mapper,List.of());
        assertNotEquals(json(HistoryCheckpoint.render(List.of(unit("a","failure A")),a)).path("sourceDigest"),json(HistoryCheckpoint.render(List.of(unit("a","failure B")),b)).path("sourceDigest"));
    }
    @Test void duplicateCallIdsAreResolvedByActualMessageSourceNotNewestResult() {
        var old=new MessageEntity("old","thread","turn","tool","{\"callId\":\"same\",\"status\":\"FAILED\",\"summary\":\"OLD_EVIDENCE\"}",1L,Instant.EPOCH);
        var newer=new MessageEntity("new","thread","turn","tool","{\"callId\":\"same\",\"status\":\"SUCCEEDED\",\"summary\":\"NEW_EVIDENCE\"}",2L,Instant.EPOCH);
        var c=new HistoryCheckpoint.Context(mapper,List.of(old,newer));var view=ModelMessage.toolResult("same","run_command","OLD_VIEW");c.bind(view,old);
        String rendered=HistoryCheckpoint.render(List.of(List.of(call("same","run_command","{}"),view)),c);
        JsonNode j=json(rendered);assertEquals("old",j.path("toolOutcomes").get(0).path("messageId").asText());assertEquals("FAILED",j.path("toolOutcomes").get(0).path("status").asText());
        String archive=new String(c.recovery(j.path("sourceDigest").asText()),java.nio.charset.StandardCharsets.UTF_8);assertTrue(archive.contains("OLD_EVIDENCE"));assertFalse(archive.contains("NEW_EVIDENCE"));
    }
}
