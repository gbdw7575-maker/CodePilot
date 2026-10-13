package com.dsh.agent.control;

/**
 * 审批策略引擎：按工具 sideEffect 决定 ALLOW / REQUIRE_APPROVAL / DENY。
 * READ_ONLY → ALLOW；WORKSPACE_WRITE / EXECUTE → REQUIRE_APPROVAL；DELEGATE / 未知 → DENY。
 */
public interface PolicyEngine {

    Decision evaluate(String sideEffect);

    enum Decision {
        /** 直接放行，无需审批。 */
        ALLOW,
        /** 需要人工审批后执行。 */
        REQUIRE_APPROVAL,
        /** 拒绝执行。 */
        DENY
    }
}
