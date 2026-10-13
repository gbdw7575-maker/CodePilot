package com.dsh.agent.control;

import com.dsh.application.DshProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 默认策略引擎：按 dsh.policy.requireForSideEffects 配置决定。
 * READ_ONLY/NONE 永远 ALLOW；DELEGATE 永远 DENY（MVP 不支持委派）；
 * WORKSPACE_WRITE/EXECUTE 在配置列表内则 REQUIRE_APPROVAL，否则 ALLOW。
 */
@Component
public class DefaultPolicyEngine implements PolicyEngine {

    private final Set<String> requireForSideEffects;

    public DefaultPolicyEngine(DshProperties properties) {
        this.requireForSideEffects = Set.copyOf(properties.policy().requireForSideEffects());
    }

    @Override
    public Decision evaluate(String sideEffect) {
        if (sideEffect == null || sideEffect.isBlank()) {
            return Decision.ALLOW;
        }
        return switch (sideEffect) {
            case "READ_ONLY", "NONE" -> Decision.ALLOW;
            case "DELEGATE" -> Decision.DENY;
            case "WORKSPACE_WRITE", "EXECUTE" -> requireForSideEffects.contains(sideEffect)
                    ? Decision.REQUIRE_APPROVAL
                    : Decision.ALLOW;
            default -> Decision.DENY;
        };
    }
}
