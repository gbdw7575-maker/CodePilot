package com.dsh.orchestration.acceptance;
import jakarta.persistence.*;
/** Whole-root immutable closure reference; physical reservations must still be verified on consumption. */
@Entity @Table(name="task_check_root_cleanups")
public class TaskCheckRootCleanupEntity {
    @Id @Column(length=128) private String rootRunId;
    @Column(nullable=false,length=64) private String closureDigest;
    @Column(nullable=false,length=128) private String artifactId;
    @Column(nullable=false,length=64) private String receiptDigest;
    protected TaskCheckRootCleanupEntity(){}
}
