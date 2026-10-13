package com.dsh.application;

import com.dsh.api.dto.Diff;
import com.dsh.api.dto.DiffFile;
import com.dsh.storage.ArtifactEntity;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.dsh.workspace.WorkspaceIgnores;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 工作区 Diff：对比基线清单与当前副本，生成 DiffFile 列表 + unified patch。
 * 二进制文件只列名不生成 patch；patch 超 256KiB 截断。Diff 存为 Artifact。
 */
@Service
public class WorkspaceDiffService {

    private static final long PATCH_LIMIT_BYTES = 256L * 1024;
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "svg",
            "pdf", "zip", "jar", "war", "tar", "gz", "7z", "rar",
            "class", "exe", "dll", "so", "dylib", "o", "a", "lib",
            "mp3", "mp4", "avi", "mov", "wav", "flac",
            "woff", "woff2", "ttf", "eot", "otf",
            "db", "sqlite", "sqlite3", "idx", "pack", "log");

    private final WorkspaceManager workspaceManager;
    private final RunRepository runRepository;
    private final ThreadRepository threadRepository;
    private final TurnRepository turnRepository;
    private final ArtifactStore artifactStore;

    public WorkspaceDiffService(WorkspaceManager workspaceManager, RunRepository runRepository,
                                ThreadRepository threadRepository, TurnRepository turnRepository,
                                ArtifactStore artifactStore) {
        this.workspaceManager = workspaceManager;
        this.runRepository = runRepository;
        this.threadRepository = threadRepository;
        this.turnRepository = turnRepository;
        this.artifactStore = artifactStore;
    }

    public Diff compute(String runId) {
        RunEntity run = runRepository.findById(runId).orElseThrow();
        String projectId = projectIdOf(run);
        WorkspaceLease lease = workspaceManager.find(runId);
        if (lease == null) {
            String ref = run.getWorkspaceRef();
            if (ref == null || ref.isBlank()) {
                return emptyDiff(runId);
            }
            WorkspaceManager.WorkspacePaths paths = workspaceManager.resolveByRef(ref);
            if (!Files.isDirectory(paths.dir())) {
                return emptyDiff(runId);
            }
            return computeFromPaths(runId, projectId, paths.dir(), paths.metaDir());
        }
        return computeFromPaths(runId, projectId, lease.dir(), lease.metaDir());
    }

    private String projectIdOf(RunEntity run) {
        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElse(null);
        if (turn == null) {
            return "";
        }
        ThreadEntity thread = threadRepository.findById(turn.getThreadId()).orElse(null);
        return thread != null ? thread.getProjectId() : "";
    }

    private Diff computeFromPaths(String runId, String projectId, Path dir, Path metaDir) {
        Map<String, String> baseline = workspaceManager.readBaselineManifest(metaDir);
        Map<String, String> current = WorkspaceManager.fileHashes(dir);
        Path baselineContent = workspaceManager.baselineContentDir(metaDir);

        List<DiffFile> files = new ArrayList<>();
        StringBuilder patch = new StringBuilder();
        boolean truncated = false;

        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(baseline.keySet());
        allPaths.addAll(current.keySet());

        for (String relPath : allPaths) {
            // 跳过 .pytest_cache/__pycache__/target 等构建缓存产物（通用忽略清单）
            if (WorkspaceIgnores.isIgnored(relPath)) {
                continue;
            }
            String baseHash = baseline.get(relPath);
            String curHash = current.get(relPath);
            Path fullPath = dir.resolve(relPath);
            Path baselinePath = baselineContent.resolve(relPath);

            if (baseHash == null) {
                boolean binary = isBinary(relPath, fullPath);
                files.add(new DiffFile(relPath, "ADDED", null, binary));
                if (!binary && Files.exists(fullPath)) {
                    truncated |= appendAddedPatch(patch, relPath, fullPath, truncated);
                }
            } else if (curHash == null) {
                boolean binary = isBinary(relPath, baselinePath);
                files.add(new DiffFile(relPath, "DELETED", null, binary));
                if (!binary) {
                    truncated |= appendChangedPatch(patch, relPath, baselinePath, null, truncated);
                }
            } else if (!baseHash.equals(curHash)) {
                boolean binary = isBinary(relPath, fullPath);
                if (binary) {
                    files.add(new DiffFile(relPath, "MODIFIED", null, true));
                } else {
                    // 纯 CRLF/LF 转换仍忽略；真实编辑导出原始行尾而非归一化字节。
                    UnifiedDiff.TextLines oldText = readText(baselinePath);
                    UnifiedDiff.TextLines newText = readText(fullPath);
                    boolean sameAfterNormalize = oldText != null && newText != null
                            && oldText.endsWithNewline() == newText.endsWithNewline()
                            && normalizedLines(oldText).equals(normalizedLines(newText));
                    if (sameAfterNormalize) {
                        continue;
                    }
                    files.add(new DiffFile(relPath, "MODIFIED", null, false));
                    truncated |= appendChangedPatch(patch, relPath, oldText, newText, truncated);
                }
            }
        }

        String baseDigest = baseline.isEmpty() ? "" : digestOfMap(baseline);
        String workspaceDigest = current.isEmpty() ? "" : digestOfMap(current);
        String patchStr = patch.toString();

        // 存为 Artifact
        ArtifactEntity artifact = artifactStore.save(runId, projectId, "diff.patch",
                "application/x-diff", patchStr.getBytes(StandardCharsets.UTF_8), true);

        return new Diff(runId, baseDigest, workspaceDigest, files, patchStr, truncated, artifact.getId());
    }

    private boolean appendAddedPatch(StringBuilder patch, String relPath, Path file,
                                     boolean alreadyTruncated) {
        if (alreadyTruncated) {
            return true;
        }
        UnifiedDiff.TextLines newText = readText(file);
        if (newText == null) {
            return true;
        }
        String segment = UnifiedDiff.fileDiff(relPath, null, true,
                newText.lines(), newText.endsWithNewline());
        return appendWithinLimit(patch, segment);
    }

    private boolean appendChangedPatch(StringBuilder patch, String relPath, Path baselineFile,
                                       Path currentFile, boolean alreadyTruncated) {
        if (alreadyTruncated) {
            return true;
        }
        if (!Files.isRegularFile(baselineFile)) {
            return true;
        }
        UnifiedDiff.TextLines oldText = readText(baselineFile);
        UnifiedDiff.TextLines newText = currentFile == null ? null : readText(currentFile);
        if (oldText == null || (currentFile != null && newText == null)) {
            return true;
        }
        String segment = UnifiedDiff.fileDiff(relPath, oldText.lines(), oldText.endsWithNewline(),
                newText == null ? null : newText.lines(),
                newText == null || newText.endsWithNewline());
        return appendWithinLimit(patch, segment);
    }

    /** MODIFIED 分支复用已读取内容，避免重复读文件。 */
    private boolean appendChangedPatch(StringBuilder patch, String relPath,
                                       UnifiedDiff.TextLines oldText,
                                       UnifiedDiff.TextLines newText, boolean alreadyTruncated) {
        if (alreadyTruncated || oldText == null || newText == null) {
            return true;
        }
        String segment = UnifiedDiff.fileDiff(relPath, oldText.lines(), oldText.endsWithNewline(),
                newText.lines(), newText.endsWithNewline());
        return appendWithinLimit(patch, segment);
    }

    /** 读文本并保留行尾；读失败返回 null，调用方标记导出不完整。 */
    private static UnifiedDiff.TextLines readText(Path file) {
        try {
            return UnifiedDiff.verbatim(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    private static List<String> normalizedLines(UnifiedDiff.TextLines text) {
        return text.lines().stream().map(line -> line.endsWith("\r")
                ? line.substring(0, line.length() - 1) : line).toList();
    }

    private boolean appendWithinLimit(StringBuilder patch, String segment) {
        if (segment == null || segment.isEmpty()) {
            return false;
        }
        if ((long) patch.toString().getBytes(StandardCharsets.UTF_8).length
                + segment.getBytes(StandardCharsets.UTF_8).length > PATCH_LIMIT_BYTES) {
            return true;
        }
        patch.append(segment);
        return false;
    }

    private boolean isBinary(String relPath, Path file) {
        int dot = relPath.lastIndexOf('.');
        if (dot >= 0) {
            String ext = relPath.substring(dot + 1).toLowerCase();
            if (BINARY_EXTENSIONS.contains(ext)) {
                return true;
            }
        }
        // 读前 8KB 检测 NUL 字节
        if (file != null && Files.exists(file)) {
            try {
                byte[] head = Files.readAllBytes(file);
                int len = Math.min(head.length, 8192);
                for (int i = 0; i < len; i++) {
                    if (head[i] == 0) {
                        return true;
                    }
                }
            } catch (IOException ignored) {
                // 读失败按文本处理
            }
        }
        return false;
    }

    private static String digestOfMap(Map<String, String> hashes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> e : hashes.entrySet()) {
                md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(e.getValue().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    private Diff emptyDiff(String runId) {
        return new Diff(runId, "", "", List.of(), "", false, null);
    }
}
