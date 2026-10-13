package com.dsh.application;

import com.dsh.storage.*;
import com.dsh.common.IdGen;
import com.dsh.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL/workspace/SandboxRunner, not a mocked persistence result. */
@SpringBootTest
class VerificationPlanVersionIntegrationTest {
    @Autowired RunRepository runs; @Autowired TurnRepository turns; @Autowired ThreadRepository threads;
    @Autowired ProjectRepository projects; @Autowired MessageRepository messages;
    @Autowired VerificationRepository verifications; @Autowired VerificationService service;
    @Autowired VerificationEvidenceContext context; @Autowired WorkspaceManager workspaces;
    @Autowired JdbcTemplate jdbc;
    @Autowired ArtifactRepository artifacts;
    @TempDir Path root;
    RunEntity create(String yaml) throws Exception {
        Files.createDirectories(root.resolve(".dsh"));
        Files.writeString(root.resolve(".dsh/verify.yaml"),yaml+"\nexpectTests: true\ntimeoutSeconds: 30\n");
        var now=Instant.now();String pid=IdGen.next("proj_"),tid=IdGen.next("thr_"),turn=IdGen.next("turn_"),rid=IdGen.next("run_");
        var p=projects.save(new ProjectEntity(pid,"C33A2",root.toString(),true,now,now));
        threads.save(new ThreadEntity(tid,pid,"C33A2","unused",now,now));
        turns.save(new TurnEntity(turn,tid,rid,"version boundary","VERIFYING","PENDING",tid,now,now));
        var run=new RunEntity(rid,turn,null,"MAIN","VERIFYING","PENDING",1,IdGen.next("ws_"),"C33A2",24,80,200000,now.plusSeconds(120),now,now);
        runs.save(run);run.setWorkspaceRef(workspaces.prepare(rid,p).workspaceId());runs.save(run);
        messages.save(new MessageEntity(IdGen.next("msg_"),tid,turn,"user","version boundary",1L,now));
        return runs.findById(rid).orElseThrow();
    }
    VerificationEntity verify(RunEntity run){return verifications.findById(service.run(run.getId())).orElseThrow();}
    static String scalar(String command){return "command: |-\n  "+command.replace("\n","\n  ");}
    @ParameterizedTest @ValueSource(strings={"a","中","😀"," "})
    void validBoundaryRoundTripsAndReusesExactEvidence(String point) throws Exception {
        String value=point.equals(" ")?" "+"v".repeat(62)+" ":point.repeat(64);
        var run=create(scalar("echo '1 passed in 0.01s'")+"\nplanVersion: '"+value+"'");
        var v=verify(run);assertEquals("PASSED",v.getStatus());assertEquals(value,v.getPlanVersion());
        assertEquals(64,jdbc.queryForObject("select char_length(plan_version) from verifications where id=?",Integer.class,v.getId()));
        assertTrue(context.isFresh(run,v));assertEquals(v.getId(),service.run(run.getId()));
    }
    @ParameterizedTest @ValueSource(strings={"a","中","😀"," "})
    void invalidBoundaryHasNoExecutionArtifactOrRecordEffects(String point) throws Exception {
        String value=point.equals(" ")?"v".repeat(64)+" ":point.repeat(65);
        var run=create(scalar(": > execution-marker; echo '1 passed in 0.01s'")+"\nplanVersion: '"+value+"'");
        Path workspace=workspaces.resolveByRef(run.getWorkspaceRef()).dir();
        String digest=WorkspaceManager.digestOf(workspace);long count=verifications.count(),artifactCount=artifacts.count();
        assertThrows(IllegalArgumentException.class,()->service.run(run.getId()));
        assertFalse(Files.exists(workspace.resolve("execution-marker")));assertEquals(digest,WorkspaceManager.digestOf(workspace));
        assertEquals(count,verifications.count());assertEquals(artifactCount,artifacts.count());
        assertEquals("PENDING",runs.findById(run.getId()).orElseThrow().getVerificationStatus());
    }
    @Test void changingOnlyVersionMakesSealedEvidenceStaleAndProducesNewRecord() throws Exception {
        String yaml=scalar("echo '1 passed in 0.01s'")+"\nplanVersion: '"+"v".repeat(63)+"a'";
        var run=create(yaml);var original=verify(run);
        Path file=root.resolve(".dsh/verify.yaml");Files.writeString(file,Files.readString(file).replace("v".repeat(63)+"a","v".repeat(63)+"b"));
        assertFalse(context.isFresh(run,original));var next=verify(run);assertNotEquals(original.getId(),next.getId());
        assertEquals("v".repeat(63)+"b",next.getPlanVersion());assertTrue(context.isFresh(run,next));
    }
}
