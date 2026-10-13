package com.dsh.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 任务副本内相对路径解析（内部接口契约 §5/§15.1）：
 * 拒绝绝对路径与 {@code ..} 穿越，并对已存在部分做真实路径（含符号链接）核验，
 * 保证工具不能借由相对路径或符号链接访问授权副本之外的主机文件。
 */
public final class SafePaths {

    private SafePaths() {
    }

    /**
     * 把任务副本相对路径解析为副本内真实路径。
     *
     * @throws PathEscapeException 路径为绝对路径、规范化后逃逸、或经符号链接指向副本外
     */
    public static Path resolve(Path root, String relative) {
        if (relative == null || relative.isBlank()) {
            throw new PathEscapeException("路径不能为空");
        }
        rejectForeignAbsolutePath(relative);
        Path rel = Path.of(relative);
        if (rel.isAbsolute()) {
            throw new PathEscapeException("不允许绝对路径: " + relative);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(rel).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new PathEscapeException("路径越出任务副本: " + relative);
        }
        // 核验已存在的最近祖先：任何一段符号链接跳到副本外都拒绝
        Path existing = resolved;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            try {
                Path realExisting = existing.toRealPath();
                Path realRoot = Files.exists(normalizedRoot) ? normalizedRoot.toRealPath() : normalizedRoot;
                if (!realExisting.startsWith(realRoot)) {
                    throw new PathEscapeException("符号链接越出任务副本: " + relative);
                }
            } catch (IOException e) {
                throw new PathEscapeException("无法核验真实路径: " + relative);
            }
        }
        return resolved;
    }

    /** 断言相对路径本身（不要求文件存在）不逃逸；供 apply_patch 目标路径预检。 */
    public static void assertRelativeInside(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new PathEscapeException("路径不能为空");
        }
        rejectForeignAbsolutePath(relative);
        Path rel;
        try {
            rel = Path.of(relative.replace('\\', '/'));
        } catch (InvalidPathException e) {
            // Windows 下 ":", "<", ">" 等字符非法；用户文本中的 file:line 引用等
            // 不应导致整体失败，统一按逃逸候选处理（由调用方忽略）。
            throw new PathEscapeException("非法路径字符: " + relative);
        }
        if (rel.isAbsolute() || rel.startsWith("/")) {
            throw new PathEscapeException("不允许绝对路径: " + relative);
        }
        for (Path part : rel) {
            if ("..".equals(part.toString())) {
                throw new PathEscapeException("路径包含越界段: " + relative);
            }
        }
    }

    /** 客户端与沙箱可处于不同 OS；不能把 Windows 盘符/UNC 在 Linux 当作普通文件名。 */
    private static void rejectForeignAbsolutePath(String value) {
        String portable = value.replace('\\', '/');
        if (portable.startsWith("/") || portable.matches("^[A-Za-z]:.*")) {
            throw new PathEscapeException("不允许绝对路径或盘符路径: " + value);
        }
    }

    /** 副本内路径转相对 POSIX 风格字符串（"/" 分隔）。 */
    public static String relativeUnix(Path root, Path path) {
        String value = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
        if (value.isEmpty()) {
            return ".";
        }
        return value;
    }

    /**
     * 判断路径本身是否为重解析点（D-01）：POSIX 符号链接或 Windows 目录联接 junction。
     *
     * <p>{@code Files.isSymbolicLink} 在 Windows 上只识别 reparse tag=SYMLINK，
     * 不识别 junction（tag=MOUNT_POINT，普通用户即可创建且可透读外部目录），
     * 因此以 {@code toRealPath} 的解析结果与词法路径比较兜底；
     * Windows 按大小写不敏感比较，避免盘符/目录大小写差异误判。无法核验时抛 IOException，由调用方 fail-closed。</p>
     */
    public static boolean isReparseRedirect(Path path) throws IOException {
        String given = path.toAbsolutePath().normalize().toString();
        String real = path.toRealPath().toString();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows ? !given.equalsIgnoreCase(real) : !given.equals(real);
    }

    /** 路径越界异常：由 Dispatcher 统一映射为 PATH_OUTSIDE_WORKSPACE 工具结果，不触碰用户文件。 */
    public static class PathEscapeException extends RuntimeException {
        public PathEscapeException(String message) {
            super(message);
        }
    }
}
