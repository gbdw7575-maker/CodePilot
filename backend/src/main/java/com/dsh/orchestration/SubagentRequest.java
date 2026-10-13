package com.dsh.orchestration;

import java.util.List;

/**
 * 子 Agent 调用请求（开发文档 §12）：可信角色 + 自然语言任务 + 产物引用。
 * seedWorkspaceRef 非空时子 Run 从指定冻结副本建立工作区（Tester/Reviewer 基于 Coder 快照）。
 *
 * <p>MA-04：writeScopeJson 为模型申请的资源写范围（{@code {"read":[],"write":[]}}），
 * 由服务端归一化并冻结进输入快照；null/空白=整副本可写（MA-03 前行为）。</p>
 */
public record SubagentRequest(String parentRunId, String role, String task,
                              List<String> artifactRefs, String seedWorkspaceRef,
                              String writeScopeJson, String attemptId) {

    public static final String ARCHITECT = "ARCHITECT";
    public static final String CODER = "CODER";
    public static final String TESTER = "TESTER";
    public static final String REVIEWER = "REVIEWER";

    /** MA-03 前 5 参数协议：不带写范围（整副本可写）。 */
    public SubagentRequest(String parentRunId, String role, String task,
                           List<String> artifactRefs, String seedWorkspaceRef) {
        this(parentRunId, role, task, artifactRefs, seedWorkspaceRef, null, null);
    }

    /** MA-04 6 参数协议：带写范围、不绑定调度 attempt。 */
    public SubagentRequest(String parentRunId, String role, String task,
                           List<String> artifactRefs, String seedWorkspaceRef,
                           String writeScopeJson) {
        this(parentRunId, role, task, artifactRefs, seedWorkspaceRef, writeScopeJson, null);
    }

    public SubagentRequest {
        artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
    }

    public boolean seedWorkspace() {
        return seedWorkspaceRef != null && !seedWorkspaceRef.isBlank();
    }
}
