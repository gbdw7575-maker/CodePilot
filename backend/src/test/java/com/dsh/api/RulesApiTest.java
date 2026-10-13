package com.dsh.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-04 规则读取端点验收（契约 getRules）：
 * 信任项目返回 files + effectiveVersion；未信任项目返回 403；缺失规则返回空 files。
 */
class RulesApiTest extends ApiTestBase {

    @org.junit.jupiter.api.io.TempDir
    Path projectRoot;

    @Test
    void trustedProjectReturnsRulesAndVersion() throws Exception {
        String projectId = createProject("规则项目", projectRoot.toString().replace('\\','/'));
        trustProject(projectId, 1);
        Files.writeString(projectRoot.resolve("AGENTS.md"), "全局规则", StandardCharsets.UTF_8);

        mockMvc.perform(get("/api/v1/projects/" + projectId + "/rules")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.files.length()").value(1))
                .andExpect(jsonPath("$.files[0].relativePath").value("AGENTS.md"))
                .andExpect(jsonPath("$.files[0].sha256").exists())
                .andExpect(jsonPath("$.files[0].content").value("全局规则"))
                .andExpect(jsonPath("$.files[0].truncated").value(false))
                .andExpect(jsonPath("$.effectiveVersion").exists());
    }

    @Test
    void untrustedProjectReturnsForbidden() throws Exception {
        String projectId = createProject("未信任项目", projectRoot.toString().replace('\\','/'));
        // 不执行 trust
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/rules")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingRulesReturnsEmptyFiles() throws Exception {
        String projectId = createProject("无规则项目", projectRoot.toString().replace('\\','/'));
        trustProject(projectId, 1);

        mockMvc.perform(get("/api/v1/projects/" + projectId + "/rules")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files.length()").value(0))
                .andExpect(jsonPath("$.effectiveVersion").exists());
    }
}
