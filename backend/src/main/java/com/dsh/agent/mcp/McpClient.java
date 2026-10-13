package com.dsh.agent.mcp;

import com.dsh.agent.model.CredentialStore;
import com.dsh.application.DshProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP Streamable HTTP 客户端（D-17b，只读切片）：
 * JSON-RPC 2.0 over HTTP POST——initialize 建立会话（Mcp-Session-Id）、
 * notifications/initialized、tools/list、tools/call。
 *
 * <p>安全与恢复：endpoint 仅 http/https 且默认拒绝环回/内网地址（SSRF 防护，
 * allow-loopback 仅供本机联调）；credentialRef 经 CredentialStore 解析为
 * Authorization 头，密钥不进入日志、异常或结果；会话失效（404/410）时重新
 * initialize 后重试一次（错误恢复闭环）；协议错误与超时有界、可观测。</p>
 */
@Component
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final DshProperties.Mcp config;
    private final CredentialStore credentialStore;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final AtomicInteger nextId = new AtomicInteger(1);

    private final Object sessionLock = new Object();
    private String sessionId;
    private String negotiatedVersion = PROTOCOL_VERSION;

    public McpClient(DshProperties properties, CredentialStore credentialStore, ObjectMapper objectMapper) {
        this.config = properties.mcp();
        this.credentialStore = credentialStore;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean enabled() {
        return config.enabled() && config.endpoint() != null && !config.endpoint().isBlank();
    }

    /** 建立 MCP 会话并返回 tools/list 结果（result.tools 数组）。 */
    public JsonNode listTools() {
        ensureSession();
        return requireResult(parseBody(send("tools/list", objectMapper.createObjectNode(), false)));
    }

    /** tools/call：返回完整 result（content 数组 + isError）。 */
    public JsonNode callTool(String remoteName, JsonNode arguments) {
        ensureSession();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", remoteName);
        params.set("arguments", arguments == null ? objectMapper.createObjectNode() : arguments);
        return requireResult(parseBody(send("tools/call", params, false)));
    }

    /** initialize + initialized 通知；已建立会话时幂等。会话失效可重入。 */
    private void ensureSession() {
        synchronized (sessionLock) {
            if (sessionId != null) {
                return;
            }
            ObjectNode clientInfo = objectMapper.createObjectNode();
            clientInfo.put("name", "dsh-agent");
            clientInfo.put("version", "1.0.0");
            ObjectNode params = objectMapper.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.set("capabilities", objectMapper.createObjectNode());
            params.set("clientInfo", clientInfo);

            HttpResponse<String> response = send("initialize", params, false);
            String header = response.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (header != null && !header.isBlank()) {
                sessionId = header;
            }
            JsonNode result = requireResult(parseBody(response));
            if (result.hasNonNull("protocolVersion")) {
                negotiatedVersion = result.path("protocolVersion").asText(PROTOCOL_VERSION);
            }
            // initialize 完成通知：无 id、不解析响应体
            send("notifications/initialized", null, true);
            log.info("MCP 会话已建立（协议 {}）", negotiatedVersion);
        }
    }

    private JsonNode requireResult(JsonNode response) {
        JsonNode error = response.path("error");
        if (error.isObject()) {
            throw new McpClientException("MCP_PROTOCOL_ERROR",
                    "MCP 错误 " + error.path("code").asInt(0) + ": "
                            + abbreviate(error.path("message").asText("未知错误"), 300));
        }
        return response.path("result");
    }

    /**
     * 单次 JSON-RPC POST。会话失效（404/410）时重置会话、重新 initialize 后重试一次
     * （错误恢复闭环）。返回原始响应；notification 返回体可能为空。
     */
    private HttpResponse<String> send(String method, ObjectNode params, boolean notification) {
        ensureEndpointAllowed();
        HttpResponse<String> response = doSend(method, params, notification, true);
        int status = response.statusCode();
        if ((status == 404 || status == 410) && sessionId != null && !notification) {
            synchronized (sessionLock) {
                sessionId = null;
            }
            ensureSession();
            response = doSend(method, params, notification, true);
        }
        return response;
    }

    private HttpResponse<String> doSend(String method, ObjectNode params, boolean notification,
                                        boolean withSession) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        if (!notification) {
            request.put("id", nextId.getAndIncrement());
        }
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.endpoint()))
                .timeout(Duration.ofSeconds(config.readTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", negotiatedVersion)
                .POST(HttpRequest.BodyPublishers.ofString(request.toString()));
        if (withSession && sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (config.credentialRef() != null && !config.credentialRef().isBlank()) {
            // 密钥只进入请求头；不写入日志/异常/结果
            builder.header("Authorization", "Bearer " + credentialStore.resolve(config.credentialRef()));
        }
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 404 || status == 410) {
                // 会话失效：返回原始响应，由 send() 决定重初始化重试
                return response;
            }
            if (status < 200 || status >= 300) {
                throw new McpClientException("HTTP_" + status, "MCP server 返回 " + status);
            }
            return response;
        } catch (IOException e) {
            throw new McpClientException("MCP_NETWORK_ERROR",
                    "MCP 传输失败: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpClientException("MCP_NETWORK_ERROR", "MCP 请求被中断", e);
        }
    }

    /** 解析响应体：JSON 或 SSE（取最后一个含 JSON-RPC 结果的 data: 帧）。 */
    private JsonNode parseBody(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();
        String body = response.body();
        try {
            if (contentType.contains("text/event-stream")) {
                return parseSse(body);
            }
            if (body == null || body.isBlank()) {
                throw new McpClientException("MCP_PROTOCOL_ERROR", "MCP 响应为空");
            }
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new McpClientException("MCP_PROTOCOL_ERROR", "MCP 响应解析失败", e);
        }
    }

    private JsonNode parseSse(String body) throws IOException {
        List<String> frames = new ArrayList<>();
        for (String line : body.split("\r?\n")) {
            if (line.startsWith("data:")) {
                frames.add(line.substring(5).trim());
            }
        }
        for (int i = frames.size() - 1; i >= 0; i--) {
            String frame = frames.get(i);
            if (frame.isBlank()) {
                continue;
            }
            JsonNode node = objectMapper.readTree(frame);
            if (node.has("result") || node.has("error")) {
                return node;
            }
        }
        throw new McpClientException("MCP_PROTOCOL_ERROR", "SSE 响应中无 JSON-RPC 结果");
    }

    private void ensureEndpointAllowed() {
        URI uri;
        try {
            uri = URI.create(config.endpoint());
        } catch (IllegalArgumentException e) {
            throw new McpClientException("MCP_CONFIG_INVALID", "MCP endpoint 无法解析");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new McpClientException("MCP_CONFIG_INVALID", "MCP endpoint 仅支持 http/https");
        }
        if (config.allowLoopback()) {
            return;
        }
        String host = uri.getHost();
        if (host == null) {
            throw new McpClientException("MCP_CONFIG_INVALID", "MCP endpoint 缺少主机名");
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                throw new McpClientException("BLOCKED_HOST",
                        "MCP endpoint 为私有/环回地址: " + host + "（未授权）");
            }
        } catch (java.net.UnknownHostException e) {
            throw new McpClientException("BLOCKED_HOST", "MCP endpoint 无法解析: " + host);
        }
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
