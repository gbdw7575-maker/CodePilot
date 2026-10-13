package com.dsh.orchestration.acceptance;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.time.Instant;

/** Immutable root policy. No repository exposes update/delete or a model write tool. */
@Entity @Immutable @Table(name="root_task_acceptance_modes")
public class RootTaskAcceptanceModeEntity {
    @Id @Column(name="root_run_id",length=128,updatable=false) private String rootRunId;
    @Column(length=32,nullable=false,updatable=false) private String mode;
    @Column(name="frozen_at",nullable=false,updatable=false) private Instant frozenAt;
    protected RootTaskAcceptanceModeEntity() { }
}
