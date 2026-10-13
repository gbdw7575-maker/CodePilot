package com.dsh.api;

import com.dsh.memory.MemoryProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DSH-009 Auto Memory 管理 REST（内部契约 §18.3）：项目作用域查看/编辑/删除/启停，
 * If-Match 并发版本校验（缺失 428、格式错误 400、不匹配 412）。
 */
class MemoryApiTest extends ApiTestBase {

    @Autowired
    private MemoryProvider memoryProvider;

    private String memoryProject() throws Exception {
        String root = "D:/code/MyAgent/DSH/backend/target/test-memory-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Files.createDirectories(Path.of(root));
        String projectId = createProject("记忆REST项目-" + UUID.randomUUID(), root.replace('\\', '/'));
        trustProject(projectId, 1);
        return projectId;
    }

    private String currentVersion(String projectId) throws Exception {
        return mockMvc.perform(get("/api/v1/projects/" + projectId + "/memory")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
    }

    @Test
    void viewReturnsVersionedEmptyMemory() throws Exception {
        String projectId = memoryProject();
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/memory")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.entries").isEmpty())
                .andExpect(jsonPath("$.indexText").isNotEmpty());
    }

    @Test
    void entryEditAndDeleteEnforceIfMatchLifecycle() throws Exception {
        String projectId = memoryProject();
        String etag = currentVersion(projectId); // "v1"
        long version = Long.parseLong(etag.substring(2, etag.length() - 1));
        com.dsh.memory.MemoryEntry saved = memoryProvider.save(projectId, "project",
                "原始背景内容", "全局", "run-1", version);
        String entryId = saved.id();

        // 缺 If-Match → 428；错版本 → 412
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/memory/entries/" + entryId)
                        .contentType("application/json")
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .content("{\"content\":\"新内容\"}"))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/memory/entries/" + entryId)
                        .contentType("application/json")
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("If-Match", "\"v" + version + "\"")
                        .content("{\"content\":\"新内容\"}"))
                .andExpect(status().isPreconditionFailed());

        // 正确版本 → 编辑生效（content 与 scope 均更新）
        String fresh = currentVersion(projectId);
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/memory/entries/" + entryId)
                        .contentType("application/json")
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("If-Match", fresh)
                        .content("{\"content\":\"编辑后的内容\",\"scope\":\"仅后端\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("编辑后的内容"))
                .andExpect(jsonPath("$.scope").value("仅后端"));

        // 删除后查看不再包含（经验删除生效）
        String afterEdit = currentVersion(projectId);
        mockMvc.perform(delete("/api/v1/projects/" + projectId + "/memory/entries/" + entryId)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("If-Match", afterEdit))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/memory")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isEmpty());
    }

    @Test
    void settingsToggleAndRulesEndpoint() throws Exception {
        String projectId = memoryProject();
        String etag = currentVersion(projectId);

        mockMvc.perform(put("/api/v1/projects/" + projectId + "/memory/settings")
                        .contentType("application/json")
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("If-Match", etag)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.version").value(2));

        // 禁用后管理接口仍可查看
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/memory")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // 规则层视图（本测试项目根无 AGENTS.md → exists=false）
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/memory/rules")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false));

        // 非法 body → 4xx
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/memory/settings")
                        .contentType("application/json")
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .content("{\"bogus\":true}"))
                .andExpect(status().is4xxClientError());
    }
}
