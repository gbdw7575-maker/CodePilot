package com.dsh.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HealthApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthReturnsContractFields() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("X-Request-Id", matchesPattern("^[A-Za-z0-9_-]{1,128}$")))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.contractVersion").value("0.1.0-design"))
                .andExpect(jsonPath("$.timestamp").isString());
    }

    @Test
    void healthTimestampIsRfc3339Utc() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode json =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(3, json.size(), "响应不得携带契约之外的额外字段");
        Instant parsed = Instant.parse(json.get("timestamp").asText());
        org.junit.jupiter.api.Assertions.assertNotNull(parsed);
    }

    @Test
    void errorResponseHeadersMatchBodyRequestId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/definitely-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.details").exists())
                .andReturn();
        String headerId = result.getResponse().getHeader("X-Request-Id");
        String body = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode json =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(headerId, json.get("requestId").asText(), "错误体 requestId 必须与响应头一致");
    }

    @Test
    void unsupportedMethodOnKnownPathIsRejected() throws Exception {
        // POST /health 是已知路径上的不支持方法；写请求先过来源防护，需带 X-DSH-Client 才能到达 405
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/health")
                        .header("X-DSH-Client", "local-ui"))
                .andExpect(status().isMethodNotAllowed());
    }
}
