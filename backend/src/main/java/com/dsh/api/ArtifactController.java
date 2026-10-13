package com.dsh.api;

import com.dsh.api.dto.Artifact;
import com.dsh.application.ArtifactStore;
import com.dsh.storage.ArtifactEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受控产物 REST：元数据查询 + 内容下载。
 * 下载仅允许 downloadable=true 的产物；来源防护由 LocalAccessGuardFilter 统一处理。
 */
@RestController
@RequestMapping("/api/v1/artifacts")
public class ArtifactController {

    private final ArtifactStore artifactStore;

    public ArtifactController(ArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    @GetMapping("/{artifactId}")
    public ResponseEntity<Artifact> get(@PathVariable String artifactId) {
        ArtifactEntity entity = artifactStore.get(artifactId);
        return ResponseEntity.ok(toDto(entity));
    }

    @GetMapping("/{artifactId}/content")
    public ResponseEntity<?> download(@PathVariable String artifactId) {
        ArtifactEntity entity = artifactStore.get(artifactId);
        if (!entity.isDownloadable()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        byte[] content = artifactStore.readContent(artifactId);
        ByteArrayResource resource = new ByteArrayResource(content);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + entity.getName() + "\"")
                .contentType(MediaType.parseMediaType(
                        entity.getMediaType() == null ? "application/octet-stream" : entity.getMediaType()))
                .contentLength(content.length)
                .body(resource);
    }

    public static Artifact toDto(ArtifactEntity entity) {
        return new Artifact(entity.getId(), entity.getRunId(), entity.getProjectId(),
                entity.getName(), entity.getMediaType(), entity.getSizeBytes(),
                entity.getSha256(), entity.getCreatedAt(), entity.isDownloadable());
    }
}
