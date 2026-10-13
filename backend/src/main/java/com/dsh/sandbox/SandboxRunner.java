package com.dsh.sandbox;

import com.dsh.workspace.WorkspaceLease;

/**
 * 沙箱执行器（内部接口契约 §1 SandboxRunner）：Docker 执行、资源限制、
 * 超时取消、日志与退出码、清理确认。命令自由文本只经此入口，不提供主机直写工具。
 */
public interface SandboxRunner {
    default TaskCheckQuarantine quarantineCheck(String operationId,String imageDigest,String ownerId){throw new UnsupportedOperationException("Independent check recovery requires Docker");}
    default boolean verifyCheckQuarantine(String operationId,String containerId,String ownerId,String imageDigest){return false;}

    /** 执行器模式标识（local/docker）。 */
    String mode();

    CommandOutcome execute(CommandSpec spec, WorkspaceLease lease);

    /** Internal preparation only; local/fake implementations must never fall back to free commands. */
    default DockerCheckIsolationPlan planCheck(com.dsh.orchestration.acceptance.TaskCheckExecutionRequest request) {
        throw new UnsupportedOperationException("Independent check preparation requires Docker");
    }

    /** Internal capability only; callers must revalidate confirmed inputs. Not task success authority. */
    default TaskCheckExecutionOutcome executeCheck(com.dsh.orchestration.acceptance.TaskCheckExecutionRequest request,
                                                   java.util.function.BooleanSupplier cancelled) {
        throw new UnsupportedOperationException("Independent check execution requires Docker");
    }

    /** Trusted controller only: identity from a durable launch journal. Observation is not release authority. */
    default TaskCheckCleanupObservation cleanupCheck(String operationId, String expectedContainerId) {
        throw new UnsupportedOperationException("Independent check recovery requires Docker");
    }
}
