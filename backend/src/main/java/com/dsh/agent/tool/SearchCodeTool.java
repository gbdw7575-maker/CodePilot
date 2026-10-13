package com.dsh.agent.tool;

import com.dsh.search.CodeMatch;
import com.dsh.search.LiteralCodeScanner;
import com.dsh.search.RipgrepRunner;
import com.dsh.search.SearchResult;
import com.dsh.workspace.SafePaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * search_code（D-05）：任务副本内代码搜索。
 *
 * <p>主路径为 ripgrep（内容字面 / 正则、尊重 .gitignore、跳过隐藏与二进制、CJK 原生 UTF-8），
 * rg 不可用时仅 literal 模式回退受限字面扫描（{@link LiteralCodeScanner}）；
 * regex 模式在 rg 缺失时明确失败，不静默改变语义。</p>
 *
 * <p>边界：path 经 SafePaths 收敛在任务副本内（拒绝绝对路径/.. /符号链接外逃）；
 * rg 不跟随符号链接；零命中是成功空结果；结果数、单行长度与输出总量均有界。</p>
 */
@Component
public class SearchCodeTool implements Tool {

    public static final String ENGINE_RG = "rg";
    public static final String ENGINE_LITERAL_FALLBACK = "literal-fallback";
    private static final int DEFAULT_LIMIT = 50;

    private final RipgrepRunner ripgrep;

    public SearchCodeTool(RipgrepRunner ripgrep) {
        this.ripgrep = ripgrep;
    }

    @Override
    public String name() {
        return "search_code";
    }

    @Override
    public ToolOutcome execute(JsonNode args, ToolExecutionContext ctx) throws Exception {
        Path root = ctx.lease().dir();
        String query = args.path("query").asText();
        String pathArg = args.path("path").asText(".");
        String mode = args.path("mode").asText("literal");
        int limit = args.path("limit").asInt(DEFAULT_LIMIT);
        List<String> includeGlobs = globs(args.path("includeGlobs"));
        List<String> excludeGlobs = globs(args.path("excludeGlobs"));
        if (!"literal".equals(mode) && !"regex".equals(mode)) {
            throw new ToolException("INVALID_INPUT", "mode 必须是 literal 或 regex: " + mode);
        }

        Path base = SafePaths.resolve(root, pathArg);
        if (!Files.exists(base)) {
            throw new ToolException("PATH_NOT_FOUND", "搜索路径不存在: " + pathArg);
        }

        boolean regex = "regex".equals(mode);
        String engine;
        SearchResult result;
        if ("docker".equals(ctx.sandbox().mode())) {
            engine = ENGINE_RG;
            result = ripgrep.searchInSandbox(base, query, regex, limit,
                    includeGlobs, excludeGlobs, ctx.lease(), ctx.sandbox());
        } else if (ripgrep.isAvailable()) {
            engine = ENGINE_RG;
            result = ripgrep.search(base, query, regex, limit, includeGlobs, excludeGlobs);
        } else if (regex) {
            throw new ToolException("RG_UNAVAILABLE",
                    "正则搜索需要 ripgrep（rg），当前环境不可用；请改用 literal 模式");
        } else {
            if (!includeGlobs.isEmpty() || !excludeGlobs.isEmpty()) {
                throw new ToolException("RG_UNAVAILABLE", "文件 glob 过滤需要 ripgrep，当前环境不可用");
            }
            engine = ENGINE_LITERAL_FALLBACK;
            result = LiteralCodeScanner.scan(root, base, query, limit);
        }
        if (result.errorCode() != null) {
            throw new ToolException(result.errorCode(), result.errorMessage());
        }

        // runner 的命中路径相对搜索目录，拼回工作区根相对路径（POSIX 风格）
        Path searchDir = Files.isDirectory(base) ? base : base.getParent();
        String prefix = SafePaths.relativeUnix(root, searchDir);

        ArrayNode matches = ctx.objectMapper().createArrayNode();
        StringBuilder text = new StringBuilder();
        for (CodeMatch m : result.matches()) {
            String fullPath = ".".equals(prefix) ? m.path() : prefix + "/" + m.path();
            ObjectNode node = ctx.objectMapper().createObjectNode();
            node.put("path", fullPath);
            node.put("line", m.line());
            node.put("text", m.text());
            node.put("lineTruncated", m.lineTruncated());
            matches.add(node);
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(fullPath).append(':').append(m.line()).append(": ").append(m.text());
        }

        ObjectNode structured = ctx.objectMapper().createObjectNode();
        structured.put("query", query);
        structured.put("mode", mode);
        structured.put("engine", engine);
        structured.put("truncated", result.truncated());
        structured.set("matches", matches);

        String summary = (matches.size() == 0 ? "零命中" : "匹配 " + matches.size())
                + (result.truncated() ? "+" : "") + " 处（" + engine + "）";
        return new ToolOutcome("SUCCEEDED", null, summary, text.toString(),
                structured, result.truncated(), 0, null, false, name());
    }

    private static List<String> globs(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) values.add(item.asText());
        return List.copyOf(values);
    }
}
