package com.dsh.agent.tool;

import com.dsh.common.IdGen;
import com.dsh.common.Digest;
import com.dsh.sandbox.CommandOutcome;
import com.dsh.sandbox.CommandSpec;
import com.dsh.sandbox.SandboxRunner;
import com.dsh.workspace.SafePaths;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * run_command（内部接口契约 §5/§15.1）：自由文本命令只在沙箱执行；
 * workdir 限定任务副本内；timeout 缺省 60s、上限 600s 且不得超过 Run 剩余截止时间。
 * 超时/进程丢失不伪造退出码；真实退出码非 0 返回 FAILED 供模型修正。
 */
@Component
public class RunCommandTool implements Tool {

    /**
     * 无网沙箱里网络失败的典型输出（DNS/TCP 层）。命中后给模型离线提示，
     * 避免它在 pip/curl 下载上空转多次。
     */
    private static final List<String> NETWORK_ERROR_MARKERS = List.of(
            "could not resolve host",
            "temporary failure in name resolution",
            "name or service not known",
            "failed to establish a new connection",
            "could not establish connection",
            "network is unreachable",
            "no route to host",
            "connection timed out",
            "connection timeout",
            "getaddrinfo failed",
            "nodename nor servname provided",
            "failed to connect to",
            "max retries exceeded with url",
            "warning: retrying");

    private final RunCommandRegistry commandRegistry;

    public RunCommandTool(RunCommandRegistry commandRegistry) {
        this.commandRegistry = commandRegistry;
    }

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public ToolOutcome execute(JsonNode args, ToolExecutionContext ctx) throws Exception {
        String command = args.path("command").asText();
        Path workdir = null;
        if (args.hasNonNull("workdir")) {
            workdir = SafePaths.resolve(ctx.lease().dir(), args.path("workdir").asText());
            if (!Files.isDirectory(workdir)) {
                throw new ToolException("WORKDIR_NOT_FOUND",
                        "workdir 不是副本内存在的目录: " + args.path("workdir").asText());
            }
        }
        boolean allowRepeated = args.path("allowRepeated").asBoolean(false);

        int timeout = args.path("timeoutSeconds").asInt(ctx.sandboxConfig().defaultTimeoutSeconds());
        timeout = Math.min(timeout, ctx.sandboxConfig().maxTimeoutSeconds());
        if (ctx.deadline() != null) {
            long remaining = Duration.between(Instant.now(), ctx.deadline()).getSeconds();
            timeout = (int) Math.min(timeout, Math.max(1, remaining));
        }

        Path effectiveDir = workdir != null ? workdir : ctx.lease().dir();

        // 无变更重复命令防护：源码未改时连跑同一条重命令没有新信息（真实负载空转主因之一）
        if (!allowRepeated) {
            String digest = safeSourceDigest(ctx.lease().dir());
            if (!digest.isEmpty()) {
                String commandKey = command.trim() + "\u0000"
                        + effectiveDir.toAbsolutePath().normalize();
                String guidance = commandRegistry.check(ctx.runId(), commandKey, digest);
                if (guidance != null) {
                    ObjectNode structured = ctx.objectMapper().createObjectNode();
                    structured.put("operationId", IdGen.next("op_"));
                    structured.put("sandbox", ctx.sandbox().mode());
                    structured.put("termination", "SKIPPED_REPEATED");
                    structured.put("blocked", true);
                    return new ToolOutcome("FAILED", "REPEATED_COMMAND_NO_CHANGE",
                            guidance, guidance, structured, false, 0, null, true, name());
                }
            }
        }

        String operationId = IdGen.next("op_");
        SandboxRunner runner = ctx.sandbox();
        CommandOutcome result = runner.execute(
                new CommandSpec(operationId, command, workdir, timeout), ctx.lease());

        ObjectNode structured = ctx.objectMapper().createObjectNode();
        structured.put("operationId", operationId);
        structured.put("commandDigest", Digest.sha256Hex(command.trim() + "\u0000"
                + SafePaths.relativeUnix(ctx.lease().dir(), effectiveDir)));
        structured.put("sandbox", runner.mode());
        structured.put("termination", result.termination().name());
        structured.put("cleanupConfirmed", result.cleanupConfirmed());
        structured.put("durationMs", result.durationMs());
        if (result.exitCode() != null) {
            structured.put("exitCode", result.exitCode());
        }
        // A3：采集被截断时如实带出规模，供上层区分「采集截断」与「视图截断」，
        // 避免在产物其实不完整时仍告诉模型「完整输出已另存产物」。
        if (result.truncated()) {
            structured.put("outputTruncated", true);
            structured.put("outputTotalBytes", result.totalBytes());
            structured.put("outputDroppedBytes", result.droppedBytes());
        }

        String stdout = nullToEmpty(result.stdout());
        String stderr = nullToEmpty(result.stderr());
        String text = stderr.isBlank() ? stdout
                : stdout + (stdout.endsWith("\n") ? "" : "\n") + "[stderr]\n" + stderr;

        ToolOutcome outcome;
        switch (result.termination()) {
            case COMPLETED -> {
                if (result.exitCode() != null && result.exitCode() == 0) {
                    recordCommand(ctx, command, effectiveDir, 0);
                    outcome = new ToolOutcome("SUCCEEDED", null,
                            "命令退出码 0（" + runner.mode() + ", " + result.durationMs() + "ms）",
                            text, structured, result.truncated(), result.durationMs(), 0, false, name());
                } else {
                    int exit = result.exitCode() == null ? -1 : result.exitCode();
                    text = withOfflineHint(text, runner.mode());
                    recordCommand(ctx, command, effectiveDir, exit);
                    outcome = new ToolOutcome("FAILED", "COMMAND_FAILED",
                            "命令退出码 " + result.exitCode() + "（" + runner.mode() + "）",
                            text, structured, result.truncated(), result.durationMs(), exit, true, name());
                }
            }
            case TIMEOUT -> outcome = new ToolOutcome("FAILED", "TIMEOUT",
                    "命令超过 " + timeout + "s 被终止；cleanupConfirmed=" + result.cleanupConfirmed(),
                    text, structured, result.truncated(), result.durationMs(), null, true, name());
            case LAUNCH_FAILED -> outcome = new ToolOutcome("FAILED", "SANDBOX_UNAVAILABLE",
                    "沙箱无法启动命令（" + runner.mode() + "）: " + abbreviate(stderr, 400),
                    text, structured, result.truncated(), result.durationMs(), null, true, name());
            default -> throw new IllegalStateException("未知终止状态: " + result.termination());
        }
        return outcome;
    }

    private void recordCommand(ToolExecutionContext ctx, String command, Path effectiveDir, int exitCode) {
        String digest = safeSourceDigest(ctx.lease().dir());
        if (digest.isEmpty()) {
            return;
        }
        String commandKey = command.trim() + "\u0000" + effectiveDir.toAbsolutePath().normalize();
        commandRegistry.record(ctx.runId(), commandKey, digest, exitCode);
    }

    private static String safeSourceDigest(Path workspaceDir) {
        try {
            return WorkspaceManager.digestOf(workspaceDir);
        } catch (Exception e) {
            return "";
        }
    }

    /** Docker 沙箱默认 --network none：命中网络错误特征时追加离线引导，阻止模型继续尝试下载。 */
    private static String withOfflineHint(String text, String sandboxMode) {
        if (!"docker".equals(sandboxMode) || text == null) {
            return text;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : NETWORK_ERROR_MARKERS) {
            if (lower.contains(marker)) {
                return text + "\n[环境提示] 本次失败是网络访问错误：沙箱默认无外网（--network none），"
                        + "pip / npm / curl / wget / git clone 等联网操作均不可用。"
                        + "请改用工作区内已安装的依赖与本地文件，不要重试联网下载。\n";
            }
        }
        return text;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String abbreviate(String text, int max) {
        return text == null ? "" : text.length() <= max ? text : text.substring(0, max);
    }
}
