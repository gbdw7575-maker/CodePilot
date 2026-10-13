package com.dsh.agent.tool;

import com.dsh.application.ArtifactStore;
import com.dsh.common.Digest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** S06: disposable diagnostic source is registered outside the source tree before execution.
 * EXECUTE approval is consumed by the existing Loop before this method; command semantics
 * (repeat guard, deadline, exit/timeout, capture limits) stay owned by RunCommandTool.
 */
@Component
public class RunProbeTool implements Tool {
    static final int MAX_SOURCE_BYTES = 16 * 1024;
    private final RunCommandTool commands;
    private final ArtifactStore artifacts;

    public RunProbeTool(RunCommandTool commands, ArtifactStore artifacts) {
        this.commands = commands;
        this.artifacts = artifacts;
    }
    @Override public String name() { return "run_probe"; }

    @Override public ToolOutcome execute(JsonNode args, ToolExecutionContext ctx) throws Exception {
        String fileName = args.path("fileName").asText();
        String content = args.path("content").asText();
        String command = args.path("command").asText();
        byte[] source = content.getBytes(StandardCharsets.UTF_8);
        // Validate defensively as well as at the Dispatcher schema boundary.
        if (!fileName.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}") || fileName.contains(".."))
            throw new ToolException("INVALID_PROBE_NAME", "探针名称必须是安全文件名，不允许路径或连续点");
        if (source.length == 0 || source.length > MAX_SOURCE_BYTES || content.indexOf('\0') >= 0)
            throw new ToolException("PROBE_SOURCE_LIMIT", "探针源码必须是非空 UTF-8 文本，最多 16384 字节且不含空字节");
        if (command.isBlank() || command.length() > 4096 || command.indexOf('\0') >= 0)
            throw new ToolException("INVALID_PROBE_COMMAND", "探针命令必须是非空文本，最多 4096 字符且不含空字节");
        if (!ctx.runId().equals(ctx.lease().runId()))
            throw new ToolException("PROBE_SCOPE_MISMATCH", "探针只能登记到当前 Run 工作区");
        if ("local".equals(ctx.sandbox().mode()) && System.getProperty("os.name", "").toLowerCase().contains("win"))
            return ToolOutcome.failed("PROBE_PLATFORM_UNSUPPORTED", "run_probe 需要 Linux 沙箱；Windows local 模式不支持", name());

        String wrapped = shellCommand(fileName, source, command);
        if (wrapped.length() > 28000)
            throw new ToolException("PROBE_COMMAND_LIMIT", "编码后探针执行参数超过 28000 字符，请缩短源码或命令");
        ObjectNode registration = ctx.objectMapper().createObjectNode();
        registration.put("schemaVersion", "1.0");
        registration.put("runId", ctx.runId());
        registration.put("callId", ctx.callId());
        registration.put("fileName", fileName);
        registration.put("sourceSha256", Digest.sha256Hex(source));
        registration.put("content", content);
        registration.put("command", command);
        registration.put("createdAt", Instant.now().toString());
        registration.put("verificationStatus", "UNKNOWN");
        var artifact = artifacts.save(ctx.runId(), ctx.projectId(), "probe-" + fileName + ".json",
                "application/json", ctx.objectMapper().writeValueAsBytes(registration), true);
        ObjectNode execution = args.deepCopy();
        execution.remove("fileName"); execution.remove("content");
        execution.put("command", wrapped);
        ToolOutcome result = commands.execute(execution, ctx);
        ObjectNode metadata = result.structuredContent().isObject()
                ? ((ObjectNode) result.structuredContent()).deepCopy() : ctx.objectMapper().createObjectNode();
        metadata.put("probeSourceArtifactId", artifact.getId());
        metadata.put("sourceSha256", Digest.sha256Hex(source));
        metadata.put("verificationStatus", "UNKNOWN");
        metadata.put("sourcePlacement", "SANDBOX_TEMP");
        return new ToolOutcome(result.status(), result.errorCode(), result.summary(), result.text(),
                metadata, result.truncated(), result.durationMs(), result.exitCode(), result.isError(), name());
    }

    static String shellCommand(String fileName, byte[] source, String command) {
        String encoded = Base64.getEncoder().encodeToString(source);
        // Fixed shell syntax; fileName is validated, source is data. User command remains
        // an approved arbitrary command, not a read-only action. Workspace writes remain possible.
        return "set -eu\nDSH_WORKSPACE=\"$PWD\"; export DSH_WORKSPACE\n"
                + "probe_dir=$(mktemp -d /tmp/dsh-probe.XXXXXXXX)\n"
                + "trap 'rm -rf -- \"$probe_dir\"' EXIT\n"
                + "DSH_PROBE_FILE=\"$probe_dir/" + fileName + "\"; export DSH_PROBE_FILE\n"
                + "printf '%s' '" + encoded + "' | base64 -d > \"$DSH_PROBE_FILE\"\n"
                + "cd \"$probe_dir\"\n" + com.dsh.sandbox.StrictShellCommand.wrap(command) + "\n";
    }
}
