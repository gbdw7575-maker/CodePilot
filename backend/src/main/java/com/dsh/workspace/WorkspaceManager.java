package com.dsh.workspace;

import com.dsh.application.DshProperties;
import com.dsh.common.ApplicationDataPaths;
import com.dsh.common.Digest;
import com.dsh.common.IdGen;
import com.dsh.storage.ProjectEntity;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作区准备与租约管理（内部接口契约 §7）：
 * 运行前把已信任项目目录整体复制为隔离任务副本（含未提交修改，保留符号链接不跟随），
 * 记录基线摘要；所有工具只操作副本，绝不直接写用户项目目录。
 */
@Component
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);
    private static final String BASELINE_CONTENT_DIR = "baseline-content";
    private static final long MAX_SNAPSHOT_FILE_BYTES = 1024L * 1024;

    private final Path workRoot;
    private final Map<String, WorkspaceLease> leases = new ConcurrentHashMap<>();

    public WorkspaceManager(DshProperties properties) {
        String configured = properties.workspace().root();
        this.workRoot = (configured == null || configured.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"), "dsh-workspaces")
                : Path.of(configured).toAbsolutePath().normalize();
    }

    public Path workRoot() {
        return workRoot;
    }

    /**
     * 为根 Run 懒准备任务副本（首次需要工作区的工具调用时触发）。
     * 同一 runId 重复调用返回同一租约；要求项目已信任且根目录真实存在。
     */
    public WorkspaceLease prepare(String runId, ProjectEntity project) {
        WorkspaceLease existing = leases.get(runId);
        if (existing != null) {
            return existing;
        }
        synchronized (leases) {
            existing = leases.get(runId);
            if (existing != null) {
                return existing;
            }
            if (!project.isTrusted()) {
                throw new WorkspaceNotAvailableException(
                        "PROJECT_NOT_TRUSTED", "项目未信任，拒绝准备工作区: " + project.getId());
            }
            Path source = Path.of(project.getRootPath()).toAbsolutePath().normalize();
            if (!Files.isDirectory(source)) {
                throw new WorkspaceNotAvailableException(
                        "WORKSPACE_UNAVAILABLE", "项目根目录不存在或不是目录: " + source);
            }
            // D-01 纵深防御：即使登记时通过授权根核验，运行前仍拒绝重解析点源根
            // （POSIX 符号链接与 Windows junction——后者 Files.isSymbolicLink 识别不了，
            // 普通用户即可创建并可透读外部目录），防止登记后被替换而把外部内容复制进任务副本。
            try {
                if (SafePaths.isReparseRedirect(source)) {
                    throw new WorkspaceNotAvailableException(
                            "WORKSPACE_UNAVAILABLE", "项目根目录不能是符号链接或目录联接: " + source);
                }
            } catch (IOException e) {
                throw new WorkspaceNotAvailableException(
                        "WORKSPACE_UNAVAILABLE", "无法核验项目根目录真实路径: " + source);
            }

            return createLease(runId, project.getId(), source, null);
        }
    }

    /**
     * DSH-010：从另一个 Run 的冻结副本准备子 Run 工作区（Tester/Reviewer 基于 Coder 快照）。
     * 复制源副本全部内容，并继承源副本的基线清单（三方合并时基线与写者一致）。
     * 源副本租约可能已释放（release 仅摘租约、目录保留作证据），按 workspaceRef 解析。
     */
    public WorkspaceLease prepareFromWorkspace(String runId, ProjectEntity project,
                                               String sourceWorkspaceRef) {
        WorkspaceLease existing = leases.get(runId);
        if (existing != null) {
            return existing;
        }
        synchronized (leases) {
            existing = leases.get(runId);
            if (existing != null) {
                return existing;
            }
            if (!project.isTrusted()) {
                throw new WorkspaceNotAvailableException(
                        "PROJECT_NOT_TRUSTED", "项目未信任，拒绝准备工作区: " + project.getId());
            }
            WorkspacePaths source = resolveByRef(sourceWorkspaceRef);
            if (!Files.isDirectory(source.dir())) {
                throw new WorkspaceNotAvailableException(
                        "WORKSPACE_UNAVAILABLE", "种子工作区副本不存在: " + sourceWorkspaceRef);
            }
            return createLease(runId, project.getId(), source.dir(), source.metaDir());
        }
    }

    /** 复制 copySource 建立新副本；inheritedMetaDir 非空时继承其基线清单。 */
    private WorkspaceLease createLease(String runId, String projectId, Path copySource,
                                       Path inheritedMetaDir) {
        String workspaceId = IdGen.next("ws_");
        Path dir = workRoot.resolve(workspaceId);
        Path metaDir = workRoot.resolve(workspaceId + ".meta");
        try {
            Files.createDirectories(dir);
            Files.createDirectories(metaDir);
            copyTree(copySource, dir, ApplicationDataPaths.dataDirectory());
        } catch (IOException e) {
            deleteQuietly(dir);
            deleteQuietly(metaDir);
            throw new WorkspaceNotAvailableException("WORKSPACE_UNAVAILABLE",
                    "准备任务副本失败: " + e.getMessage());
        }
        String baseline = digestOf(dir);
        // 继承写者基线（DSH-010 三方合并一致），否则持久化当前项目基线清单（path→sha256）
        Path inheritedManifest = inheritedMetaDir == null ? null
                : inheritedMetaDir.resolve(BASELINE_MANIFEST);
        if (inheritedManifest != null && Files.isRegularFile(inheritedManifest)) {
            try {
                Files.copy(inheritedManifest, metaDir.resolve(BASELINE_MANIFEST),
                        StandardCopyOption.REPLACE_EXISTING);
                copyBaselineContent(inheritedMetaDir.resolve(BASELINE_CONTENT_DIR),
                        metaDir.resolve(BASELINE_CONTENT_DIR));
            } catch (IOException e) {
                deleteQuietly(dir);
                deleteQuietly(metaDir);
                throw new WorkspaceNotAvailableException("WORKSPACE_UNAVAILABLE",
                        "继承基线清单失败: " + e.getMessage());
            }
        } else {
            writeBaselineManifest(metaDir, fileHashes(dir));
            try {
                copyBaselineContent(dir, metaDir.resolve(BASELINE_CONTENT_DIR));
            } catch (IOException e) {
                deleteQuietly(dir);
                deleteQuietly(metaDir);
                throw new WorkspaceNotAvailableException("WORKSPACE_UNAVAILABLE",
                        "保存基线内容失败: " + e.getMessage());
            }
        }
        WorkspaceLease lease = new WorkspaceLease(workspaceId, runId, projectId, copySource,
                dir, metaDir, baseline, Instant.now());
        leases.put(runId, lease);
        log.info("工作区已准备 run={} project={} base={} digest={} inherited={}",
                runId, projectId, copySource, baseline, inheritedManifest != null);
        return lease;
    }

    public WorkspaceLease require(String runId) {
        WorkspaceLease lease = leases.get(runId);
        if (lease == null) {
            throw new WorkspaceNotAvailableException(
                    "WORKSPACE_UNAVAILABLE", "Run 尚未准备工作区: " + runId);
        }
        return lease;
    }

    public WorkspaceLease find(String runId) {
        return leases.get(runId);
    }

    /**
     * 按 workspaceRef 解析副本路径（租约释放后用于 checkpoint restore）。
     * 目录可能已被清理；调用方需自行判断存在性。
     */
    public WorkspacePaths resolveByRef(String workspaceRef) {
        return new WorkspacePaths(workRoot.resolve(workspaceRef),
                workRoot.resolve(workspaceRef + ".meta"));
    }

    /** 副本目录与元数据目录路径对。 */
    public record WorkspacePaths(Path dir, Path metaDir) {
    }

    /** 释放租约；MVP 保留副本作为 Diff/证据来源，仅摘除写租约。 */
    public WorkspaceLease release(String runId) {
        return leases.remove(runId);
    }

    /**
     * 删除 Run 的工作区副本与元数据（删除会话/项目时回收磁盘）。
     * 租约仍在内存则按租约路径删除；否则按持久化的 workspaceRef 解析，目录不存在即 no-op。
     */
    public void discardRun(String runId, String workspaceRef) {
        WorkspaceLease lease = leases.remove(runId);
        if (lease != null) {
            deleteQuietly(lease.dir());
            deleteQuietly(lease.metaDir());
            return;
        }
        if (workspaceRef != null && !workspaceRef.isBlank()) {
            WorkspacePaths paths = resolveByRef(workspaceRef);
            deleteQuietly(paths.dir());
            deleteQuietly(paths.metaDir());
        }
    }

    /** 当前副本内容摘要（写入后变化；排除副本外元数据目录）。 */
    public String currentDigest(WorkspaceLease lease) {
        return digestOf(lease.dir());
    }

    /**
     * MA-07 AT-12：物化上游产物到工作区后刷新基线清单与基线内容。
     *
     * <p>物化写入的 upstream/ 文件必须进入基线，否则子 Run 封存 diff 会把它们误判为 ADDED
     * 并产出污染 patch。刷新后 readBaselineManifest 含物化文件，diff 正确跳过未改动的
     * upstream/ 文件；baseline-content 也补齐物化文件以支持子 Run 修改 upstream/ 时的
     * 三方对照。lease.baselineDigest 不变（仅用于 InputSnapshot 冻结记录，不参与 diff）。</p>
     */
    public void refreshBaseline(String runId) {
        WorkspaceLease lease = require(runId);
        Path metaDir = lease.metaDir();
        Path dir = lease.dir();
        writeBaselineManifest(metaDir, fileHashes(dir));
        try {
            copyBaselineContent(dir, metaDir.resolve(BASELINE_CONTENT_DIR));
        } catch (IOException e) {
            throw new WorkspaceNotAvailableException("WORKSPACE_UNAVAILABLE",
                    "刷新基线内容失败: " + e.getMessage());
        }
        log.info("基线已刷新 run={}（含物化上游产物）", runId);
    }

    /**
     * 全量摘要：所有常规文件（不跟随符号链接）相对路径 + SHA-256，
     * 按路径排序后汇总；新增/删除/修改任一文件都会改变摘要。
     */
    public static String digestOf(Path root) {
        try {
            Map<String, String> hashes = fileHashes(root);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> e : hashes.entrySet()) {
                md.update(e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new WorkspaceNotAvailableException("WORKSPACE_UNAVAILABLE", "计算工作区摘要失败: " + e.getMessage());
        }
    }

    /** 收集 root 下所有常规文件（跳过符号链接）的相对路径→SHA-256 映射。 */
    public static Map<String, String> fileHashes(Path root) {
        Map<String, String> hashes = new TreeMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root)
                            && WorkspaceIgnores.isIgnored(SafePaths.relativeUnix(root, dir), true)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = SafePaths.relativeUnix(root, file);
                    if (WorkspaceIgnores.isIgnored(rel)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        hashes.put(rel, Digest.sha256Hex(Files.readAllBytes(file)));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    throw new UncheckedIOException(exc);
                }
            });
        } catch (IOException e) {
            throw new WorkspaceNotAvailableException("WORKSPACE_UNAVAILABLE", "收集文件哈希失败: " + e.getMessage());
        }
        return hashes;
    }

    private static final String BASELINE_MANIFEST = "baseline-manifest.json";

    /** 保存可生成文本补丁的基线内容；大文件仍由哈希清单跟踪，但不伪造补丁。 */
    private static void copyBaselineContent(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || attrs.isSymbolicLink()
                        || attrs.size() > MAX_SNAPSHOT_FILE_BYTES) {
                    return FileVisitResult.CONTINUE;
                }
                Path dest = target.resolve(source.relativize(file).toString());
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 元数据目录内的只读基线副本，供 Diff 导出使用。 */
    public Path baselineContentDir(Path metaDir) {
        return metaDir.resolve(BASELINE_CONTENT_DIR);
    }

    /** 将基线文件清单写入 metaDir/baseline-manifest.json。 */
    private static void writeBaselineManifest(Path metaDir, Map<String, String> hashes) {
        try {
            Files.createDirectories(metaDir);
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writeValue(metaDir.resolve(BASELINE_MANIFEST).toFile(), hashes);
        } catch (IOException e) {
            throw new WorkspaceNotAvailableException("WORKSPACE_UNAVAILABLE", "写入基线清单失败: " + e.getMessage());
        }
    }

    /** 读取基线文件清单；不存在返回空 Map（无工作区变更）。 */
    public Map<String, String> readBaselineManifest(Path metaDir) {
        Path manifest = metaDir.resolve(BASELINE_MANIFEST);
        if (!Files.exists(manifest)) {
            return new TreeMap<>();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> result = mapper.readValue(manifest.toFile(), Map.class);
            return new TreeMap<>(result);
        } catch (IOException e) {
            throw new WorkspaceNotAvailableException("WORKSPACE_UNAVAILABLE", "读取基线清单失败: " + e.getMessage());
        }
    }

    /**
     * 整体复制项目目录：保留符号链接本身（NOFOLLOW_LINKS），不把副本外内容拉进副本。
     * D-01：Windows 目录联接（junction）不被 Files.isSymbolicLink 识别却会被 walkFileTree 跟随，
     * 因此在进入目录前用真实路径核验，发现重解析点子树整体跳过，不复制其指向的宿主外部内容。
     */
    static void copyTree(Path source, Path target, Path applicationDataDirectory) throws IOException {
        Path protectedDirectory = applicationDataDirectory.toAbsolutePath().normalize();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.toAbsolutePath().normalize().startsWith(protectedDirectory)) {
                    if (dir.equals(source)) {
                        throw new IOException("项目根目录位于应用数据目录，不能建立任务副本");
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!dir.equals(source) && SafePaths.isReparseRedirect(dir)) {
                    log.warn("跳过重解析点目录，不把副本外内容复制进任务副本: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path dest = target.resolve(source.relativize(dir).toString());
                Files.createDirectories(dest);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(file).toString());
                if (attrs.isSymbolicLink()) {
                    // 重建符号链接而非跟随复制：链接目标仍由 SafePaths 在访问时核验
                    if (Files.exists(dest)) {
                        Files.delete(dest);
                    }
                    Files.createSymbolicLink(dest, Files.readSymbolicLink(file));
                } else {
                    Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES,
                            StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                throw new UncheckedIOException(exc);
            }
        });
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
        } catch (IOException e) {
            log.warn("清理目录失败: {}", path, e);
        }
    }

    @PreDestroy
    void shutdown() {
        leases.clear();
    }

    /** 工作区不可用/未信任：由 Dispatcher 映射为可解释工具结果或 Run 失败。 */
    public static class WorkspaceNotAvailableException extends RuntimeException {
        private final String code;

        public WorkspaceNotAvailableException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
