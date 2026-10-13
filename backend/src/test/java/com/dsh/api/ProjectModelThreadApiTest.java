package com.dsh.api;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Projects/ModelProfiles/Threads CRUD、ETag/If-Match 与入参校验（DSH-002）。 */
class ProjectModelThreadApiTest extends ApiTestBase {

    @Test
    void projectCrudAndEtag() throws Exception {
        String name = "项目-" + uniqueKey("p");
        MvcResult created = postJson("/api/v1/projects",
                "{\"name\":\"" + name + "\",\"rootPath\":\"D:/code/demo-1\"}", uniqueKey("proj"));
        org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isCreated().match(created);
        header().string("ETag", "\"v1\"").match(created);
        assertEquals(name, jsonField(created, "name"));
        assertEquals("false", jsonField(created, "trusted"));
        String projectId = jsonField(created, "id");

        mockMvc.perform(get("/api/v1/projects/" + projectId))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"v1\""))
                .andExpect(jsonPath("$.rootPath").value("D:/code/demo-1"));

        mockMvc.perform(get("/api/v1/projects?limit=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").exists())
                .andExpect(jsonPath("$.nextCursor").exists());
    }

    @Test
    void projectValidationFailures() throws Exception {
        mockMvc.perform(writeRequest("POST", "/api/v1/projects", "{\"rootPath\":\"D:/x\"}")
                        .header("Idempotency-Key", uniqueKey("proj")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(writeRequest("POST", "/api/v1/projects", "{\"name\":\"x\",\"rootPath\":\"relative/path\"}")
                        .header("Idempotency-Key", uniqueKey("proj")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/projects/proj_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void trustRequiresMatchingIfMatch() throws Exception {
        String projectId = createProject("信任项目-" + uniqueKey("t"), "D:/code/demo-2");

        // 缺失 If-Match → 428
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/trust")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .content("{\"trusted\":true}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        // 格式错误（含 *）→ 400
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/trust")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("If-Match", "*")
                        .content("{\"trusted\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 版本不符 → 412
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/trust")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("If-Match", "\"v999\"")
                        .content("{\"trusted\":true}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 正确 → 200，版本 v2，trusted=true
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/trust")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("If-Match", "\"v1\"")
                        .content("{\"trusted\":true}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"v2\""))
                .andExpect(jsonPath("$.trusted").value(true));

        // 再次信任为 false → v3
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/trust")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("If-Match", "\"v2\"")
                        .content("{\"trusted\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trusted").value(false));
    }

    @Test
    void modelProfileCreatePatchValidate() throws Exception {
        String body = "{\"name\":\"配置-" + uniqueKey("m") + "\",\"provider\":\"fake\",\"model\":\"fake-model\","
                + "\"baseUrl\":\"http://127.0.0.1:9\",\"options\":"
                + "{\"thinking\":\"disabled\",\"maxOutputTokens\":4096,\"streaming\":false}}";
        MvcResult created = postJson("/api/v1/model-profiles", body, uniqueKey("prof"));
        org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isCreated().match(created);
        header().string("ETag", "\"v1\"").match(created);
        assertEquals("UNKNOWN", jsonField(created, "capabilityStatus"));
        String profileId = jsonField(created, "id");

        // 缺 baseUrl → 400
        mockMvc.perform(writeRequest("POST", "/api/v1/model-profiles",
                        "{\"name\":\"x\",\"provider\":\"fake\",\"model\":\"m\","
                                + "\"options\":{\"thinking\":\"disabled\",\"maxOutputTokens\":1,\"streaming\":false}}")
                        .header("Idempotency-Key", uniqueKey("prof")))
                .andExpect(status().isBadRequest());

        // thinking=disabled 且 reasoningEffort 非空 → 422
        mockMvc.perform(writeRequest("POST", "/api/v1/model-profiles",
                        "{\"name\":\"x\",\"provider\":\"fake\",\"model\":\"m\",\"baseUrl\":\"http://127.0.0.1:9\","
                                + "\"options\":{\"thinking\":\"disabled\",\"reasoningEffort\":\"low\","
                                + "\"maxOutputTokens\":1,\"streaming\":false}}")
                        .header("Idempotency-Key", uniqueKey("prof")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        // 未知枚举 provider → 400
        mockMvc.perform(writeRequest("POST", "/api/v1/model-profiles",
                        "{\"name\":\"x\",\"provider\":\"anthropic\",\"model\":\"m\",\"baseUrl\":\"http://127.0.0.1:9\","
                                + "\"options\":{\"thinking\":\"disabled\",\"maxOutputTokens\":1,\"streaming\":false}}")
                        .header("Idempotency-Key", uniqueKey("prof")))
                .andExpect(status().isBadRequest());

        // PATCH：空补丁 → 400；baseUrl 变化 → 技术性变更 → v2 且 capabilityStatus 仍 UNKNOWN
        mockMvc.perform(patch("/api/v1/model-profiles/" + profileId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("If-Match", "\"v1\"")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/v1/model-profiles/" + profileId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("If-Match", "\"v1\"")
                        .content("{\"baseUrl\":\"http://127.0.0.1:10\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"v2\""))
                .andExpect(jsonPath("$.capabilityStatus").value("UNKNOWN"));

        // validate：fake + CONNECTIVITY → passed=true
        mockMvc.perform(writeRequest("POST", "/api/v1/model-profiles/" + profileId + "/validate",
                        "{\"level\":\"CONNECTIVITY\"}")
                        .header("Idempotency-Key", uniqueKey("val")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileId").value(profileId))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.checks[0].passed").value(true))
                .andExpect(jsonPath("$.usage.status").value("KNOWN"));
    }

    @Test
    void threadCrudAndMessages() throws Exception {
        String projectId = createProject("会话项目-" + uniqueKey("th"), "D:/code/demo-3");
        String profileId = createProfile("会话配置-" + uniqueKey("th"));

        // 缺 modelProfileId → 400
        mockMvc.perform(writeRequest("POST", "/api/v1/projects/" + projectId + "/threads", "{}")
                        .header("Idempotency-Key", uniqueKey("thr")))
                .andExpect(status().isBadRequest());

        // 引用不存在的模型配置 → 404
        mockMvc.perform(writeRequest("POST", "/api/v1/projects/" + projectId + "/threads",
                        "{\"modelProfileId\":\"prof_missing\"}")
                        .header("Idempotency-Key", uniqueKey("thr")))
                .andExpect(status().isNotFound());

        // 默认标题“新会话”
        MvcResult created = postJson("/api/v1/projects/" + projectId + "/threads",
                "{\"modelProfileId\":\"" + profileId + "\"}", uniqueKey("thr"));
        org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isCreated().match(created);
        header().string("ETag", "\"v1\"").match(created);
        assertEquals("新会话", jsonField(created, "title"));
        String threadId = jsonField(created, "id");

        // PATCH 标题
        mockMvc.perform(patch("/api/v1/threads/" + threadId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("If-Match", "\"v1\"")
                        .content("{\"title\":\"排查登录问题\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"v2\""))
                .andExpect(jsonPath("$.title").value("排查登录问题"));

        // 空消息列表
        mockMvc.perform(get("/api/v1/threads/" + threadId + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        // 会话列表分页：仅一条会话且 limit=1 时已取完，nextCursor 为空
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/threads?limit=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }
}
