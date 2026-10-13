package com.dsh.orchestration;

import com.dsh.application.ArtifactStore;
import com.dsh.common.Digest;
import com.dsh.common.IdGen;
import com.dsh.storage.ArtifactEntity;
import com.dsh.storage.InputSnapshotEntity;
import com.dsh.storage.InputSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 输入快照冻结服务（MA-04，规范 §7/§8.1）。
 *
 * <p>子 Run 启动前把种子工作区基线摘要、引用产物内容 sha256 与归一化写范围落一行
 * agent_input_snapshots：执行与封存对照的就是这份冻结输入。MA-04 不把依赖产物物化进
 * 副本（MA-07），只冻结哈希与范围；执行点写白名单由本服务提供当前 Run 的冻结范围。</p>
 */
@Service
public class InputSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(InputSnapshotService.class);

    private final InputSnapshotRepository snapshotRepository;
    private final ArtifactStore artifactStore;
    private final WriteScopePolicy writeScopePolicy;
    private final ObjectMapper objectMapper;

    public InputSnapshotService(InputSnapshotRepository snapshotRepository,
                                ArtifactStore artifactStore,
                                WriteScopePolicy writeScopePolicy,
                                ObjectMapper objectMapper) {
        this.snapshotRepository = snapshotRepository;
        this.artifactStore = artifactStore;
        this.writeScopePolicy = writeScopePolicy;
        this.objectMapper = objectMapper;
    }

    /**
     * 冻结一次子 Run 的输入。写范围非法时抛 {@link IllegalArgumentException}（INVALID_SCOPE），
     * 调用方须在任何预约/启动前转有界失败。
     */
    public FrozenSnapshot freeze(String childRunId, String rootRunId, String projectId,
                                 String seedWorkspaceRef, String baseDigest,
                                 List<String> artifactRefs, String resourceScopeJson) {
        WriteScopePolicy.Scope scope = writeScopePolicy.parse(resourceScopeJson);

        ArrayNode artifacts = objectMapper.createArrayNode();
        if (artifactRefs != null) {
            for (String artifactId : artifactRefs) {
                ArtifactEntity entity = artifactStore.get(artifactId);
                if (entity == null) {
                    throw new IllegalArgumentException("输入产物不存在: " + artifactId);
                }
                byte[] content = artifactStore.readContent(artifactId);
                ObjectNode item = artifacts.addObject();
                item.put("artifactId", artifactId);
                item.put("mediaType", entity.getMediaType() == null ? "" : entity.getMediaType());
                item.put("sha256", Digest.sha256Hex(content));
            }
        }

        String scopeJson = scope.unrestricted() ? null : writeScopeToJson(scope);

        String snapshotId = IdGen.next("snap_");
        Instant now = Instant.now();
        InputSnapshotEntity entity = new InputSnapshotEntity(snapshotId, childRunId, rootRunId,
                projectId, seedWorkspaceRef == null ? "" : seedWorkspaceRef,
                baseDigest == null ? "" : baseDigest,
                artifacts.toString(), scopeJson, now);
        snapshotRepository.save(entity);
        log.info("输入快照已冻结 snapshot={} child={} seed={} artifacts={} scope={}",
                snapshotId, childRunId, entity.getSeedWorkspaceRef(), artifacts.size(),
                scope.unrestricted() ? "WORKSPACE" : scope.writePatterns());
        return new FrozenSnapshot(snapshotId, entity.getBaseDigest(), scope);
    }

    /** 取某 Run 启动时冻结的写范围；无快照（MAIN/旧 Run）视为整副本不受限。 */
    public WriteScopePolicy.Scope findFrozenScope(String runId) {
        Optional<InputSnapshotEntity> snapshot =
                snapshotRepository.findFirstByRunIdOrderByCreatedAtDesc(runId);
        if (snapshot.isEmpty() || snapshot.get().getWriteScopeJson() == null
                || snapshot.get().getWriteScopeJson().isBlank()) {
            return WriteScopePolicy.Scope.UNRESTRICTED;
        }
        return writeScopePolicy.parse(snapshot.get().getWriteScopeJson());
    }

    private String writeScopeToJson(WriteScopePolicy.Scope scope) {
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode write = node.putArray("write");
        for (String pattern : scope.writePatterns()) {
            write.add(pattern);
        }
        return node.toString();
    }

    /** 冻结结果：快照 ID、基线摘要与已归一化写范围。 */
    public record FrozenSnapshot(String snapshotId, String baseDigest,
                                 WriteScopePolicy.Scope scope) {
    }
}
