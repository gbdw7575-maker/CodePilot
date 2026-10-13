package com.dsh.agent.mcp;

import com.dsh.agent.model.EnvCredentialStore;
import com.dsh.agent.tool.ToolDefinition;
import com.dsh.agent.tool.ToolExecutionContext;
import com.dsh.agent.tool.ToolOutcome;
import com.dsh.agent.tool.ToolRegistry;
import com.dsh.agent.tool.RunToolMountStore;
import com.dsh.agent.tool.ToolDispatcher;
import com.dsh.api.dto.ToolCapabilityEntry;
import com.dsh.api.dto.event.ContentBlock;
import com.dsh.api.dto.event.ToolOutcomeDto;
import com.dsh.application.DshProperties;
import com.dsh.application.ToolCapabilityService;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-17b MCP 只读接入（环回 fake MCP server，dsh.mcp.enabled=true + allow-loopback=true）：
 * 只读工具注册/非只读拒绝；挂载后执行带凭据与会话头、密钥不泄漏；isError→FAILED
 * 且原文保留；超长输出按 max-chars 截断；会话失效（404）重初始化恢复一次；
 * 未挂载扩展工具不可执行；默认关闭不可达；allow-loopback=false 拒绝环回地址。
 */
@SpringBootTest
class McpDsh017Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "sk-mcp-secret-1";
    private static final AtomicInteger initializeCount = new AtomicInteger();
    private static final AtomicReference<String> lastAuthorization = new AtomicReference<>("");
    private static final AtomicReference<String> lastSessionHeader = new AtomicReference<>("");
    private static final AtomicReference<String> latestSession = new AtomicReference<>("");
    private static final AtomicBoolean expireNextToolCall = new AtomicBoolean(false);

    private static final HttpServer SERVER = startServer();

    private static HttpServer startServer() {
        try {
            System.setProperty("DSH_CREDENTIAL_MCP_KEY", SECRET);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", McpDsh017Test::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** fake MCP server：initialize 发会话头；tools/list 走 SSE；tools/call 走 JSON。 */
    private static void handle(HttpExchange exchange) throws IOException {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        lastAuthorization.set(auth == null ? "" : auth);
        String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        lastSessionHeader.set(session == null ? "" : session);
        JsonNode request;
        try {
            request = MAPPER.readTree(requestBytes);
        } catch (IOException e) {
            respondPlain(exchange, 400, "bad json");
            return;
        }
        String method = request.path("method").asText("");
        JsonNode id = request.path("id");
        switch (method) {
            case "initialize" -> {
                latestSession.set("sess-" + initializeCount.incrementAndGet());
                exchange.getResponseHeaders().add("Mcp-Session-Id", latestSession.get());
                ObjectNode result = MAPPER.createObjectNode();
                result.put("protocolVersion", "2025-06-18");
                result.set("capabilities", MAPPER.createObjectNode());
                ObjectNode info = MAPPER.createObjectNode();
                info.put("name", "fake-mcp");
                info.put("version", "0.1");
                result.set("serverInfo", info);
                respondRpc(exchange, 200, id, result, "application/json; charset=utf-8", false);
            }
            case "notifications/initialized" -> {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            }
            case "tools/list" -> {
                if (!latestSession.get().equals(lastSessionHeader.get())) {
                    respondPlain(exchange, 404, "session expired");
                    return;
                }
                respondRpc(exchange, 200, id, toolsResult(), "text/event-stream; charset=utf-8", true);
            }
            case "tools/call" -> {
                if (expireNextToolCall.compareAndSet(true, false)) {
                    respondPlain(exchange, 404, "session expired");
                    return;
                }
                if (!latestSession.get().equals(lastSessionHeader.get())) {
                    respondPlain(exchange, 404, "session expired");
                    return;
                }
                String name = request.path("params").path("name").asText("");
                ObjectNode result = MAPPER.createObjectNode();
                if ("multi".equals(name)) {
                    ObjectNode first = MAPPER.createObjectNode();
                    first.put("type", "text");
                    first.put("text", "第一段");
                    ObjectNode image = MAPPER.createObjectNode();
                    image.put("type", "image");
                    image.put("mimeType", "image/png");
                    image.put("data", "ZmFrZQ==");
                    ObjectNode last = MAPPER.createObjectNode();
                    last.put("type", "text");
                    last.put("text", "第二段");
                    result.set("content", MAPPER.createArrayNode().add(first).add(image).add(last));
                    result.put("isError", false);
                } else if ("boom".equals(name)) {
                    ObjectNode block = MAPPER.createObjectNode();
                    block.put("type", "text");
                    block.put("text", "远程内部错误");
                    result.set("content", MAPPER.createArrayNode().add(block));
                    result.put("isError", true);
                } else {
                    String query = request.path("params").path("arguments").path("query").asText("");
                    ObjectNode block = MAPPER.createObjectNode();
                    block.put("type", "text");
                    block.put("text", query.startsWith("LONG")
                            ? "L".repeat(3000)
                            : "查询结果：3 条 for " + query);
                    result.set("content", MAPPER.createArrayNode().add(block));
                    result.put("isError", false);
                }
                respondRpc(exchange, 200, id, result, "application/json; charset=utf-8", false);
            }
            default -> respondPlain(exchange, 400, "unknown method: " + method);
        }
    }

    private static JsonNode toolsResult() {
        ObjectNode result = MAPPER.createObjectNode();
        var tools = MAPPER.createArrayNode();
        ObjectNode echo = MAPPER.createObjectNode();
        echo.put("name", "echo_search");
        echo.put("description", "回显搜索查询");
        ObjectNode annotations = MAPPER.createObjectNode();
        annotations.put("readOnlyHint", true);
        echo.set("annotations", annotations);
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode queryProp = MAPPER.createObjectNode();
        queryProp.put("type", "string");
        queryProp.put("description", "搜索关键词");
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("query", queryProp);
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("query"));
        echo.set("inputSchema", schema);
        tools.add(echo);

        ObjectNode boom = MAPPER.createObjectNode();
        boom.put("name", "boom");
        boom.put("description", "远程返回 isError 的工具");
        ObjectNode boomAnnotations = MAPPER.createObjectNode();
        boomAnnotations.put("readOnlyHint", true);
        boom.set("annotations", boomAnnotations);
        boom.set("inputSchema", MAPPER.createObjectNode().put("type", "object"));
        tools.add(boom);

        ObjectNode multi = MAPPER.createObjectNode();
        multi.put("name", "multi");
        multi.put("description", "多块内容测试");
        multi.set("annotations", boomAnnotations.deepCopy());
        multi.set("inputSchema", MAPPER.createObjectNode().put("type", "object"));
        tools.add(multi);

        ObjectNode writer = MAPPER.createObjectNode();
        writer.put("name", "write_remote");
        writer.put("description", "写远程文件");
        ObjectNode writeAnnotations = MAPPER.createObjectNode();
        writeAnnotations.put("readOnlyHint", false);
        writer.set("annotations", writeAnnotations);
        writer.set("inputSchema", MAPPER.createObjectNode().put("type", "object"));
        tools.add(writer);

        result.set("tools", tools);
        return result;
    }

    private static void respondRpc(HttpExchange exchange, int status, JsonNode id,
                                   JsonNode result, String contentType, boolean sse) throws IOException {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (!id.isMissingNode() && !id.isNull()) {
            response.set("id", id);
        }
        response.set("result", result);
        String payload = sse ? "data: " + response + "\n\n" : response.toString();
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respondPlain(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static int port() {
        return SERVER.getAddress().getPort();
    }

    @DynamicPropertySource
    static void mcpProperties(DynamicPropertyRegistry registry) {
        registry.add("dsh.mcp.enabled", () -> "true");
        registry.add("dsh.mcp.endpoint", () -> "http://127.0.0.1:" + port() + "/mcp");
        registry.add("dsh.mcp.credential-ref", () -> "mcp-key");
        registry.add("dsh.mcp.allow-loopback", () -> "true");
        registry.add("dsh.mcp.max-chars", () -> "200");
    }

    @AfterAll
    static void cleanCredential() {
        System.clearProperty("DSH_CREDENTIAL_MCP_KEY");
        SERVER.stop(0);
    }

    @Autowired
    private ToolDispatcher dispatcher;
    @Autowired
    private RunToolMountStore mountStore;
    @Autowired
    private ToolRegistry registry;
    @Autowired
    private ToolCapabilityService capabilityService;
    @Autowired
    private McpClient mcpClient;
    @Autowired
    private ProjectRepository projectRepository;

    @TempDir
    Path projectRoot;

    private void persistProject(String projectId) {
        if (projectRepository.findById(projectId).isEmpty()) {
            projectRepository.save(new ProjectEntity(projectId, "mcp环回测试项目",
                    projectRoot.toString(), true, Instant.now(), Instant.now()));
        }
    }

    private String mountedRun(String projectId, String tool) {
        String runId = "run-mcp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        mountStore.mount(runId, tool);
        persistProject(projectId);
        return runId;
    }

    private ToolOutcomeDto dispatch(String projectId, String runId, String tool, String argumentsJson) {
        return dispatcher.dispatch(runId, "thr-mcp", "turn-mcp", projectId,
                Instant.now().plusSeconds(600), tool, argumentsJson, "call-mcp-" + tool);
    }

    private static String textOf(ToolOutcomeDto outcome) {
        return ((ContentBlock.TextBlock) outcome.content().get(0)).text();
    }

    @Test
    @Transactional
    void readOnlyToolsRegisteredAndReachable() {
        assertTrue(mcpClient.enabled(), "enabled 配置下客户端应可用");
        var definition = registry.definition("mcp_echo_search");
        assertTrue(definition.isPresent(), "只读工具应被注册");
        assertEquals("MCP", definition.get().category());
        assertEquals("READ_ONLY", definition.get().sideEffect());
        assertTrue(definition.get().description().startsWith("[MCP 只读]"));
        assertTrue(definition.get().inputSchema().path("properties").has("query"),
                "inputSchema 应透传远程定义");
        assertTrue(registry.isExtension("mcp_echo_search"), "动态工具应进扩展目录");
        assertTrue(registry.definition("mcp_write_remote").isEmpty(), "非只读工具不得注册");

        var snapshot = capabilityService.snapshot();
        ToolCapabilityEntry entry = snapshot.extensions().stream()
                .filter(t -> t.name().equals("mcp_echo_search")).findFirst().orElseThrow();
        assertTrue(entry.authorized() && entry.reachable(), "只读扩展工具应可达");
        assertTrue(snapshot.extensions().stream().noneMatch(t -> t.name().equals("mcp_write_remote")),
                "非只读工具对模型不可见");
    }

    @Test
    @Transactional
    void mountedCallSucceedsWithCredentialAndSession() {
        String runId = mountedRun("proj-mcp-call", "mcp_echo_search");
        ToolOutcomeDto outcome = dispatch("proj-mcp-call", runId, "mcp_echo_search",
                "{\"query\":\"DSH 文档\"}");
        assertEquals("SUCCEEDED", outcome.status(), outcome.summary());
        String text = textOf(outcome);
        assertTrue(text.contains("查询结果：3 条 for DSH 文档"));
        assertEquals("Bearer " + SECRET, lastAuthorization.get(), "凭据应进入 Authorization 头");
        assertTrue(lastSessionHeader.get().startsWith("sess-"), "应携带 MCP 会话头");
        assertFalse(text.contains(SECRET), "密钥不得进入工具输出");
        assertFalse(outcome.summary().contains(SECRET), "密钥不得进入摘要");
        var structured = outcome.structuredContent();
        assertEquals("mcp", structured.path("server").asText());
        assertEquals("echo_search", structured.path("tool").asText());
        assertFalse(structured.path("isError").asBoolean());
        assertFalse(outcome.truncated());
        mountStore.release(runId);
    }

    @Test
    @Transactional
    void remoteIsErrorMappedToFailedWithContentKept() {
        String runId = mountedRun("proj-mcp-err", "mcp_boom");
        ToolOutcomeDto outcome = dispatch("proj-mcp-err", runId, "mcp_boom", "{}");
        assertEquals("FAILED", outcome.status());
        assertEquals("MCP_TOOL_ERROR", outcome.errorCode());
        assertTrue(outcome.isError());
        assertTrue(textOf(outcome).contains("远程内部错误"), "isError 原文应保留");
        assertEquals(1, outcome.structuredContent().path("blockCount").asInt());
        mountStore.release(runId);
    }

    @Test
    @Transactional
    void oversizedOutputTruncatedToMaxChars() {
        String runId = mountedRun("proj-mcp-long", "mcp_echo_search");
        ToolOutcomeDto outcome = dispatch("proj-mcp-long", runId, "mcp_echo_search",
                "{\"query\":\"LONG-term\"}");
        assertEquals("SUCCEEDED", outcome.status(), outcome.summary());
        assertTrue(outcome.truncated(), "超长输出应标记截断: " + outcome.summary()
                + " / charCount=" + outcome.structuredContent().path("charCount").asInt());
        assertTrue(textOf(outcome).contains("[正文已截断至 200 字符]"));
        assertTrue(outcome.structuredContent().path("truncated").asBoolean());
        assertEquals(3000, outcome.structuredContent().path("content").get(0).path("text").asText().length(),
                "模型视图截断后，结构化结果仍应保存 MCP 原文");
        mountStore.release(runId);
    }

    @Test
    @Transactional
    void multipleMcpBlocksKeepTypesAndOriginalFields() {
        String runId = mountedRun("proj-mcp-multi", "mcp_multi");
        ToolOutcomeDto outcome = dispatch("proj-mcp-multi", runId, "mcp_multi", "{}");
        assertEquals("SUCCEEDED", outcome.status());
        assertEquals("mcp_multi", outcome.source());
        assertEquals("call-mcp-mcp_multi", outcome.callId());
        JsonNode blocks = outcome.structuredContent().path("content");
        assertEquals(3, blocks.size());
        assertEquals("text", blocks.get(0).path("type").asText());
        assertEquals("第一段", blocks.get(0).path("text").asText());
        assertEquals("image", blocks.get(1).path("type").asText());
        assertEquals("image/png", blocks.get(1).path("mimeType").asText());
        assertEquals("ZmFrZQ==", blocks.get(1).path("data").asText());
        assertEquals("第二段", blocks.get(2).path("text").asText());
        assertTrue(textOf(outcome).contains("不支持的内容块类型: image"));
        mountStore.release(runId);
    }

    @Test
    @Transactional
    void sessionInvalidationRecoversViaReinitialize() {
        long before = initializeCount.get();
        expireNextToolCall.set(true);
        String runId = mountedRun("proj-mcp-recover", "mcp_echo_search");
        ToolOutcomeDto outcome = dispatch("proj-mcp-recover", runId, "mcp_echo_search",
                "{\"query\":\"恢复后查询\"}");
        assertEquals("SUCCEEDED", outcome.status(), outcome.summary());
        assertEquals(before + 1, initializeCount.get(), "会话失效应重 initialize 一次并重试成功");
        assertTrue(textOf(outcome).contains("恢复后查询"));
        mountStore.release(runId);
    }

    @Test
    @Transactional
    void unmountedMcpToolNotExecutable() {
        persistProject("proj-mcp-nomount");
        ToolOutcomeDto outcome = dispatch("proj-mcp-nomount", "run-mcp-nomount-1",
                "mcp_echo_search", "{\"query\":\"x\"}");
        assertEquals("FAILED", outcome.status());
        assertEquals("TOOL_NOT_MOUNTED", outcome.errorCode());
    }

    @Test
    void disabledByDefaultClientNotEnabledAndNotAuthorized() {
        DshProperties props = new DshProperties(null, null, null, null, null, null, null,
                null, null, null, null, null,
                new DshProperties.Mcp(false, "http://127.0.0.1:" + port() + "/mcp", "", false, 5, 20, 20000), null, null);
        McpClient client = new McpClient(props, new EnvCredentialStore(), new ObjectMapper());
        assertFalse(client.enabled(), "enabled=false 时客户端不可用");
        McpTool tool = new McpTool(client, props.mcp(), "echo_search", "搜索", null, new ObjectMapper());
        assertFalse(tool.extensionMeta().authorized(), "禁用时扩展元数据应标记未授权");
    }

    @Test
    void loopbackDeniedWithoutAllowLoopback() {
        DshProperties props = new DshProperties(null, null, null, null, null, null, null,
                null, null, null, null, null,
                new DshProperties.Mcp(true, "http://127.0.0.1:" + port() + "/mcp", "", false, 5, 20, 20000), null, null);
        McpClient client = new McpClient(props, new EnvCredentialStore(), new ObjectMapper());
        assertTrue(client.enabled());
        McpTool tool = new McpTool(client, props.mcp(), "echo_search", "搜索", null, new ObjectMapper());
        ToolOutcome outcome = tool.execute(
                new ObjectMapper().createObjectNode().put("query", "x"),
                new ToolExecutionContext("run-x", "thr-x", "turn-x", "call-x", "proj-x",
                        null, Instant.now().plusSeconds(60), null, null, new ObjectMapper()));
        assertEquals("DENIED", outcome.status());
        assertEquals("BLOCKED_HOST", outcome.errorCode());
    }
}
