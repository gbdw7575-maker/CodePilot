package com.dsh.rules;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.application.DshProperties;
import com.dsh.common.Digest;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.workspace.SafePaths;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 项目规则层（D-04，开发文档 §10.2）：装配前加载根规则 + 按目录作用域规则。
 * 根规则固定为项目根下的规则文件（默认 AGENTS.md，可配置 CLAUDE.md 兼容）；
 * 目录规则来自用户输入中提及的相对路径，沿「提及目录 → 项目根」向上链收集同名规则文件，
 * 更深的目录规则视为更具体、排序在后覆盖冲突；内容更新只改变 sha256/版本，不改变结构。
 * 规则是可信项目约定，只指导模型：不提升工作区权限、不绕过审批与执行策略；
 * 不受 memory.enabled 门控。未信任项目一律拒绝（fail-closed）。
 */
@Component
public class RuleLoader {

    /** 从用户输入提取相对路径候选：引号/反引号包裹或裸 POSIX 相对路径。 */
    private static final Pattern QUOTED_PATH = Pattern.compile("[\"'`]([^\"'`\\r\\n]{1,200})[\"'`]");
    private static final Pattern BARE_PATH =
            Pattern.compile("(?<![A-Za-z0-9_.:/~-])((?:[A-Za-z0-9_.@+-]+/)+[A-Za-z0-9_.@+-]*)");
    /** 明显不是工作区相对路径的候选（URL、主机路径、Windows 盘符等）。 */
    private static final Pattern NOT_WORKSPACE_PATH =
            Pattern.compile("^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//|~|/)");

    /** 规则文件到达模型前的呈现片段：来源路径 + 内容版本 + 截断标记 + 正文。 */
    public record RuleFile(String relativePath, String sha256, String version,
                           boolean truncated, String content) {
    }

    /** 一次装配的完整规则视图：files 有序（根在前、目录按深度升序），effectiveVersion 汇总全部文件版本。 */
    public record LoadedRules(List<RuleFile> files, String effectiveVersion) {
    }

    private final ProjectRepository projectRepository;
    private final DshProperties properties;

    public RuleLoader(ProjectRepository projectRepository, DshProperties properties) {
        this.projectRepository = projectRepository;
        this.properties = properties;
    }

    /**
     * 加载项目规则。userInput 可为 null（如评估入口），此时仅加载根规则。
     * 无规则文件时返回空 files 与空内容的哈希版本。
     */
    public LoadedRules load(String projectId, String userInput) {
        Path root = projectRoot(projectId);
        DshProperties.Rules cfg = properties.rules();
        String fileName = cfg.file() == null || cfg.file().isBlank() ? "AGENTS.md" : cfg.file();

        List<Path> dirs = scopedDirs(root, userInput, cfg.maxDepth());
        List<RuleFile> files = new ArrayList<>();
        // 根规则固定最前（稳定前缀），随后按目录深度升序（浅→深，深者更具体）
        addRuleFile(files, root, root.resolve(fileName), cfg.maxBytes());
        for (Path dir : dirs) {
            if (files.size() >= cfg.maxFiles()) {
                break;
            }
            addRuleFile(files, root, dir.resolve(fileName), cfg.maxBytes());
        }
        return new LoadedRules(List.copyOf(files), effectiveVersion(files));
    }

    // ---------- 内部 ----------

    /** 信任校验 + 项目根真实路径（与记忆层同一套边界，但不走 memory.enabled 开关）。 */
    private Path projectRoot(String projectId) {
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

    /**
     * 从用户输入提取目录作用域：去重、按深度升序、去重父子（父目录链已覆盖子目录链的根侧部分），
     * 深度以项目根为 0 计，超过 maxDepth 的目录只保留根侧 maxDepth 层。
     */
    private List<Path> scopedDirs(Path root, String userInput, int maxDepth) {
        if (userInput == null || userInput.isBlank()) {
            return List.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        Matcher quoted = QUOTED_PATH.matcher(userInput);
        while (quoted.find()) {
            candidates.add(quoted.group(1).trim());
        }
        Matcher bare = BARE_PATH.matcher(userInput);
        while (bare.find()) {
            candidates.add(bare.group(1));
        }

        Set<String> seen = new LinkedHashSet<>();
        List<Path> dirs = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.isBlank() || NOT_WORKSPACE_PATH.matcher(candidate).find()) {
                continue;
            }
            final Path resolved;
            try {
                SafePaths.assertRelativeInside(candidate);
                resolved = SafePaths.resolve(root, candidate);
            } catch (SafePaths.PathEscapeException e) {
                // 用户文本中的任意字符串不构成授权，越界候选直接忽略
                continue;
            }
            // 提及文件路径时取其所在目录；目录不存在时同样回退到最近存在祖先
            Path dir = Files.isDirectory(resolved) ? resolved : resolved.getParent();
            while (dir != null && !Files.isDirectory(dir)) {
                dir = dir.getParent();
            }
            if (dir == null || !dir.startsWith(root)) {
                continue;
            }
            int depth = root.relativize(dir).getNameCount();
            if (depth < 1) {
                continue; // 根目录规则固定加载，无需重复
            }
            // 只保留根侧 maxDepth 层，再沿「目录 → 根」向上链逐级收集（不含根，根规则固定加载）
            Path capped = dir;
            while (root.relativize(capped).getNameCount() > maxDepth) {
                capped = capped.getParent();
            }
            for (Path p = capped; p != null && !p.equals(root) && p.startsWith(root); p = p.getParent()) {
                if (seen.add(SafePaths.relativeUnix(root, p))) {
                    dirs.add(p);
                }
            }
        }
        dirs.sort((a, b) -> Integer.compare(root.relativize(a).getNameCount(), root.relativize(b).getNameCount()));
        return dirs;
    }

    private void addRuleFile(List<RuleFile> files, Path root, Path rulePath, int maxBytes) {
        if (!Files.isRegularFile(rulePath)) {
            return;
        }
        // 核验规则文件真实路径仍在项目根内（防符号链接逃逸）
        try {
            if (!rulePath.toRealPath().startsWith(root)) {
                return;
            }
        } catch (IOException e) {
            return;
        }
        try {
            byte[] raw = Files.readAllBytes(rulePath);
            boolean truncated = raw.length > maxBytes;
            String content = truncated
                    ? decodeUtf8Bounded(raw, maxBytes)
                    : new String(raw, StandardCharsets.UTF_8);
            String sha256 = Digest.sha256Hex(raw);
            files.add(new RuleFile(SafePaths.relativeUnix(root, rulePath), sha256,
                    sha256.substring(0, 12), truncated, content));
        } catch (IOException e) {
            // 单文件读取失败不阻断装配：规则是增强而非硬依赖
        }
    }

    /** 按字节上限截断并去掉末尾不完整的 UTF-8 序列（避免替换字符 U+FFFD 进入模型上下文）。 */
    private String decodeUtf8Bounded(byte[] raw, int cap) {
        int end = Math.min(raw.length, cap);
        String text = new String(raw, 0, end, StandardCharsets.UTF_8);
        int i = text.length();
        while (i > 0 && text.charAt(i - 1) == '\uFFFD') {
            i--;
        }
        return text.substring(0, i);
    }

    /** 全量文件「路径:哈希」拼接的整体摘要：任一文件内容/顺序变化都会改变版本（冲突与更新可见）。 */
    private String effectiveVersion(List<RuleFile> files) {
        StringBuilder sb = new StringBuilder();
        for (RuleFile f : files) {
            sb.append(f.relativePath()).append(':').append(f.sha256()).append('\n');
        }
        return Digest.sha256Hex(sb.toString());
    }
}
