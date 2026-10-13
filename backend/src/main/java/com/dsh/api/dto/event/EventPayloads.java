package com.dsh.api.dto.event;

import com.dsh.api.dto.Usage;

/**
 * DSH-003 覆盖事件类型的 payload 记录；字段与 doc/events.schema.json 严格一致。
 */
public final class EventPayloads {

    private EventPayloads() {
    }

    /** run.state_changed：from 可为 null（Run 诞生）。 */
    public record StateChanged(String from, String to, int version) {
    }

    /** run.completed：verificationStatus 仅允许 PASSED / NOT_REQUIRED。 */
    public record RunCompleted(String state, String verificationStatus, String completionBasis,
                               String summary, int version) {
    }

    /** run.failed。 */
    public record RunFailed(String state, String reason, String message, int version) {
    }

    /** run.cancelled。 */
    public record RunCancelled(String state, String reason, int version) {
    }

    /** run.input_applied：补充输入在对应模型轮次开始前被确认。 */
    public record InputApplied(String messageId, int attempt) {
    }

    /** assistant.text.delta（同步 fake 不发，流式阶段使用）。 */
    public record TextDelta(String messageId, int index, String text) {
    }

    /** assistant.message.completed。 */
    public record MessageCompleted(String messageId, String content) {
    }

    /** model.completed：modelReturned 可空；finishReason 为归一化字符串。 */
    public record ModelCompleted(String modelRequested, String modelReturned, String finishReason,
                                 int durationMs, Usage usage) {
    }

    /** tool.started。 */
    public record ToolStarted(String callId, String name, String argumentsSummary) {
    }

    /** tool.completed。 */
    public record ToolCompleted(String callId, String name, ToolOutcomeDto result) {
    }

    /** approval.requested。 */
    public record ApprovalRequested(String approvalId, String toolCallId, java.time.Instant expiresAt) {
    }

    /** approval.resolved。 */
    public record ApprovalResolved(String approvalId, String status) {
    }

    /** checkpoint.created。 */
    public record CheckpointCreated(String checkpointId, String workspaceDigest) {
    }

    /** verification.completed。 */
    public record VerificationCompleted(String verificationId, String status,
                                        String workspaceDigest, String preVerificationDigest,
                                        boolean workspaceModified, boolean stale) {
    }

    // ----------------------- MA-10 编排任务域（规范 §11；MA-01 ADR §6 冻结字段） -----------------------

    /** plan.updated：计划草案创建/修订；revision 为服务端当前修订号。 */
    public record PlanUpdated(String planId, int revision) {
    }

    /** task.dispatched：dispatch_tasks 登记成功（DRAFT→PENDING），attemptId 可空。 */
    public record TaskDispatched(String planId, String taskId, int taskVersion,
                                 String attemptId, String state) {
    }

    /** task.state.changed：任务状态迁移；from 可空，attemptId/childRunId 可空。 */
    public record TaskStateChanged(String planId, String taskId, int taskVersion,
                                   String attemptId, String childRunId,
                                   String from, String to, String reasonCode) {
    }

    /** task.result.ready：attempt 终态结果就绪；只含引用与摘要，不含隐藏推理或凭据。 */
    public record TaskResultReady(String planId, String taskId, int taskVersion,
                                  String attemptId, String childRunId, String state,
                                  java.util.List<String> artifactRefs,
                                  String verificationRef, String inputDigest,
                                  String outputDigest) {
    }

    /** run.waiting_tasks：主 Agent 持久化停车等待任务集合；cursor 为十进制任务事件游标。 */
    public record WaitingTasks(java.util.List<String> taskIds, String waitMode,
                               String cursor, java.time.Instant waitUntil) {
    }

    /** integration.completed / integration.failed：resultDigest 可空。 */
    public record IntegrationFinished(String integrationId, String state,
                                      String resultDigest, String message) {
    }
}
