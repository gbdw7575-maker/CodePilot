package com.dsh.agent;

/** B03: 保守的调用准入预算，与真实供应商 usage 分开；运算先使用 long。 */
final class CumulativeBudgetAdmission {
    private CumulativeBudgetAdmission() {}

    static int minimumOutput(int configuredOutput) {
        if (configuredOutput <= 0) throw new IllegalArgumentException("输出额度必须为正数");
        return Math.min(configuredOutput, 256);
    }

    // 输入已经过 TokenEstimator 的 8/5 校准，再留协议包装与估计误差余量。
    static long headroom(int estimatedInput) {
        if (estimatedInput <= 0) throw new IllegalArgumentException("输入估计必须为正数");
        return Math.max(64L, (estimatedInput + 19L) / 20L);
    }

    static long inputCost(int estimatedInput) {
        return estimatedInput + headroom(estimatedInput);
    }

    /** 解 input + max(64, ceil(input/20)) + output <= remaining。 */
    static int inputLimit(long remaining, int output) {
        long afterOutput = Math.max(0L, remaining - output);
        return (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, Math.min(afterOutput - 64L, afterOutput * 20L / 21L)));
    }

    static int outputLimit(int estimatedInput, int configuredOutput, long remaining) {
        long availableOutput = remaining - inputCost(estimatedInput);
        if (availableOutput < minimumOutput(configuredOutput)) {
            throw new BudgetAdmissionException("累计 Token 余额不足以容纳下一次输入及最小输出额度");
        }
        return (int) Math.min(configuredOutput, availableOutput);
    }

    static int reservation(int estimatedInput, int output) {
        long cost = inputCost(estimatedInput) + output;
        if (cost > Integer.MAX_VALUE) throw new BudgetAdmissionException("本次请求预算超出可预约范围");
        return (int) cost;
    }
}
