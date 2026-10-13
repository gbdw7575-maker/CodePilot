package com.dsh.agent.tool;

import com.dsh.workspace.SafePaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** list_files：查看授权任务副本目录；不跟随符号链接，不越出副本；glob 可选、结果有界。 */
@Component
public class ListFilesTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;
    private static final Set<String> SKIP_DIRS = Set.of(".git");

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public ToolOutcome execute(JsonNode args, ToolExecutionContext ctx) throws Exception {
        Path root = ctx.lease().dir();
        Path dir = SafePaths.resolve(root, args.path("path").asText());
        if (!Files.isDirectory(dir)) {
            throw new ToolException("NOT_A_DIRECTORY", "不是目录或不存在: " + SafePaths.relativeUnix(root, dir));
        }
        int limit = args.path("limit").asInt(DEFAULT_LIMIT);
        String glob = args.path("glob").asText(null);
        PathMatcher matcher = (glob == null || glob.isBlank())
                ? null : root.getFileSystem().getPathMatcher("glob:" + glob);

        List<Path> matched;
        boolean truncated;
        try (Stream<Path> walk = Files.walk(dir, 64)) {
            List<Path> all = walk
                    .filter(p -> !p.equals(dir))
                    .filter(p -> !containsSkippedSegment(root, p))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            Stream<Path> stream = all.stream();
            if (matcher != null) {
                stream = stream.filter(p -> matcher.matches(Paths.get(SafePaths.relativeUnix(root, p)))
                        || matcher.matches(p.getFileName()));
            }
            List<Path> filtered = stream.toList();
            truncated = filtered.size() > limit;
            matched = filtered.subList(0, Math.min(limit, filtered.size()));
        }

        ArrayNode entries = ctx.objectMapper().createArrayNode();
        for (Path p : matched) {
            String rel = SafePaths.relativeUnix(root, p);
            entries.add(Files.isDirectory(p) && !Files.isSymbolicLink(p) ? rel + "/" : rel);
        }
        ObjectNode structured = ctx.objectMapper().createObjectNode();
        structured.put("path", SafePaths.relativeUnix(root, dir));
        structured.put("truncated", truncated);
        structured.set("entries", entries);

        StringBuilder text = new StringBuilder();
        entries.forEach(n -> {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(n.asText());
        });
        return ToolOutcome.succeeded(
                "列出 " + entries.size() + (truncated ? "+" : "") + " 个条目", text.toString(), structured, name());
    }

    /** .git 整棵子树（含深层对象）不列出；符号链接本身可列出但不跟随。 */
    private static boolean containsSkippedSegment(Path root, Path path) {
        for (Path segment : root.relativize(path)) {
            if (SKIP_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }
}
