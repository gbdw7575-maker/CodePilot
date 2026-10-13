package com.dsh.orchestration.acceptance;

import com.dsh.common.IdGen;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.sql.Timestamp;

/** Package-only append/read ledger. Future trusted confirmation coordinator owns authorization/CAS. */
// JdbcTemplate already translates SQL exceptions. @Repository would also translate our
// IllegalStateException boundary/corruption failures into InvalidDataAccessApiUsageException.
@Component
class TaskCheckRegistrationStore {
    private final JdbcTemplate jdbc;
    TaskCheckRegistrationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    record Stored(String id, String bindingDigest, String bindingJson, String snapshotDigest,
                  String snapshotJson, int observedPlanRevision, Instant recordedAt) { }
    static final class BindingConflict extends RuntimeException {
        BindingConflict() { super("Task version already has a different frozen check binding"); }
    }
    Stored append(TaskCheckRegistrationBinding binding) {
        if (binding == null) throw new IllegalArgumentException("Missing registration binding");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Registration append requires a caller-owned writable transaction");
        // PG uniqueness resolves races without poisoning the transaction with a caught SQL exception.
        jdbc.update("""
                INSERT INTO task_check_registrations
                (id,project_id,root_run_id,turn_id,plan_id,task_id,task_version,observed_plan_revision,
                 binding_digest,binding_json,snapshot_digest,snapshot_json,recorded_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT ON CONSTRAINT uk_task_check_registration_slot DO NOTHING
                """, IdGen.next("checkreg_"), binding.projectId, binding.rootRunId, binding.turnId,
                binding.planId, binding.taskId, binding.taskVersion, binding.observedPlanRevision,
                binding.bindingDigest, binding.canonicalBinding, binding.snapshotDigest, binding.canonicalSnapshot,
                Timestamp.from(Instant.now()));
        var rows = jdbc.query("""
                SELECT id,binding_digest,binding_json,snapshot_digest,snapshot_json,observed_plan_revision,recorded_at
                FROM task_check_registrations
                WHERE project_id=? AND root_run_id=? AND turn_id=? AND plan_id=? AND task_id=? AND task_version=?
                """, (rs, n) -> new Stored(rs.getString("id"), rs.getString("binding_digest"),
                rs.getString("binding_json"), rs.getString("snapshot_digest"), rs.getString("snapshot_json"),
                rs.getInt("observed_plan_revision"), rs.getTimestamp("recorded_at").toInstant()),
                binding.projectId, binding.rootRunId, binding.turnId, binding.planId, binding.taskId, binding.taskVersion);
        if (rows.size() != 1) throw new IllegalStateException("Registration winner is not visible in this transaction");
        var stored = rows.getFirst();
        TaskCheckRegistrationBinding original;
        try {
            original = TaskCheckRegistrationBinding.decode(stored.snapshotJson());
            if (!stored.id().matches("checkreg_[a-f0-9]{32}") || stored.recordedAt() == null
                    || !original.projectId.equals(binding.projectId) || !original.rootRunId.equals(binding.rootRunId)
                    || !original.turnId.equals(binding.turnId) || !original.planId.equals(binding.planId)
                    || !original.taskId.equals(binding.taskId) || original.taskVersion != binding.taskVersion
                    || original.observedPlanRevision != stored.observedPlanRevision()
                    || !original.canonicalBinding.equals(stored.bindingJson()) || !original.bindingDigest.equals(stored.bindingDigest())
                    || !original.snapshotDigest.equals(stored.snapshotDigest())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException damaged) {
            throw new IllegalStateException("Invalid frozen task check registration data");
        }
        if (!stored.bindingJson().equals(binding.canonicalBinding)) throw new BindingConflict();
        return stored;
    }
}
