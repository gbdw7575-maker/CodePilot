package com.dsh.evals;

import com.dsh.api.dto.DiffFile;
import com.dsh.common.IdGen;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/**
 * 干净起点应用最终补丁（doc/Agent项目开发文档.md §17：独立评测在干净起点应用最终补丁）。
 *
 * <p>流程：复制冻结基线目录 → 干净评分工作区；遍历 Agent 最终 DiffFile 列表，
 * ADDED/MODIFIED 用 Agent 副本对应文件覆盖，DELETED 从干净副本删除。
 * 结果 = 干净基线 + 仅 Agent 最终 delta，不含探索中间态。</p>
 */
@Component
public class CleanWorkspaceApplier {

    /**
     * Single-copy retry cap. Evidence (2026-10-06, ext-sphinx-doc__sphinx-10466):
     * Files.copy(..., REPLACE_EXISTING) onto {code sphinx/builders/gettext.py} - a file this
     * same process had written seconds earlier - threw AccessDeniedException and aborted the
     * whole grading prep. The baseline file was not read-only, so this is a transient hold
     * (scanner/indexer), not a permission problem.
     */
    static final int COPY_ATTEMPTS = 4;
    /** Backoff base; doubled each attempt (50/100/200/400ms ~ 750ms total). */
    static final long COPY_BACKOFF_MS = 50L;

    private final Path evalWorkRoot;

    public CleanWorkspaceApplier() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "dsh-eval-workspaces"));
    }

    public CleanWorkspaceApplier(Path evalWorkRoot) {
        this.evalWorkRoot = evalWorkRoot.toAbsolutePath().normalize();
    }

    /**
     * @param baselineDir        冻结基线目录（Agent 从未写入）
     * @param agentWorkspaceDir  Agent 副本目录（含最终修改）
     * @param diffFiles          Agent 最终差异列表（来自 WorkspaceDiffService）
     * @return 干净评分工作区目录路径
     */
    public Path apply(Path baselineDir, Path agentWorkspaceDir, List<DiffFile> diffFiles) {
        String id = IdGen.next("evalws_");
        Path cleanDir = evalWorkRoot.resolve(id);
        try {
            Files.createDirectories(cleanDir);
            copyTree(baselineDir, cleanDir);
            for (DiffFile df : diffFiles) {
                String rel = df.path();
                if (rel == null || rel.isBlank()) {
                    continue;
                }
                Path target = cleanDir.resolve(rel);
                switch (df.status()) {
                    case "ADDED", "MODIFIED" -> copyFile(agentWorkspaceDir.resolve(rel), target);
                    case "DELETED" -> Files.deleteIfExists(target);
                    default -> { /* 未知状态忽略，保持基线 */ }
                }
            }
        } catch (IOException e) {
            deleteQuietly(cleanDir);
            throw new UncheckedIOException("准备干净评分工作区失败: " + id, e);
        }
        return cleanDir;
    }

    /** 评分后清理干净工作区。 */
    public void cleanup(Path cleanDir) {
        deleteQuietly(cleanDir);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(dir).toString());
                Files.createDirectories(dest);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(file).toString());
                if (attrs.isSymbolicLink()) {
                    if (Files.exists(dest)) {
                        Files.delete(dest);
                    }
                    Files.createSymbolicLink(dest, Files.readSymbolicLink(file));
                } else {
                    copyFile(file, dest);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                // Must be a checked IOException: UncheckedIOException would bypass apply()'s
                // catch (IOException), leaking the clean workspace and losing the path in the message.
                throw new IOException("failed to copy baseline file: " + file, exc);
            }
        });
    }

    /**
     * Retrying copy: only AccessDeniedException is retried (the typical transient-hold signal).
     * Any other IOException is rethrown at once - hiding a genuinely unreadable source behind
     * retries only makes the failure slower.
     */
    static void copyFile(Path source, Path target) throws IOException {
        AccessDeniedException last = null;
        for (int i = 0; i < COPY_ATTEMPTS; i++) {
            try {
                Files.createDirectories(target.getParent());
                // Prefer delete-then-create. Overwriting an existing file is the high-frequency
                // AccessDenied site on Windows; after a successful delete the copy is a plain
                // create. If the delete fails (handle still held) we fall back to overwriting.
                try {
                    Files.deleteIfExists(target);
                } catch (IOException | RuntimeException ignored) {
                    // fall through to REPLACE_EXISTING
                }
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException denied) {
                last = denied;
                sleepQuietly(COPY_BACKOFF_MS << i);
            }
        }
        throw new IOException("copy denied after " + COPY_ATTEMPTS + " attempts: " + source + " -> " + target, last);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 清理失败不抛出，仅留副本
        }
    }
}
