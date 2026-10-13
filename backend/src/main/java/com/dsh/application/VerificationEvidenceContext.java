package com.dsh.application;

import com.dsh.common.Digest;
import com.dsh.storage.*;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** C10：验证上下文只保存摘要；旧的未绑定记录不能冒充当前目标的证据。 */
@Component
public class VerificationEvidenceContext {
    private final TurnRepository turns;
    private final ThreadRepository threads;
    private final ProjectRepository projects;
    private final MessageRepository messages;
    private final ArtifactRepository artifacts;
    private final ArtifactStore store;
    private final WorkspaceManager workspaces;
    private final ObjectMapper mapper;

    public record Binding(String goalDigest, String planDigest) { }
    public record Snapshot(String projectId, Binding binding, String workspaceDigest) { }

    public VerificationEvidenceContext(TurnRepository turns, ThreadRepository threads,
            ProjectRepository projects, MessageRepository messages, ArtifactRepository artifacts,
            ArtifactStore store, WorkspaceManager workspaces, ObjectMapper mapper) {
        this.turns = turns; this.threads = threads; this.projects = projects;
        this.messages = messages; this.artifacts = artifacts; this.store = store;
        this.workspaces = workspaces; this.mapper = mapper;
    }

    public Binding capture(RunEntity run, VerificationPlan plan) {
        var inputs = mapper.createArrayNode();
        for (var message : humanInputs(run)) {
                inputs.addObject().put("id", message.getId()).put("sequence", message.getSequence())
                        .put("content", message.getContent());
        }
        var effectivePlan = mapper.createObjectNode().put("command", plan.commandSummary())
                .put("expectTests", plan.expectTests()).put("timeoutSeconds", plan.timeoutSeconds())
                .put("planVersion", plan.planVersion())
                .put("executionPolicy", com.dsh.sandbox.StrictShellCommand.POLICY_VERSION);
        if (plan.testReports() != null) {
            effectivePlan.set("testReports", mapper.valueToTree(plan.testReports()));
            effectivePlan.put("testReportPolicy", plan.testReports().policy());
        }
        if (run.getVerificationRequirement() != null)
            effectivePlan.put("runRequirementDigest", Digest.sha256Hex(run.getVerificationRequirement()));
        if (plan.defectFix()) {
            effectivePlan.put("purpose", "DEFECT_FIX").put("reproductionDigest",
                    plan.reproduction()==null ? "MISSING" : plan.reproduction().digest());
            String baseline;
            try { baseline=DefectReproduction.baselineDigest(workspaces,workspaces.resolveByRef(run.getWorkspaceRef()).metaDir()); }
            catch (RuntimeException unavailable) { baseline="UNAVAILABLE"; }
            effectivePlan.put("reproductionBaselineDigest",baseline);
        }
        return new Binding(Digest.sha256Hex(inputs.toString()), Digest.sha256Hex(effectivePlan.toString()));
    }

    /** 与证据绑定使用同一来源判断；复核不能另按前缀猜测人类需求。 */
    public java.util.List<MessageEntity> humanInputs(RunEntity run) {
        var turn = turns.findById(run.getTurnId()).orElseThrow();
        String projectId = threads.findById(turn.getThreadId()).orElseThrow().getProjectId();
        return messages.listByThread(turn.getThreadId()).stream()
                .filter(m -> run.getTurnId().equals(m.getTurnId()) && "user".equals(m.getRole()))
                .filter(m -> m.getContent() == null || !m.getContent().startsWith("[verification-fix:")
                        || !sealed(run.getId(), projectId, feedbackName(m), feedbackBytes(run, m)))
                .toList();
    }

    public Snapshot current(RunEntity run) {
        var turn = turns.findById(run.getTurnId()).orElseThrow();
        var thread = threads.findById(turn.getThreadId()).orElseThrow();
        var project = projects.findById(thread.getProjectId()).orElseThrow();
        var dir = workspaces.resolveByRef(run.getWorkspaceRef()).dir();
        if (!Files.isDirectory(dir)) throw new IllegalStateException("验证工作区不可用");
        return new Snapshot(project.getId(), capture(run, VerificationPlan.forRun(run, Path.of(project.getRootPath()))),
                WorkspaceManager.digestOf(dir));
    }

    public void seal(RunEntity run, VerificationEntity verification, String projectId, Binding binding) {
        store.save(run.getId(), projectId, name(verification), "application/json",
                bytes(run, verification, binding), false);
    }

    /** A4 的协议 user 消息是程序反馈，不是新增人类需求；只按可验证来源排除。 */
    public void markInternalFeedback(RunEntity run, MessageEntity message, String projectId) {
        if (!run.getTurnId().equals(message.getTurnId()) || !"user".equals(message.getRole()))
            throw new IllegalArgumentException("内部反馈归属不符");
        store.save(run.getId(), projectId, feedbackName(message), "application/json",
                feedbackBytes(run, message), false);
    }

    private byte[] feedbackBytes(RunEntity run, MessageEntity message) {
        return mapper.createObjectNode().put("version", "verification-feedback-origin-v1")
                .put("runId", run.getId()).put("turnId", run.getTurnId()).put("messageId", message.getId())
                .put("contentDigest", Digest.sha256Hex(message.getContent()))
                .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String feedbackName(MessageEntity message) {
        return "verification-feedback-origin-" + message.getId() + ".json";
    }

    public boolean matches(RunEntity run, VerificationEntity verification, String projectId, Binding binding) {
        if (verification.isStale() || !run.getId().equals(verification.getRunId())) return false;
        try {
            byte[] expected = bytes(run, verification, binding);
            return sealed(run.getId(), projectId, name(verification), expected);
        } catch (RuntimeException invalidLogs) { return false; }
    }

    private boolean sealed(String runId, String projectId, String name, byte[] expected) {
        var found = artifacts.findFirstByRunIdAndNameAndSha256(runId, name, Digest.sha256Hex(expected));
        if (found.isEmpty()) return false;
        var artifact = found.get();
        if (!projectId.equals(artifact.getProjectId()) || artifact.isDownloadable()
                || artifact.getSizeBytes() != expected.length) return false;
        try {
            // 有界且拒绝链接；元数据 SHA 相同还需核对磁盘封存字节，不能只相信数据库。
            var page = store.readTextPage(artifact.getId(), 0, 4096);
            return !page.hasMore() && page.sizeBytes() == expected.length
                    && Arrays.equals(expected, page.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException unavailable) { return false; }
    }

    public boolean isFresh(RunEntity run, VerificationEntity verification, Snapshot current) {
        return current != null && current.workspaceDigest().equals(verification.getWorkspaceDigest())
                && matches(run, verification, current.projectId(), current.binding());
    }

    public boolean isFresh(RunEntity run, VerificationEntity verification) {
        try { return isFresh(run, verification, current(run)); }
        catch (RuntimeException unavailable) { return false; }
    }

    private byte[] bytes(RunEntity run, VerificationEntity verification, Binding binding) {
        var logs = mapper.createArrayNode();
        String projectId = threads.findById(turns.findById(run.getTurnId()).orElseThrow().getThreadId())
                .orElseThrow().getProjectId();
        for (String id : logArtifactIds(verification)) {
            var artifact = store.get(id);
            if (!run.getId().equals(artifact.getRunId()) || !projectId.equals(artifact.getProjectId())
                    || !artifact.isDownloadable() || artifact.getMediaType() == null
                    || !artifact.getMediaType().startsWith("text/plain")
                    || !store.verifyIntegrity(id, 16L * 1024 * 1024))
                throw new IllegalStateException("验证日志归属或完整性不可核验");
            logs.addObject().put("id", id).put("sizeBytes", artifact.getSizeBytes()).put("sha256", artifact.getSha256());
        }
        return mapper.createObjectNode().put("version", "verification-context-v2")
                .put("verificationId", verification.getId()).put("runId", run.getId())
                .put("turnId", run.getTurnId()).put("goalDigest", binding.goalDigest())
                .put("planDigest", binding.planDigest()).put("workspaceDigest", verification.getWorkspaceDigest())
                .set("logArtifacts", logs)
                .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Log IDs are trusted only after caller checks the v2 binding and bytes. */
    public static java.util.List<String> logArtifactIds(VerificationEntity verification) {
        String value = verification.getLogArtifactIds();
        if (value == null || value.isBlank() || value.length() > 2048)
            throw new IllegalStateException("验证日志列表缺失或过长");
        var ids = Arrays.stream(value.split(",", -1)).map(String::trim).toList();
        if (ids.size() > 8 || ids.stream().anyMatch(String::isBlank)
                || new java.util.HashSet<>(ids).size() != ids.size())
            throw new IllegalStateException("验证日志列表无效");
        return ids;
    }

    private static String name(VerificationEntity verification) {
        return "verification-context-" + verification.getId() + ".json";
    }
}
