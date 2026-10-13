package com.dsh.api;

import com.dsh.orchestration.acceptance.*;
import com.dsh.sandbox.SandboxRunner;
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
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc
class StrongReceiptConsumptionTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){TaskCheckExecutionPreparationTest.properties(r);}
    @MockitoBean SandboxRunner runner;
    @MockitoBean com.dsh.orchestration.task.TaskSchedulingService scheduling;
    @Autowired ApplicationContext context;
    @Autowired TaskCheckReceiptReader receipts;
    @Autowired JdbcTemplate jdbc;
    TaskCheckCompletionTest h;
    @BeforeEach void setup(){h=new TaskCheckCompletionTest();context.getAutowireCapableBeanFactory().autowireBean(h);h.runner=runner;h.setup();}
    @Test void fullCommittedReceiptIsUsableAfterLeaseClearedAndRootCompleted()throws Exception{
        var f=h.fixture("slugify-semantic","second-semantic");var result=h.observed(f);h.completion.commit(result);
        receipts.requireAttempt(f.attempt());assertTrue(receipts.rootReady(f.confirmed().chain().run()));
        var c=f.confirmed().chain();jdbc.update("UPDATE runs SET state='COMPLETED' WHERE id=?",c.run());
        jdbc.update("UPDATE turns SET state='COMPLETED',active_key=NULL WHERE id=?",c.turn());
        receipts.requireAttempt(f.attempt());
    }
    @ParameterizedTest @ValueSource(strings={"requirement","source","xml","receipt","registration","plan","fence","launch","slot","input","trust","result"})
    void staleOrMissingActualClosureRefusesEveryConsumer(String drift)throws Exception{
        var f=h.fixture("slugify-semantic");var result=h.observed(f);var committed=h.completion.commit(result);var c=f.confirmed().chain();
        receipts.requireAttempt(f.attempt());
        switch(drift){
            case "requirement"->jdbc.update("UPDATE messages SET content='changed' WHERE turn_id=? AND role='user'",c.turn());
            case "source"->Files.writeString(f.lease().dir().resolve("src/main/java/sample/Value.java"),"changed");
            case "xml"->{var ref=result.checks().getFirst().sealed().members().stream().filter(a->a.mediaType().equals("application/xml")).findFirst().orElseThrow();jdbc.update("UPDATE artifacts SET sha256=? WHERE id=?","a".repeat(64),ref.id());}
            case "receipt"->jdbc.update("UPDATE task_check_completions SET receipt_digest=? WHERE attempt_id=?","b".repeat(64),f.attempt());
            case "registration"->jdbc.update("UPDATE task_check_registrations SET binding_digest=? WHERE id=?","c".repeat(64),f.confirmed().registration());
            case "plan"->jdbc.update("UPDATE agent_plans SET revision=revision+1 WHERE id=?",c.plan());
            case "fence"->jdbc.update("UPDATE agent_task_attempts SET fencing_token=fencing_token+1 WHERE attempt_id=?",f.attempt());
            case "launch"->jdbc.update("UPDATE task_check_launches SET cancel_requested=true WHERE attempt_id=?",f.attempt());
            case "slot"->jdbc.update("UPDATE task_check_executions SET state='OBSERVED' WHERE id=?",committed.executionId());
            case "input"->jdbc.update("UPDATE agent_input_snapshots SET base_digest=? WHERE run_id=?","d".repeat(64),f.child());
            case "trust"->jdbc.update("UPDATE projects SET trusted=false WHERE id=?",c.project());
            case "result"->jdbc.update("UPDATE agent_task_results SET artifact_refs_json='[]' WHERE attempt_id=?",f.attempt());
        }
        assertThrows(IllegalStateException.class,()->receipts.requireAttempt(f.attempt()));assertFalse(receipts.rootReady(c.run()));
    }
}
