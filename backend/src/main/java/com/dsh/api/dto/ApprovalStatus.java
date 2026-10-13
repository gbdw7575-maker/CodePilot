package com.dsh.api.dto;

/** 审批状态；与 openapi.json Approval.status 一致。 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    CONSUMED,
    DENIED,
    EXPIRED,
    INVALIDATED
}
