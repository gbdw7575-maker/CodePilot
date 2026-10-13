package com.dsh.agent;
import com.dsh.agent.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class ToolOutputReplayTest {
    ModelMessage call(String id) {return ModelMessage.assistantToolCalls(List.of(new ModelToolCall(id,"run_command","{\"command\":\"unchanged\"}")),"private-live-reasoning");}
    ModelMessage result(String id) {return ModelMessage.toolResult(id,"run_command","observations "+"x".repeat(8000));}
    List<ModelMessage> history(){return List.of(ModelMessage.user("需求逐字保留"),call("old"),result("old"),call("live"),result("live"));}
    @Test void reducesRepeatedInputPreservingUserCallsReasoningAndRecentBatch(){
        var original=history();var reduced=ToolOutputReplay.fold(original,1,m->ModelMessage.toolResult(m.toolCallId(),m.toolName(),"FAILED exitCode:1 read_artifact:art_old"));
        assertSame(original.get(0),reduced.get(0));assertSame(original.get(1),reduced.get(1));assertSame(original.get(3),reduced.get(3));assertSame(original.get(4),reduced.get(4));
        assertEquals(original.size(),reduced.size());assertTrue(TokenEstimator.estimateMessages(reduced)<TokenEstimator.estimateMessages(original));
        assertEquals("private-live-reasoning",reduced.get(1).reasoningContent());assertEquals("old",reduced.get(2).toolCallId());
    }
    @ParameterizedTest @ValueSource(ints={0,-1,99}) void offOrAllProtectedDoesNotFold(int n){AtomicInteger calls=new AtomicInteger();assertEquals(history(),ToolOutputReplay.fold(history(),n,m->{calls.incrementAndGet();return m;}));assertEquals(0,calls.get());}
    @ParameterizedTest @ValueSource(strings={"partial","duplicate-call","duplicate-result","wrong-id","wrong-name","orphan"}) void damagedPairIsNeverFolded(String fault){
        var batch=new ArrayList<ModelMessage>();batch.add(call("a"));batch.add(result("a"));
        switch(fault){case "partial"->batch.removeLast();case "duplicate-call"->batch.set(0,ModelMessage.assistantToolCalls(List.of(new ModelToolCall("a","run_command","{}"),new ModelToolCall("a","run_command","{}"))));case "duplicate-result"->batch.add(result("a"));case "wrong-id"->batch.set(1,result("other"));case "wrong-name"->batch.set(1,ModelMessage.toolResult("a","read_file","x".repeat(8000)));case "orphan"->batch.removeFirst();}
        batch.add(ModelMessage.user("后续原文"));AtomicInteger calls=new AtomicInteger();assertEquals(batch,ToolOutputReplay.fold(batch,1,m->{calls.incrementAndGet();return ModelMessage.toolResult(m.toolCallId(),m.toolName(),"short");}));assertEquals(0,calls.get());
    }
    @Test void multiCallBatchIsProtectedTogether(){var batch=List.of(ModelMessage.user("q"),ModelMessage.assistantToolCalls(List.of(new ModelToolCall("a","run_command","{}"),new ModelToolCall("b","run_command","{}"))),result("a"),result("b"));assertEquals(batch,ToolOutputReplay.fold(batch,1,m->ModelMessage.toolResult(m.toolCallId(),m.toolName(),"short")));}
    @ParameterizedTest @ValueSource(strings={"null","larger","id","name","role"}) void unsafeOrNonReducingProjectionIsRejected(String fault){
        assertEquals(history(),ToolOutputReplay.fold(history(),1,m->switch(fault){case "null"->null;case "larger"->ModelMessage.toolResult(m.toolCallId(),m.toolName(),"x".repeat(20000));case "id"->ModelMessage.toolResult("different",m.toolName(),"short");case "name"->ModelMessage.toolResult(m.toolCallId(),"other","short");default->ModelMessage.assistant("short");}));
    }
}
