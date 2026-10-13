package com.dsh.agent.tool;

import com.dsh.application.DshProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * web_search 扩展工具（D-15 外部资料，EXTENSION）：
 * 只读搜索定位外部资料来源，结果保留标题/URL/摘要，供 web_fetch 抓取选中正文。
 *
 * <p>安全边界：搜索走 dsh.web.search-endpoint 配置的只读端点（约定返回
 * {"results":[{"title","url","snippet"}]}），未配置时明确报错而非静默失败；
 * 仅 http/https 结果；enabled=false 时不可发现也不可执行（未经授权不联网）。</p>
 */
@Component
public class WebSearchTool implements Tool {

    private final HttpClient client;
    private final DshProperties.Web config;

    public WebSearchTool(DshProperties properties) {
        this.config = properties.web();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public ToolDefinition customDefinition() {
        var factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        ObjectNode query = factory.objectNode();
        query.put("type", "string");
        query.put("description", "搜索关键词");
        ObjectNode maxResults = factory.objectNode();
        maxResults.put("type", "integer");
        maxResults.put("minimum", 1);
        maxResults.put("maximum", 10);
        maxResults.put("description", "可选；返回结果条数上限，默认 5");
        ObjectNode properties = factory.objectNode();
        properties.set("query", query);
        properties.set("maxResults", maxResults);
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", factory.arrayNode().add("query"));
        schema.set("additionalProperties", factory.booleanNode(false));
        return new ToolDefinition(name(),
                "只读搜索外部资料来源：返回标题/URL/摘要，供 web_fetch 抓取选中页面",
                "1.0.0", "WEB", "READ_ONLY", schema);
    }

    @Override
    public ExtensionMeta extensionMeta() {
        return new ExtensionMeta(
                "任务确需外部资料时先搜索定位来源 URL（再经 web_fetch 读取正文）",
                config.enabled());
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public ToolOutcome execute(JsonNode arguments, ToolExecutionContext ctx) {
        if (config.searchEndpoint().isBlank()) {
            return ToolOutcome.failed("SEARCH_NOT_CONFIGURED",
                    "未配置 dsh.web.search-endpoint，web_search 不可用；请改用本地资料", name());
        }
        String query = arguments.path("query").asText("").trim();
        if (query.isEmpty()) {
            return ToolOutcome.failed("INVALID_ARGUMENTS", "query 不能为空", name());
        }
        int maxResults = arguments.path("maxResults").asInt(5);
        maxResults = Math.min(10, Math.max(1, maxResults));

        String url = config.searchEndpoint()
                + (config.searchEndpoint().contains("?") ? "&" : "?")
                + "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&maxResults=" + maxResults;

        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(config.readTimeoutSeconds()))
                    .header("User-Agent", "dsh-agent-web-search/1.0 (read-only)")
                    .GET()
                    .build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            return ToolOutcome.failed("SEARCH_TIMEOUT",
                    "搜索超时（read " + config.readTimeoutSeconds() + "s）", name());
        } catch (Exception e) {
            return ToolOutcome.failed("SEARCH_FAILED", "搜索请求失败: " + e, name());
        }

        int status = response.statusCode();
        if (status >= 400) {
            return ToolOutcome.failed("HTTP_" + status,
                    "搜索端点返回 " + status + "（" + config.searchEndpoint() + "）", name());
        }

        JsonNode root;
        try {
            root = ctx.objectMapper().readTree(response.body());
        } catch (Exception e) {
            return ToolOutcome.failed("SEARCH_UPSTREAM_ERROR",
                    "搜索端点返回的不是合法 JSON: " + abbreviate(response.body(), 200), name());
        }
        JsonNode resultsNode = root.path("results");
        if (!resultsNode.isArray()) {
            return ToolOutcome.failed("SEARCH_UPSTREAM_ERROR",
                    "搜索端点响应缺少 results 数组", name());
        }

        ArrayNode results = ctx.objectMapper().createArrayNode();
        StringBuilder text = new StringBuilder();
        int index = 0;
        for (JsonNode item : resultsNode) {
            if (index >= maxResults) {
                break;
            }
            String title = item.path("title").asText("");
            String resultUrl = item.path("url").asText("");
            String snippet = item.path("snippet").asText("");
            String scheme = URI.create(resultUrl).getScheme() == null ? "" : URI.create(resultUrl).getScheme();
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                continue;
            }
            ObjectNode entry = results.addObject();
            entry.put("title", title);
            entry.put("url", resultUrl);
            entry.put("snippet", snippet);
            text.append(++index).append(". ").append(title).append("\n   ").append(resultUrl)
                    .append("\n   ").append(snippet).append("\n");
        }

        ObjectNode structured = ctx.objectMapper().createObjectNode();
        structured.put("query", query);
        structured.put("searchedAt", Instant.now().toString());
        structured.put("resultCount", index);
        structured.set("results", results);

        return ToolOutcome.succeeded(
                "搜索「" + abbreviate(query, 60) + "」返回 " + index + " 条结果",
                text.isEmpty() ? "无结果" : text.toString(), structured, name());
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "未知错误";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
