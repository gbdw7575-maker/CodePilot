package com.dsh.api.dto;

/** 对应 openapi.json 的 components/schemas/ApprovalDecision；scope 固定 ONCE。 */
public record ApprovalDecision(Decision decision, String parametersDigest, String workspaceDigest) {

    public enum Decision {
        APPROVE,
        DENY
    }
}
