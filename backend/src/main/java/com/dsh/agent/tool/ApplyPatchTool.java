package com.dsh.agent.tool;

import com.dsh.common.Digest;
import com.dsh.workspace.SafePaths;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * apply_patch（内部接口契约 §5/§6，TOOL-01）：
 * <ol>
 *   <li>解析 unified diff，目标路径全部通过副本边界校验（绝对路径/穿越/符号链接拒绝）；</li>
 *   <li>写入前用 expectedFileHashes 对<b>所有</b>目标文件做 SHA-256 预检：
 *       新建文件期望 null，已存在文件期望当前哈希；任一不符返回 HASH_CONFLICT 且不落一笔；</li>
 *   <li>全部补丁在内存准备成功后统一写入；写失败用原文件字节回滚，
 *       无法确认回滚时返回 UNKNOWN 而非伪装成功。</li>
 * </ol>
 */
@Component
public class ApplyPatchTool implements Tool {

    /** Spring 注入构造器：保留旧构造签名；P04 由持久化工具结果重建进展，不保存永久写标志。 */
    @Autowired
    public ApplyPatchTool(RunReadRegistry readRegistry) {
    }

    /** 测试用无参构造器：与历史构造签名兼容。 */
    public ApplyPatchTool() {
        this(null);
    }

    @Override
    public String name() {
        return "apply_patch";
    }

    /** 统一 diff 的格式要求；解析失败时回给模型，避免模型在未知格式上反复空转。 */
    private static final String FORMAT_REQUIREMENT =
            "本工具只接受标准 unified diff：每个文件段以 `--- a/<路径>` 与 `+++ b/<路径>` 开头，"
          + "紧接至少一个 `@@ -<起行>[,<行数>] +<起行>[,<行数>] @@` hunk；"
          + "hunk 内以单个空格开头表示上下文行，`-` 表示删除、`+` 表示新增。"
          + "**不支持** `*** Begin Patch` / `*** Update File` / `*** End Patch` 这类标记格式。";

    /**
     * 解析不出任何文件段时的可执行指引：先识别常见的标记格式给出针对性提示，
     * 再补上格式要求与修正办法。包可见以便单元测试。
     */
    static String patchFormatGuidance(String patchText) {
        String head = patchText == null ? "" : patchText;
        if (head.contains("*** Begin Patch") || head.contains("*** Update File")
                || head.contains("*** End Patch")) {
            return "检测到 `*** Begin Patch` 标记格式，本工具不支持。"
                    + "请把同一改动改写成标准 unified diff 后重试。" + FORMAT_REQUIREMENT;
        }
        return "补丁中没有任何文件段。请提交标准 unified diff 文本（不要只描述改动）。"
                + FORMAT_REQUIREMENT;
    }

    @Override
    public ToolOutcome execute(JsonNode args, ToolExecutionContext ctx) throws Exception {
        WorkspaceLease lease = ctx.lease();
        String patchText = args.path("patch").asText();
        JsonNode expected = args.path("expectedFileHashes");

        List<PatchFile> patches = UnifiedPatch.parse(patchText);
        if (patches.isEmpty()) {
            // 2026-10-06：模型连续两次提交 `*** Begin Patch` 标记格式都被这句笼统报错挡回，
            // 白烧两轮（`ext-sphinx-doc__sphinx-10466` seq 125/127）。改为直接说清格式与修正办法。
            throw new ToolException("INVALID_PATCH", patchFormatGuidance(patchText));
        }

        // 1. 路径边界校验（每一段都在副本内；不支持任意绝对路径）
        for (PatchFile pf : patches) {
            SafePaths.assertRelativeInside(pf.relativePath);
            SafePaths.resolve(lease.dir(), pf.relativePath());
        }

        // 2. expectedFileHashes 必须恰好覆盖全部目标文件
        Map<String, String> expectedMap = new LinkedHashMap<>();
        expected.fields().forEachRemaining(e -> expectedMap.put(e.getKey(),
                e.getValue().isNull() ? null : e.getValue().asText()));
        List<String> conflicts = new ArrayList<>();
        for (PatchFile pf : patches) {
            if (!expectedMap.containsKey(pf.relativePath())) {
                throw new ToolException("INVALID_ARGUMENTS",
                        "expectedFileHashes 未覆盖目标文件: " + pf.relativePath());
            }
        }
        for (String key : expectedMap.keySet()) {
            if (patches.stream().noneMatch(p -> p.relativePath().equals(key))) {
                throw new ToolException("INVALID_ARGUMENTS",
                        "expectedFileHashes 包含补丁未涉及的文件: " + key);
            }
        }

        // 3. 读当前内容并校验哈希（全部通过才允许写）
        Map<String, List<String>> originalLines = new LinkedHashMap<>();
        Map<String, byte[]> originalBytes = new LinkedHashMap<>();
        Map<String, Boolean> originallyExisted = new LinkedHashMap<>();
        for (PatchFile pf : patches) {
            Path target = SafePaths.resolve(lease.dir(), pf.relativePath());
            boolean exists = Files.isRegularFile(target);
            originallyExisted.put(pf.relativePath(), exists);
            String currentHash = null;
            if (exists) {
                byte[] bytes = Files.readAllBytes(target);
                originalBytes.put(pf.relativePath(), bytes);
                originalLines.put(pf.relativePath(), splitLines(new String(bytes, StandardCharsets.UTF_8)));
                currentHash = Digest.sha256Hex(bytes);
            }
            String expectedHash = expectedMap.get(pf.relativePath());
            if (expectedHash == null) {
                if (exists) {
                    conflicts.add(pf.relativePath() + " 期望为新文件(null)，但文件已存在 sha256=" + currentHash);
                }
            } else if (!exists) {
                conflicts.add(pf.relativePath() + " 期望已存在文件哈希，但文件不存在");
            } else if (!expectedHash.equals(currentHash)) {
                conflicts.add(pf.relativePath() + " 哈希冲突 expected=" + expectedHash
                        + " actual=" + currentHash);
            }
        }
        if (!conflicts.isEmpty()) {
            throw new ToolException("HASH_CONFLICT", "文件哈希预检失败，未执行任何写入: "
                    + String.join("; ", conflicts.size() > 3 ? conflicts.subList(0, 3) : conflicts)
                    + (conflicts.size() > 3 ? "（共 " + conflicts.size() + " 个冲突）" : ""));
        }

        // 4. 内存中应用全部补丁（应用失败同样没有任何写入）
        Map<String, List<String>> resultLines = new LinkedHashMap<>();
        for (PatchFile pf : patches) {
            List<String> oldLines = originalLines.getOrDefault(pf.relativePath(), List.of());
            resultLines.put(pf.relativePath(), pf.applyTo(oldLines));
        }

        // 5. 统一写入；失败逐文件回滚并复核
        List<String> written = new ArrayList<>();
        try {
            for (PatchFile pf : patches) {
                Path target = SafePaths.resolve(lease.dir(), pf.relativePath());
                if (pf.deleted) {
                    Files.deleteIfExists(target);
                    written.add(pf.relativePath());
                    continue;
                }
                Files.createDirectories(target.getParent());
                String content = joinLines(resultLines.get(pf.relativePath()));
                Files.write(target, content.getBytes(StandardCharsets.UTF_8));
                written.add(pf.relativePath());
            }
        } catch (IOException | RuntimeException writeError) {
            boolean rollbackConfirmed = rollback(patches, originalBytes, originallyExisted, lease);
            if (!rollbackConfirmed) {
                return new ToolOutcome("UNKNOWN", "WORKSPACE_STATE_UNKNOWN",
                        "补丁写入失败且无法确认回滚: " + writeError.getMessage(),
                        "写入失败且回滚未确认，工作区需要隔离核对",
                        ctx.objectMapper().createObjectNode(), false, 0, null, true, name());
            }
            throw new ToolException("PATCH_WRITE_FAILED", "补丁写入失败，已回滚到预检状态: "
                    + writeError.getMessage());
        }

        ArrayNode applied = ctx.objectMapper().createArrayNode();
        written.forEach(applied::add);
        ObjectNode structured = ctx.objectMapper().createObjectNode();
        structured.set("applied", applied);
        boolean changed = patches.stream().anyMatch(pf -> pf.deleted
                ? originallyExisted.get(pf.relativePath())
                : !originallyExisted.get(pf.relativePath()) || !java.util.Arrays.equals(
                    originalBytes.get(pf.relativePath()), joinLines(resultLines.get(pf.relativePath())).getBytes(StandardCharsets.UTF_8)));
        structured.put("changed", changed);
        structured.put("workspaceDigest", WorkspaceManager.digestOf(lease.dir()));
        return ToolOutcome.succeeded("补丁已应用到 " + written.size() + " 个文件",
                "applied: " + String.join(", ", written), structured, name());
    }

    private boolean rollback(List<PatchFile> patches, Map<String, byte[]> originalBytes,
                             Map<String, Boolean> existed, WorkspaceLease lease) {
        try {
            for (PatchFile pf : patches) {
                Path target = SafePaths.resolve(lease.dir(), pf.relativePath());
                if (existed.get(pf.relativePath()) != null && existed.get(pf.relativePath())) {
                    Files.write(target, originalBytes.get(pf.relativePath()));
                } else {
                    Files.deleteIfExists(target);
                }
            }
            for (PatchFile pf : patches) {
                Path target = SafePaths.resolve(lease.dir(), pf.relativePath());
                boolean exists = Files.isRegularFile(target);
                if (existed.get(pf.relativePath()) != null && existed.get(pf.relativePath())) {
                    if (!exists || !Digest.sha256Hex(Files.readAllBytes(target))
                            .equals(Digest.sha256Hex(originalBytes.get(pf.relativePath())))) {
                        return false;
                    }
                } else if (exists) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static List<String> splitLines(String text) {
        String t = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        if (t.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : t.split("\n", -1)) {
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        return lines;
    }

    /** 文本文件按 POSIX 习惯统一写回结尾换行；补丁语义由内容行决定。 */
    private static String joinLines(List<String> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines) + "\n";
    }

    /** 一个文件段（一个 --- / +++ 对及其 hunk）。 */
    record PatchFile(String relativePath, boolean deleted, List<Hunk> hunks) {

        PatchFile(String relativePath, boolean deleted) {
            this(relativePath, deleted, new ArrayList<>());
        }

        List<String> applyTo(List<String> oldLines) {
            List<String> result = new ArrayList<>(oldLines);
            // 从后往前应用，避免行号位移影响后续 hunk
            List<Hunk> ordered = new ArrayList<>(hunks);
            ordered.sort((a, b) -> Integer.compare(b.oldStart, a.oldStart));
            for (Hunk hunk : ordered) {
                hunk.apply(result);
            }
            return result;
        }
    }

    static final class Hunk {
        final int oldStart; // 1 起，0 表示新文件
        final List<String> ops = new ArrayList<>(); // 每项首字符 ' '/'+'/'-'，其余为内容

        Hunk(int oldStart) {
            this.oldStart = oldStart;
        }

        void apply(List<String> lines) {
            List<String> expected = new ArrayList<>();
            List<String> replacement = new ArrayList<>();
            for (String op : ops) {
                String content = op.length() > 1 ? op.substring(1) : "";
                char kind = op.charAt(0);
                if (kind == ' ' || kind == '-') {
                    expected.add(content);
                }
                if (kind == ' ' || kind == '+') {
                    replacement.add(content);
                }
            }
            int index = findSequence(lines, expected, Math.max(0, oldStart - 1));
            if (index < 0) {
                throw new IllegalStateException("补丁上下文不匹配（hunk 起点 " + oldStart + "）");
            }
            for (int i = 0; i < expected.size(); i++) {
                lines.remove(index);
            }
            lines.addAll(index, replacement);
        }

        /** 从期望位置附近开始找完全匹配序列；找不到再全文搜索（容忍行号漂移）。 */
        private static int findSequence(List<String> lines, List<String> expected, int preferred) {
            if (expected.isEmpty()) {
                return Math.min(preferred, lines.size());
            }
            if (expected.size() > lines.size()) {
                return -1;
            }
            int[] starts = new int[lines.size() - expected.size() + 1];
            if (starts.length == 0) {
                return -1;
            }
            // 优先期望位置，再按距离扩散
            List<Integer> order = new ArrayList<>();
            for (int d = 0; d < starts.length + Math.abs(preferred); d++) {
                int below = preferred - d;
                if (below >= 0 && below < starts.length && !order.contains(below)) {
                    order.add(below);
                }
                int above = preferred + d;
                if (above >= 0 && above < starts.length && !order.contains(above)) {
                    order.add(above);
                }
            }
            for (int s : order) {
                boolean match = true;
                for (int i = 0; i < expected.size(); i++) {
                    if (!lines.get(s + i).equals(expected.get(i))) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return s;
                }
            }
            return -1;
        }
    }

    /** 最小 unified diff 解析器：支持多文件、新建、删除、标准 hunk；不支持二进制补丁。 */
    static final class UnifiedPatch {

        private static final Pattern HUNK_HEADER = Pattern.compile(
                "^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@(?:.*)?$");

        private UnifiedPatch() {
        }

        static List<PatchFile> parse(String patch) throws ToolException {
            List<String> raw = splitLines(patch);
            List<PatchFile> files = new ArrayList<>();
            int i = 0;
            while (i < raw.size()) {
                String line = raw.get(i);
                if (!line.startsWith("--- ")) {
                    i++;
                    continue;
                }
                String from = stripHeader(line.substring(4));
                if (i + 1 >= raw.size() || !raw.get(i + 1).startsWith("+++ ")) {
                    throw new IllegalArgumentException("缺少 +++ 头（--- " + from + "）");
                }
                String to = stripHeader(raw.get(i + 1).substring(4));
                i += 2;

                boolean deleted = "/dev/null".equals(to) || to.isEmpty();
                boolean newFile = "/dev/null".equals(from) || from.isEmpty();
                String target = deleted ? from : to;
                if (target.startsWith("a/") || target.startsWith("b/")) {
                    target = target.substring(2);
                }
                if (target.isBlank()) {
                    throw new IllegalArgumentException("补丁文件路径为空");
                }
                PatchFile pf = new PatchFile(target, deleted);
                while (i < raw.size() && raw.get(i).startsWith("@@")) {
                    String header = raw.get(i);
                    Matcher match = HUNK_HEADER.matcher(header);
                    int oldStart;
                    if (match.matches()) {
                        try {
                            oldStart = Integer.parseInt(match.group(1));
                        } catch (NumberFormatException e) {
                            throw new ToolException("INVALID_PATCH", "hunk 行号无效: " + header, e);
                        }
                    } else if (header.equals("@@") || (header.startsWith("@@ ")
                            && !header.startsWith("@@ -"))) {
                        // 常见的无行号上下文 hunk；仍由下面的内容匹配确认位置。
                        oldStart = 1;
                    } else {
                        throw new ToolException("INVALID_PATCH", "hunk 头无效: " + header);
                    }
                    Hunk hunk = new Hunk(newFile ? 0 : oldStart);
                    i++;
                    while (i < raw.size()) {
                        String h = raw.get(i);
                        if (h.startsWith("@@") || h.startsWith("--- ")) {
                            break;
                        }
                        if (h.startsWith("\\ No newline at end of file") || h.startsWith("\\")
                                || h.startsWith("diff --git") || h.startsWith("index ")) {
                            i++;
                            continue;
                        }
                        if (!h.isEmpty() && (h.charAt(0) == ' ' || h.charAt(0) == '+' || h.charAt(0) == '-')) {
                            hunk.ops.add(h);
                        }
                        i++;
                    }
                    pf.hunks.add(hunk);
                }
                if (pf.hunks.isEmpty()) {
                    throw new ToolException("INVALID_PATCH", "补丁文件段缺少 hunk: " + target
                            + "。每个文件段至少要有一个 `@@ -l,s +l,s @@` hunk。" + FORMAT_REQUIREMENT);
                }
                files.add(pf);
            }
            return files;
        }

        private static String stripHeader(String value) {
            String v = value.trim();
            if (v.startsWith("a/") || v.startsWith("b/")) {
                return v;
            }
            return v;
        }
    }
}
