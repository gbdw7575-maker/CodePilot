package com.dsh.agent.tool;

import com.dsh.sandbox.CommandOutcome;
import com.dsh.sandbox.CommandSpec;
import com.dsh.workspace.SafePaths;
import com.dsh.workspace.TextFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * git_diff：检查相对任务基线的修改，含新文件。
 * 已跟踪变更走 {@code git diff}；未跟踪新文件构造标准 new-file diff（不改暂存区）。
 */
@Component
public class GitDiffTool implements Tool {

    @Override
    public String name() {
        return "git_diff";
    }

    @Override
    public ToolOutcome execute(JsonNode args, ToolExecutionContext ctx) throws Exception {
        List<String> pathFilter = new ArrayList<>();
        JsonNode paths = args.path("paths");
        if (paths.isArray()) {
            for (JsonNode p : paths) {
                String rel = p.asText();
                SafePaths.assertRelativeInside(rel);
                SafePaths.resolve(ctx.lease().dir(), rel);
                pathFilter.add(rel);
            }
        }
        String pathspec = pathFilter.isEmpty() ? ""
                : " -- " + pathFilter.stream().map(GitSupport::quotePath).reduce((a, b) -> a + " " + b).orElse("");

        // 已跟踪/已暂存内容的修改与删除
        CommandOutcome diffResult = GitSupport.git(ctx, "diff --no-color" + pathspec, 30);
        if (diffResult.termination() != CommandSpec.Termination.COMPLETED || diffResult.exitCode() == null
                || diffResult.exitCode() != 0) {
            return GitSupport.gitFailure(name(), "git_diff", diffResult);
        }

        // 未跟踪新文件（porcelain ??）：手工构造 unified diff，包含新文件也可见
        CommandOutcome statusResult = GitSupport.git(ctx, "status --porcelain=v1 --untracked-files=all", 30);
        if (statusResult.termination() != CommandSpec.Termination.COMPLETED || statusResult.exitCode() == null
                || statusResult.exitCode() != 0) {
            return GitSupport.gitFailure(name(), "git_diff(status)", statusResult);
        }

        StringBuilder diff = new StringBuilder(diffResult.stdout() == null ? "" : diffResult.stdout());
        int newFiles = 0;
        for (String[] entry : GitSupport.parsePorcelain(
                statusResult.stdout() == null ? "" : statusResult.stdout())) {
            if (!"??".equals(entry[0])) {
                continue;
            }
            String rel = entry[1];
            if (!pathFilter.isEmpty() && !pathFilter.contains(rel)) {
                continue;
            }
            String newFileDiff = newFileDiff(ctx.lease().dir(), rel);
            if (newFileDiff != null) {
                if (!diff.isEmpty()) {
                    diff.append('\n');
                }
                diff.append(newFileDiff);
                newFiles++;
            }
        }

        String text = diff.toString();
        ObjectNode structured = ctx.objectMapper().createObjectNode();
        structured.put("empty", text.isBlank());
        structured.put("untrackedIncluded", newFiles);
        return ToolOutcome.succeeded(text.isBlank() ? "相对基线无差异" : "差异已生成（含 " + newFiles + " 个新文件）",
                text, structured, name());
    }

    /** 为未跟踪常规文本文件构造 git 风格 new-file diff；二进制/超大文件跳过。 */
    private static String newFileDiff(Path root, String relativeUnix) throws Exception {
        Path file = SafePaths.resolve(root, relativeUnix);
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > TextFiles.MAX_TEXT_BYTES || TextFiles.isProbablyBinary(bytes)) {
            return null;
        }
        List<String> lines = TextFiles.readLines(file);
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(relativeUnix).append(" b/").append(relativeUnix).append('\n');
        sb.append("new file mode 100644\n");
        sb.append("--- /dev/null\n");
        sb.append("+++ b/").append(relativeUnix).append('\n');
        sb.append("@@ -0,0 +1,").append(Math.max(1, lines.size())).append(" @@\n");
        for (String line : lines) {
            sb.append('+').append(line).append('\n');
        }
        return sb.toString();
    }
}
