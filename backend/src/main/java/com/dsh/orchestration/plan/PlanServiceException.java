package com.dsh.orchestration.plan;

/**
 * 计划校验/状态冲突（MA-02）。reasonCode 取 MA-01 冻结 fixtures reasonCodes 的键，
 * 其值全部落在现行 HTTP x-error-codes 词表内；工具边界映射为 ToolOutcome.failed，
 * 不向 Loop 抛异常。
 */
public class PlanServiceException extends RuntimeException {

    private final String reasonCode;

    public PlanServiceException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
