package com.dsh.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * RAG 文档加载与切分（R0 新增）：扫描配置目录下 {@code *.md}，按 {@code ##} 标题切分，
 * 单 chunk 超 chunkSizeLimit 时按段落（空行分隔）再切，超长段落按字符硬切。
 * <p>
 * 切分策略适配现有语料结构（story1.md/story2.md 按 {@code ## 故事一/二/...} 组织），
 * 保留语义完整性。chunkIndex 在单文件内从 0 递增；id = sourcePath + "#" + chunkIndex。
 */
public class RagDocumentLoader {

    private final int chunkSizeLimit;

    public RagDocumentLoader(int chunkSizeLimit) {
        this.chunkSizeLimit = Math.max(100, chunkSizeLimit);
    }

    /** 扫描 baseDir 下所有 .md 文件并切分，返回按文件名排序的 chunk 列表。 */
    public List<RagDocument> load(Path baseDir) throws IOException {
        if (baseDir == null || !Files.isDirectory(baseDir)) {
            return List.of();
        }
        List<RagDocument> docs = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(baseDir)) {
            List<Path> mdFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .collect(Collectors.toList());
            for (Path file : mdFiles) {
                String relative = baseDir.relativize(file).toString().replace('\\', '/');
                docs.addAll(splitFile(file, relative));
            }
        }
        return docs;
    }

    private List<RagDocument> splitFile(Path file, String relativePath) throws IOException {
        String content = Files.readString(file);
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<RagDocument> docs = new ArrayList<>();
        List<Chunk> sections = splitByHeading(content);
        int idx = 0;
        for (Chunk section : sections) {
            // 每块前缀注入故事标题 + 角色/作品身份，避免续块丢失同名指代上下文
            String prefix = buildPrefix(section.heading(), section.cast());
            String body = section.text();
            if (body.length() <= chunkSizeLimit - prefix.length()) {
                docs.add(new RagDocument(relativePath + "#" + idx, relativePath,
                        idx, section.heading(), prefix + body));
                idx++;
            } else {
                for (String pc : splitByParagraph(body, chunkSizeLimit - prefix.length())) {
                    docs.add(new RagDocument(relativePath + "#" + idx, relativePath,
                            idx, section.heading(), prefix + pc));
                    idx++;
                }
            }
        }
        return docs;
    }

    /** 构建每块前缀：故事标题 + 角色/作品列表，续块也能保留同名指代身份。 */
    private String buildPrefix(String heading, String cast) {
        StringBuilder sb = new StringBuilder();
        if (heading != null && !heading.isBlank()) {
            sb.append("[").append(heading).append("]\n");
        }
        if (cast != null && !cast.isBlank()) {
            sb.append("角色：").append(cast).append("\n");
        }
        return sb.toString();
    }

    /** 按 {@code ## } 标题切分（不切 {@code ### }），标题行本身不进 chunk 正文。
     *  同时解析 {@code **角色：**} 行并从正文中剔除（含 {@code **故事：**} 标签行），
     *  cast 信息由 buildPrefix 统一前缀到每块。 */
    private List<Chunk> splitByHeading(String content) {
        List<Chunk> chunks = new ArrayList<>();
        String currentHeading = "";
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (line.startsWith("## ") && !line.startsWith("### ")) {
                if (current.length() > 0) {
                    chunks.add(buildChunk(currentHeading, current.toString()));
                }
                currentHeading = line.substring(3).strip();
                current = new StringBuilder();
            } else {
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(line);
            }
        }
        if (current.length() > 0) {
            Chunk c = buildChunk(currentHeading, current.toString());
            if (c != null) {
                chunks.add(c);
            }
        }
        return chunks;
    }

    /** 从 section 正文中抽取 cast 行，剔除角色/故事标签行，返回 Chunk（正文为空时 null）。 */
    private Chunk buildChunk(String heading, String raw) {
        String cast = "";
        StringBuilder body = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("**角色") && trimmed.contains("：")) {
                int idx = trimmed.indexOf("：");
                cast = trimmed.substring(idx + 1).strip().replaceAll("\\*", "");
            } else if (trimmed.equals("**故事：**") || trimmed.equals("**故事:**")) {
                // 标签行，跳过
            } else {
                if (body.length() > 0) {
                    body.append('\n');
                }
                body.append(line);
            }
        }
        String text = body.toString().strip();
        if (text.isEmpty()) {
            return null;
        }
        return new Chunk(heading, text, cast);
    }

    /** 超长 chunk 按段落（{@code \n\n}）合并，单段落仍超限按字符硬切。 */
    private List<String> splitByParagraph(String text, int limit) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String para : text.split("\n\n", -1)) {
            if (current.length() > 0 && current.length() + para.length() + 2 > limit) {
                result.add(current.toString().strip());
                current = new StringBuilder();
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(para);
            while (current.length() > limit) {
                result.add(current.substring(0, limit).strip());
                current = new StringBuilder(current.substring(limit));
            }
        }
        if (current.length() > 0) {
            result.add(current.toString().strip());
        }
        return result;
    }

    private record Chunk(String heading, String text, String cast) {
    }
}
