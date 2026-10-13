package com.dsh.orchestration.acceptance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

/** Internal capability, deliberately not reconstructible from a persisted row. */
final class TaskCheckControllerFence {
    private final String instance=nonce();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;
    TaskCheckControllerFence(JdbcTemplate jdbc,TransactionTemplate writes){this.jdbc=jdbc;this.writes=writes;}
    private static String nonce(){return UUID.randomUUID().toString().replace("-","");}
    private static void require(boolean ok){if(!ok)throw new IllegalStateException("Check controller fence unavailable");}

    // Must join the execution reservation transaction: never authorize a launch after a failed insert.
    Gate register(String execution,String root,String attempt,long fence){
        require(TransactionSynchronizationManager.isActualTransactionActive()
            &&!TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        require(valid(execution)&&valid(root)&&valid(attempt)&&fence>0);
        Gate gate=new Gate(this,execution,root,attempt,fence,nonce());
        require(jdbc.update("""
            INSERT INTO task_check_controllers(execution_id,root_run_id,attempt_id,fencing_token,
                instance_id,owner_id,state,created_at)
            SELECT id,root_run_id,attempt_id,fencing_token,?,?,'ACTIVE',clock_timestamp()
            FROM task_check_executions WHERE id=? AND root_run_id=? AND attempt_id=?
                AND fencing_token=? AND state='RESERVED'
            """,instance,gate.owner,execution,root,attempt,fence)==1);
        return gate;
    }
    private static boolean valid(String id){return id!=null&&id.matches("[A-Za-z0-9_-]{1,128}");}

    // No caller-provided booleans, lease/terminal-state inference, retry launch, or startup backfill.
    void quiesce(Gate gate){
        require(gate!=null&&gate.service==this);
        require(!TransactionSynchronizationManager.isActualTransactionActive());
        gate.closeAdmission();
        writes.executeWithoutResult(tx->{
            require(jdbc.update("""
                UPDATE task_check_controllers c SET state='QUIESCENT_LOCAL',quiescent_at=clock_timestamp()
                WHERE c.execution_id=? AND c.root_run_id=? AND c.attempt_id=? AND c.fencing_token=?
                    AND c.instance_id=? AND c.owner_id=? AND c.state='ACTIVE' AND c.quiescent_at IS NULL
                    AND EXISTS (SELECT 1 FROM task_check_executions e WHERE e.id=c.execution_id
                        AND e.root_run_id=c.root_run_id AND e.attempt_id=c.attempt_id
                        AND e.fencing_token=c.fencing_token)
                """,gate.execution,gate.root,gate.attempt,gate.fence,instance,gate.owner)==1);
        });
    }

    static final class Gate {
        private final TaskCheckControllerFence service;
        private final String execution,root,attempt,owner;
        private final long fence;
        private final Thread thread=Thread.currentThread();
        private boolean closed,inFlight;
        private Gate(TaskCheckControllerFence service,String execution,String root,String attempt,long fence,String owner){
            this.service=service;this.execution=execution;this.root=root;this.attempt=attempt;this.fence=fence;this.owner=owner;
        }
        synchronized Permit enter(){
            require(Thread.currentThread()==thread&&!closed&&!inFlight);
            inFlight=true;return new Permit(this);
        }
        private synchronized void closeAdmission(){
            require(Thread.currentThread()==thread);
            closed=true; // Even a failed close never reopens admission.
            require(!inFlight);
        }
        private synchronized void leave(){require(Thread.currentThread()==thread&&inFlight);inFlight=false;}
    }
    static final class Permit implements AutoCloseable {
        private final Gate gate;
        private boolean returned;
        private Permit(Gate gate){this.gate=gate;}
        @Override public void close(){if(!returned){gate.leave();returned=true;}}
    }
}
