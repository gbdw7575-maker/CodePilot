package com.dsh.orchestration.acceptance;

import jakarta.persistence.*;
import java.time.Instant;

/** Internal immutable success receipt, committed atomically with task/attempt/result. */
@Entity
@Table(name="task_check_completions", uniqueConstraints=@UniqueConstraint(
        name="uk_task_check_completion_attempt", columnNames="attempt_id"))
public class TaskCheckCompletionEntity {
    @Id @Column(length=128) private String executionId;
    @Column(nullable=false,length=128) private String attemptId;
    @Column(nullable=false,length=128) private String rootRunId;
    @Column(nullable=false) private long fencingToken;
    @Column(nullable=false,length=128) private String artifactId;
    @Column(nullable=false,length=64) private String receiptDigest;
    @Column(nullable=false,columnDefinition="text") private String receiptJson;
    @Column(nullable=false) private Instant committedAt;
    protected TaskCheckCompletionEntity() { }
}
