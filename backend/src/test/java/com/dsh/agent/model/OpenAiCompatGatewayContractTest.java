package com.dsh.agent.model;

import com.dsh.agent.control.CancellationToken;
import com.dsh.agent.tool.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-17c openai-compat 网关协议测试：内嵌 HttpServer 模拟 OpenAI 兼容端点。
 * 覆盖：请求体不含 DeepSeek 特有字段（thinking/reasoning_content）、
 * 标准响应解析（tool_calls/finish_reason/usage 含 cached_tokens→cacheHit），
 * usage 缺失为 UNKNOWN 不补零、429 退避重试、流式拼接、baseUrl 必填、凭据仅进 Authorization 头。
 */
class OpenAiCompatGatewayContractTest {

    private HttpServer server;
    private int port;
    private OpenAiCompatModelGateway gateway;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>("");
    private final AtomicReference<JsonNode> lastRequestBody = new AtomicReference<>(null);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
        System.setProperty("DSH_CREDENTIAL_TESTKEY", "sk-test");
        gateway = new OpenAiCompatModelGateway(new EnvCredentialStore(),
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
        return new ModelRequest("openai-compat", "gpt-4o-mini", messages, 1024, "enabled",
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

    private void respond(String ctx, ObjectNode body) {
        server.createContext(ctx, ex -> {
            lastAuthorization.set(ex.getRequestHeaders().getFirst("Authorization") == null
                    ? "" : ex.getRequestHeaders().getFirst("Authorization"));
            byte[] req = ex.getRequestBody().readAllBytes();
            try {
                lastRequestBody.set(mapper.readTree(req));
            } catch (IOException ignored) {
                // 请求体解析失败留给断言暴露
            }
            byte[] b = mapper.writeValueAsBytes(body);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
    }

    @Test
    void requestBodyOmitsProviderSpecificFields() throws Exception {
        ObjectNode resp = mapper.createObjectNode();
        ObjectNode choice = resp.putArray("choices").addObject();
        choice.putObject("message").put("content", "ok");
        choice.put("finish_reason", "stop");
        respond("/v1/chat/completions", resp);

        // assistant 携带 reasoning_content + thinking=enabled：openai-compat 均不发送
        List<ModelMessage> messages = List.of(
                ModelMessage.system("你是助手"),
                ModelMessage.assistant("上一轮回答", "上一轮思考"),
                ModelMessage.user("继续"));
        ModelRequest req = baseRequest(messages, List.of(echoTool()), true);
        gateway.generate(req, new CancellationToken());

        JsonNode body = lastRequestBody.get();
        assertFalse(body.has("thinking"), "openai-compat 不发送 DeepSeek thinking 字段");
        assertEquals("gpt-4o-mini", body.path("model").asText());
        assertEquals(1024, body.path("max_tokens").asInt());
        assertTrue(body.path("messages").isArray());
        assertEquals(3, body.path("messages").size());
        JsonNode assistant = body.path("messages").get(1);
        assertEquals("assistant", assistant.path("role").asText());
        assertFalse(assistant.has("reasoning_content"), "assistant 消息不发送 reasoning_content");
        assertTrue(body.has("tools"), "工具定义应透传");
    }

    @Test
    void syncResponseParsesToolCallsAndOpenAiUsage() throws Exception {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("id", "chatcmpl-1");
        ObjectNode choice = resp.putArray("choices").addObject();
        ObjectNode message = choice.putObject("message");
        message.put("content", "");
        ArrayNode tcs = message.putArray("tool_calls");
        ObjectNode tc = tcs.addObject();
        tc.put("id", "call-1");
        tc.put("type", "function");
        tc.putObject("function").put("name", "echo").put("arguments", "{\"text\":\"hello\"}");
        choice.put("finish_reason", "tool_calls");
        ObjectNode usage = resp.putObject("usage");
        usage.put("prompt_tokens", 100);
        usage.put("completion_tokens", 50);
        usage.putObject("prompt_tokens_details").put("cached_tokens", 64);

        respond("/v1/chat/completions", resp);

        ModelResponse r = gateway.generate(baseRequest(List.of(ModelMessage.user("调用 echo")),
                List.of(echoTool()), false), new CancellationToken());
        assertEquals(FinishReason.TOOL_CALLS, r.finishReason());
        assertEquals(1, r.toolCalls().size());
        assertEquals("echo", r.toolCalls().get(0).name());
        assertNull(r.reasoningContent(), "OpenAI 标准协议无 reasoning_content");
        assertEquals("KNOWN", r.usage().status());
        assertEquals(100, r.usage().inputTokens());
        assertEquals(50, r.usage().outputTokens());
        assertEquals(64, r.usage().cacheHitTokens(), "cached_tokens 记入 cacheHit");
        assertNull(r.usage().cacheMissTokens(), "cacheMiss 不补零");
        assertEquals("chatcmpl-1", r.providerResponseId());
    }

    @Test
    void missingUsageIsUnknown() throws Exception {
        ObjectNode resp = mapper.createObjectNode();
        ObjectNode choice = resp.putArray("choices").addObject();
        choice.putObject("message").put("content", "ok");
        choice.put("finish_reason", "stop");
        respond("/v1/chat/completions", resp);

        ModelResponse r = gateway.generate(baseRequest(List.of(ModelMessage.user("ping")),
                List.of(), false), new CancellationToken());
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

        ModelResponse r = gateway.generate(baseRequest(List.of(ModelMessage.user("ping")),
                List.of(), false), new CancellationToken());
        assertEquals("ok", r.text());
        assertEquals(2, attempts.get());
    }

    @Test
    void streamingConcatenatesContentAndToolArguments() throws Exception {
        StringBuilder sse = new StringBuilder();
        sse.append(sseDelta(delta -> delta.put("content", "你"), null));
        sse.append(sseDelta(delta -> delta.put("content", "好"), null));
        sse.append(sseDelta(delta -> {
            ArrayNode tcs = delta.putArray("tool_calls");
            ObjectNode tc = tcs.addObject();
            tc.put("index", 0).put("id", "c1");
            tc.putObject("function").put("name", "echo").put("arguments", "{\"text\":\"");
        }, null));
        sse.append(sseDelta(delta -> {
            ArrayNode tcs = delta.putArray("tool_calls");
            ObjectNode tc = tcs.addObject();
            tc.put("index", 0);
            tc.putObject("function").put("arguments", "hello\"}");
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

        ModelResponse resp = gateway.generate(baseRequest(List.of(ModelMessage.user("hi")),
                List.of(echoTool()), true), new CancellationToken());
        assertEquals("你好", resp.text());
        assertEquals(FinishReason.TOOL_CALLS, resp.finishReason());
        assertEquals(1, resp.toolCalls().size());
        assertEquals("{\"text\":\"hello\"}", resp.toolCalls().get(0).argumentsJson());
        assertEquals("UNKNOWN", resp.usage().status());
    }

    private interface DeltaBuilder {
        void build(ObjectNode delta);
    }

    private String sseDelta(DeltaBuilder db, String finishReason) throws IOException {
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
    void baseUrlIsRequired() {
        ModelRequest req = new ModelRequest("openai-compat", "gpt-4o-mini",
                List.of(ModelMessage.user("ping")), 1024, "disabled", List.of(),
                "TESTKEY", "", false);
        ModelGatewayException e = assertThrows(ModelGatewayException.class,
                () -> gateway.generate(req, new CancellationToken()));
        assertTrue(e.getMessage().contains("baseUrl"));
    }

    @Test
    void credentialOnlyInAuthHeader() throws Exception {
        ObjectNode resp = mapper.createObjectNode();
        ObjectNode choice = resp.putArray("choices").addObject();
        choice.putObject("message").put("content", "ok");
        choice.put("finish_reason", "stop");
        respond("/v1/chat/completions", resp);

        ModelResponse r = gateway.generate(baseRequest(List.of(ModelMessage.user("ping")),
                List.of(), false), new CancellationToken());
        assertEquals("Bearer sk-test", lastAuthorization.get(), "凭据应进入 Authorization 头");
        assertEquals("ok", r.text());
        assertFalse(r.toString().contains("sk-test"), "密钥不得进入响应对象");
        assertFalse(String.valueOf(lastRequestBody.get()).contains("sk-test"), "密钥不得进入请求体");
    }
}
