package com.dsh.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** DSH-002 API 测试公共基座：建项目→信任→建模型配置→建会话。 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiTestBase {

    protected static final String CLIENT_HEADER = "X-DSH-Client";
    protected static final String CLIENT_VALUE = "local-ui";

    @Autowired
    protected MockMvc mockMvc;

    protected final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    protected String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 带 local-ui 标记与幂等键的 POST。 */
    protected MvcResult postJson(String url, String json, String idempotencyKey) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(json))
                .andReturn();
    }

    protected MockHttpServletRequestBuilder writeRequest(String method, String url, String json) {
        MockHttpServletRequestBuilder builder;
        builder = switch (method) {
            case "POST" -> post(url);
            case "PUT" -> put(url);
            default -> throw new IllegalArgumentException(method);
        };
        return builder.contentType(MediaType.APPLICATION_JSON)
                .header(CLIENT_HEADER, CLIENT_VALUE)
                .content(json);
    }

    protected String jsonField(MvcResult result, String field) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path(field).asText();
    }

    /** 创建未信任项目，返回 projectId。 */
    protected String createProject(String name, String rootPath) throws Exception {
        MvcResult result = postJson("/api/v1/projects",
                "{\"name\":\"" + name + "\",\"rootPath\":\"" + rootPath + "\"}", uniqueKey("proj-create"));
        assertEquals(201, result.getResponse().getStatus(), "创建项目失败");
        return jsonField(result, "id");
    }

    /** 信任项目。 */
    protected void trustProject(String projectId, int currentVersion) throws Exception {
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/trust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("If-Match", "\"v" + currentVersion + "\"")
                        .content("{\"trusted\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    /**
     * 创建 fake 供应商模型配置（默认 fake-waiting：首轮追问暂停，保持活动轮次语义）。
     */
    protected String createProfile(String name) throws Exception {
        return createProfile(name, "fake-waiting");
    }

    /** 创建指定 model 脚本的 fake 供应商模型配置（fake-answer/fake-tool/fake-error/fake-waiting）。 */
    protected String createProfile(String name, String model) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"provider\":\"fake\",\"model\":\"" + model + "\","
                + "\"baseUrl\":\"http://127.0.0.1:9\",\"options\":"
                + "{\"thinking\":\"disabled\",\"maxOutputTokens\":4096,\"streaming\":false}}";
        MvcResult result = postJson("/api/v1/model-profiles", body, uniqueKey("prof-create"));
        assertEquals(201, result.getResponse().getStatus(), "创建模型配置失败");
        return jsonField(result, "id");
    }

    /** 提交一个 Turn（202），返回响应 JSON。 */
    protected com.fasterxml.jackson.databind.JsonNode submitTurn(String threadId, String input) throws Exception {
        MvcResult result = postJson("/api/v1/threads/" + threadId + "/turns",
                mapper.writeValueAsString(java.util.Map.of("input", input)), uniqueKey("turn"));
        assertEquals(202, result.getResponse().getStatus(), "提交 Turn 应返回 202");
        return mapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 读取事件补拉接口，返回响应 JSON。 */
    protected com.fasterxml.jackson.databind.JsonNode getEvents(String threadId, String queryString) throws Exception {
        String url = "/api/v1/threads/" + threadId + "/events" + (queryString == null ? "" : queryString);
        MvcResult result = mockMvc.perform(get(url)
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), "事件补拉应返回 200: "
                + result.getResponse().getStatus());
        return mapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 在项目下创建会话，返回 threadId。 */
    protected String createThread(String projectId, String profileId) throws Exception {
        MvcResult result = postJson("/api/v1/projects/" + projectId + "/threads",
                "{\"modelProfileId\":\"" + profileId + "\"}", uniqueKey("thr-create"));
        assertEquals(201, result.getResponse().getStatus(), "创建会话失败");
        return jsonField(result, "id");
    }

    /** 组合：创建已信任项目 + 模型配置 + 会话。 */
    protected String bootstrapThread() throws Exception {
        String projectId = createProject("测试项目-" + UUID.randomUUID(), "D:/code/MyAgent/DSH/backend/target/test-run");
        trustProject(projectId, 1);
        String profileId = createProfile("fake 配置-" + UUID.randomUUID());
        return createThread(projectId, profileId);
    }
}
