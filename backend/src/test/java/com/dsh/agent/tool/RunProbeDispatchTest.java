package com.dsh.agent.tool;

import com.dsh.application.ArtifactStore;
import com.dsh.api.dto.event.ContentBlock;
import com.dsh.storage.*;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class RunProbeDispatchTest {
    @Autowired ToolDispatcher dispatcher;
    @Autowired ToolRegistry registry;
    @Autowired ArtifactStore store;
    @Autowired ProjectRepository projects;
    @Autowired WorkspaceManager workspaces;
    @Autowired ObjectMapper mapper;
    @TempDir Path root;
    String project(boolean trust) {
        String id="proj_"+UUID.randomUUID();
        projects.save(new ProjectEntity(id,"S06",root.toString(),trust,Instant.now(),Instant.now()));return id;
    }
    com.dsh.api.dto.event.ToolOutcomeDto probe(String p,String r,String args) {
        return dispatcher.dispatch(r,"thread","turn",p,Instant.now().plusSeconds(60),"run_probe",args,"call_probe");
    }
    @Test void coreExecuteRegistrationIsOutsideDiffAndCurrentRunCanReadIt() throws Exception {
        String p=project(true),r="run_s06_"+UUID.randomUUID();
        Files.writeString(root.resolve("app.txt"),"baseline");
        var git=new ProcessBuilder("sh","-c","git init -q && git add app.txt && git -c user.name=S06 -c user.email=s06@example.invalid commit -qm baseline").directory(root.toFile()).redirectErrorStream(true).start();
        String gitOutput=new String(git.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0,git.waitFor(),gitOutput);
        var args=mapper.createObjectNode().put("fileName","test_probe.sh").put("content","echo observed\n")
                .put("command","sh \"$DSH_PROBE_FILE\"");
        var result=probe(p,r,args.toString());assertEquals("SUCCEEDED",result.status(),result.summary());
        assertEquals("run_probe",result.source());assertEquals("call_probe",result.callId());
        var lease=workspaces.require(r);assertFalse(Files.exists(lease.dir().resolve("test_probe.sh")));
        assertEquals(lease.baselineDigest(),WorkspaceManager.digestOf(lease.dir()));
        String id=result.structuredContent().path("probeSourceArtifactId").asText();
        assertEquals(r,store.get(id).getRunId());assertEquals(java.util.List.of(id),result.artifactIds());
        assertTrue(Files.exists(lease.metaDir().resolve("artifacts").resolve(id)));
        var read=dispatcher.dispatch(r,"thread","turn",p,null,"read_artifact",mapper.createObjectNode().put("artifactId",id).toString(),"read");
        assertEquals("SUCCEEDED",read.status());assertTrue(((ContentBlock.TextBlock)read.content().getFirst()).text().contains("echo observed"));
        var foreign=dispatcher.dispatch("other_run","thread","turn",p,null,"read_artifact",mapper.createObjectNode().put("artifactId",id).toString(),"read");
        assertEquals("DENIED",foreign.status());
        assertEquals("EXECUTE",dispatcher.sideEffectOf("run_probe"));
        assertTrue(registry.mountedDefinitions().stream().anyMatch(d->d.name().equals("run_probe")));
        // A genuine new regression test remains a source change and appears in baseline diff.
        var write=dispatcher.dispatch(r,"thread","turn",p,null,"write_file","{\"path\":\"test_real.sh\",\"content\":\"echo real\\n\"}","write");
        assertEquals("SUCCEEDED",write.status());assertNotEquals(lease.baselineDigest(),WorkspaceManager.digestOf(lease.dir()));
        var diff=dispatcher.dispatch(r,"thread","turn",p,null,"git_diff","{}","diff");
        String diffText=diff.content().stream().filter(b->b instanceof ContentBlock.TextBlock).map(b->((ContentBlock.TextBlock)b).text()).reduce("",String::concat);
        assertTrue(diffText.contains("test_real.sh"),diffText);assertFalse(diffText.contains("test_probe.sh"));
    }
    @Test void schemaRejectsHostPathsAndAdditionalPermissions() {
        String p=project(true);
        for(String input:java.util.List.of("{}","{\"fileName\":\"../x.py\",\"content\":\"x\",\"command\":\"true\"}",
            "{\"fileName\":\"probe.sh\",\"content\":\"x\",\"command\":\"true\",\"workdir\":\"/tmp\"}",
            "{\"fileName\":\"probe.sh\",\"content\":\"x\",\"command\":\"true\",\"permission\":\"ALLOW\"}"))
            assertEquals("INVALID_ARGUMENTS",probe(p,"run_invalid",input).errorCode());
        assertNull(workspaces.find("run_invalid"));
    }
    @Test void untrustedProjectCannotCreateProbe() {
        var result=probe(project(false),"run_untrusted","{\"fileName\":\"probe.sh\",\"content\":\"echo x\",\"command\":\"true\"}");
        assertNotEquals("SUCCEEDED",result.status());assertNull(workspaces.find("run_untrusted"));
    }
}
