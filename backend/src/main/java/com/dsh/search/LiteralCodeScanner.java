package com.dsh.search;

import com.dsh.workspace.SafePaths;
import com.dsh.workspace.TextFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 受限字面扫描器（D-05 安全回退）：rg 不可用时的降级路径。
 *
 * <p>与 rg 主路径的能力差距是刻意的：只支持固定字符串、大小写敏感；
 * 仅跳过 .git（不解析 .gitignore、不识别隐藏目录约定）、不跟随符号链接、
 * 跳过二进制与超大文件；结果数与单行长度均有界。调用方必须在结果中标注 engine，
 * 不得向模型宣称其等同 rg 能力。</p>
 */
public final class LiteralCodeScanner {

    private static final int MAX_WALK_DEPTH = 64;
    private static final int MAX_LINE_CHARS = 500;
    private static final Set<String> SKIP_DIRS = Set.of(".git");
    /** 与 rg 主路径一致的纯生成/类型声明文件后缀，字面回退同样跳过。 */
    private static final List<String> SKIP_NAME_SUFFIXES = List.of(".d.ts", ".min.js", ".min.css");

    private LiteralCodeScanner() {
    }

    public static SearchResult scan(Path root, Path base, String query, int limit) {
        List<CodeMatch> matches = new ArrayList<>();
        boolean truncated = false;

        List<Path> files;
        try (Stream<Path> walk = Files.walk(base, MAX_WALK_DEPTH)) {
            files = walk
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> !containsSkippedSegment(root, p))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (Exception e) {
            return SearchResult.error("SEARCH_FAILED", "字面扫描失败: " + e.getMessage());
        }

        outer:
        for (Path file : files) {
            try {
                String name = file.getFileName().toString();
                if (SKIP_NAME_SUFFIXES.stream().anyMatch(name::endsWith)) {
                    continue;
                }
                if (Files.isSymbolicLink(file) || Files.size(file) > TextFiles.MAX_TEXT_BYTES) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(file);
                if (TextFiles.isProbablyBinary(bytes)) {
                    continue;
                }
                List<String> lines = TextFiles.readLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.contains(query)) {
                        if (matches.size() >= limit) {
                            truncated = true;
                            break outer;
                        }
                        boolean lineTruncated = line.length() > MAX_LINE_CHARS;
                        String text = lineTruncated ? line.substring(0, MAX_LINE_CHARS) : line;
                        matches.add(new CodeMatch(SafePaths.relativeUnix(root, file),
                                i + 1, text, lineTruncated));
                    }
                }
            } catch (Exception e) {
                // 单文件读取失败跳过其余内容，不阻断整体搜索
            }
        }
        return SearchResult.ok(matches, truncated);
    }

    /** .git 整棵子树不参与搜索。 */
    private static boolean containsSkippedSegment(Path root, Path path) {
        for (Path segment : root.relativize(path)) {
            if (SKIP_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }
}
