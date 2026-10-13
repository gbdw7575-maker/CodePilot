package com.dsh.agent.tool;

import com.dsh.application.ArtifactStore;
import com.dsh.application.DshProperties;
import com.dsh.common.Digest;
import com.dsh.sandbox.*;
import com.dsh.storage.*;
import com.dsh.workspace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunProbeToolTest {
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

    ArtifactStore artifacts;
    RunProbeTool tool;
    @BeforeEach void toolSetup() {
        artifacts=mock(ArtifactStore.class);
        when(artifacts.save(anyString(),anyString(),anyString(),anyString(),any(byte[].class),eq(true)))
                .thenReturn(new ArtifactEntity("art_probe","run_p04","project","probe.json","application/json",1,"sha",Instant.now(),true));
        tool=new RunProbeTool(new RunCommandTool(new RunCommandRegistry()),artifacts);
    }
    ObjectNode args(String name,String content,String command) {
        return mapper.createObjectNode().put("fileName",name).put("content",content).put("command",command);
    }
    @Test void isolatedSourceDoesNotChangeWorkspaceAndRealTestIsRetained() throws Exception {
        // POSIX commands really execute in the outer Docker test sandbox.
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
        Files.writeString(context.lease().dir().resolve("test_real.sh"),"echo real\n");
        String before=WorkspaceManager.digestOf(context.lease().dir());
        var outcome=tool.execute(args("test_probe.sh","echo diagnostic\n",
                "sh \"$DSH_PROBE_FILE\"; test \"$(find \"$DSH_WORKSPACE\" -name 'test_*.sh' | wc -l)\" -eq 1; printf '%s\\n' \"$DSH_PROBE_FILE\""),context);
        assertEquals("SUCCEEDED",outcome.status());assertTrue(outcome.text().contains("diagnostic"));
        String path=outcome.text().lines().filter(l->l.startsWith("/tmp/dsh-probe.")).findFirst().orElseThrow();
        assertFalse(Files.exists(Path.of(path)));assertFalse(Files.exists(Path.of(path).getParent()));
        assertEquals(before,WorkspaceManager.digestOf(context.lease().dir()));
        assertTrue(Files.isRegularFile(context.lease().dir().resolve("test_real.sh")));
        assertFalse(Files.exists(context.lease().dir().resolve("test_probe.sh")));
        assertEquals("UNKNOWN",outcome.structuredContent().path("verificationStatus").asText());
        assertEquals("art_probe",outcome.structuredContent().path("probeSourceArtifactId").asText());
        var capture=org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(artifacts).save(eq("run_p04"),eq("project"),eq("probe-test_probe.sh.json"),eq("application/json"),capture.capture(),eq(true));
        var registered=mapper.readTree(capture.getValue());assertEquals("echo diagnostic\n",registered.path("content").asText());
        assertEquals(Digest.sha256Hex("echo diagnostic\n"),registered.path("sourceSha256").asText());
    }
    @Test void sourceIsDataAndCommandQuotesArePreserved() throws Exception {
        var outcome=tool.execute(args("probe.sh","echo '$(touch /workspace/SHOULD_NOT_EXIST)'\n",
                "sh \"$DSH_PROBE_FILE\"; printf '%s' 'quoted command'"),context);
        assertEquals("SUCCEEDED",outcome.status());assertTrue(outcome.text().contains("$(touch /workspace/SHOULD_NOT_EXIST)"));
        assertTrue(outcome.text().contains("quoted command"));
    }
    @Test void invalidNamesRejectedBeforeRegistration() {
        for(String name:List.of("../a.sh","/tmp/a.sh","a..sh","a'evil.sh","a/b.sh","a\\b.sh","a".repeat(81)))
            assertThrows(ToolException.class,()->tool.execute(args(name,"true","true"),context));
        verifyNoInteractions(artifacts);
    }
    @Test void byteLimitRejectsMultibyteOverflowAndNull() {
        for(String content:List.of("中".repeat(6000),"", "bad\0source"))
            assertThrows(ToolException.class,()->tool.execute(args("probe.sh",content,"true"),context));
        verifyNoInteractions(artifacts);
    }
    @Test void commandBoundaryAndForeignLeaseRejectedBeforeRegistration() {
        for(String command:List.of(" ","x".repeat(4097),"bad\0command"))
            assertThrows(ToolException.class,()->tool.execute(args("probe.sh","true",command),context));
        var foreign=new ToolExecutionContext("foreign","thread","turn","call","project",context.lease(),context.deadline(),context.sandbox(),context.sandboxConfig(),mapper);
        assertThrows(ToolException.class,()->tool.execute(args("probe.sh","true","true"),foreign));
        verifyNoInteractions(artifacts);
    }
    @Test void failedProbeIsNotVerificationAndDoesNotWriteSource() throws Exception {
        var outcome=tool.execute(args("test_bad.sh","exit 7\n","sh \"$DSH_PROBE_FILE\""),context);
        assertEquals("FAILED",outcome.status());assertEquals(7,outcome.exitCode());assertEquals("COMMAND_FAILED",outcome.errorCode());
        assertEquals("UNKNOWN",outcome.structuredContent().path("verificationStatus").asText());
        assertFalse(Files.exists(context.lease().dir().resolve("test_bad.sh")));
    }

    @Test void failedPipelineCannotBecomeSuccessfulProbe() throws Exception {
        var outcome=tool.execute(args("probe.sh","exit 23\n",
                "sh \"$DSH_PROBE_FILE\" | tail -1; echo should-not-run"),context);
        assertEquals("FAILED",outcome.status());
        assertEquals(23,outcome.exitCode());
        assertFalse(outcome.text().contains("should-not-run"));
        assertEquals("UNKNOWN",outcome.structuredContent().path("verificationStatus").asText());
    }

    @Test void diagnosticOutputStaysInTemporaryDirectory() throws Exception {
        String before=WorkspaceManager.digestOf(context.lease().dir());
        var outcome=tool.execute(args("probe.sh","echo diagnostic\n",
                "sh \"$DSH_PROBE_FILE\" > diagnostic-output.txt; cat diagnostic-output.txt; printf '%s\\n' \"$PWD\""),context);
        assertEquals("SUCCEEDED",outcome.status());
        assertTrue(outcome.text().contains("diagnostic"));
        Path temporary=Path.of(outcome.text().lines().filter(l->l.startsWith("/tmp/dsh-probe.")).findFirst().orElseThrow());
        assertFalse(Files.exists(temporary));
        assertFalse(Files.exists(context.lease().dir().resolve("diagnostic-output.txt")));
        assertEquals(before,WorkspaceManager.digestOf(context.lease().dir()));
    }
    @Test void repeatGuardIsSharedAndExplicitRerunStillWorks() throws Exception {
        var input=args("probe.sh","echo fact\n","sh \"$DSH_PROBE_FILE\"");
        assertEquals("SUCCEEDED",tool.execute(input,context).status());assertEquals("SUCCEEDED",tool.execute(input,context).status());
        var blocked=tool.execute(input,context);assertEquals("REPEATED_COMMAND_NO_CHANGE",blocked.errorCode());
        input.put("allowRepeated",true);assertEquals("SUCCEEDED",tool.execute(input,context).status());
    }
    @Test void byteBoundaryAndEncodedArgumentLimitAreEnforced() throws Exception {
        var input=args("probe.sh","#"+"x".repeat(16383),"true");
        assertEquals("SUCCEEDED",tool.execute(input,context).status());
        reset(artifacts);
        var tooLarge=args("probe.sh","x".repeat(16384),"'".repeat(4096));
        assertThrows(ToolException.class,()->tool.execute(tooLarge,context));verifyNoInteractions(artifacts);
        // Export the production wrapper for a separate real Docker/unittest fixture.
        String export=System.getProperty("s06.wrapper.export");
        if(export!=null) Files.writeString(Path.of(export),RunProbeTool.shellCommand("test_probe.py",
                "if __name__ != '__main__': raise RuntimeError('temporary probe discovered')\nprint('S06 probe executed')\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "python \"$DSH_PROBE_FILE\"; cd \"$DSH_WORKSPACE\"; python -m unittest discover -v"));
    }
    @Test void timeoutAndCaptureLossStayTruthful() throws Exception {
        SandboxRunner runner=mock(SandboxRunner.class);when(runner.mode()).thenReturn("docker");
        when(runner.execute(any(),any())).thenReturn(new CommandOutcome("op",null,CommandSpec.Termination.TIMEOUT,"part","",true,1000,false,5000,4000));
        var fake=new ToolExecutionContext(context.runId(),"thread","turn","call","project",context.lease(),context.deadline(),runner,context.sandboxConfig(),mapper);
        var outcome=tool.execute(args("probe.sh","true","true"),fake);
        assertEquals("TIMEOUT",outcome.errorCode());assertNull(outcome.exitCode());assertTrue(outcome.truncated());
        assertEquals(4000,outcome.structuredContent().path("outputDroppedBytes").asLong());
        assertFalse(outcome.structuredContent().path("cleanupConfirmed").asBoolean());
        assertEquals("run_probe",outcome.source());
    }
}
