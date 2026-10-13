package com.dsh.orchestration.plan;

/**
 * 可恢复的计划校验拒绝，仅允许在修改计划/任务之前抛出。
 * 由工具层转换为原有 reasonCode，不把参与的 Loop 事务标为 rollback-only。
 * 内部序列化、数据库或写入后故障必须使用其他异常，保留原子回滚。
 */
final class PlanValidationException extends PlanServiceException {
    PlanValidationException(String reasonCode, String message) {
        super(reasonCode, message);
    }
}
