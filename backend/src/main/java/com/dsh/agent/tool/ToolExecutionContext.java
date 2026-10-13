package com.dsh.agent.tool;

import com.dsh.application.DshProperties;
import com.dsh.sandbox.SandboxRunner;
import com.dsh.workspace.WorkspaceLease;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * 工具执行上下文（内部接口契约 §2 ToolExecutionContext）：
 * 由系统注入，模型不能通过参数指定其他项目、主机路径、Docker socket 或凭据目录。
 */
public record ToolExecutionContext(String runId, String threadId, String turnId, String callId,
                                   String projectId, WorkspaceLease lease, Instant deadline,
                                   SandboxRunner sandbox, DshProperties.Sandbox sandboxConfig,
                                   ObjectMapper objectMapper) {
}
