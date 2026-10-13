package com.dsh.application;

import com.dsh.common.IdGen;
import com.dsh.storage.*;
import com.dsh.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL persistence and actual sandbox execution; commands exceed the old VARCHAR limit. */
@SpringBootTest
class LongVerificationCommandIntegrationTest {
    @Autowired RunRepository runs; @Autowired TurnRepository turns; @Autowired ThreadRepository threads;
    @Autowired ProjectRepository projects; @Autowired MessageRepository messages;
    @Autowired VerificationRepository verifications; @Autowired VerificationService service;
    @Autowired VerificationEvidenceContext context; @Autowired WorkspaceManager workspaces;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path root;

    RunEntity create(String yaml) throws Exception {
        Files.createDirectories(root.resolve(".dsh"));
        Files.writeString(root.resolve(".dsh/verify.yaml"), yaml + "\nexpectTests: true\ntimeoutSeconds: 30\n");
        var now = Instant.now();
        String pid=IdGen.next("proj_"), tid=IdGen.next("thr_"), turn=IdGen.next("turn_"), rid=IdGen.next("run_");
        var p=projects.save(new ProjectEntity(pid,"C33A",root.toString(),true,now,now));
        threads.save(new ThreadEntity(tid,pid,"C33A","unused",now,now));
        turns.save(new TurnEntity(turn,tid,rid,"long verification","VERIFYING","PENDING",tid,now,now));
        var run=new RunEntity(rid,turn,null,"MAIN","VERIFYING","PENDING",1,IdGen.next("ws_"),"C33A",
                24,80,200000,now.plusSeconds(120),now,now);
        runs.save(run); run.setWorkspaceRef(workspaces.prepare(rid,p).workspaceId()); runs.save(run);
        messages.save(new MessageEntity(IdGen.next("msg_"),tid,turn,"user","long verification",1L,now));
        return runs.findById(rid).orElseThrow();
    }
    VerificationEntity verify(RunEntity run) { return verifications.findById(service.run(run.getId())).orElseThrow(); }
    static String scalar(String command) { return "command: |-\n  " + command.replace("\n", "\n  "); }
    static String longPrefix() { return ": '" + "padding-".repeat(45) + "'; "; }

    @Test void longScalarExecutesTailPersistsExactlyAndReusesSealedEvidence() throws Exception {
        String command=longPrefix()+"echo '2 passed in 0.01s'";
        var run=create(scalar(command)); var v=verify(run);
        assertTrue(command.length()>256); assertEquals(command,v.getCommandSummary());
        assertEquals("PASSED",v.getStatus()); assertEquals(2,v.getTestsPassed());
        assertTrue(context.isFresh(run,v)); assertEquals(v.getId(),service.run(run.getId()));
        assertEquals(1,verifications.findAll().stream().filter(x -> run.getId().equals(x.getRunId())).count());
        assertEquals("text",jdbc.queryForObject("select data_type from information_schema.columns where table_schema=current_schema() and table_name='verifications' and column_name='command_summary'",String.class));
        assertEquals("NO",jdbc.queryForObject("select is_nullable from information_schema.columns where table_schema=current_schema() and table_name='verifications' and column_name='command_summary'",String.class));
    }

    @Test void multilineUnicodeCommandRoundTripsAndActuallyChecksUnicode() throws Exception {
        String value="汉字与空格 ".repeat(50);
        String command="value='"+value+"'\n[ \"$value\" = '"+value+"' ] || exit 7\necho '1 passed in 0.01s'";
        var run=create(scalar(command)); var v=verify(run);
        assertEquals(command,v.getCommandSummary()); assertEquals("PASSED",v.getStatus()); assertEquals(0,v.getExitCode());
        assertTrue(context.isFresh(run,v));
    }

    @Test void quotedArgvExpansionExecutesApostrophesAndPreservesActualShellIdentity() throws Exception {
        String value="a'b ".repeat(90);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        String script="[ \"$1\" = \"$2\" ] || exit 7; echo '1 passed in 0.01s'";
        var run=create("command: " + mapper.writeValueAsString(java.util.List.of("sh","-c",script,"c33a",value,value)));
        String actual=VerificationPlan.forRun(run,root).commandSummary(); var v=verify(run);
        assertTrue(actual.length()>value.length()*2); assertTrue(actual.contains("'\"'\"'"));
        assertEquals(actual,v.getCommandSummary()); assertEquals("PASSED",v.getStatus()); assertEquals(0,v.getExitCode());
        assertTrue(context.isFresh(run,v));
    }

    @Test void failedLongCommandIsRecordedWithItsExitCode() throws Exception {
        String command=longPrefix()+"echo '1 failed in 0.01s'; exit 7";
        var run=create(scalar(command)); var v=verify(run);
        assertEquals(command,v.getCommandSummary()); assertEquals("FAILED",v.getStatus()); assertEquals(7,v.getExitCode());
        assertEquals(0,v.getTestsPassed());
    }

    @Test void changingOnlyTailBeyondOldLimitInvalidatesEvidence() throws Exception {
        String command=longPrefix()+"echo '1 passed in 0.01s'";
        var run=create(scalar(command)); var v=verify(run);
        String changed=longPrefix()+"echo '2 passed in 0.01s'";
        assertEquals(command.substring(0,256),changed.substring(0,256));
        Files.writeString(root.resolve(".dsh/verify.yaml"),scalar(changed)+"\nexpectTests: true\ntimeoutSeconds: 30\n");
        assertFalse(context.isFresh(run,v));
        var next=verify(run); assertNotEquals(v.getId(),next.getId()); assertEquals(changed,next.getCommandSummary());
        assertEquals(2,next.getTestsPassed()); assertTrue(context.isFresh(run,next));
    }
}
