package com.dsh.evals.model;

/** 离线受保护评分规格声明证据用途，不能从 shell 命令文本猜测是否有断言。 */
public enum EvalGradingMode {
    /** 退出成功且至少实际执行 minTests 个测试，不能全跳过。 */
    TESTS,
    /** 可信独立评分器用退出码表达业务断言；须在规格中显式声明。 */
    ASSERTIONS,
    /** 仅观察运行是否完成；即使退出 0，也不能证明缺陷解决。 */
    SMOKE
}
