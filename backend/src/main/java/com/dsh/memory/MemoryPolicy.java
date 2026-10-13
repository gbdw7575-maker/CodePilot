package com.dsh.memory;

import com.dsh.application.DshProperties;
import org.springframework.stereotype.Component;

/**
 * 记忆注入策略与配额计算（S3 新增）：在 assemble 阶段决定 L4(语义)/L5(情景)
 * 段落可占用的 token 配额。L6 程序基线（RulePrompt）稳定不截，不参与配额分配。
 * <p>
 * 配额 = max(0, (effectiveWindow - systemBase - tools - reservedOutput - history) * memoryBudgetRatio)。
 * <p>
 * 优先级：L4 优先于 L5（L4 先消费配额，L5 用剩余）；二者超出按 token 预算截断。
 * <p>
 * 非任何基准特化：仅基于 Run 自身的上下文窗口与已占用预算，不引用任何外部任务集。
 */
@Component
public class MemoryPolicy {

    private final double memoryBudgetRatio;

    public MemoryPolicy(DshProperties properties) {
        this.memoryBudgetRatio = properties.memory() == null
                ? 0.15
                : properties.memory().memoryBudgetRatio();
    }

    /**
     * 计算 L4+L5 记忆段落共享的 token 配额上限。
     * remaining 为窗口扣除 system 基线、工具、预留输出与历史后的余额；余额非正时返回 0。
     */
    public int memoryBudget(int effectiveWindow, int systemBaseTokens, int toolsTokens,
                            int reservedOutput, int historyTokens) {
        int remaining = effectiveWindow - systemBaseTokens - toolsTokens
                - reservedOutput - historyTokens;
        if (remaining <= 0) {
            return 0;
        }
        return (int) Math.max(0, remaining * memoryBudgetRatio);
    }

    double memoryBudgetRatio() {
        return memoryBudgetRatio;
    }
}
