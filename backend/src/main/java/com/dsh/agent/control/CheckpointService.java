package com.dsh.agent.control;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.RestoreResult;
import com.dsh.api.dto.event.EventPayloads;
import com.dsh.api.dto.event.EventType;
import com.dsh.application.event.EventMeta;
import com.dsh.application.event.EventStore;
import com.dsh.application.trace.TraceContext;
import com.dsh.common.IdGen;
import com.dsh.storage.CheckpointEntity;
import com.dsh.storage.CheckpointRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 检查点服务：capture 把工作区 zip 到 metaDir/checkpoints/；
 * restore 先比较 expectedWorkspaceDigest 与检查点摘要，不匹配→409 WORKSPACE_CONFLICT，
 * 匹配则先保存安全快照再恢复，使验证标记过期。
 */
@Service
public class CheckpointService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

    private final CheckpointRepository checkpointRepository;
    private final RunRepository runRepository;
    private final TurnRepository turnRepository;
    private final ThreadRepository threadRepository;
    private final WorkspaceManager workspaceManager;
    private final EventStore eventStore;
    private final TraceContext traceContext;

    public CheckpointService(CheckpointRepository checkpointRepository, RunRepository runRepository,
                             TurnRepository turnRepository, ThreadRepository threadRepository,
                             WorkspaceManager workspaceManager, EventStore eventStore,
                             TraceContext traceContext) {
        this.checkpointRepository = checkpointRepository;
        this.runRepository = runRepository;
        this.turnRepository = turnRepository;
        this.threadRepository = threadRepository;
        this.workspaceManager = workspaceManager;
        this.eventStore = eventStore;
        this.traceContext = traceContext;
    }

    /**
     * 在写工具执行前 capture：zip 副本内容，记录摘要，发出 checkpoint.created 事件。
     *
     * <p>传播级别 REQUIRED（DSH010 修订）：唯一生产调用点在 LoopSteps.apply（REQUIRES_NEW）
     * 持有的事件游标行锁事务内；若 capture 另开 REQUIRES_NEW 事务追加同 thread 事件会自锁
     * （游标 X 锁等待，10s 超时）。加入外层事务后，检查点与工具轮原子提交；
     * 无外层事务的直接调用仍自动开启独立事务。zip 副本落盘不受事务回滚影响。</p>
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public CheckpointEntity capture(String runId, WorkspaceLease lease) {
        RunEntity run = runRepository.findById(runId).orElseThrow();
        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElseThrow();
        ThreadEntity thread = threadRepository.findById(turn.getThreadId()).orElseThrow();

        Instant now = Instant.now();
        String checkpointId = IdGen.next("ckpt_");
        String digest = WorkspaceManager.digestOf(lease.dir());
        Path checkpointsDir = lease.metaDir().resolve("checkpoints");
        String artifactRef = checkpointId + ".zip";
        try {
            Files.createDirectories(checkpointsDir);
            zipDirectory(lease.dir(), checkpointsDir.resolve(artifactRef));
        } catch (IOException e) {
            throw new UncheckedIOException("检查点 zip 失败: " + runId, e);
        }

        CheckpointEntity checkpoint = new CheckpointEntity(checkpointId, runId, digest, artifactRef, now);
        checkpointRepository.save(checkpoint);

        EventMeta meta = new EventMeta(thread.getProjectId(), thread.getId(),
                turn.getId(), runId, run.getParentRunId(), run.getTraceId(), traceContext.spanId());
        eventStore.append(meta, EventType.CHECKPOINT_CREATED,
                new EventPayloads.CheckpointCreated(checkpointId, digest));
        return checkpoint;
    }

    /** 列出 Run 的检查点（最新在前）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<CheckpointEntity> listByRun(String runId) {
        return checkpointRepository.listByRun(runId);
    }

    /** CheckpointEntity → Checkpoint DTO。 */
    public static com.dsh.api.dto.Checkpoint toDto(CheckpointEntity e) {
        return new com.dsh.api.dto.Checkpoint(e.getId(), e.getRunId(),
                e.getWorkspaceDigest(), List.of(), e.getCreatedAt(), e.getVersion() + 1);
    }

    /**
     * 恢复检查点：工作区摘要不匹配→409；匹配则先存安全快照再恢复，标记验证过期。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RestoreResult restore(String checkpointId, String expectedWorkspaceDigest) {
        CheckpointEntity checkpoint = checkpointRepository.findById(checkpointId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "检查点不存在"));
        RunEntity run = runRepository.findById(checkpoint.getRunId()).orElseThrow();

        WorkspaceManager.WorkspacePaths paths = workspaceManager.resolveByRef(run.getWorkspaceRef());
        if (!Files.isDirectory(paths.dir())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "检查点对应的工作区已不存在");
        }

        String currentDigest = WorkspaceManager.digestOf(paths.dir());
        if (!expectedWorkspaceDigest.equals(checkpoint.getWorkspaceDigest())) {
            throw new ApiException(ErrorCode.WORKSPACE_CONFLICT,
                    "检查点摘要与请求的 expectedWorkspaceDigest 不匹配",
                    Map.<String, Object>of("checkpointDigest", checkpoint.getWorkspaceDigest(),
                            "expectedWorkspaceDigest", expectedWorkspaceDigest));
        }

        // 恢复前保存安全快照
        String previousDigest = currentDigest;
        String snapshotRef = "snapshot_" + checkpointId + ".zip";
        Path checkpointsDir = paths.metaDir().resolve("checkpoints");
        try {
            Files.createDirectories(checkpointsDir);
            zipDirectory(paths.dir(), checkpointsDir.resolve(snapshotRef));
            // 清空工作区目录后从检查点 zip 恢复
            cleanDirectory(paths.dir());
            unzipTo(checkpointsDir.resolve(checkpoint.getArtifactRef()), paths.dir());
        } catch (IOException e) {
            throw new UncheckedIOException("检查点恢复失败: " + checkpointId, e);
        }

        String restoredDigest = WorkspaceManager.digestOf(paths.dir());

        // 标记验证过期
        run.setVerificationStatus("STALE");
        run.setUpdatedAt(Instant.now());
        runRepository.save(run);

        return new RestoreResult(checkpointId, previousDigest, restoredDigest, List.of(), Instant.now());
    }

    private static void zipDirectory(Path source, Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = source.relativize(file).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(rel));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(source)) {
                        String rel = source.relativize(dir).toString().replace('\\', '/') + "/";
                        zos.putNextEntry(new ZipEntry(rel));
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static void unzipTo(Path zipFile, Path target) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path dest = target.resolve(entry.getName()).normalize();
                if (!dest.startsWith(target)) {
                    throw new IOException("zip 条目逃逸工作区: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(zis, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private static void cleanDirectory(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (!dir.equals(root)) {
                    Files.deleteIfExists(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
