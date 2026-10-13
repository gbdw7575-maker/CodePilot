package com.dsh.sandbox;

import java.nio.file.Path;

/**
 * 待执行命令（内部接口契约 §2 CommandSpec/CommandOutcome）：
 * 自由文本命令只允许在沙箱中执行；workdir 已由工具层解析为任务副本内目录。
 */
public record CommandSpec(String operationId, String command, Path workdir, int timeoutSeconds) {

    public enum Termination {
        /** 进程自然结束，exitCode 为真实退出码。 */
        COMPLETED,
        /** 超时被杀：exitCode 必须为 null，cleanupConfirmed 表示进程/容器已确认回收。 */
        TIMEOUT,
        /** 无法启动（沙箱不可用）：exitCode 为 null。 */
        LAUNCH_FAILED
    }
}
