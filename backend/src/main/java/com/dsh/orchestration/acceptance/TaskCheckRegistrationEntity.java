package com.dsh.orchestration.acceptance;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.time.Instant;

/** Schema mapping only. No JpaRepository/save/update/delete interface or task-row FK. */
@Entity
@Immutable
@Table(name = "task_check_registrations", uniqueConstraints = @UniqueConstraint(
        name = "uk_task_check_registration_slot",
        columnNames = {"project_id", "root_run_id", "turn_id", "plan_id", "task_id", "task_version"}))
public class TaskCheckRegistrationEntity {
    @Id @Column(length = 128, updatable = false) private String id;
    @Column(name = "project_id", length = 128, nullable = false, updatable = false) private String projectId;
    @Column(name = "root_run_id", length = 128, nullable = false, updatable = false) private String rootRunId;
    @Column(name = "turn_id", length = 128, nullable = false, updatable = false) private String turnId;
    @Column(name = "plan_id", length = 128, nullable = false, updatable = false) private String planId;
    @Column(name = "task_id", length = 64, nullable = false, updatable = false) private String taskId;
    @Column(name = "task_version", nullable = false, updatable = false) private int taskVersion;
    @Column(name = "observed_plan_revision", nullable = false, updatable = false) private int observedPlanRevision;
    @Column(name = "binding_digest", length = 64, nullable = false, updatable = false) private String bindingDigest;
    @Column(name = "binding_json", columnDefinition = "text", nullable = false, updatable = false) private String bindingJson;
    @Column(name = "snapshot_digest", length = 64, nullable = false, updatable = false) private String snapshotDigest;
    @Column(name = "snapshot_json", columnDefinition = "text", nullable = false, updatable = false) private String snapshotJson;
    @Column(name = "recorded_at", nullable = false, updatable = false) private Instant recordedAt;
    protected TaskCheckRegistrationEntity() { }
}
