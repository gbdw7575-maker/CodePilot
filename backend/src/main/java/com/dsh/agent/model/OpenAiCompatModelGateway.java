package com.dsh.agent.model;

import com.dsh.agent.control.CancellationToken;
import com.dsh.api.dto.Usage;
import com.dsh.application.DshProperties;
import com.dsh.agent.tool.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容网关（D-17c）：接入任何实现 OpenAI Chat Completions 协议的服务端点。
 *
 * <p>与 DeepSeek 网关的协议差异：不发送 thinking 等 DeepSeek 特有字段，
 * 不发送/接收 reasoning_content（兼容服务端大多拒绝未知字段）；
 * usage 按 OpenAI 标准解析（prompt_tokens/completion_tokens，
 * prompt_tokens_details.cached_tokens 记入 cacheHit，cacheMiss 保持 null 不补零）。</p>
 *
 * <ul>
 *   <li>同步：POST {baseUrl}/chat/completions；流式：stream:true 逐行解析 SSE；</li>
 *   <li>重试：429/5xx 指数退避 maxRetries 次；每次尝试前检查取消令牌；
 *       已收到 2xx 不重试（避免工具副作用重复执行）；</li>
 *   <li>baseUrl 必填（兼容端点无规范默认值）；密钥仅进入 Authorization 头，
 *       不写入日志、异常或事件。</li>
 * </ul>
 */
@Component
public class OpenAiCompatModelGateway implements ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatModelGateway.class);
    public static final String PROVIDER = "openai-compat";

    private final CredentialStore credentialStore;
    private final DshProperties.Provider providerConfig;
    private final HttpClient httpClient;

    public OpenAiCompatModelGateway(CredentialStore credentialStore, DshProperties properties) {
        this.credentialStore = credentialStore;
        this.providerConfig = properties.provider();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(providerConfig.connectTimeoutSeconds()))
                .build();
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public ModelResponse generate(ModelRequest request, CancellationToken token) {
        if (token != null) {
            token.checkpoint();
        }
        String apiKey = credentialStore.resolve(request.credentialRef());
        ObjectNode body = buildRequestBody(request);
        boolean streaming = request.streaming();

        int attempt = 0;
        while (true) {
            if (token != null) {
                token.checkpoint();
            }
            try {
                return streaming ? streamingGenerate(request, apiKey, body) : syncGenerate(request, apiKey, body);
            } catch (RetryableGatewayException e) {
                attempt++;
                if (attempt > providerConfig.maxRetries()) {
                    throw new ModelGatewayException("供应商重试耗尽: " + e.getMessage());
                }
                long delay = providerConfig.retryBaseDelayMs() * (1L << (attempt - 1));
                log.warn("openai-compat 可重试失败 attempt={} delay={}ms: {}", attempt, delay, e.getMessage());
                sleepUninterruptibly(delay);
            }
        }
    }

    /**
     * OpenAI Chat Completions 请求体：model/messages/tools/max_tokens。
     * 与 DeepSeek 映射的差异：不发 thinking；assistant 消息不带 reasoning_content。
     */
    static ObjectNode buildRequestBody(ModelRequest request) {
        ObjectNode body = JsonSupport.mapper().createObjectNode();
        body.put("model", request.model());

        ArrayNode messages = JsonSupport.mapper().createArrayNode();
        for (ModelMessage msg : request.messages()) {
            messages.add(mapMessage(msg));
        }
        body.set("messages", messages);

        List<ToolDefinition> tools = request.tools();
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArray = JsonSupport.mapper().createArrayNode();
            for (ObjectNode def : ToolDefinitionMapper.toFunctionSchemas(tools)) {
                toolArray.add(def);
            }
            body.set("tools", toolArray);
        }

        body.put("max_tokens", request.maxOutputTokens());
        return body;
    }

    private static ObjectNode mapMessage(ModelMessage msg) {
        ObjectNode node = JsonSupport.mapper().createObjectNode();
        switch (msg.role()) {
            case "system" -> {
                node.put("role", "system");
                node.put("content", msg.content());
            }
            case "user" -> {
                node.put("role", "user");
                node.put("content", msg.content());
            }
            case "assistant" -> {
                node.put("role", "assistant");
                if (msg.content() != null && !msg.content().isEmpty()) {
                    node.put("content", msg.content());
                } else {
                    node.putNull("content");
                }
                // OpenAI 标准协议无 reasoning_content：不发送（兼容端点会拒绝未知字段）
                if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                    ArrayNode calls = JsonSupport.mapper().createArrayNode();
                    for (ModelToolCall call : msg.toolCalls()) {
                        ObjectNode tc = calls.addObject();
                        tc.put("id", call.callId());
                        tc.put("type", "function");
                        ObjectNode fn = tc.putObject("function");
                        fn.put("name", call.name());
                        fn.put("arguments", call.argumentsJson());
                    }
                    node.set("tool_calls", calls);
                }
            }
            case "tool" -> {
                node.put("role", "tool");
                node.put("tool_call_id", msg.toolCallId());
                node.put("content", msg.content());
            }
            default -> throw new IllegalArgumentException("未知消息角色: " + msg.role());
        }
        return node;
    }

    private ModelResponse syncGenerate(ModelRequest request, String apiKey, ObjectNode body) {
        try {
            String requestJson = JsonSupport.mapper().writeValueAsString(body);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(completionsUri(request))
                    .timeout(Duration.ofSeconds(providerConfig.readTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                JsonNode root = JsonSupport.mapper().readTree(response.body());
                return parseResponse(root);
            }
            if (status == 429 || status >= 500) {
                throw new RetryableGatewayException("HTTP " + status + ": " + truncate(response.body()));
            }
            throw new ModelGatewayException("openai-compat 调用失败 HTTP " + status + ": " + truncate(response.body()));
        } catch (RetryableGatewayException e) {
            throw e;
        } catch (ModelGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new RetryableGatewayException("网络错误: " + e.getMessage());
        }
    }

    private ModelResponse streamingGenerate(ModelRequest request, String apiKey, ObjectNode body) {
        body.put("stream", true);
        try {
            String requestJson = JsonSupport.mapper().writeValueAsString(body);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(completionsUri(request))
                    .timeout(Duration.ofSeconds(providerConfig.readTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofLines());

            int status = response.statusCode();
            if (status == 429 || status >= 500) {
                throw new RetryableGatewayException("流式 HTTP " + status);
            }
            if (status < 200 || status >= 300) {
                throw new ModelGatewayException("openai-compat 流式调用失败 HTTP " + status);
            }

            StreamAccumulator acc = new StreamAccumulator();
            response.body().forEach(line -> {
                line = line.trim();
                if (!line.startsWith("data:")) {
                    return;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    return;
                }
                try {
                    JsonNode delta = JsonSupport.mapper().readTree(data);
                    acc.accumulate(delta);
                } catch (Exception e) {
                    log.warn("SSE 行解析失败: {}", e.getMessage());
                }
            });
            return acc.toModelResponse();
        } catch (RetryableGatewayException e) {
            throw e;
        } catch (ModelGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new RetryableGatewayException("流式网络错误: " + e.getMessage());
        }
    }

    private URI completionsUri(ModelRequest request) {
        String baseUrl = request.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ModelGatewayException("openai-compat 需要配置 baseUrl（兼容端点无规范默认值）");
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalized + "/chat/completions");
    }

    /** OpenAI 标准响应解析：usage 缺失为 UNKNOWN；cached_tokens 记入 cacheHit，cacheMiss 不补零。 */
    static ModelResponse parseResponse(JsonNode root) {
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");

        String text = message.path("content").asText(null);
        List<ModelToolCall> toolCalls = new ArrayList<>();
        JsonNode tcs = message.path("tool_calls");
        if (tcs.isArray()) {
            for (JsonNode tc : tcs) {
                String callId = tc.path("id").asText();
                String name = tc.path("function").path("name").asText();
                String args = tc.path("function").path("arguments").asText("{}");
                toolCalls.add(new ModelToolCall(callId, name, args));
            }
        }

        String finishReason = mapFinishReason(choice.path("finish_reason").asText());
        Usage usage = parseUsage(root.path("usage"));
        String providerResponseId = root.path("id").asText(null);
        // OpenAI 标准协议无 reasoning_content
        return new ModelResponse(text, toolCalls, finishReason, usage, providerResponseId, null);
    }

    private static String mapFinishReason(String raw) {
        if (raw == null) {
            return FinishReason.PROTOCOL_ERROR;
        }
        return switch (raw) {
            case "stop" -> FinishReason.STOP;
            case "tool_calls" -> FinishReason.TOOL_CALLS;
            case "length" -> FinishReason.LENGTH;
            case "content_filter" -> FinishReason.LENGTH;
            default -> FinishReason.PROTOCOL_ERROR;
        };
    }

    private static Usage parseUsage(JsonNode usage) {
        if (usage == null || usage.isNull() || usage.isMissingNode()) {
            return Usage.unknown();
        }
        Integer input = intOrNull(usage.path("prompt_tokens"));
        Integer output = intOrNull(usage.path("completion_tokens"));
        // OpenAI 标准缓存命中字段：prompt_tokens_details.cached_tokens
        Integer cacheHit = intOrNull(usage.path("prompt_tokens_details").path("cached_tokens"));
        if (input == null && output == null && cacheHit == null) {
            return Usage.unknown();
        }
        return new Usage("KNOWN", input, output, cacheHit, null, null, null, null);
    }

    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.asInt();
    }

    private static void sleepUninterruptibly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException("重试等待被中断");
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }

    /** 可重试异常：429/5xx/网络错误。收到 2xx 但解析失败不算可重试。 */
    private static final class RetryableGatewayException extends RuntimeException {
        RetryableGatewayException(String message) {
            super(message);
        }
    }

    /** 流式增量累加器（OpenAI 标准：无 reasoning_content）。 */
    private static final class StreamAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final List<ToolCallBuilder> toolCalls = new ArrayList<>();
        private String finishReason = null;
        private String providerResponseId = null;

        void accumulate(JsonNode delta) {
            if (providerResponseId == null) {
                providerResponseId = delta.path("id").asText(null);
            }
            JsonNode choice = delta.path("choices").path(0);
            JsonNode msg = choice.path("delta");

            String c = msg.path("content").asText(null);
            if (c != null) {
                content.append(c);
            }

            JsonNode tcs = msg.path("tool_calls");
            if (tcs.isArray()) {
                for (JsonNode tc : tcs) {
                    int index = tc.path("index").asInt(0);
                    while (toolCalls.size() <= index) {
                        toolCalls.add(new ToolCallBuilder());
                    }
                    ToolCallBuilder builder = toolCalls.get(index);
                    String id = tc.path("id").asText(null);
                    if (id != null) {
                        builder.callId = id;
                    }
                    String name = tc.path("function").path("name").asText(null);
                    if (name != null) {
                        builder.name = name;
                    }
                    String args = tc.path("function").path("arguments").asText(null);
                    if (args != null) {
                        builder.arguments.append(args);
                    }
                }
            }

            String fr = choice.path("finish_reason").asText(null);
            if (fr != null && !"null".equals(fr)) {
                finishReason = fr;
            }
        }

        ModelResponse toModelResponse() {
            List<ModelToolCall> calls = new ArrayList<>();
            for (ToolCallBuilder b : toolCalls) {
                if (b.callId != null || b.name != null) {
                    calls.add(new ModelToolCall(
                            b.callId == null ? "" : b.callId,
                            b.name == null ? "" : b.name,
                            b.arguments.length() == 0 ? "{}" : b.arguments.toString()));
                }
            }
            String mappedFinish = finishReason == null ? FinishReason.STOP : mapFinish(finishReason);
            String text = content.length() == 0 ? null : content.toString();
            return new ModelResponse(text, calls, mappedFinish, Usage.unknown(),
                    providerResponseId, null);
        }

        private static String mapFinish(String raw) {
            return switch (raw) {
                case "stop" -> FinishReason.STOP;
                case "tool_calls" -> FinishReason.TOOL_CALLS;
                case "length" -> FinishReason.LENGTH;
                default -> FinishReason.PROTOCOL_ERROR;
            };
        }
    }

    private static final class ToolCallBuilder {
        String callId;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
