package com.dsh.orchestration.acceptance;

import jakarta.persistence.*;
import java.time.Instant;

/** Schema only. An immutable execution slot is never a task-success permission. */
@Entity
@Table(name="task_check_executions", uniqueConstraints=@UniqueConstraint(
        name="uk_task_check_execution_slot",columnNames={"attempt_id","fencing_token"}))
public class TaskCheckExecutionEntity {
    @Id @Column(length=128) private String id;
    @Column(nullable=false,length=128) private String rootRunId;
    @Column(nullable=false,length=128) private String attemptId;
    @Column(nullable=false) private long fencingToken;
    @Column(nullable=false,length=128) private String registrationId;
    @Column(nullable=false,columnDefinition="text") private String checkRefsJson;
    @Column(nullable=false) private Instant leaseUntil;
    @Column(nullable=false) private Instant rootDeadline;
    @Column(nullable=false,length=32) private String state;
    @Column(columnDefinition="text") private String launchIdsJson;
    @Column(nullable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    protected TaskCheckExecutionEntity() { }
}
