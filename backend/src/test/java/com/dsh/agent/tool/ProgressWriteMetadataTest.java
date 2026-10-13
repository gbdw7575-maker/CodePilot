package com.dsh.agent.tool;

import com.dsh.application.DshProperties;
import com.dsh.common.Digest;
import com.dsh.sandbox.LocalProcessSandboxRunner;
import com.dsh.storage.ProjectEntity;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProgressWriteMetadataTest {
    @TempDir Path source;
    @TempDir Path work;
    ObjectMapper mapper=new ObjectMapper();ToolExecutionContext context;
    @BeforeEach void setup() {
        DshProperties p=new DshProperties(new DshProperties.Security(List.of()),
                new DshProperties.Budget(24,80,200000,20,128000),new DshProperties.Loop(true),
                new DshProperties.Workspace(work.toString()),new DshProperties.Sandbox("local","dsh-sandbox:latest","512m","2.0",60,600,4096),
                new DshProperties.Policy(10,List.of("WORKSPACE_WRITE","EXECUTE")),new DshProperties.Provider(10,120,3,500),null,null,null,null,null,null,null,null);
        WorkspaceLease lease=new WorkspaceManager(p).prepare("run_p04",new ProjectEntity("project","P04",source.toString(),true,Instant.now(),Instant.now()));
        context=new ToolExecutionContext("run_p04","thread","turn","call","project",lease,Instant.now().plusSeconds(60),new LocalProcessSandboxRunner(p),p.sandbox(),mapper);
    }
    @Test void sameByteOverwriteIsSuccessButNotProgress() throws Exception {
        var args=mapper.createObjectNode().put("path","a.txt").put("content","same\n");
        ToolOutcome first=new WriteFileTool().execute(args,context);assertTrue(first.structuredContent().path("changed").asBoolean());
        args.put("expectedFileHash",Digest.sha256Hex(Files.readAllBytes(context.lease().dir().resolve("a.txt"))));
        ToolOutcome repeated=new WriteFileTool().execute(args,context);
        assertEquals("SUCCEEDED",repeated.status());assertFalse(repeated.structuredContent().path("changed").asBoolean());
        assertEquals(first.structuredContent().path("workspaceDigest"),repeated.structuredContent().path("workspaceDigest"));
    }
    private ToolOutcome patch(String replacement) throws Exception {
        Path file=context.lease().dir().resolve("a.txt");Files.writeString(file,"same\n");
        var args=mapper.createObjectNode().put("patch","--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-same\n+"+replacement+"\n");
        args.putObject("expectedFileHashes").put("a.txt",Digest.sha256Hex(Files.readAllBytes(file)));
        return new ApplyPatchTool().execute(args,context);
    }
    @Test void patchThatLeavesBytesIdenticalIsNotProgress() throws Exception {
        ToolOutcome outcome=patch("same");assertEquals("SUCCEEDED",outcome.status());assertFalse(outcome.structuredContent().path("changed").asBoolean());
    }
    @Test void changedPatchProducesByteEvidenceWhileKeepingHashPrecheck() throws Exception {
        ToolOutcome outcome=patch("fixed");assertTrue(outcome.structuredContent().path("changed").asBoolean());
        assertEquals("fixed\n",Files.readString(context.lease().dir().resolve("a.txt")));
    }
}
