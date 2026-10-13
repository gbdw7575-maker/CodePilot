package com.dsh.orchestration.acceptance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Joins the caller's lifecycle transaction. Durable facts prohibit legacy replay, never authorize success. */
@Service
public class TaskCheckLifecycleGuard {
    private final JdbcTemplate jdbc;
    private final RootTaskAcceptanceModes modes;

    public TaskCheckLifecycleGuard(JdbcTemplate jdbc, RootTaskAcceptanceModes modes) {
        this.jdbc = jdbc;
        this.modes = modes;
    }

    private List<String> lockAttempt(String attemptId) {
        var roots = jdbc.queryForList("""
                SELECT p.root_run_id FROM agent_task_attempts a JOIN agent_plans p ON p.id=a.plan_id
                WHERE a.attempt_id=?
                """, String.class, attemptId);
        // Legacy lifecycle writes publish thread events too. Never lock an attempt before that thread.
        if (!roots.isEmpty()) lockRoot(roots.getFirst());
        // Register and verification lease reservation acquire this same lock before inserting facts.
        return jdbc.queryForList("SELECT plan_id FROM agent_task_attempts WHERE attempt_id=? FOR UPDATE",
                String.class, attemptId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockRoot(String rootId) {
        lockRootSource(jdbc, rootId);
    }

    static void lockRootSource(JdbcTemplate jdbc, String rootId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new org.springframework.transaction.IllegalTransactionStateException("Check source lock requires a transaction");
        var origins = jdbc.queryForList("""
                SELECT u.id AS turn_id,u.thread_id FROM runs r JOIN turns u ON u.id=r.turn_id WHERE r.id=?
                """, rootId);
        if (origins.size()!=1) throw new IllegalStateException("Check lifecycle source unavailable");
        var origin=origins.getFirst();String turnId=(String)origin.get("turn_id"),threadId=(String)origin.get("thread_id");
        // SQL row locks observe the current row without an earlier JPA @Version snapshot becoming lock authority.
        if (jdbc.queryForList("SELECT id FROM threads WHERE id=? FOR UPDATE",String.class,threadId).size()!=1)
            throw new IllegalStateException("Check lifecycle source unavailable");
        var roots=jdbc.queryForList("SELECT turn_id,parent_run_id,role FROM runs WHERE id=? FOR UPDATE",rootId);
        if (roots.size()!=1 || !turnId.equals(roots.getFirst().get("turn_id"))
                || roots.getFirst().get("parent_run_id")!=null || !"MAIN".equals(roots.getFirst().get("role")))
            throw new IllegalStateException("Check lifecycle source unavailable");
        var turns=jdbc.queryForList("SELECT thread_id,root_run_id FROM turns WHERE id=? FOR UPDATE",turnId);
        if (turns.size()!=1 || !threadId.equals(turns.getFirst().get("thread_id"))
                || !rootId.equals(turns.getFirst().get("root_run_id")))
            throw new IllegalStateException("Check lifecycle source unavailable");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean blocksLegacy(String attemptId) {
        var plans = lockAttempt(attemptId);
        if (plans.isEmpty()) return false; // Missing attempts are already refused by each caller.
        // Even terminal, malformed or older-fence facts block replay; no inference about process absence.
        boolean facts = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM task_check_executions WHERE attempt_id=?)
                    OR EXISTS (SELECT 1 FROM task_check_launches WHERE attempt_id=?)
                """, Boolean.class, attemptId, attemptId));
        return facts || !modes.legacyAllowed(plans.getFirst());
    }

    /** Persist cancellation with task cancellation. A rollback rolls back both; claimed processes stay uncertain. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelChecks(String attemptId) {
        if (lockAttempt(attemptId).isEmpty()) return;
        // Deterministic ordering matches attempts sorted by the task cancellation caller.
        var launches = jdbc.queryForList("""
                SELECT id FROM task_check_launches WHERE attempt_id=? ORDER BY id FOR UPDATE
                """, String.class, attemptId);
        for (String launch : launches) {
            jdbc.update("""
                    UPDATE task_check_launches SET cancel_requested=true,
                        state=CASE WHEN state='PENDING' THEN 'CANCELLED' ELSE state END, updated_at=now()
                    WHERE id=? AND state IN ('PENDING','STARTING','UNKNOWN') AND cancel_requested=false
                    """, launch);
        }
    }

    /** Root cancellation includes old generations and orphaned facts. Joins the root control transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelRootChecks(String rootId) {
        lockRoot(rootId);
        jdbc.queryForList("""
                SELECT attempt_id FROM agent_task_attempts
                WHERE plan_id IN (SELECT id FROM agent_plans WHERE root_run_id=?)
                   OR attempt_id IN (SELECT attempt_id FROM task_check_launches WHERE root_run_id=?)
                   OR attempt_id IN (SELECT attempt_id FROM task_check_executions WHERE root_run_id=?)
                ORDER BY attempt_id FOR UPDATE
                """, String.class, rootId, rootId, rootId);
        var launches = jdbc.queryForList("""
                SELECT l.id FROM task_check_launches l WHERE l.root_run_id=?
                  OR EXISTS (SELECT 1 FROM agent_task_attempts a JOIN agent_plans p ON p.id=a.plan_id
                             WHERE a.attempt_id=l.attempt_id AND p.root_run_id=?)
                ORDER BY l.id FOR UPDATE
                """, String.class, rootId, rootId);
        for (String launch : launches) {
            jdbc.update("""
                    UPDATE task_check_launches SET cancel_requested=true,
                      state=CASE WHEN state='PENDING' THEN 'CANCELLED' ELSE state END,updated_at=now()
                    WHERE id=? AND state IN ('PENDING','STARTING','UNKNOWN') AND cancel_requested=false
                    """, launch);
        }
    }

    /** Conservative until actual cleanup evidence is consumed. Terminal journal states are not cleanup authority. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasRootChecks(String rootId) {
        lockRoot(rootId);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM task_check_launches l WHERE l.root_run_id=?
                  OR EXISTS (SELECT 1 FROM agent_task_attempts a JOIN agent_plans p ON p.id=a.plan_id
                             WHERE a.attempt_id=l.attempt_id AND p.root_run_id=?))
                  OR EXISTS (SELECT 1 FROM task_check_executions e WHERE e.root_run_id=?
                  OR EXISTS (SELECT 1 FROM agent_task_attempts a JOIN agent_plans p ON p.id=a.plan_id
                             WHERE a.attempt_id=e.attempt_id AND p.root_run_id=?))
                """, Boolean.class, rootId, rootId, rootId, rootId));
    }
}
