package com.dsh.api;

import com.dsh.application.ArtifactStore;
import com.dsh.storage.ArtifactEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DSH-007 Artifacts 验收：产物元数据查询、下载权限控制、内容 sha256 一致。
 */
class ArtifactIntegrationTest extends ApiTestBase {

    @Autowired
    private ArtifactStore artifactStore;

    private String bootstrapToolRun() throws Exception {
        String projectId = createProject("产物项目-" + UUID.randomUUID(),
                "D:/code/MyAgent/DSH/backend/target/test-run");
        trustProject(projectId, 1);
        String profileId = createProfile("artifact-test-" + UUID.randomUUID(), "fake-tool");
        String threadId = createThread(projectId, profileId);
        JsonNode accepted = submitTurn(threadId, "调用工具演示");
        return accepted.get("rootRunId").asText();
    }

    @Test
    void artifactMetadataAndDownload() throws Exception {
        String runId = bootstrapToolRun();
        byte[] content = "diff --git a/test b/test\n+hello\n".getBytes(StandardCharsets.UTF_8);

        ArtifactEntity saved = artifactStore.save(runId, "proj-x", "test.diff",
                "application/x-diff", content, true);
        assertNotNull(saved.getId());

        // 元数据
        mockMvc.perform(get("/api/v1/artifacts/" + saved.getId())
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saved.getId()))
                .andExpect(jsonPath("$.name").value("test.diff"))
                .andExpect(jsonPath("$.mediaType").value("application/x-diff"))
                .andExpect(jsonPath("$.sizeBytes").value(content.length))
                .andExpect(jsonPath("$.sha256").value(saved.getSha256()))
                .andExpect(jsonPath("$.downloadable").value(true));

        // 下载
        mockMvc.perform(get("/api/v1/artifacts/" + saved.getId() + "/content")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("test.diff")));
    }

    @Test
    void nonDownloadableArtifactReturns403() throws Exception {
        String runId = bootstrapToolRun();
        byte[] content = "internal".getBytes(StandardCharsets.UTF_8);
        ArtifactEntity saved = artifactStore.save(runId, "proj-x", "internal.log",
                "text/plain", content, false);

        mockMvc.perform(get("/api/v1/artifacts/" + saved.getId() + "/content")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownArtifactReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/artifacts/art_nonexistent")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isNotFound());
    }

    @Test
    void artifactSha256MatchesContent() throws Exception {
        String runId = bootstrapToolRun();
        byte[] content = "hello artifact".getBytes(StandardCharsets.UTF_8);
        ArtifactEntity saved = artifactStore.save(runId, "proj-x", "a.txt",
                "text/plain", content, true);

        byte[] read = artifactStore.readContent(saved.getId());
        assertEquals(saved.getSha256(), com.dsh.common.Digest.sha256Hex(read));
    }
}
