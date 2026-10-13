package com.dsh.orchestration.acceptance;
import jakarta.persistence.*;
/** Persistent quarantine owner allocated before Docker; no automatic deletion of retained fences. */
@Entity @Table(name="task_check_recovery_fences")
public class TaskCheckRecoveryEntity {
    @Id @Column(length=128) private String launchId;
    @Column(nullable=false,length=128) private String rootRunId;
    @Column(nullable=false,length=32) private String operationId;
    @Column(nullable=false,length=71) private String imageDigest;
    @Column(nullable=false,length=32) private String ownerId;
    @Column(length=64) private String containerId;
    protected TaskCheckRecoveryEntity(){}
}
