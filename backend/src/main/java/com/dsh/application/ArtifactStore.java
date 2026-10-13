package com.dsh.application;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.common.Digest;
import com.dsh.common.IdGen;
import com.dsh.storage.ArtifactEntity;
import com.dsh.storage.ArtifactRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 受控产物存储：内容写入工作区副本的 .meta/artifacts/{artifactId}，
 * 元数据入库。读取时校验路径不越出 artifacts 目录（拒绝 .. 穿越）。
 */
@Service
public class ArtifactStore {

    private final ArtifactRepository repository;
    private final WorkspaceManager workspaceManager;
    private final RunRepository runRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ArtifactStore(ArtifactRepository repository, WorkspaceManager workspaceManager,
                         RunRepository runRepository) {
        this.repository = repository;
        this.workspaceManager = workspaceManager;
        this.runRepository = runRepository;
    }

    /**
     * 保存产物：写入 .meta/artifacts/{artifactId}，返回入库实体。
     * runId 可空（项目级产物）。
     */
    public ArtifactEntity save(String runId, String projectId, String name, String mediaType,
                               byte[] content, boolean downloadable) {
        String id = IdGen.next("art_");
        String sha256 = Digest.sha256Hex(content);

        Path artifactsDir = resolveArtifactsDir(runId, projectId);
        try {
            Files.createDirectories(artifactsDir);
            Files.write(artifactsDir.resolve(id), content);
        } catch (IOException e) {
            throw new UncheckedIOException("写入产物失败: " + id, e);
        }

        ArtifactEntity entity = new ArtifactEntity(id, runId, projectId, name, mediaType,
                content.length, sha256, Instant.now(), downloadable);
        return repository.save(entity);
    }

    /** Internal check evidence: explicit DB owner/ref, bounded bytes, exclusive leaf, no caller TX. */
    public ArtifactEntity saveCheckEvidence(String runId,String projectId,String workspaceRef,String name,
                                           String mediaType,byte[] content) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || runId==null || projectId==null || workspaceRef==null || !workspaceRef.matches("ws_[a-f0-9]{32}")
                || content==null || content.length>64*1024*1024 || name==null || !name.matches("check-[A-Za-z0-9_.-]{1,180}")
                || mediaType==null || !mediaType.matches("[A-Za-z0-9.+-]{1,80}/[A-Za-z0-9.+-]{1,80}"))
            throw new IllegalStateException("CHECK_EVIDENCE_UNAVAILABLE");
        requireEvidenceOwner(runId,projectId,workspaceRef);
        String id=IdGen.next("art_");Path dir=workspaceManager.workRoot().resolve(workspaceRef+".meta").resolve("artifacts");
        try {
            checkedDirectory(dir.getParent());
            if(!Files.exists(dir,java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(dir);
            checkedDirectory(dir);
            try(var output=Files.newOutputStream(dir.resolve(id),java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS)){output.write(content);}
            checkedDirectory(dir);requireEvidenceOwner(runId,projectId,workspaceRef);
            var entity=repository.save(new ArtifactEntity(id,runId,projectId,name,mediaType,content.length,
                    Digest.sha256Hex(content),Instant.now(),true));
            if(!java.util.Arrays.equals(content,readVerifiedContent(id,content.length))) throw new IllegalStateException("CHECK_EVIDENCE_UNAVAILABLE");
            return entity;
        } catch(IOException invalid){throw new IllegalStateException("CHECK_EVIDENCE_UNAVAILABLE");}
    }

    private void requireEvidenceOwner(String runId,String projectId,String ref) {
        var rows=jdbc.query("""
            SELECT r.workspace_ref,h.project_id FROM runs r JOIN turns t ON t.id=r.turn_id
            JOIN threads h ON h.id=t.thread_id WHERE r.id=?
            """,(r,n)->new String[]{r.getString(1),r.getString(2)},runId);
        if(rows.size()!=1 || !ref.equals(rows.getFirst()[0]) || !projectId.equals(rows.getFirst()[1])
                || jdbc.queryForObject("SELECT count(*) FROM runs WHERE workspace_ref=?",Integer.class,ref)!=1)
            throw new IllegalStateException("CHECK_EVIDENCE_UNAVAILABLE");
    }
    private static void checkedDirectory(Path path)throws IOException {
        Path cursor=path.toAbsolutePath().getRoot();for(Path part:path.toAbsolutePath()){
            cursor=cursor.resolve(part);
            if(Files.isSymbolicLink(cursor)||com.dsh.workspace.SafePaths.isReparseRedirect(cursor))throw new IOException();
        }
        if(!Files.isDirectory(path,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IOException();
    }

    /** Bounded raw bytes with before/after metadata, size, hash and no-link verification. */
    public byte[] readVerifiedContent(String artifactId,long maxBytes) {
        if(maxBytes<0 || maxBytes>64L*1024*1024)throw new IllegalArgumentException("CHECK_EVIDENCE_LIMIT");
        var entity=get(artifactId);
        if(entity.getSizeBytes()<0 || entity.getSizeBytes()>maxBytes)throw new IllegalStateException("CHECK_EVIDENCE_UNAVAILABLE");
        try {
            Path file=safePagePath(artifactId);checkedDirectory(file.getParent());byte[] bytes;
            try(var input=Files.newInputStream(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes((int)maxBytes+1);}
            var after=get(artifactId);safePagePath(artifactId);
            if(bytes.length!=entity.getSizeBytes() || !Digest.sha256Hex(bytes).equals(entity.getSha256())
                    || !entity.getSha256().equals(after.getSha256()) || entity.getSizeBytes()!=after.getSizeBytes()
                    || !java.util.Objects.equals(entity.getRunId(),after.getRunId()) || !entity.getProjectId().equals(after.getProjectId()))
                throw new IllegalStateException("CHECK_EVIDENCE_UNAVAILABLE");
            return bytes;
        } catch(IOException invalid){throw new IllegalStateException("CHECK_EVIDENCE_UNAVAILABLE");}
    }

    /** C05: Run-scoped immutable evidence package; same public bytes are reused across assembly/restart. */
    public ArtifactEntity saveCheckpointEvidence(String runId, String projectId, byte[] content) {
        if (runId == null || content.length > 2 * 1024 * 1024)
            throw new IllegalArgumentException("检查点证据包需 Run 归属且最多 2 MiB");
        String sha256 = Digest.sha256Hex(content);
        String name = "history-checkpoint-" + sha256 + ".jsonl";
        var existing = repository.findFirstByRunIdAndNameAndSha256(runId, name, sha256);
        if (existing.isPresent()) {
            ArtifactEntity artifact = existing.get();
            if (!projectId.equals(artifact.getProjectId()) || !artifact.isDownloadable()
                    || artifact.getSizeBytes() != content.length)
                throw new IllegalStateException("检查点证据归属或封存元数据不符");
            // Fail closed if a sealed package has disappeared; do not advertise an unreadable reference.
            if (readTextPage(artifact.getId(), 0, 4).sizeBytes() != artifact.getSizeBytes())
                throw new IllegalStateException("检查点证据大小不符");
            return artifact;
        }
        return save(runId, projectId, name, "text/plain", content, true);
    }

    /** 读取产物内容（校验路径安全）。 */
    public byte[] readContent(String artifactId) {
        Path file = contentPath(artifactId);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("读取产物失败: " + artifactId, e);
        }
    }

    public record TextExcerpt(String text, boolean truncated, long sizeBytes) {}

    /** 有界读取已保存日志的头尾；不把整个大日志分配到堆内存。 */
    public TextExcerpt readTextExcerpt(String artifactId, int maxBytes) {
        if (maxBytes < 2 || maxBytes > 65536) {
            throw new IllegalArgumentException("maxBytes 必须在 2..65536 之间");
        }
        try (SeekableByteChannel channel = Files.newByteChannel(contentPath(artifactId))) {
            long size = channel.size();
            boolean truncated = size > maxBytes;
            int headSize = (int) Math.min(size, truncated ? maxBytes / 2 : maxBytes);
            String head = readUtf8(channel, headSize);
            if (!truncated) return new TextExcerpt(head, false, size);
            int tailSize = maxBytes - headSize;
            channel.position(size - tailSize);
            return new TextExcerpt(head + "\n[已保存日志的中间部分省略]\n"
                    + readUtf8(channel, tailSize), true, size);
        } catch (IOException e) {
            throw new UncheckedIOException("读取产物失败: " + artifactId, e);
        }
    }

    /** C22: bounded streaming integrity check; no full-log allocation or symlink following. */
    public boolean verifyIntegrity(String artifactId, long maxBytes) {
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must be nonnegative");
        try {
            ArtifactEntity entity = get(artifactId);
            long expected = entity.getSizeBytes();
            if (expected < 0 || expected > maxBytes || entity.getSha256() == null
                    || !entity.getSha256().matches("[0-9a-f]{64}")) return false;
            Path file = safePagePath(artifactId);
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            try (SeekableByteChannel channel = Files.newByteChannel(file,
                    java.util.Set.of(java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
                if (channel.size() != expected) return false;
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                long total = 0;
                int count;
                while ((count = channel.read(buffer)) != -1) {
                    total += count;
                    if (total > expected || total > maxBytes) return false;
                    buffer.flip(); hash.update(buffer); buffer.clear();
                }
                return total == expected && channel.size() == expected
                        && entity.getSha256().equals(java.util.HexFormat.of().formatHex(hash.digest()));
            }
        } catch (IOException | RuntimeException | java.security.NoSuchAlgorithmException invalid) {
            return false;
        }
    }

    /** 页游标为原始字节位置；页末不会切开 UTF-8 字符。hasMore 只描述已保存产物。 */
    public record TextPage(String text, long byteOffset, long nextOffset, long sizeBytes, boolean hasMore) {}

    public TextPage readTextPage(String artifactId, long byteOffset, int maxBytes) {
        if (byteOffset < 0 || maxBytes < 4 || maxBytes > 16384) {
            throw new IllegalArgumentException("分页参数越界");
        }
        try {
            Path file = safePagePath(artifactId);
            try (SeekableByteChannel channel = Files.newByteChannel(file,
                    java.util.Set.of(java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
                long size = channel.size();
                if (byteOffset > size) throw new IllegalArgumentException("游标超过产物末尾");
                channel.position(byteOffset);
                ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(size - byteOffset, maxBytes + 3L));
                while (buffer.hasRemaining() && channel.read(buffer) != -1) { }
                byte[] bytes = buffer.array();
                int available = buffer.position();
                int count = Math.min(available, maxBytes);
                // 若下一字节仍是 continuation byte，退回该字符起始位置。maxBytes>=4 保证合法页能前进。
                while (count > 0 && count < available && (bytes[count] & 0xc0) == 0x80) count--;
                for (int i = 0; i < count; i++) {
                    if (bytes[i] == 0) throw new IllegalArgumentException("产物页含二进制空字节");
                }
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, count)).toString();
                long next = byteOffset + count;
                if (next == byteOffset && byteOffset < size) throw new IllegalArgumentException("游标不在 UTF-8 字符边界");
                return new TextPage(text, byteOffset, next, size, next < size);
            }
        } catch (IOException e) {
            // 工具侧只返回稳定失败，不暴露存储路径或内容。
            throw new UncheckedIOException("读取产物页失败", e);
        }
    }

    /** 分页读取额外拒绝中间目录/叶子符号链接，避免受控元数据路径被链接替换。 */
    private Path safePagePath(String artifactId) throws IOException {
        Path file = contentPath(artifactId).toAbsolutePath().normalize();
        Path root = workspaceManager.workRoot().toAbsolutePath().normalize();
        if (!file.startsWith(root)) throw new IOException("产物不在存储根目录");
        Path cursor = root;
        for (Path part : root.relativize(file)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("产物存储路径包含链接");
        }
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("产物内容不可读取");
        }
        return file;
    }

    private static String readUtf8(SeekableByteChannel channel, int bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(bytes);
        while (buffer.hasRemaining() && channel.read(buffer) != -1) { }
        return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
    }

    private Path contentPath(String artifactId) {
        ArtifactEntity entity = get(artifactId);
        Path artifactsDir = resolveArtifactsDir(entity.getRunId(), entity.getProjectId());
        Path file = artifactsDir.resolve(entity.getId()).normalize();
        Path dirNormalized = artifactsDir.normalize();
        if (!file.startsWith(dirNormalized)) {
            throw new ApiException(ErrorCode.INVALID_INPUT, "产物路径非法");
        }
        return file;
    }

    public ArtifactEntity get(String artifactId) {
        return repository.findById(artifactId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "产物不存在"));
    }

    private Path resolveArtifactsDir(String runId, String projectId) {
        if (runId != null) {
            WorkspaceLease lease = workspaceManager.find(runId);
            if (lease != null) {
                return lease.metaDir().resolve("artifacts");
            }
            // 租约已释放，从 RunEntity 取 workspaceRef 解析
            RunEntity run = runRepository.findById(runId).orElse(null);
            if (run != null && run.getWorkspaceRef() != null && !run.getWorkspaceRef().isBlank()) {
                WorkspaceManager.WorkspacePaths paths = workspaceManager.resolveByRef(run.getWorkspaceRef());
                return paths.metaDir().resolve("artifacts");
            }
        }
        // 项目级产物：放 workRoot/projects/{projectId}/artifacts
        return workspaceManager.workRoot().resolve("projects").resolve(projectId).resolve("artifacts");
    }
}
