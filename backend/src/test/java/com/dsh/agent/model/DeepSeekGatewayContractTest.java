package com.dsh.agent.model;

import com.dsh.agent.control.CancellationToken;
import com.dsh.agent.tool.ToolDefinition;
import com.dsh.api.dto.Usage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepSeek 网关契约测试：用内嵌 HttpServer 模拟供应商端点。
 * 覆盖：thinking+tool 续接、流式拼接、usage 归一化、429 重试、取消、工具协议。
 */
class DeepSeekGatewayContractTest {

    private HttpServer server;
    private int port;
    private DeepSeekModelGateway gateway;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
        System.setProperty("DSH_CREDENTIAL_TESTKEY", "sk-test");
        gateway = new DeepSeekModelGateway(new EnvCredentialStore(),
                new com.dsh.application.DshProperties(
                        null, null, null, null, null, null,
                        new com.dsh.application.DshProperties.Provider(5, 30, 2, 10),
                        null, null, null, null, null, null, null, null));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        System.clearProperty("DSH_CREDENTIAL_TESTKEY");
    }

    private ModelRequest baseRequest(List<ModelMessage> messages, List<ToolDefinition> tools, boolean streaming) {
        return new ModelRequest("deepseek", "deepseek-reasoner", messages, 1024, "enabled",
                tools, "TESTKEY", "http://127.0.0.1:" + port + "/v1", streaming);
    }

    private ToolDefinition echoTool() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("text").put("type", "string");
        schema.putArray("required").add("text");
        return new ToolDefinition("echo", "回显", "1.0", "utility", "READ_ONLY", schema);
    }

    @Test
    void thinkingAndToolContinuation() throws Exception {
        // 首轮：返回 reasoning_content + tool_calls
        ObjectNode firstResp = mapper.createObjectNode();
        firstResp.put("id", "resp-1");
        ObjectNode choice = firstResp.putArray("choices").addObject();
        ObjectNode message = choice.putObject("message");
        message.put("content", "");
        message.put("reasoning_content", "我需要调用 echo 工具");
        ArrayNode tcs = message.putArray("tool_calls");
        ObjectNode tc = tcs.addObject();
        tc.put("id", "call-1");
        tc.put("type", "function");
        tc.putObject("function").put("name", "echo").put("arguments", "{\"text\":\"hello\"}");
        choice.put("finish_reason", "tool_calls");
        firstResp.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 50);

        server.createContext("/v1/chat/completions", ex -> {
            byte[] body = mapper.writeValueAsBytes(firstResp);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });

        ModelRequest req = baseRequest(List.of(ModelMessage.user("调用 echo")), List.of(echoTool()), false);
        ModelResponse resp = gateway.generate(req, new CancellationToken());

        assertEquals(FinishReason.TOOL_CALLS, resp.finishReason());
        assertEquals("我需要调用 echo 工具", resp.reasoningContent());
        assertEquals(1, resp.toolCalls().size());
        assertEquals("echo", resp.toolCalls().get(0).name());
        assertEquals("{\"text\":\"hello\"}", resp.toolCalls().get(0).argumentsJson());
        assertEquals("KNOWN", resp.usage().status());
        assertEquals(100, resp.usage().inputTokens());
        assertEquals(50, resp.usage().outputTokens());
    }

    @Test
    void streamingConcatenatesContentAndToolArguments() throws Exception {
        StringBuilder sse = new StringBuilder();
        sse.append(sseDelta(mapper, delta -> delta.put("content", "你"), null));
        sse.append(sseDelta(mapper, delta -> delta.put("content", "好"), null));
        sse.append(sseDelta(mapper, delta -> delta.put("reasoning_content", "思考中"), null));
        sse.append(sseDelta(mapper, delta -> {
            ArrayNode tcs = delta.putArray("tool_calls");
            ObjectNode tc = tcs.addObject();
            tc.put("index", 0).put("id", "c1");
            tc.putObject("function").put("name", "echo").put("arguments", "{\"text\":\"");
        }, null));
        sse.append(sseDelta(mapper, delta -> {
            ArrayNode tcs = delta.putArray("tool_calls");
            ObjectNode tc = tcs.addObject();
            tc.put("index", 0);
            tc.putObject("function").put("arguments", "hel");
        }, null));
        sse.append(sseDelta(mapper, delta -> {
            ArrayNode tcs = delta.putArray("tool_calls");
            ObjectNode tc = tcs.addObject();
            tc.put("index", 0);
            tc.putObject("function").put("arguments", "lo\"}");
        }, "tool_calls"));
        sse.append("data: [DONE]\n\n");

        byte[] body = sse.toString().getBytes();
        server.createContext("/v1/chat/completions", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });

        ModelRequest req = baseRequest(List.of(ModelMessage.user("hi")), List.of(echoTool()), true);
        ModelResponse resp = gateway.generate(req, new CancellationToken());

        assertEquals("你好", resp.text());
        assertEquals("思考中", resp.reasoningContent());
        assertEquals(FinishReason.TOOL_CALLS, resp.finishReason());
        assertEquals(1, resp.toolCalls().size());
        assertEquals("echo", resp.toolCalls().get(0).name());
        assertEquals("{\"text\":\"hello\"}", resp.toolCalls().get(0).argumentsJson());
        assertEquals("UNKNOWN", resp.usage().status());
    }

    private interface DeltaBuilder {
        void build(ObjectNode delta);
    }

    private static String sseDelta(ObjectMapper mapper, DeltaBuilder db, String finishReason) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", "r1");
        ObjectNode choice = root.putArray("choices").addObject();
        ObjectNode delta = choice.putObject("delta");
        db.build(delta);
        if (finishReason == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finishReason);
        }
        return "data: " + mapper.writeValueAsString(root) + "\n\n";
    }

    @Test
    void usageNormalizationWithCacheTokens() throws Exception {
        ObjectNode resp = mapper.createObjectNode();
        ObjectNode choice = resp.putArray("choices").addObject();
        choice.putObject("message").put("content", "ok");
        choice.put("finish_reason", "stop");
        ObjectNode usage = resp.putObject("usage");
        usage.put("prompt_tokens", 100);
        usage.put("completion_tokens", 20);
        usage.put("prompt_cache_hit_tokens", 60);
        usage.put("prompt_cache_miss_tokens", 40);

        server.createContext("/v1/chat/completions", ex -> {
            byte[] b = mapper.writeValueAsBytes(resp);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });

        ModelRequest req = baseRequest(List.of(ModelMessage.user("ping")), List.of(), false);
        ModelResponse r = gateway.generate(req, new CancellationToken());

        assertEquals(100, r.usage().inputTokens());
        assertEquals(20, r.usage().outputTokens());
        assertEquals(60, r.usage().cacheHitTokens());
        assertEquals(40, r.usage().cacheMissTokens());
    }

    @Test
    void missingUsageIsUnknown() throws Exception {
        ObjectNode resp = mapper.createObjectNode();
        ObjectNode choice = resp.putArray("choices").addObject();
        choice.putObject("message").put("content", "ok");
        choice.put("finish_reason", "stop");

        server.createContext("/v1/chat/completions", ex -> {
            byte[] b = mapper.writeValueAsBytes(resp);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });

        ModelRequest req = baseRequest(List.of(ModelMessage.user("ping")), List.of(), false);
        ModelResponse r = gateway.generate(req, new CancellationToken());
        assertEquals("UNKNOWN", r.usage().status());
        assertNull(r.usage().inputTokens());
    }

    @Test
    void retryOn429ThenSucceed() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        server.createContext("/v1/chat/completions", ex -> {
            int n = attempts.incrementAndGet();
            if (n <= 1) {
                ex.sendResponseHeaders(429, -1);
                return;
            }
            ObjectNode resp = mapper.createObjectNode();
            ObjectNode choice = resp.putArray("choices").addObject();
            choice.putObject("message").put("content", "ok");
            choice.put("finish_reason", "stop");
            byte[] b = mapper.writeValueAsBytes(resp);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });

        ModelRequest req = baseRequest(List.of(ModelMessage.user("ping")), List.of(), false);
        ModelResponse r = gateway.generate(req, new CancellationToken());
        assertEquals("ok", r.text());
        assertEquals(2, attempts.get());
    }

    @Test
    void cancelledTokenDoesNotSendRequest() {
        AtomicInteger attempts = new AtomicInteger(0);
        server.createContext("/v1/chat/completions", ex -> attempts.incrementAndGet());

        CancellationToken token = new CancellationToken();
        token.cancel();

        ModelRequest req = baseRequest(List.of(ModelMessage.user("ping")), List.of(), false);
        assertThrows(CancellationToken.RunCancelledException.class,
                () -> gateway.generate(req, token));
        assertEquals(0, attempts.get());
    }

    @Test
    void toolCallArgumentsFullyAssembled() throws Exception {
        String longArgs = "{\"text\":\"" + "a".repeat(2000) + "\"}";
        ObjectNode resp = mapper.createObjectNode();
        ObjectNode choice = resp.putArray("choices").addObject();
        ObjectNode message = choice.putObject("message");
        message.put("content", "");
        ArrayNode tcs = message.putArray("tool_calls");
        ObjectNode tc = tcs.addObject();
        tc.put("id", "call-x");
        tc.put("type", "function");
        tc.putObject("function").put("name", "echo").put("arguments", longArgs);
        choice.put("finish_reason", "tool_calls");

        server.createContext("/v1/chat/completions", ex -> {
            byte[] b = mapper.writeValueAsBytes(resp);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });

        ModelRequest req = baseRequest(List.of(ModelMessage.user("echo long")), List.of(echoTool()), false);
        ModelResponse r = gateway.generate(req, new CancellationToken());
        assertEquals(longArgs, r.toolCalls().get(0).argumentsJson());
        assertTrue(r.toolCalls().get(0).argumentsJson().length() > 2000);
    }
}
