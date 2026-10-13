package com.dsh.agent.model;

import com.dsh.agent.control.CancellationToken;
import com.dsh.api.dto.Usage;
import com.dsh.application.DshProperties;
import com.fasterxml.jackson.databind.JsonNode;
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
 * DeepSeek 生产适配网关（内部接口契约 §4）。
 *
 * <ul>
 *   <li>同步：POST /chat/completions，解析完整响应；</li>
 *   <li>流式：stream:true，逐行解析 SSE data:，拼接 content/reasoning_content/tool_call arguments；</li>
 *   <li>重试：429/5xx 指数退避，maxRetries 次；每次重试前检查取消令牌；</li>
 *   <li>取消：token.checkpoint() 前置 + 每次重试前检查；已收到 2xx 不重试（避免工具副作用重复）。</li>
 * </ul>
 */
@Component
public class DeepSeekModelGateway implements ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekModelGateway.class);
    public static final String PROVIDER = "deepseek";

    private final CredentialStore credentialStore;
    private final DshProperties.Provider providerConfig;
    private final HttpClient httpClient;

    public DeepSeekModelGateway(CredentialStore credentialStore, DshProperties properties) {
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
        ObjectNode body = DeepSeekRequestMapper.buildRequestBody(request);
        boolean streaming = isStreaming(request);

        int attempt = 0;
        while (true) {
            if (token != null) {
                token.checkpoint();
            }
            try {
                if (streaming) {
                    return streamingGenerate(request, apiKey, body);
                }
                return syncGenerate(request, apiKey, body);
            } catch (RetryableGatewayException e) {
                attempt++;
                if (attempt > providerConfig.maxRetries()) {
                    throw new ModelGatewayException("供应商重试耗尽: " + e.getMessage());
                }
                long delay = providerConfig.retryBaseDelayMs() * (1L << (attempt - 1));
                log.warn("DeepSeek 可重试失败 attempt={} delay={}ms: {}", attempt, delay, e.getMessage());
                sleepUninterruptibly(delay);
            }
        }
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
                return DeepSeekResponseParser.parse(root);
            }
            if (status == 429 || status >= 500) {
                throw new RetryableGatewayException("HTTP " + status + ": " + truncate(response.body()));
            }
            throw new ModelGatewayException("DeepSeek 调用失败 HTTP " + status + ": " + truncate(response.body()));
        } catch (RetryableGatewayException e) {
            throw e;
        } catch (ModelGatewayException e) {
            throw e;
        } catch (Exception e) {
            // 网络错误、超时等可重试
            throw new RetryableGatewayException("网络错误: " + e.getMessage());
        }
    }

    /** 流式 SSE：逐行解析 data: 并拼接，返回完整 ModelResponse（流式无 usage → UNKNOWN）。 */
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
                throw new ModelGatewayException("DeepSeek 流式调用失败 HTTP " + status);
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
            baseUrl = System.getProperty("dsh.deepseek.baseUrl", "https://api.deepseek.com/v1");
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalized + "/chat/completions");
    }

    private boolean isStreaming(ModelRequest request) {
        return request.streaming();
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

    /**
     * 流式增量累加器：拼接 content、reasoning_content、每个 tool_call 的 arguments。
     * finish_reason 取最后一个非 null 的 delta.finish_reason。
     */
    private static final class StreamAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoningContent = new StringBuilder();
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
            String rc = msg.path("reasoning_content").asText(null);
            if (rc != null) {
                reasoningContent.append(rc);
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
            String reasoning = reasoningContent.length() == 0 ? null : reasoningContent.toString();
            return new ModelResponse(text, calls, mappedFinish, Usage.unknown(),
                    providerResponseId, reasoning);
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
