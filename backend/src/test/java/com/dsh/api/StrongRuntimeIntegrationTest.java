package com.dsh.api;

import com.dsh.orchestration.*;
import com.dsh.orchestration.acceptance.*;
import com.dsh.orchestration.task.*;
import com.dsh.sandbox.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"dsh.orchestration.strong-runtime-enabled=true","dsh.orchestration.recovery-scan-ms=3600000"}) @AutoConfigureMockMvc
class StrongRuntimeIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){TaskCheckExecutionPreparationTest.properties(r);}
    @MockitoBean SandboxRunner runner;
    @MockitoBean SubagentService subagents;
    @MockitoSpyBean TaskSchedulingService scheduling;
    @Autowired TaskWorkerExecutor workers;
    @Autowired ApplicationContext context;
    @Autowired TaskCheckReceiptReader receipts;
    @Autowired TaskDispatchService dispatch;
    @Autowired JdbcTemplate jdbc;
    TaskCheckCompletionTest h;
    @BeforeEach void setup(){doNothing().when(scheduling).onStrongCompletion(anyString());doNothing().when(scheduling).promoteAndPump(anyString());
        h=new TaskCheckCompletionTest();context.getAutowireCapableBeanFactory().autowireBean(h);h.runner=runner;h.setup();}
    @Test void actualWorkerClaimsClosedChildChecksCommitsAndSharedQueryConsumes()throws Exception{
        var f=h.fixture("slugify-semantic");h.helper.success();var c=f.confirmed().chain();
        // Use the actual production child factory: a separate child Turn and thread, not a same-Turn shortcut.
        var parent=context.getBean(com.dsh.storage.RunRepository.class).findById(c.run()).orElseThrow();
        var parentThread=context.getBean(com.dsh.storage.ThreadRepository.class).findById(c.thread()).orElseThrow();
        var profile=context.getBean(com.dsh.storage.ModelProfileRepository.class).findById(parentThread.getModelProfileId()).orElseThrow();
        context.getBean(ChildRunFactory.class).create(f.child(),parent,parentThread,profile,"CODER","child task",f.lease().workspaceId(),24,80,200000,parent.getBudgetDeadlineAt());
        jdbc.update("UPDATE runs SET state='COMPLETED' WHERE id=?",f.child());
        jdbc.update("UPDATE turns SET state='COMPLETED',active_key=NULL WHERE id=(SELECT turn_id FROM runs WHERE id=?)",f.child());
        jdbc.update("UPDATE agent_tasks SET state='READY' WHERE plan_id=? AND task_id='A'",c.plan());
        jdbc.update("UPDATE agent_task_attempts SET state='QUEUED',child_run_id=NULL,lease_owner=NULL,lease_until=NULL WHERE attempt_id=?",f.attempt());
        when(subagents.run(any())).thenAnswer(call->{SubagentRequest request=call.getArgument(0);assertEquals(f.attempt(),request.attemptId());
            jdbc.update("UPDATE agent_task_attempts SET child_run_id=? WHERE attempt_id=?",f.child(),f.attempt());
            return new SubagentResult(f.child(),"CODER","COMPLETED","VERIFICATION_PASSED","closed candidate",List.of(),null,f.lease().workspaceId(),"",0,0,null,0);
        });
        workers.pumpRoot(c.run());long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
        while(System.nanoTime()<until&&jdbc.queryForObject("SELECT count(*) FROM agent_task_results WHERE attempt_id=?",Integer.class,f.attempt())==0)Thread.sleep(50);
        receipts.requireAttempt(f.attempt());assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT state FROM agent_task_attempts WHERE attempt_id=?",String.class,f.attempt()));
        verify(subagents,times(1)).run(any());verify(runner,times(1)).executeCheck(any(),any());verify(scheduling).onStrongCompletion(c.run());
        var task=h.helper.base.helper.tasks.findByPlanIdAndTaskId(c.plan(),"A").orElseThrow();
        var result=h.helper.base.results.findById(f.attempt()).orElseThrow();
        assertTrue(context.getBean(TaskQueryService.class).isEvidenceValid(task,result));
    }
    @Test void realStrongDispatchAcceptsV2AndNeverFreezesLegacy()throws Exception{
        var f=h.fixture("slugify-semantic");var c=f.confirmed().chain();
        jdbc.update("DELETE FROM agent_task_attempts WHERE attempt_id=?",f.attempt());jdbc.update("UPDATE agent_tasks SET state='DRAFT' WHERE plan_id=?",c.plan());
        int revision=jdbc.queryForObject("SELECT revision FROM agent_plans WHERE id=?",Integer.class,c.plan());
        var accepted=dispatch.register(c.run(),c.plan(),revision,List.of("A"),"strong-valid");assertTrue(accepted.accepted(),accepted.errorMessage());
        assertEquals("EXECUTABLE_CHECKS",jdbc.queryForObject("SELECT mode FROM root_task_acceptance_modes WHERE root_run_id=?",String.class,c.run()));
        verify(subagents,never()).run(any());
    }
    @Test void modelSuccessWithFailedIndependentCheckCannotReleaseDownstream()throws Exception{
        var f=h.fixture("slugify-semantic");var c=f.confirmed().chain();jdbc.update("UPDATE agent_tasks SET state='RUNNING' WHERE plan_id=?",c.plan());
        when(runner.executeCheck(any(),any())).thenAnswer(call->TaskCheckExecutionCoordinatorTest.outcome(call.getArgument(0),TaskCheckExecutionOutcome.Termination.COMPLETED,1,TaskCheckExecutionOutcome.Presence.ABSENT,
            Map.of("target/surefire-reports/TEST-Trusted.xml",TaskCheckSelectionAssessmentTest.xml(1,1,0))));
        context.getBean(StrongTaskPipeline.class).complete(f.attempt(),new SubagentResult(f.child(),"CODER","COMPLETED","PASSED","done",List.of(),null,"","",0,0,null,0));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_check_completions WHERE attempt_id=?",Integer.class,f.attempt()));assertFalse(receipts.rootReady(c.run()));
        assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM agent_tasks WHERE plan_id=? AND task_id='A'",String.class,c.plan()));
    }
}
