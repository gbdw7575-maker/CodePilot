package com.dsh.api;

import com.dsh.orchestration.acceptance.*;
import com.dsh.sandbox.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PG/v2 confirmation/files and synchronous Runner substitution; no actual Docker control. */
@SpringBootTest @AutoConfigureMockMvc
class TaskCheckControllerIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){TaskCheckExecutionPreparationTest.properties(r);}
    @MockitoBean SandboxRunner runner;
    @MockitoBean com.dsh.orchestration.task.TaskSchedulingService scheduling;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskCheckExecutionCoordinator coordinator;
    TaskCheckExecutionCoordinatorTest helper;
    @BeforeEach void setup(){helper=new TaskCheckExecutionCoordinatorTest();context.getAutowireCapableBeanFactory().autowireBean(helper);helper.runner=runner;helper.setup();}
    Map<String,Object> row(String attempt){return jdbc.queryForMap("SELECT * FROM task_check_controllers WHERE attempt_id=?",attempt);}
    void quiescent(TaskCheckAttemptEvidenceTest.Fixture f){var r=row(f.attempt());assertEquals("QUIESCENT_LOCAL",r.get("state"));assertNotNull(r.get("quiescent_at"));
        assertEquals(f.confirmed().chain().run(),r.get("root_run_id"));assertEquals(2L,((Number)r.get("fencing_token")).longValue());
        assertTrue(r.get("owner_id").toString().matches("[a-f0-9]{32}"));assertTrue(r.get("instance_id").toString().matches("[a-f0-9]{32}"));helper.noTaskSuccess(f);}
    @ParameterizedTest @ValueSource(strings={"single","multiple"})
    void returnedSelectionHasBoundLocalQuiescenceOnly(String size)throws Exception{
        var f=size.equals("single")?helper.fixture("slugify-semantic"):helper.fixture("slugify-semantic","second-semantic");
        helper.success();var result=helper.execute(f);assertEquals("OBSERVED",result.state());quiescent(f);
        assertEquals(result.executionId(),row(f.attempt()).get("execution_id"));
        assertThrows(RuntimeException.class,()->helper.execute(f));verify(runner,times(result.checks().size())).executeCheck(any(),any());
    }
    @Test void cancellationAndFutureInterruptDoNotMakeOutstandingControllerQuiescent()throws Exception{
        var f=helper.fixture("slugify-semantic");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var exited=new CountDownLatch(1);
        when(runner.executeCheck(any(),any())).thenAnswer(call->{entered.countDown();
            // A stubborn synchronous call ignores interrupts until actually released.
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);boolean done=false;
            while(!done&&System.nanoTime()<until){try{done=release.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}}
            assertTrue(done);return TaskCheckExecutionCoordinatorTest.outcome(call.getArgument(0),TaskCheckExecutionOutcome.Termination.CANCELLED,null,TaskCheckExecutionOutcome.Presence.UNKNOWN,Map.of());
        });
        var pool=Executors.newSingleThreadExecutor();
        try{var future=pool.submit(()->{try{return helper.execute(f);}finally{exited.countDown();}});
            assertTrue(entered.await(30,TimeUnit.SECONDS));assertEquals("ACTIVE",row(f.attempt()).get("state"));assertNull(row(f.attempt()).get("quiescent_at"));
            jdbc.update("UPDATE runs SET cancel_requested_at=now() WHERE id=?",f.confirmed().chain().run());future.cancel(true);
            assertEquals("ACTIVE",row(f.attempt()).get("state"));assertNull(row(f.attempt()).get("quiescent_at"));
            release.countDown();assertTrue(exited.await(30,TimeUnit.SECONDS));quiescent(f);
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(30,TimeUnit.SECONDS));}
    }
    @ParameterizedTest @ValueSource(strings={"runtime","error"})
    void exceptionalStackUnwindClosesLocalControllerWithoutSuccess(String kind)throws Exception{
        var f=helper.fixture("slugify-semantic");when(runner.executeCheck(any(),any())).thenAnswer(call->{if(kind.equals("error"))throw new AssertionError("controlled failure");throw new IllegalStateException("controlled failure");});
        if(kind.equals("error"))assertThrows(AssertionError.class,()->helper.execute(f));else assertEquals("UNKNOWN",helper.execute(f).state());
        quiescent(f);assertThrows(RuntimeException.class,()->helper.execute(f));verify(runner,times(1)).executeCheck(any(),any());
    }
    @ParameterizedTest @ValueSource(strings={"owner","instance","root","attempt","fence","state","timestamp","missing","execution"})
    void changedOrMissingDurableIdentityCannotCommitLocalQuiescence(String drift)throws Exception{
        var f=helper.fixture("slugify-semantic");when(runner.executeCheck(any(),any())).thenAnswer(call->{
            assertEquals("ACTIVE",row(f.attempt()).get("state"));
            switch(drift){
                case "missing"->jdbc.update("DELETE FROM task_check_controllers WHERE attempt_id=?",f.attempt());
                case "execution"->jdbc.update("UPDATE task_check_executions SET fencing_token=3 WHERE attempt_id=?",f.attempt());
                default->{String column=switch(drift){case "owner"->"owner_id";case "instance"->"instance_id";case "root"->"root_run_id";case "attempt"->"attempt_id";case "fence"->"fencing_token";case "state"->"state";default->"quiescent_at";};
                    Object value=switch(drift){case "fence"->3L;case "timestamp"->java.sql.Timestamp.from(java.time.Instant.now());default->"changed";};
                    jdbc.update("UPDATE task_check_controllers SET "+column+"=? WHERE attempt_id=?",value,f.attempt());}
            }
            return TaskCheckExecutionCoordinatorTest.outcome(call.getArgument(0),TaskCheckExecutionOutcome.Termination.COMPLETED,0,TaskCheckExecutionOutcome.Presence.ABSENT,
                Map.of("target/surefire-reports/TEST-Trusted.xml",TaskCheckSelectionAssessmentTest.xml(1,0,0)));
        });
        assertThrows(IllegalStateException.class,()->helper.execute(f));helper.noTaskSuccess(f);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_check_controllers WHERE execution_id IN (SELECT id FROM task_check_executions WHERE attempt_id=?) AND state='QUIESCENT_LOCAL'",Integer.class,f.attempt()));
        assertThrows(RuntimeException.class,()->helper.execute(f));verify(runner,times(1)).executeCheck(any(),any());
    }
    @Test void failedReservationRollsBackLeaseExecutionAndControllerTogether()throws Exception{
        var f=helper.fixture("slugify-semantic");helper.shortLease(f);var before=helper.lease(f);
        // Fake durable orphan conflicts at insert; deliberately not a valid controller proof.
        // Force every new controller owner to violate a temporary PG CHECK, then remove only our constraint.
        jdbc.execute("ALTER TABLE task_check_controllers ADD CONSTRAINT test_controller_refuse CHECK (state <> 'ACTIVE') NOT VALID");
        try{assertThrows(RuntimeException.class,()->helper.execute(f));assertEquals(before,helper.lease(f));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_check_executions WHERE attempt_id=?",Integer.class,f.attempt()));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_check_controllers WHERE attempt_id=?",Integer.class,f.attempt()));verifyNoInteractions(runner);
        }finally{jdbc.execute("ALTER TABLE task_check_controllers DROP CONSTRAINT test_controller_refuse");}
    }
}
