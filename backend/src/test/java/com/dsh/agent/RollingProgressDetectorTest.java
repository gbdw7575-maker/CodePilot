package com.dsh.agent;

import com.dsh.storage.MessageEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollingProgressDetectorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<MessageEntity> stored = new ArrayList<>();
    private void tool(String name, String status, String metadata, String text) throws Exception {
        ObjectNode n=mapper.createObjectNode(); n.put("name",name);n.put("status",status);
        n.set("structuredContent",mapper.readTree(metadata));
        if (Set.of("run_command","run_probe").contains(name)) n.put("exitCode",1);
        n.putArray("content").addObject().put("type","text").put("text",text);
        message("turn", "tool",n.toString());
    }
    private void message(String turn, String role, String content) {
        stored.add(new MessageEntity("msg_"+stored.size(),"thread",turn,role,content,(long)stored.size(),Instant.now()));
    }
    private void echoes(int n) throws Exception { for(int i=0;i<n;i++)tool("echo","SUCCEEDED","{}","same"); }
    private RollingProgressDetector.Snapshot snapshot() { return RollingProgressDetector.analyze(stored,"turn",mapper); }
    private void read(int first,int last, boolean already, String extra) throws Exception {
        tool("read_file","SUCCEEDED","{\"path\":\"src/a.java\",\"sha256\":\"same-hash\",\"startLine\":"+first
                +",\"endLine\":"+last+",\"alreadyRead\":"+already+extra+"}","file text");
    }
    private void command(String key,String output) throws Exception {
        tool("run_command","FAILED","{\"termination\":\"COMPLETED\",\"commandDigest\":\""+key+"\"}",output);
    }

    @Test void probeSourceRegistrationIsNotModificationAndRepeatedEvidenceIsNotProgress() throws Exception {
        tool("run_probe","SUCCEEDED","{\"termination\":\"COMPLETED\",\"commandDigest\":\"probe\",\"probeSourceArtifactId\":\"art_first\"}","diagnostic fact");
        for(int i=0;i<8;i++) tool("run_probe","SUCCEEDED","{\"termination\":\"COMPLETED\",\"commandDigest\":\"probe\",\"probeSourceArtifactId\":\"art_"+i+"\"}","diagnostic fact");
        assertEquals(0,snapshot().modificationCandidates()); assertEquals(0,snapshot().newEvidence());
        assertNotNull(snapshot().hint());
    }
    @Test void sevenActivitiesDoNotWarnPrematurely() throws Exception { echoes(7);assertNull(snapshot().hint()); }
    @Test void eightEchoesAreActivityWithoutEvidence() throws Exception {
        echoes(8);assertNotNull(snapshot().hint());assertEquals(0,snapshot().newEvidence());
    }
    @Test void temporaryWriteExpiresInsteadOfPermanentlyDisablingGuard() throws Exception {
        tool("write_file","SUCCEEDED","{\"changed\":true,\"workspaceDigest\":\"tmp\"}","written");
        echoes(8);assertEquals(0,snapshot().modificationCandidates());assertNotNull(snapshot().hint());
    }
    @Test void recentActualChangeSuppressesWarningWithoutClaimingValidatedPatch() throws Exception {
        echoes(7);tool("apply_patch","SUCCEEDED","{\"changed\":true,\"workspaceDigest\":\"new\"}","applied");
        assertEquals(1,snapshot().modificationCandidates());assertNull(snapshot().hint());
    }
    @Test void noopAndFailedWritesDoNotCountAsModification() throws Exception {
        tool("write_file","SUCCEEDED","{\"changed\":false,\"workspaceDigest\":\"same\"}","written");
        tool("write_file","FAILED","{\"changed\":true,\"workspaceDigest\":\"bad\"}","failed");
        echoes(6);assertEquals(0,snapshot().modificationCandidates());assertNotNull(snapshot().hint());
    }
    @Test void repeatedForceFullDoesNotInventEvidenceFromReadCountOrBody() throws Exception {
        read(1,10,false,"");
        for(int i=0;i<8;i++)read(1,10,false,",\"readCount\":"+(i+2));
        assertEquals(0,snapshot().newEvidence());assertNotNull(snapshot().hint());
    }
    @Test void containedAndOverlappingReadRangesOnlyCreditUncoveredLines() throws Exception {
        read(1,10,false,""); read(2,5,false,"");read(5,15,false,"");read(11,15,false,"");
        assertEquals(2,snapshot().newEvidence());
    }
    @Test void deduplicatedReferenceDoesNotClaimNewEvidence() throws Exception {
        read(1,10,true,""); assertEquals(0,snapshot().newEvidence());
    }
    @Test void artifactPagesCreditNewBytesButNotEmptyEofOrContainedRanges() throws Exception {
        tool("read_artifact","SUCCEEDED","{\"sha256\":\"s\",\"byteOffset\":0,\"nextOffset\":20}","page");
        tool("read_artifact","SUCCEEDED","{\"sha256\":\"s\",\"byteOffset\":5,\"nextOffset\":10}","page");
        tool("read_artifact","SUCCEEDED","{\"sha256\":\"s\",\"byteOffset\":20,\"nextOffset\":30}","next");
        tool("read_artifact","SUCCEEDED","{\"sha256\":\"s\",\"byteOffset\":30,\"nextOffset\":30}","");
        assertEquals(2,snapshot().newEvidence());
    }
    @Test void resultKeyOrderAndOperationIdentifiersDoNotCreateFreshSearchEvidence() throws Exception {
        tool("search_code","SUCCEEDED","{\"query\":\"q\",\"matches\":[],\"operationId\":\"a\"}","no matches");
        for(int i=0;i<8;i++)tool("search_code","SUCCEEDED","{\"durationMs\":"+i+",\"matches\":[],\"query\":\"q\",\"operationId\":\"b"+i+"\"}","no matches");
        assertEquals(0,snapshot().newEvidence());assertNotNull(snapshot().hint());
    }
    @Test void identicalFailureWithDifferentElapsedTimeIsNotNewDiagnosis() throws Exception {
        command("c","2026-09-30T13:01:00Z 1 failed in 0.01s");
        for(int i=0;i<8;i++)command("c","2026-09-30T13:02:00Z 1 failed in 0.99s");
        assertEquals(0,snapshot().newEvidence());assertEquals(0,snapshot().diagnosticChanges());
    }
    @Test void changedFailureIsEvidenceAndDiagnosticChangeButNotSemanticProof() throws Exception {
        command("c","AssertionError: expected A");command("c","AssertionError: expected B");echoes(6);
        assertEquals(2,snapshot().newEvidence());assertEquals(1,snapshot().diagnosticChanges());
        assertTrue(snapshot().hint().contains("假设是否收敛仍未知"));
    }
    @Test void oscillatingOldDiagnosesDoNotKeepCountingAsConvergence() throws Exception {
        command("c","Error A");command("c","Error B");
        for(int i=0;i<8;i++)command("c",i%2==0?"Error A":"Error B");
        assertEquals(0,snapshot().newEvidence());assertEquals(0,snapshot().diagnosticChanges());
    }
    @Test void differentCommandAloneDoesNotMakeIdenticalOutputNewEvidence() throws Exception {
        command("first","1 failed in 0.01s");
        for(int i=0;i<8;i++)command("different"+i,"1 failed in 0.02s");
        assertEquals(0,snapshot().newEvidence());assertEquals(0,snapshot().diagnosticChanges());
    }
    @Test void lookbackIsBoundedAndSnapshotCanBeRebuiltWithoutGlobalMemory() throws Exception {
        for(int i=0;i<80;i++)read(1,10,false,"");
        assertTrue(snapshot().lookbackLimited());assertEquals(8,snapshot().observations());
        assertEquals(snapshot(),RollingProgressDetector.analyze(List.copyOf(stored),"turn",new ObjectMapper()));
        assertEquals(0,snapshot().newEvidence());
    }
    @Test void newUserInputAndOtherTurnsDoNotInheritOldStagnation() throws Exception {
        echoes(8);message("turn","user","新的目标");
        message("other-turn","tool","invalid");echoes(2);
        assertEquals(2,snapshot().observations());assertNull(snapshot().hint());
    }
    @Test void malformedResultsAreUnknownAndNeverLeakRawContentIntoSystemHint() {
        for(int i=0;i<8;i++)message("turn","tool","private invalid JSON");
        assertEquals(8,snapshot().unobservable());assertNotNull(snapshot().hint());
        assertFalse(snapshot().hint().contains("private"));
    }
    @Test void hintTextIsStableAcrossVaryingCounters() {
        // 前缀稳定性：本段拼进 system，而 prefixHash 覆盖 system 内容。
        // 计数每轮都变，若进入文本则每轮都会让前缀缓存全部失效。
        java.util.Set<String> texts=new java.util.HashSet<>();
        for(int observations=8;observations<=64;observations+=7)
            for(int newEvidence=0;newEvidence<=3;newEvidence++)
                for(int diagnostic=0;diagnostic<=3;diagnostic++)
                    for(int unobservable=0;unobservable<=3;unobservable++){
                        String h=new RollingProgressDetector.Snapshot(
                                observations,0,newEvidence,diagnostic,unobservable,0,false).hint();
                        assertNotNull(h);texts.add(h);
                    }
        assertEquals(2,texts.size(),"提示文本只应随定性结论变化（2 种），不得随计数变化");
    }
    @Test void hintTextExcludesVolatileCounters() {
        String h=new RollingProgressDetector.Snapshot(31,0,2,1,3,0,false).hint();
        assertNotNull(h);
        assertFalse(h.contains("次工具活动"),"不得内嵌观察次数");
        assertFalse(h.contains("新证据"),"不得内嵌新证据计数");
        assertFalse(h.contains("诊断变化"),"不得内嵌诊断变化计数");
    }
}
