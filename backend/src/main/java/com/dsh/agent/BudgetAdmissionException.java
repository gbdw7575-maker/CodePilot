package com.dsh.agent;

/** 下一次模型调用尚未发生时的累计预算拒绝。 */
public class BudgetAdmissionException extends RuntimeException {
    public BudgetAdmissionException(String message) { super(message); }
}
