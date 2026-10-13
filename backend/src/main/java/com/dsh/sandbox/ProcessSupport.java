package com.dsh.sandbox;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** 进程等待与超时清理的公共语义：超时后强制回收整个进程树（shell 派生子进程一并清理）。 */
final class ProcessSupport {

    private ProcessSupport() {
    }

    /**
     * 等待进程结束：超时后先优雅终止，短等后强制终止其全部子孙进程与自身。
     * 返回 null 表示正常结束（exitValue 可读）；否则返回进程树是否已确认全部回收。
     */
    static Boolean awaitWithTimeout(Process process, int timeoutSeconds) throws InterruptedException {
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (finished) {
            return null;
        }
        process.destroy();
        if (process.waitFor(2, TimeUnit.SECONDS) && !hasLiveDescendants(process)) {
            return Boolean.TRUE;
        }
        // 强制清理：先子孙（如 cmd→ping、sh→sleep），再根进程
        try (Stream<ProcessHandle> descendants = process.toHandle().descendants()) {
            descendants.forEach(handle -> {
                handle.destroyForcibly();
                try {
                    handle.onExit().get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 尽力回收，最终以存活检查为准
                }
            });
        }
        process.destroyForcibly();
        boolean reaped = process.waitFor(5, TimeUnit.SECONDS);
        return reaped && !hasLiveDescendants(process);
    }

    private static boolean hasLiveDescendants(Process process) {
        try (Stream<ProcessHandle> descendants = process.toHandle().descendants()) {
            return descendants.anyMatch(ProcessHandle::isAlive);
        }
    }
}
