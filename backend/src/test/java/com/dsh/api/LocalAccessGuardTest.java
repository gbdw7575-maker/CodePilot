package com.dsh.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本地访问与写请求防护（API-06）：
 * 写请求必须携带 X-DSH-Client: local-ui；Origin/Host 必须本机；请求体 ≤1MiB；
 * allowed-roots 配置后越界项目根目录 → 403 PATH_OUTSIDE_WORKSPACE。
 */
@SpringBootTest(properties = {
        "dsh.security.allowed-roots[0]=D:/code/traework",
        "dsh.security.allowed-roots[1]=D:/code/MyAgent/DSH"
})
class LocalAccessGuardTest extends ApiTestBase {

    @Test
    void readsDoNotRequireClientHeader() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk());
    }

    @Test
    void writeWithoutClientHeaderForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueKey("proj"))
                        .content("{\"name\":\"x\",\"rootPath\":\"D:/code/demo-5\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_DENIED"));
    }

    @Test
    void writeWithWrongClientValueForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, "curl")
                        .header("Idempotency-Key", uniqueKey("proj"))
                        .content("{\"name\":\"x\",\"rootPath\":\"D:/code/demo-5\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_DENIED"));
    }

    @Test
    void crossSiteOriginForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Origin", "https://evil.example")
                        .header("Idempotency-Key", uniqueKey("proj"))
                        .content("{\"name\":\"x\",\"rootPath\":\"D:/code/demo-5\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_DENIED"));

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Origin", "http://evil.example:8080")
                        .header("Idempotency-Key", uniqueKey("proj"))
                        .content("{\"name\":\"x\",\"rootPath\":\"D:/code/demo-5\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void localOriginAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Origin", "http://127.0.0.1:5173")
                        .header("Idempotency-Key", uniqueKey("proj"))
                        .content("{\"name\":\"本机来源\",\"rootPath\":\"D:/code/traework/demo-6\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void nonLoopbackHostForbiddenEvenForReads() throws Exception {
        mockMvc.perform(get("/api/v1/projects").header("Host", "intranet.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_DENIED"));
    }

    @Test
    void oversizedBodyRejectedWith413() throws Exception {
        String hugeInput = "a".repeat(1_100_000);
        mockMvc.perform(post("/api/v1/threads/thr_dummy/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Idempotency-Key", uniqueKey("turn"))
                        .content(mapper.writeValueAsString(java.util.Map.of("input", hugeInput))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("INPUT_TOO_LARGE"));
    }

    @Test
    void rootPathOutsideAllowedRootsForbidden() throws Exception {
        // allowed-roots = D:/code/traework 与 D:/code/MyAgent/DSH；越界 → 403
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Idempotency-Key", uniqueKey("proj"))
                        .content("{\"name\":\"越界项目\",\"rootPath\":\"D:/elsewhere/demo\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PATH_OUTSIDE_WORKSPACE"));

        // 列表内 → 201
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Idempotency-Key", uniqueKey("proj"))
                        .content("{\"name\":\"界内项目\",\"rootPath\":\"D:/code/traework/demo-7\"}"))
                .andExpect(status().isCreated());
    }
}
