package com.dsh.orchestration.task;

import com.dsh.orchestration.SubagentResult;
import com.dsh.orchestration.acceptance.*;
import com.dsh.storage.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.util.*;

/** Strong child completion uses the existing execution coordinator and atomic receipt committer. */
@Service
public class StrongTaskPipeline {
    @org.springframework.beans.factory.annotation.Autowired private com.dsh.agent.control.RunControlService controls;
    private final boolean enabled;private final RootTaskAcceptanceModes modes;private final JdbcTemplate jdbc;
    private final TaskCheckExecutionCoordinator coordinator;private final TaskCheckCompletionService completion;
    private final TaskCheckLifecycleGuard lifecycle;private final TransactionTemplate writes;
    private final ObjectProvider<TaskSchedulingService> scheduling;private final ObjectProvider<TaskEventJournal> events;
    public StrongTaskPipeline(@Value("${dsh.orchestration.strong-runtime-enabled:false}") boolean enabled,
            RootTaskAcceptanceModes modes,JdbcTemplate jdbc,TaskCheckExecutionCoordinator coordinator,TaskCheckCompletionService completion,
            TaskCheckLifecycleGuard lifecycle,PlatformTransactionManager tx,ObjectProvider<TaskSchedulingService> scheduling,ObjectProvider<TaskEventJournal> events){
        this.enabled=enabled;this.modes=modes;this.jdbc=jdbc;this.coordinator=coordinator;this.completion=completion;this.lifecycle=lifecycle;this.scheduling=scheduling;this.events=events;
        writes=new TransactionTemplate(tx);writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);writes.setTimeout(5);
    }
    public boolean handles(String attempt){if(!enabled)return false;
        var roots=jdbc.queryForList("SELECT p.root_run_id FROM agent_task_attempts a JOIN agent_plans p ON p.id=a.plan_id WHERE a.attempt_id=?",String.class,attempt);
        return roots.size()==1&&modes.isStrong(roots.getFirst());
    }
    /** No model result, summary or patch can directly create SUCCEEDED. */
    public void complete(String attempt,SubagentResult child){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Strong checks require transaction-free worker");
        if(!handles(attempt))throw new IllegalStateException("Strong runtime unavailable");
        String root=jdbc.queryForObject("SELECT p.root_run_id FROM agent_task_attempts a JOIN agent_plans p ON p.id=a.plan_id WHERE a.attempt_id=?",String.class,attempt);
        try{
            var identity=writes.execute(tx->{
                lifecycle.lockRoot(root);var a=jdbc.queryForMap("SELECT * FROM agent_task_attempts WHERE attempt_id=? FOR UPDATE",attempt);
                if(!"RUNNING".equals(a.get("state")))return null;
                if(!"COMPLETED".equals(child.state())||!child.runId().equals(a.get("child_run_id")))throw new IllegalStateException("CHILD_NOT_COMPLETED");
                if(jdbc.queryForObject("SELECT count(*) FROM runs WHERE id=? AND parent_run_id=? AND state='COMPLETED' AND cancel_requested_at IS NULL",Integer.class,child.runId(),root)!=1)throw new IllegalStateException("CHILD_NOT_CURRENT");
                if(jdbc.queryForObject("SELECT count(*) FROM runs WHERE id=? AND state='RUNNING' AND cancel_requested_at IS NULL AND budget_deadline_at>clock_timestamp()",Integer.class,root)!=1)throw new IllegalStateException("ROOT_NOT_CURRENT");
                if(jdbc.queryForObject("SELECT count(*) FROM agent_task_attempts WHERE attempt_id=? AND lease_until>clock_timestamp()",Integer.class,attempt)!=1)throw new IllegalStateException("LEASE_EXPIRED");
                if(jdbc.queryForObject("SELECT count(*) FROM task_check_executions WHERE attempt_id=?",Integer.class,attempt)!=0)throw new IllegalStateException("CHECK_NOT_REPLAYED");
                var regs=jdbc.queryForList("SELECT id FROM task_check_registrations WHERE root_run_id=? AND plan_id=? AND task_id=? AND task_version=?",String.class,root,a.get("plan_id"),a.get("task_id"),a.get("task_version"));
                if(regs.size()!=1)throw new IllegalStateException("REGISTRATION_MISSING");
                if(jdbc.update("UPDATE agent_tasks SET state='VERIFYING',updated_at=now() WHERE plan_id=? AND task_id=? AND task_version=? AND state='RUNNING'",a.get("plan_id"),a.get("task_id"),a.get("task_version"))!=1)throw new IllegalStateException("TASK_NOT_CURRENT");
                events.getObject().append(root,(String)a.get("plan_id"),(String)a.get("task_id"),((Number)a.get("task_version")).intValue(),attempt,child.runId(),"RUNNING","VERIFYING","CHILD_DELIVERY_CLOSED");
                return new Identity(regs.getFirst(),((Number)a.get("fencing_token")).longValue());
            });
            if(identity==null)return;
            completion.commit(coordinator.execute(identity.registration(),attempt,child.runId(),identity.fence()));
            scheduling.getObject().onStrongCompletion(root);
        }catch(RuntimeException failure){fail(attempt,root,"CHECK_REJECTED_OR_RECOVERY_REQUIRED");}
    }
    /** Startup quota refusal has no child/effect to replay; bounded retry is owned by the scheduler. */
    public void fail(String attempt,String root,String reason){
        boolean recovery=Boolean.TRUE.equals(writes.execute(tx->{
            lifecycle.lockRoot(root);var a=jdbc.queryForMap("SELECT * FROM agent_task_attempts WHERE attempt_id=? FOR UPDATE",attempt);
            if(!"RUNNING".equals(a.get("state")))return false;
            boolean uncertain=jdbc.queryForObject("SELECT count(*) FROM task_check_executions WHERE attempt_id=? AND state NOT IN ('COMMITTED','REJECTED')",Integer.class,attempt)>0
                    ||(a.get("child_run_id")!=null&&jdbc.queryForObject("SELECT count(*) FROM runs WHERE id=? AND state NOT IN ('COMPLETED','FAILED','CANCELLED')",Integer.class,a.get("child_run_id"))>0);
            // Durable effects are retained for explicit recovery; never return them to QUEUED.
            if(uncertain){lifecycle.cancelRootChecks(root);}
            else {jdbc.update("UPDATE agent_task_attempts SET state='FAILED',lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE attempt_id=? AND state='RUNNING'",attempt);
                jdbc.update("UPDATE agent_tasks SET state='FAILED',updated_at=now() WHERE plan_id=? AND task_id=? AND task_version=? AND state IN ('RUNNING','VERIFYING')",a.get("plan_id"),a.get("task_id"),a.get("task_version"));
                events.getObject().append(root,(String)a.get("plan_id"),(String)a.get("task_id"),((Number)a.get("task_version")).intValue(),attempt,(String)a.get("child_run_id"),"VERIFYING","FAILED",reason);
            }
            return uncertain;
        }));
        if(recovery)controls.blockCheckRecovery(root);
        scheduling.getObject().onStrongCompletion(root);
    }
    private record Identity(String registration,long fence){}
}
