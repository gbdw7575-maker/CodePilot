package com.dsh.agent.tool;

import com.dsh.common.Digest;
import com.dsh.workspace.SafePaths;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * write_file 基础写工具（内部定义，不进契约 x-tools）：整文件写入/新建，
 * 供模型在无需构造 unified diff 时创建或全量改写文件。
 *
 * <p>并发保护与 apply_patch 同口径（TOOL-01）：目标已存在时必须提供
 * expectedFileHash=当前 SHA-256 才允许覆盖（哈希不符或缺失即 HASH_CONFLICT，
 * 不落任何写入）；文件不存在时若提供了期望哈希同样拒绝（疑似过期意图）。
 * 路径经副本边界校验（绝对路径/穿越/符号链接拒绝）。</p>
 */
@Component
public class WriteFileTool implements Tool {

    /** 写入重试次数上限：只针对 Windows 上一闪而过的 {@link AccessDeniedException}。 */
    static final int DEFAULT_WRITE_ATTEMPTS = 3;
    /** 重试退避基数（毫秒），第 i 次重试前睡 {@code BACKOFF_MS * i}。 */
    static final long BACKOFF_MS = 50L;

    private final int writeAttempts;

    /**
     * Spring 注入构造器：保留旧构造签名；P04 由持久化工具结果重建进展，不保存永久写标志。
     *
     * <p>2026-10-06：Windows 宿主机上 {@code Files.write} 会偶发
     * {@link AccessDeniedException}（同一路径手工写入正常，属瞬时占用）。
     * 实测影响：`ext-sphinx-doc__sphinx-10466` 的 write_file 被此异常挡下，
     * 加上此前 3 次 apply_patch 失败，整轮 65 个请求、1,558,857 token 后源码改动为 0
     * （官方判定 empty_patch）。故对这一类异常做有限重试。</p>
     */
    @Autowired
    public WriteFileTool(RunReadRegistry readRegistry,
                         @Value("${dsh.workspace.write-attempts:3}") int writeAttempts) {
        this.writeAttempts = Math.max(1, writeAttempts);
    }

    /** 测试用无参构造器：与历史构造签名兼容。 */
    public WriteFileTool() {
        this(null, DEFAULT_WRITE_ATTEMPTS);
    }

    /** 写入动作抽象：生产传 {@code Files::write}，单元测试可注入"先失败后成功"的替身。 */
    @FunctionalInterface
    interface FileWriter {
        void write(Path target, byte[] bytes) throws IOException;
    }

    static void writeWithRetry(Path target, byte[] bytes, int attempts) throws IOException {
        writeWithRetry(target, bytes, attempts, Files::write);
    }

    /**
     * 带重试的写入：只有 {@link AccessDeniedException} 才重试（它是瞬时的）；
     * 其它 {@link IOException} 原样抛出，避免把"路径不存在/权限真的不足"也拖成多次。
     *
     * <p>包可见以便单元测试直接构造失败场景。</p>
     */
    static void writeWithRetry(Path target, byte[] bytes, int attempts, FileWriter writer)
            throws IOException {
        int max = Math.max(1, attempts);
        AccessDeniedException last = null;
        for (int i = 1; i <= max; i++) {
            try {
                writer.write(target, bytes);
                return;
            } catch (AccessDeniedException denied) {
                last = denied;
                if (i == max) break;
                try {
                    Thread.sleep(BACKOFF_MS * i);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IOException("写入被拒绝（已尝试 " + max + " 次）: " + target
                + " —— " + (last == null ? "" : last.getClass().getSimpleName()), last);
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public ToolDefinition customDefinition() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode path = factory.objectNode();
        path.put("type", "string");
        path.put("description", "相对工作区副本的相对路径（正斜杠），如 src/main.rs");
        ObjectNode content = factory.objectNode();
        content.put("type", "string");
        content.put("description", "完整文件内容（UTF-8 文本）");
        ObjectNode expectedHash = factory.objectNode();
        expectedHash.put("type", "string");
        expectedHash.put("description", "覆盖已存在文件时必填：当前文件 SHA-256 十六进制值；新建文件省略或传 null");
        ObjectNode properties = factory.objectNode();
        properties.set("path", path);
        properties.set("content", content);
        properties.set("expectedFileHash", expectedHash);
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", factory.arrayNode().add("path").add("content"));
        schema.set("additionalProperties", factory.booleanNode(false));
        return new ToolDefinition(name(), "写入或新建一个文件（整文件覆盖）。覆盖已有文件前必须先用 read_file/list_files"
                + " 确认当前内容并在 expectedFileHash 提供其 SHA-256，防止覆盖用户修改。", "1.0.0", "FILE",
                "WORKSPACE_WRITE", schema);
    }

    @Override
    public ToolOutcome execute(JsonNode args, ToolExecutionContext ctx) throws Exception {
        WorkspaceLease lease = ctx.lease();
        String relativePath = args.path("path").asText();
        String content = args.path("content").asText();
        if (relativePath == null || relativePath.isBlank()) {
            throw new ToolException("INVALID_ARGUMENTS", "path 必填且不能为空白");
        }
        SafePaths.assertRelativeInside(relativePath);
        Path target = SafePaths.resolve(lease.dir(), relativePath);

        JsonNode hashArg = args.path("expectedFileHash");
        String expectedHash = hashArg.isMissingNode() || hashArg.isNull() ? null : hashArg.asText();
        if (expectedHash != null && expectedHash.isBlank()) {
            expectedHash = null;
        }

        boolean exists = Files.isRegularFile(target);
        if (exists) {
            byte[] bytes = Files.readAllBytes(target);
            String currentHash = Digest.sha256Hex(bytes);
            if (expectedHash == null) {
                throw new ToolException("HASH_CONFLICT",
                        "文件已存在，拒绝覆盖。请先 read_file 获取内容，并在 expectedFileHash 提供 sha256=" + currentHash);
            }
            if (!expectedHash.equalsIgnoreCase(currentHash)) {
                throw new ToolException("HASH_CONFLICT",
                        "文件哈希冲突 expected=" + expectedHash + " actual=" + currentHash + "，未执行任何写入");
            }
        } else if (expectedHash != null) {
            throw new ToolException("HASH_CONFLICT",
                    "提供了 expectedFileHash 但文件不存在（疑似过期意图），未执行任何写入");
        }

        byte[] newBytes = content.getBytes(StandardCharsets.UTF_8);
        boolean changed = !exists || !java.util.Arrays.equals(Files.readAllBytes(target), newBytes);
        Files.createDirectories(target.getParent());
        try {
            writeWithRetry(target, newBytes, writeAttempts);
        } catch (IOException io) {
            throw new ToolException("TOOL_ERROR",
                    "写入失败: " + io.getMessage() + "。这是环境层面的写入受阻，不是补丁语义问题；"
                            + "可稍后重试同一写入，或改对同一文件分段写入。");
        }

        ObjectNode structured = ctx.objectMapper().createObjectNode();
        structured.put("path", relativePath);
        structured.put("bytes", content.getBytes(StandardCharsets.UTF_8).length);
        structured.put("created", !exists);
        structured.put("changed", changed);
        structured.put("workspaceDigest", WorkspaceManager.digestOf(lease.dir()));
        return ToolOutcome.succeeded(
                (exists ? "已覆盖" : "已新建") + " " + relativePath,
                (exists ? "已覆盖" : "已新建") + " " + relativePath + "（" + content.length() + " 字符）",
                structured, name());
    }
}
