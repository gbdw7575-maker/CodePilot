package com.dsh.sandbox;

/**
 * 命令执行结果（内部接口契约 §2）：
 * 超时或进程丢失不得伪造退出码（exitCode=null），以终止原因为准；
 * cleanupConfirmed 只在确认进程/容器已回收时为 true；日志为有界内容，超限 truncated=true。
 *
 * <p>A3 可追溯性：truncated=true 时额外带出原始输出总字节数与被丢弃字节数。
 * 必须区分「采集被截断」与「模型视图被截断」——采集截断会让尾部证据永久消失，
 * 上层据此不能笼统宣称「完整输出已另存产物」。
 */
public record CommandOutcome(String operationId,
                             Integer exitCode,
                             CommandSpec.Termination termination,
                             String stdout,
                             String stderr,
                             boolean truncated,
                             long durationMs,
                             boolean cleanupConfirmed,
                             long totalBytes,
                             long droppedBytes) {

    /** 兼容既有构造点：未报告字节统计时按「未丢弃」处理。 */
    public CommandOutcome(String operationId, Integer exitCode, CommandSpec.Termination termination,
                          String stdout, String stderr, boolean truncated, long durationMs,
                          boolean cleanupConfirmed) {
        this(operationId, exitCode, termination, stdout, stderr, truncated, durationMs,
                cleanupConfirmed, 0L, 0L);
    }

    public boolean succeeded() {
        return termination == CommandSpec.Termination.COMPLETED && exitCode != null && exitCode == 0;
    }
}
