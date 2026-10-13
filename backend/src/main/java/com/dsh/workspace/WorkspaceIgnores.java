package com.dsh.workspace;

import java.util.Set;

/**
 * 工作区「构建/缓存产物」忽略清单（通用能力，非任何基准/语言特化）。
 *
 * <p>这些目录/文件由工具链在测试或构建过程中生成，不属于作者改动：
 * 真实负载观测到 agent 跑 pytest 后把 {@code .pytest_cache/} 带进补丁，
 * 跑 Maven/Gradle 后产生 {@code target/}、{@code build/}，Python 安装产生
 * {@code *.egg-info}。将它们排除出 Diff 与快速摘要，避免补丁噪声、
 * 避免「测试一跑工作区就变脏」的误判。
 *
 * <p>规则与常见 .gitignore 约定一致（任意层级的同名目录均忽略）。
 */
public final class WorkspaceIgnores {

    /** 任意层级命中即忽略的目录段。 */
    private static final Set<String> IGNORED_DIR_SEGMENTS = Set.of(
            // VCS
            ".git", ".svn", ".hg",
            // Python
            ".pytest_cache", "__pycache__", ".tox", ".mypy_cache", ".ruff_cache",
            ".venv", "venv", "env", ".eggs", "htmlcov", "site-packages",
            // JVM
            "target", "build", "out", ".gradle",
            // Node
            "node_modules",
            // 文档构建
            "_build", "site",
            // IDE / OS
            ".idea", ".vscode"
    );

    /** 精确文件名忽略。 */
    private static final Set<String> IGNORED_FILE_NAMES = Set.of(
            ".coverage", ".DS_Store", "Thumbs.db"
    );

    /** 文件后缀（小写，含点）忽略。 */
    private static final Set<String> IGNORED_SUFFIXES = Set.of(
            ".pyc", ".pyo"
    );

    /** 目录段后缀（小写）忽略，如 Sphinx.egg-info。 */
    private static final String EGG_INFO_SUFFIX = ".egg-info";

    private WorkspaceIgnores() {
    }

    /**
     * 相对 POSIX 路径（{@code a/b/c.txt}）是否属于构建/缓存产物（末段按文件判断）。
     */
    public static boolean isIgnored(String relUnixPath) {
        return isIgnored(relUnixPath, false);
    }

    /**
     * @param trailingIsDirectory 末段是否为目录（目录树遍历时末段也要按目录名规则判断）
     */
    public static boolean isIgnored(String relUnixPath, boolean trailingIsDirectory) {
        if (relUnixPath == null || relUnixPath.isEmpty() || ".".equals(relUnixPath)) {
            return false;
        }
        String[] parts = relUnixPath.split("/");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                continue;
            }
            String lower = part.toLowerCase();
            boolean lastSegment = i == parts.length - 1;
            boolean directorySegment = !lastSegment || trailingIsDirectory;
            if (directorySegment
                    && (IGNORED_DIR_SEGMENTS.contains(part) || lower.endsWith(EGG_INFO_SUFFIX))) {
                return true;
            }
            if (!directorySegment) {
                if (IGNORED_FILE_NAMES.contains(part)) {
                    return true;
                }
                for (String suffix : IGNORED_SUFFIXES) {
                    if (lower.endsWith(suffix)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
