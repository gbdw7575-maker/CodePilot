package com.dsh.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实启动冒烟：验证服务可以以 loopback 方式启动并响应健康检查。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthBootSmokeTest {

    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void bootsAndServesHealth() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/health", String.class);
        assertEquals(200, response.getStatusCode().value());
        HttpHeaders headers = response.getHeaders();
        String requestId = headers.getFirst("X-Request-Id");
        assertNotNull(requestId);
        assertTrue(REQUEST_ID.matcher(requestId).matches());
        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"status\":\"UP\""), "意外响应体: " + body);
    }
}
