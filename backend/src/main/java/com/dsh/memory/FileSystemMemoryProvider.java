package com.dsh.memory;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.application.DshProperties;
import com.dsh.common.Digest;
import com.dsh.common.IdGen;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.workspace.SafePaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 文件系统经验记忆（开发文档 §10.3）：
 * 经验层存于 &lt;项目根&gt;/.myagent/memory/&lt;projectId&gt;/ —— state.json 为唯一事实来源，
 * MEMORY.md 与主题文件（user/feedback/project/reference.md）为每次写入后再生成的可读视图。
 * 写入全程持锁：读取状态 → If-Match 版本校验 → 变更 → state.json 原子替换（ATOMIC_MOVE）。
 * 路径经 SafePaths 收敛在项目根内；Auto Memory 无任何写规则文件的路径（save 不接受路径参数）。
 * 主题文件按需读取的等价实现：{@link #entry(String, String)} 返回单条全文。
 */
@Component
public class FileSystemMemoryProvider implements MemoryProvider {

    private static final Logger log = LoggerFactory.getLogger(FileSystemMemoryProvider.class);
    static final Set<String> CATEGORIES = Set.of("user", "feedback", "project", "reference");
    private static final String[] TOPIC_FILES = {"user", "feedback", "project", "reference"};
    private static final int RULES_MAX_BYTES = 262144;
    private static final int INDEX_SUMMARY_CHARS = 160;

    private final ProjectRepository projectRepository;
    private final DshProperties properties;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public FileSystemMemoryProvider(ProjectRepository projectRepository,
                                    DshProperties properties,
                                    com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public MemoryView view(String projectId) {
        requireFeatureEnabled();
        State state = loadState(memoryDir(projectId));
        return toView(projectId, state);
    }

    @Override
    public MemoryIndex retrieve(String projectId) {
        requireFeatureEnabled();
        requireReadEnabled();
        Path dir = memoryDir(projectId);
        State state = loadState(dir);
        if (!state.enabled()) {
            throw ApiException.of(ErrorCode.MEMORY_DISABLED, "本项目已禁用 Auto Memory");
        }
        Bounded bounded = renderIndex(state, true);
        return new MemoryIndex(state.version(), state.enabled(), state.entries().size(),
                bounded.text(), bounded.truncated());
    }

    @Override
    public MemoryEntry entry(String projectId, String entryId) {
        requireFeatureEnabled();
        requireReadEnabled();
        State state = loadState(memoryDir(projectId));
        return state.entries().stream().filter(e -> e.id().equals(entryId)).findFirst()
                .orElseThrow(() -> ApiException.of(ErrorCode.RESOURCE_NOT_FOUND, "经验不存在: " + entryId));
    }

    @Override
    public MemoryEntry save(String projectId, String category, String content, String scope,
                            String sourceRef, long expectedVersion) {
        requireFeatureEnabled();
        requireWriteEnabled();
        if (category == null || !CATEGORIES.contains(category)) {
            throw ApiException.of(ErrorCode.INVALID_INPUT,
                    "category 必须是 user/feedback/project/reference 之一");
        }
        if (content == null || content.isBlank()) {
            throw ApiException.of(ErrorCode.INVALID_INPUT, "经验正文不能为空");
        }
        String trimmed = content.strip();
        if (trimmed.length() > 20000) {
            throw ApiException.of(ErrorCode.INPUT_TOO_LARGE, "单条经验正文超过 20000 字符上限");
        }
        Path dir = memoryDir(projectId);
        synchronized (this) {
            State state = loadState(dir);
            checkEnabled(state);
            requireVersion(state, expectedVersion);
            Instant now = Instant.now();
            MemoryEntry created = new MemoryEntry(IdGen.next("mem_"), category, trimmed,
                    scope == null || scope.isBlank() ? "未指定" : scope.strip(),
                    sourceRef == null || sourceRef.isBlank() ? "model" : sourceRef.strip(), now);
            State next = new State(state.version() + 1, state.enabled(), now,
                    append(state.entries(), created));
            persist(dir, next);
            return created;
        }
    }

    @Override
    public MemoryEntry updateEntry(String projectId, String entryId, String content, String scope,
                                   long expectedVersion) {
        requireFeatureEnabled();
        Path dir = memoryDir(projectId);
        synchronized (this) {
            State state = loadState(dir);
            requireVersion(state, expectedVersion);
            List<MemoryEntry> updated = new ArrayList<>();
            boolean found = false;
            Instant now = Instant.now();
            for (MemoryEntry e : state.entries()) {
                if (e.id().equals(entryId)) {
                    found = true;
                    String newContent = content == null || content.isBlank()
                            ? e.content() : content.strip();
                    updated.add(new MemoryEntry(e.id(), e.category(), newContent,
                            scope == null || scope.isBlank() ? e.scope() : scope.strip(),
                            e.sourceRef(), now));
                } else {
                    updated.add(e);
                }
            }
            if (!found) {
                throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND, "经验不存在: " + entryId);
            }
            State next = new State(state.version() + 1, state.enabled(), now, updated);
            persist(dir, next);
            return updated.stream().filter(e -> e.id().equals(entryId)).findFirst().orElseThrow();
        }
    }

    @Override
    public void deleteEntry(String projectId, String entryId, long expectedVersion) {
        requireFeatureEnabled();
        Path dir = memoryDir(projectId);
        synchronized (this) {
            State state = loadState(dir);
            requireVersion(state, expectedVersion);
            List<MemoryEntry> remaining = state.entries().stream()
                    .filter(e -> !e.id().equals(entryId)).toList();
            if (remaining.size() == state.entries().size()) {
                throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND, "经验不存在: " + entryId);
            }
            persist(dir, new State(state.version() + 1, state.enabled(), Instant.now(), remaining));
        }
    }

    @Override
    public MemoryView setEnabled(String projectId, boolean enabled, long expectedVersion) {
        requireFeatureEnabled();
        Path dir = memoryDir(projectId);
        synchronized (this) {
            State state = loadState(dir);
            requireVersion(state, expectedVersion);
            State next = new State(state.version() + 1, enabled, Instant.now(), state.entries());
            persist(dir, next);
            return toView(projectId, next);
        }
    }

    @Override
    public RulesView loadRules(String projectId) {
        Path root = projectRoot(projectId);
        String rulesFile = properties.memory().rulesFile() == null
                || properties.memory().rulesFile().isBlank()
                ? "AGENTS.md" : properties.memory().rulesFile();
        Path rules;
        try {
            rules = SafePaths.resolve(root, rulesFile);
        } catch (SafePaths.PathEscapeException e) {
            throw ApiException.of(ErrorCode.PATH_OUTSIDE_WORKSPACE, e.getMessage());
        }
        if (!Files.isRegularFile(rules)) {
            return new RulesView(projectId, rules.toString(), null, null, false, false, 0, "");
        }
        try {
            byte[] raw = Files.readAllBytes(rules);
            boolean truncated = raw.length > RULES_MAX_BYTES;
            String content = truncated ? decodeUtf8Bounded(raw, RULES_MAX_BYTES)
                    : new String(raw, StandardCharsets.UTF_8);
            String sha256 = Digest.sha256Hex(raw);
            return new RulesView(projectId, rules.toRealPath().toString(), sha256,
                    sha256.substring(0, 12), true, truncated, raw.length, content);
        } catch (IOException e) {
            throw ApiException.of(ErrorCode.INTERNAL_ERROR, "规则文件读取失败: " + rulesFile);
        }
    }

    // ---------- 内部：路径收敛与状态持久化 ----------

    private Path projectRoot(String projectId) {
        requireFeatureEnabled();
        if (projectId == null || !projectId.matches("[A-Za-z0-9._-]{1,64}") || projectId.contains("..")) {
            throw ApiException.of(ErrorCode.INVALID_INPUT, "非法 projectId");
        }
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> ApiException.of(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在: " + projectId));
        if (!project.isTrusted()) {
            throw ApiException.of(ErrorCode.PROJECT_NOT_TRUSTED, "项目未信任: " + projectId);
        }
        try {
            return Path.of(project.getRootPath()).toRealPath();
        } catch (IOException e) {
            throw ApiException.of(ErrorCode.RESOURCE_NOT_FOUND, "项目根目录不存在: " + project.getRootPath());
        }
    }

    private Path memoryDir(String projectId) {
        try {
            return SafePaths.resolve(projectRoot(projectId),
                    properties.memory().memoryDir() + "/" + projectId);
        } catch (SafePaths.PathEscapeException e) {
            throw ApiException.of(ErrorCode.PATH_OUTSIDE_WORKSPACE, e.getMessage());
        }
    }

    private void requireFeatureEnabled() {
        if (!properties.memory().enabled()) {
            throw ApiException.of(ErrorCode.MEMORY_DISABLED, "Auto Memory 功能未启用");
        }
    }

    private void requireReadEnabled() {
        if (!properties.memory().readEnabled()) {
            throw ApiException.of(ErrorCode.MEMORY_DISABLED, "Auto Memory 读取未启用");
        }
    }

    private void requireWriteEnabled() {
        if (!properties.memory().writeEnabled()) {
            throw ApiException.of(ErrorCode.MEMORY_DISABLED, "Auto Memory 写入未启用");
        }
    }

    private void checkEnabled(State state) {
        if (!state.enabled()) {
            throw ApiException.of(ErrorCode.MEMORY_DISABLED, "本项目已禁用 Auto Memory");
        }
    }

    private void requireVersion(State state, long expectedVersion) {
        if (expectedVersion != state.version()) {
            throw ApiException.of(ErrorCode.VERSION_CONFLICT,
                    "记忆版本已变化（当前 v" + state.version() + "，请求基于 v" + expectedVersion + "），请重新读取",
                    java.util.Map.of("currentVersion", state.version()));
        }
    }

    /** 加载状态；缺失时返回初始态（version=1、启用、空经验）。 */
    private State loadState(Path dir) {
        Path file = dir.resolve("state.json");
        if (!Files.isRegularFile(file)) {
            return new State(1, true, null, List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            long version = root.path("version").asLong(1);
            boolean enabled = root.path("enabled").asBoolean(true);
            JsonNode updatedAt = root.path("updatedAt");
            List<MemoryEntry> entries = new ArrayList<>();
            for (JsonNode n : root.path("entries")) {
                entries.add(new MemoryEntry(n.path("id").asText(), n.path("category").asText(),
                        n.path("content").asText(), n.path("scope").asText(""),
                        n.path("sourceRef").asText(""), parseInstant(n.path("updatedAt"))));
            }
            return new State(version, enabled, parseInstant(updatedAt), entries);
        } catch (IOException e) {
            throw ApiException.of(ErrorCode.INTERNAL_ERROR, "记忆状态读取失败: " + file);
        }
    }

    private Instant parseInstant(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : Instant.parse(node.asText());
    }

    /** 原子替换持久化：state.json 为事实来源，MEMORY.md 与主题文件为再生视图。 */
    private void persist(Path dir, State state) {
        try {
            Files.createDirectories(dir);
            ObjectNode root = objectMapper.createObjectNode();
            root.put("version", state.version());
            root.put("enabled", state.enabled());
            if (state.updatedAt() != null) {
                root.put("updatedAt", state.updatedAt().toString());
            }
            ArrayNode entries = root.putArray("entries");
            for (MemoryEntry e : state.entries()) {
                ObjectNode n = entries.addObject();
                n.put("id", e.id());
                n.put("category", e.category());
                n.put("content", e.content());
                n.put("scope", e.scope());
                n.put("sourceRef", e.sourceRef());
                if (e.updatedAt() != null) {
                    n.put("updatedAt", e.updatedAt().toString());
                }
            }
            atomicWrite(dir.resolve("state.json"), objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root));
            atomicWrite(dir.resolve("MEMORY.md"), renderIndex(state, false).text());
            for (String topic : TOPIC_FILES) {
                atomicWrite(dir.resolve(topic + ".md"), renderTopic(topic, state));
            }
        } catch (IOException e) {
            throw ApiException.of(ErrorCode.INTERNAL_ERROR, "记忆写入失败: " + e.getMessage());
        }
    }

    private void atomicWrite(Path file, String content) throws IOException {
        Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---------- 渲染：有界索引（整行边界）与主题文件 ----------

    private record Bounded(String text, boolean truncated) {
    }

    /** 有界索引渲染；bounded=true 时应用行数/字节上限（整行粒度，绝不切断 UTF-8 字符）。 */
    private Bounded renderIndex(State state, boolean bounded) {
        DshProperties.Memory memory = properties.memory();
        List<String> lines = new ArrayList<>();
        lines.add("# Auto Memory Index" + (state.version() > 0 ? " (v" + state.version() + ")" : ""));
        lines.add("");
        for (MemoryEntry e : state.entries()) {
            lines.add("- [" + e.id() + "] " + e.category() + " | " + e.scope()
                    + " | " + e.updatedAt() + " | " + flatten(e.content()));
        }
        if (!bounded) {
            return new Bounded(String.join("\n", lines) + "\n", false);
        }
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        boolean truncated = false;
        int emitted = 0;
        for (String line : lines) {
            if (emitted >= memory.indexMaxLines()) {
                truncated = true;
                break;
            }
            int lineBytes = (line + "\n").getBytes(StandardCharsets.UTF_8).length;
            if (bytes + lineBytes > memory.indexMaxBytes()) {
                truncated = true;
                break;
            }
            sb.append(line).append('\n');
            bytes += lineBytes;
            emitted++;
        }
        if (truncated) {
            sb.append("\n[索引已截断：超过 ").append(memory.indexMaxLines()).append(" 行 / ")
                    .append(memory.indexMaxBytes()).append(" 字节上限；主题文件按需读取]\n");
        }
        return new Bounded(sb.toString(), truncated);
    }

    private String renderTopic(String category, State state) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(category).append("\n\n");
        boolean any = false;
        for (MemoryEntry e : state.entries()) {
            if (!e.category().equals(category)) {
                continue;
            }
            any = true;
            sb.append("## [").append(e.id()).append("] 有效范围: ").append(e.scope())
                    .append(" · 更新: ").append(e.updatedAt()).append("\n\n");
            sb.append(e.content()).append("\n\n");
            sb.append("来源: ").append(e.sourceRef() == null ? "unknown" : e.sourceRef()).append("\n\n");
        }
        if (!any) {
            sb.append("（暂无 ").append(category).append(" 类经验）\n");
        }
        return sb.toString();
    }

    private String flatten(String content) {
        String single = content.replace("\r", " ").replace("\n", " ⏎ ");
        return single.length() <= INDEX_SUMMARY_CHARS ? single
                : single.substring(0, INDEX_SUMMARY_CHARS) + "…";
    }

    private String decodeUtf8Bounded(byte[] raw, int cap) {
        int end = Math.min(raw.length, cap);
        String text = new String(raw, 0, end, StandardCharsets.UTF_8);
        int i = text.length();
        while (i > 0 && text.charAt(i - 1) == '\uFFFD') {
            i--;
        }
        return text.substring(0, i);
    }

    private MemoryView toView(String projectId, State state) {
        Bounded bounded = renderIndex(state, true);
        return new MemoryView(projectId, state.version(), state.enabled(), state.updatedAt(),
                List.copyOf(state.entries()), bounded.text(), bounded.truncated());
    }

    private List<MemoryEntry> append(List<MemoryEntry> entries, MemoryEntry created) {
        List<MemoryEntry> next = new ArrayList<>(entries);
        next.add(created);
        return next;
    }

    /** 持久化状态（内存形态）。 */
    private record State(long version, boolean enabled, Instant updatedAt, List<MemoryEntry> entries) {
    }
}
