package com.dsh.api;

import com.dsh.orchestration.acceptance.*;
import com.dsh.agent.control.RunControlService;
import com.dsh.sandbox.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest @AutoConfigureMockMvc
class StrongRootRecoveryTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){TaskCheckExecutionPreparationTest.properties(r);}
    @MockitoBean SandboxRunner runner;
    @MockitoBean com.dsh.orchestration.task.TaskSchedulingService scheduling;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskCheckRootRecovery recovery;
    @Autowired RunControlService controls;
    TaskCheckCompletionTest h;
    @BeforeEach void setup(){h=new TaskCheckCompletionTest();context.getAutowireCapableBeanFactory().autowireBean(h);h.runner=runner;h.setup();}
    TaskCheckAttemptEvidenceTest.Fixture blocked()throws Exception{var f=h.fixture("slugify-semantic");h.observed(f);
        jdbc.update("UPDATE runs SET state='BLOCKED',stop_reason='RECOVERY_REQUIRED' WHERE id=?",f.confirmed().chain().run());return f;}
    void physicalFence(){when(runner.quarantineCheck(anyString(),anyString(),anyString())).thenAnswer(call->{TaskCheckQuarantine q=mock(TaskCheckQuarantine.class);
        when(q.operationId()).thenReturn(call.getArgument(0));when(q.imageDigest()).thenReturn(call.getArgument(1));when(q.ownerId()).thenReturn(call.getArgument(2));
        when(q.containerId()).thenReturn("f".repeat(64));when(q.fenced()).thenReturn(true);return q;});when(runner.verifyCheckQuarantine(anyString(),anyString(),anyString(),anyString())).thenReturn(true);}
    @Test void durableClosureAndActualFencePermitRootReleaseWithoutReplay()throws Exception{var f=blocked();physicalFence();var c=f.confirmed().chain();
        recovery.recover(c.run());assertTrue(recovery.verified(c.run()));var proof=recovery.observeRelease(c.run());
        assertEquals("CANCELLED",controls.consumeRecoveredChecks(proof).getState());
        assertNull(jdbc.queryForObject("SELECT active_turn_id FROM threads WHERE id=?",String.class,c.thread()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM task_check_recovery_fences WHERE root_run_id=? AND container_id IS NOT NULL",Integer.class,c.run()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_check_completions WHERE root_run_id=?",Integer.class,c.run()));verify(runner,times(1)).executeCheck(any(),any());
    }
    @Test void unknownPhysicalStateKeepsActivePointers()throws Exception{var f=blocked();var c=f.confirmed().chain();
        assertThrows(ApiException.class,()->controls.recoverChecks(c.run()));assertEquals("BLOCKED",jdbc.queryForObject("SELECT state FROM runs WHERE id=?",String.class,c.run()));
        assertEquals(c.turn(),jdbc.queryForObject("SELECT active_turn_id FROM threads WHERE id=?",String.class,c.thread()));assertFalse(recovery.verified(c.run()));}
    @Test void vanishedFenceInvalidatesPersistedReceipt()throws Exception{var f=blocked();physicalFence();recovery.recover(f.confirmed().chain().run());
        when(runner.verifyCheckQuarantine(anyString(),anyString(),anyString(),anyString())).thenReturn(false);
        assertFalse(recovery.verified(f.confirmed().chain().run()));assertThrows(RuntimeException.class,()->recovery.observeRelease(f.confirmed().chain().run()));}
    @Test void changedClosureAfterObservationCannotReleaseRoot()throws Exception{var f=blocked();physicalFence();var root=f.confirmed().chain().run();recovery.recover(root);var proof=recovery.observeRelease(root);
        jdbc.update("UPDATE task_check_executions SET check_refs_json='[]' WHERE root_run_id=?",root);
        assertThrows(RuntimeException.class,()->controls.consumeRecoveredChecks(proof));assertEquals("BLOCKED",jdbc.queryForObject("SELECT state FROM runs WHERE id=?",String.class,root));}
    @Test void stillActiveChildPreventsRootReleaseEvenWithFencedChecks()throws Exception{var f=blocked();physicalFence();var root=f.confirmed().chain().run();recovery.recover(root);var proof=recovery.observeRelease(root);
        jdbc.update("UPDATE runs SET state='RUNNING' WHERE id=?",f.child());assertThrows(RuntimeException.class,()->controls.consumeRecoveredChecks(proof));}
}
