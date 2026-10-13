package com.dsh.agent.tool;

import com.dsh.application.ArtifactStore;
import com.dsh.api.ApiException;
import com.dsh.storage.ArtifactEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.io.UncheckedIOException;
import java.util.Locale;

/** 回读当前 Run 可公开的已保存文本；不接受宿主路径或跨 Run 隐式授权。 */
@Component
public class ReadArtifactTool implements Tool {
    private final ArtifactStore artifacts;
    private final com.dsh.storage.ProjectRepository projects;
    public ReadArtifactTool(ArtifactStore artifacts, com.dsh.storage.ProjectRepository projects) {
        this.artifacts = artifacts; this.projects = projects;
    }
    @Override public boolean requiresWorkspace() { return false; }
    @Override public String name() { return "read_artifact"; }

    @Override
    public ToolOutcome execute(JsonNode args, ToolExecutionContext ctx) {
        String id = args.path("artifactId").asText();
        long offset = args.path("byteOffset").asLong(0);
        int maxBytes = args.path("maxBytes").asInt(4096);
        if (!id.matches("art_[A-Za-z0-9_-]{1,124}") || offset < 0 || maxBytes < 4 || maxBytes > 16384) {
            return ToolOutcome.failed("INVALID_ARGUMENTS", "产物 ID 或分页参数非法", name());
        }
        var project = projects.findById(ctx.projectId()).orElse(null);
        if (project == null || !project.isTrusted()) {
            return ToolOutcome.denied("PROJECT_NOT_TRUSTED", "项目未信任，不能回读产物", name());
        }
        ArtifactEntity artifact;
        try { artifact = artifacts.get(id); }
        catch (ApiException e) { return ToolOutcome.failed("ARTIFACT_NOT_FOUND", "产物不存在", name()); }
        // 在读取内容和返回元数据之前校验归属；猜测到 ID 不构成授权。
        if (!ctx.projectId().equals(artifact.getProjectId()) || !ctx.runId().equals(artifact.getRunId())
                || !artifact.isDownloadable()) {
            return ToolOutcome.denied("PERMISSION_DENIED", "只允许回读当前 Run 的公开文本产物", name());
        }
        String media = artifact.getMediaType() == null ? "" : artifact.getMediaType().toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
        if (!(media.startsWith("text/") || media.equals("application/json") || media.endsWith("+json"))) {
            return ToolOutcome.failed("UNSUPPORTED_FILE", "该产物不支持 UTF-8 文本回读", name());
        }
        try {
            // 单页保持在当前模型视图阈值以内，游标不能跨过未回灌的正文。
            int effectiveBytes = Math.min(maxBytes, 4096);
            var page = artifacts.readTextPage(id, offset, effectiveBytes);
            if (page.sizeBytes() != artifact.getSizeBytes()) {
                return ToolOutcome.failed("ARTIFACT_CHANGED", "产物大小与封存元数据不符", name());
            }
            var meta = ctx.objectMapper().createObjectNode();
            meta.put("artifactId", id);
            meta.put("sha256", artifact.getSha256());
            meta.put("byteOffset", page.byteOffset());
            meta.put("nextOffset", page.nextOffset());
            meta.put("sizeBytes", page.sizeBytes());
            meta.put("hasMore", page.hasMore());
            meta.put("maxBytes", effectiveBytes);
            meta.put("requestedMaxBytes", maxBytes);
            meta.put("sha256Source", "sealed-metadata");
            String summary = "读取产物 " + id + " 字节 " + page.byteOffset() + ".." + page.nextOffset();
            if (page.hasMore()) summary += "；需要后续内容时用 nextOffset 继续调用 read_artifact";
            return ToolOutcome.succeeded(summary, page.text(), meta, name()).withExit(null, page.hasMore());
        } catch (IllegalArgumentException e) {
            return ToolOutcome.failed("INVALID_ARGUMENTS", "游标越界、不是字符边界或产物不是文本", name());
        } catch (UncheckedIOException | ApiException e) {
            return ToolOutcome.failed("ARTIFACT_READ_ERROR", "产物不可读取或不是有效 UTF-8 文本", name());
        }
    }
}
