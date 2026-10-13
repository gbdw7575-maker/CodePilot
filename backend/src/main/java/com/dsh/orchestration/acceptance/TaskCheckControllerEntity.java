package com.dsh.orchestration.acceptance;

import jakarta.persistence.*;
import java.time.Instant;

/** Schema only. Local controller return is never Docker absence or root-release authority. */
@Entity
@Table(name="task_check_controllers")
public class TaskCheckControllerEntity {
    @Id @Column(length=128) private String executionId;
    @Column(nullable=false,length=128) private String rootRunId;
    @Column(nullable=false,length=128) private String attemptId;
    @Column(nullable=false) private long fencingToken;
    @Column(nullable=false,length=32) private String instanceId;
    @Column(nullable=false,length=32) private String ownerId;
    @Column(nullable=false,length=32) private String state;
    @Column(nullable=false) private Instant createdAt;
    @Column private Instant quiescentAt;
    protected TaskCheckControllerEntity() { }
}
