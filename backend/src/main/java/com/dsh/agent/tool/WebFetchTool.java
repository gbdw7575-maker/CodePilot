package com.dsh.agent.tool;

import com.dsh.application.DshProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * web_fetch 扩展工具（D-15 外部资料，EXTENSION）：
 * 只读 GET 抓取任务确需的外部网页正文，保留来源 URL、最终 URL、抓取时间等可追溯信息。
 *
 * <p>安全边界：仅 http/https；默认拒绝环回/内网地址（SSRF 防护，allow-loopback 仅供本机联调）；
 * 手动跟随重定向（最多 5 跳）逐跳校验目标地址并记录跳转链；响应体按 maxBytes 封顶、
 * 正文按 maxChars 截断；仅接受文本类内容。输出经 Dispatcher 的 D-12 注入中和。</p>
 */
@Component
public class WebFetchTool implements Tool {

    private static final int MAX_REDIRECTS = 5;

    private final HttpClient client;
    private final DshProperties.Web config;

    public WebFetchTool(DshProperties properties) {
        this.config = properties.web();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public ToolDefinition customDefinition() {
        var factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        ObjectNode url = factory.objectNode();
        url.put("type", "string");
        url.put("description", "要抓取的 http/https 网页地址");
        ObjectNode maxChars = factory.objectNode();
        maxChars.put("type", "integer");
        maxChars.put("description", "可选；返回正文最大字符数，默认取全局配置");
        ObjectNode properties = factory.objectNode();
        properties.set("url", url);
        properties.set("maxChars", maxChars);
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", factory.arrayNode().add("url"));
        schema.set("additionalProperties", factory.booleanNode(false));
        return new ToolDefinition(name(),
                "只读抓取外部网页正文：官方文档、公开资料；保留来源 URL、重定向链与抓取时间",
                "1.0.0", "WEB", "READ_ONLY", schema);
    }

    @Override
    public ExtensionMeta extensionMeta() {
        return new ExtensionMeta(
                "任务确需外部资料时读取网页正文（先 web_search 定位来源再抓取选中页面）",
                config.enabled());
    }

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public ToolOutcome execute(JsonNode arguments, ToolExecutionContext ctx) {
        String requested = arguments.path("url").asText("");
        URI current;
        try {
            current = URI.create(requested.trim());
        } catch (IllegalArgumentException e) {
            return ToolOutcome.failed("INVALID_URL", "URL 无法解析: " + requested, name());
        }
        String scheme = current.getScheme() == null ? "" : current.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return ToolOutcome.failed("INVALID_URL",
                    "仅支持 http/https，拒绝 " + (scheme.isEmpty() ? "无协议" : scheme) + " 地址", name());
        }
        int maxChars = arguments.path("maxChars").asInt(0);
        if (maxChars <= 0 || maxChars > config.maxChars()) {
            maxChars = config.maxChars();
        }

        List<String> redirectChain = new ArrayList<>();
        HttpResponse<InputStream> response;
        try {
            response = fetchFollowingRedirects(current, redirectChain);
        } catch (BlockedHostException e) {
            return ToolOutcome.denied("BLOCKED_HOST",
                    "拒绝访问私有/环回地址: " + e.getMessage() + "（未授权不联网）", name());
        } catch (java.net.http.HttpTimeoutException e) {
            return ToolOutcome.failed("FETCH_TIMEOUT",
                    "抓取超时（connect " + config.connectTimeoutSeconds() + "s / read "
                            + config.readTimeoutSeconds() + "s）", name());
        } catch (Exception e) {
            return ToolOutcome.failed("FETCH_FAILED",
                    "抓取失败: " + abbreviate(e.toString(), 300), name());
        }

        int status = response.statusCode();
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (status >= 400) {
            return ToolOutcome.failed("HTTP_" + status,
                    "目标返回 " + status + "（" + abbreviate(current.toString(), 200) + "）", name());
        }
        String lowerType = contentType.toLowerCase();
        boolean textual = lowerType.startsWith("text/") || lowerType.contains("json")
                || lowerType.contains("xml");
        if (!textual) {
            return ToolOutcome.failed("UNSUPPORTED_CONTENT_TYPE",
                    "仅支持文本类内容，拒绝 " + (contentType.isEmpty() ? "未知类型" : contentType), name());
        }

        byte[] body;
        boolean bodyTruncated;
        try (InputStream in = response.body()) {
            body = in.readNBytes(config.maxBytes() + 1);
        } catch (Exception e) {
            return ToolOutcome.failed("FETCH_FAILED", "读取响应体失败: " + abbreviate(e.getMessage(), 200), name());
        }
        bodyTruncated = body.length > config.maxBytes();
        if (bodyTruncated) {
            body = java.util.Arrays.copyOf(body, config.maxBytes());
        }

        Charset charset = charsetOf(contentType);
        String raw = new String(body, charset);
        String text = lowerType.contains("html") ? htmlToText(raw) : raw;
        boolean textTruncated = text.length() > maxChars;
        if (textTruncated) {
            text = text.substring(0, maxChars) + "\n[正文已截断至 " + maxChars + " 字符]";
        }

        ObjectNode structured = ctx.objectMapper().createObjectNode();
        structured.put("url", requested);
        structured.put("finalUrl", response.uri().toString());
        structured.put("status", status);
        structured.put("contentType", contentType);
        structured.put("fetchedAt", Instant.now().toString());
        structured.put("bytes", body.length);
        structured.put("charCount", text.length());
        structured.put("truncated", textTruncated || bodyTruncated);
        ArrayNode chain = structured.putArray("redirectChain");
        redirectChain.forEach(chain::add);

        return ToolOutcome.succeeded(
                "已抓取 " + abbreviate(response.uri().toString(), 120) + "（" + status + "，"
                        + text.length() + " 字符" + (redirectChain.isEmpty() ? "" : "，重定向 " + redirectChain.size() + " 跳")
                        + "）",
                text, structured, name());
    }

    /** 手动跟随重定向：逐跳校验 scheme 与目标地址，记录跳转链。 */
    private HttpResponse<InputStream> fetchFollowingRedirects(URI start, List<String> chain)
            throws Exception {
        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkHostAllowed(current);
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(config.readTimeoutSeconds()))
                    .header("User-Agent", "dsh-agent-web-fetch/1.0 (read-only)")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("location").orElse("");
                if (location.isBlank()) {
                    return response;
                }
                URI next = current.resolve(location);
                if (hop == MAX_REDIRECTS) {
                    throw new IllegalStateException("重定向超过 " + MAX_REDIRECTS + " 跳");
                }
                chain.add(next.toString());
                current = next;
                continue;
            }
            return response;
        }
        throw new IllegalStateException("unreachable");
    }

    private void checkHostAllowed(URI uri) throws BlockedHostException {
        if (config.allowLoopback()) {
            return;
        }
        String host = uri.getHost();
        if (host == null) {
            throw new BlockedHostException("(无主机名)");
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                throw new BlockedHostException(host + " → " + address.getHostAddress());
            }
        } catch (java.net.UnknownHostException e) {
            throw new BlockedHostException(host + "（无法解析）");
        }
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            var match = java.util.regex.Pattern
                    .compile("charset=([\\w\\-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(contentType);
            if (match.find()) {
                try {
                    return Charset.forName(match.group(1));
                } catch (Exception ignored) {
                    // 非法 charset 名回退 UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** 最小 HTML 转文本：去 script/style/注释，块级标签转行，内联标签转空格，收敛空白。 */
    static String htmlToText(String html) {
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?i)<(br|/p|/div|/h[1-6]|/li|/tr)[^>]*>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" ?\n ?", "\n")
                .replaceAll("\n{3,}", "\n\n");
        return s.strip();
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "未知错误";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 私有/环回地址拒绝（SSRF 防护）。 */
    private static final class BlockedHostException extends Exception {
        BlockedHostException(String message) {
            super(message);
        }
    }
}
