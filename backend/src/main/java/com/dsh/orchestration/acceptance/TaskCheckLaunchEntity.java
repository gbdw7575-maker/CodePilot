package com.dsh.orchestration.acceptance;

import jakarta.persistence.*;
import java.time.Instant;

/** Schema only; no JPA save/delete API. Runtime journal mutations use explicit JDBC CAS. */
@Entity
@Table(name = "task_check_launches", uniqueConstraints = {
    @UniqueConstraint(name = "uk_task_check_launch_slot", columnNames = {"attempt_id", "fencing_token", "check_id"}),
    @UniqueConstraint(name = "uk_task_check_launch_operation", columnNames = "operation_id")
}, indexes = @Index(name = "ix_task_check_launch_root", columnList = "root_run_id,id"))
public class TaskCheckLaunchEntity {
    @Id @Column(length = 128) private String id;
    @Column(name="root_run_id",nullable=false,length=128,updatable=false) private String rootRunId;
    @Column(name="attempt_id",nullable=false,length=128,updatable=false) private String attemptId;
    @Column(name="fencing_token",nullable=false,updatable=false) private long fencingToken;
    @Column(name="check_id",nullable=false,length=128,updatable=false) private String checkId;
    @Column(name="operation_id",nullable=false,length=32,updatable=false) private String operationId;
    @Column(name="intent_digest",nullable=false,length=64,updatable=false) private String intentDigest;
    @Column(name="intent_json",nullable=false,columnDefinition="text",updatable=false) private String intentJson;
    @Column(nullable=false,length=32) private String state;
    @Column(name="claim_owner",length=128) private String claimOwner;
    @Column(name="cancel_requested",nullable=false) private boolean cancelRequested;
    @Column(name="manifest_json",columnDefinition="text") private String manifestJson;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    protected TaskCheckLaunchEntity() { }
}
