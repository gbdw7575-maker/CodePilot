package com.dsh.agent.tool;

import com.dsh.common.Digest;
import com.dsh.workspace.SafePaths;
import com.dsh.workspace.TextFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** read_file：按行读取 UTF-8 文本（行号从 1 开始），返回完整文件内容哈希作为来源证据。 */
@Component
public class ReadFileTool implements Tool {

    private final RunReadRegistry readRegistry;

    public ReadFileTool(RunReadRegistry readRegistry) {
        this.readRegistry = readRegistry;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public ToolOutcome execute(JsonNode args, ToolExecutionContext ctx) throws Exception {
        Path root = ctx.lease().dir();
        Path file = SafePaths.resolve(root, args.path("path").asText());
        String rel = SafePaths.relativeUnix(root, file);
        if (!Files.isRegularFile(file)) {
            throw new ToolException("FILE_NOT_FOUND", "文件不存在或不是常规文件: " + rel);
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > TextFiles.MAX_TEXT_BYTES) {
            throw new ToolException("FILE_TOO_LARGE", "文件超过文本读取上限: " + rel);
        }
        if (TextFiles.isProbablyBinary(bytes)) {
            throw new ToolException("UNSUPPORTED_FILE", "疑似二进制文件，不按文本读取: " + rel);
        }

        List<String> lines = TextFiles.readLines(file);
        int total = lines.size();
        int start = args.path("startLine").asInt(1);
        int end = args.path("endLine").asInt(total);
        if (start < 1 || end < start) {
            throw new ToolException("INVALID_ARGUMENTS",
                    "行号非法：startLine>=1 且 endLine>=startLine（当前 " + start + "-" + end + "）");
        }
        boolean forceFull = args.path("forceFull").asBoolean(false);
        int from = Math.min(start - 1, total);
        int to = Math.min(end, total);
        int actualEnd = Math.max(start - 1, to);
        String sha = Digest.sha256Hex(bytes);

        // D-18：仅当本次确实请求了行内容时才参与去重判定（空区间维持原返回，不登记）。
        if (to > from) {
            RunReadRegistry.Decision decision =
                    readRegistry.resolve(ctx.runId(), rel, sha, start, actualEnd, forceFull);
            if (decision.deduplicated()) {
                return referenceView(ctx, rel, start, actualEnd, total, sha, decision);
            }
            String content = String.join("\n", lines.subList(from, to));
            ObjectNode structured = baseMeta(ctx, rel, start, actualEnd, total, sha);
            structured.put("alreadyRead", false);
            structured.put("readCount", decision.readCount());
            return ToolOutcome.succeeded("读取 " + rel + " 第 " + start + "-" + actualEnd
                    + " 行（共 " + total + " 行）", content, structured, name());
        }

        String content = String.join("\n", lines.subList(from, to));
        ObjectNode structured = baseMeta(ctx, rel, start, actualEnd, total, sha);
        return ToolOutcome.succeeded("读取 " + rel + " 第 " + start + "-" + actualEnd
                + " 行（共 " + total + " 行）", content, structured, name());
    }

    /**
     * D-18 重复读取引用视图：文件字节未变且请求行区间已在本 Run 提供，不重复正文，
     * 但明确告知模型在上下文被压缩后可用 forceFull=true 重新取正文。
     */
    private ToolOutcome referenceView(ToolExecutionContext ctx, String rel, int start, int end,
                                      int total, String sha, RunReadRegistry.Decision decision) {
        ObjectNode structured = baseMeta(ctx, rel, start, end, total, sha);
        structured.put("alreadyRead", true);
        structured.put("readCount", decision.readCount());
        structured.put("forceFullHint", "内容未变且本 Run 已读过；若当前上下文已看不到正文，"
                + "重新调用 read_file 并设置 forceFull=true 即可再次获取");
        ArrayNode ranges = structured.putArray("coveredRanges");
        for (RunReadRegistry.CoveredRange r : decision.coveredRanges()) {
            ObjectNode node = ranges.addObject();
            node.put("startLine", r.startLine());
            node.put("endLine", r.endLine());
        }
        String text = "文件 `" + rel + "` 自上次读取后未变化（sha256:" + sha.substring(0, 12)
                + "…），第 " + start + "-" + end + " 行已在本 Run 提供（累计读取 "
                + decision.readCount() + " 次），为节省上下文此处不重复正文。\n"
                + "若这些内容已不在你当前可见的上下文中（较早历史可能已被压缩），或确实需要再次查看，"
                + "请重新调用 read_file 并设置 forceFull=true。";
        return ToolOutcome.succeeded(rel + " 未变化，第 " + start + "-" + end
                + " 行本 Run 已读取，按引用返回（不重复正文；需要正文用 forceFull=true）",
                text, structured, name());
    }

    private ObjectNode baseMeta(ToolExecutionContext ctx, String rel, int start, int end,
                                int total, String sha) {
        ObjectNode structured = ctx.objectMapper().createObjectNode();
        structured.put("path", rel);
        structured.put("startLine", start);
        structured.put("endLine", end);
        structured.put("totalLines", total);
        structured.put("sha256", sha);
        return structured;
    }
}
