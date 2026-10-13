package com.dsh.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 任务写范围策略（MA-04，规范 §7 resourceScope 交集、§8.2 写范围）。
 *
 * <p>resourceScope 是模型申请，本策略做服务端归一化与副本内收敛：只接受任务副本内的
 * 相对路径（拒绝绝对路径、盘符与任何 {@code ..} 段），写权限为精确文件或目录前缀白名单。
 * 项目授权/父 Run 能力的更外层交集仍由工作区副本与 SafePaths 保证——白名单只可能更小。</p>
 *
 * <p>自由文本 shell 命令不能靠字符串匹配证明写范围（§8.2），因此本策略不检查命令文本：
 * 命令一律在隔离副本执行，由封存点按实际 Diff 拒绝越界（SubagentService）。</p>
 */
@Component
public class WriteScopePolicy {

    /** 归一化写范围；{@code null}/空白表示整副本可写（MA-04 前既有行为）。 */
    public Scope parse(String resourceScopeJson) {
        if (resourceScopeJson == null || resourceScopeJson.isBlank()) {
            return Scope.UNRESTRICTED;
        }
        try {
            JsonScope raw = new ObjectMapper()
                    .readValue(resourceScopeJson, JsonScope.class);
            List<String> write = normalizePatterns(raw.write() == null ? List.of() : raw.write());
            return new Scope(write);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("resourceScope 不是合法 JSON 对象 {read:[],write:[]}: "
                    + e.getMessage(), e);
        }
    }

    /** 直接由已归一化模式构造（输入快照重放时复用，避免二次解析）。 */
    public Scope ofPatterns(List<String> normalizedWritePatterns) {
        if (normalizedWritePatterns == null) {
            return Scope.UNRESTRICTED;
        }
        return new Scope(List.copyOf(normalizedWritePatterns));
    }

    private List<String> normalizePatterns(List<String> rawPatterns) {
        List<String> patterns = new ArrayList<>();
        for (String raw : rawPatterns) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("写范围包含空路径");
            }
            String p = raw.strip().replace('\\', '/');
            if (p.startsWith("./")) {
                p = p.substring(2);
            }
            if (p.equals(".") || p.equals("./") || p.isEmpty()) {
                throw new IllegalArgumentException("写范围不能指向副本根（请显式列出文件或子目录）: " + raw);
            }
            if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) {
                throw new IllegalArgumentException("写范围只接受副本内相对路径，拒绝绝对路径: " + raw);
            }
            String[] segments = p.split("/");
            for (String seg : segments) {
                if ("..".equals(seg)) {
                    throw new IllegalArgumentException("写范围不得包含 .. 段: " + raw);
                }
            }
            if (p.contains(":")) {
                throw new IllegalArgumentException("写范围包含非法字符 ':': " + raw);
            }
            patterns.add(p);
        }
        return List.copyOf(patterns);
    }

    /** 返回违例消息；允许返回 null。 */
    public String checkWritable(Scope scope, String relativePath) {
        if (scope == null || scope.unrestricted()) {
            return null;
        }
        if (relativePath == null || relativePath.isBlank()) {
            return "SCOPE_DENIED: 目标路径为空";
        }
        String p = relativePath.strip().replace('\\', '/');
        if (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (isAllowed(scope.writePatterns(), p)) {
            return null;
        }
        return "SCOPE_DENIED: 路径不在任务授权写范围内: " + relativePath
                + "；允许写: " + scope.writePatterns();
    }

    /** 封存点：返回实际变更中落在写白名单外的路径（保持顺序、去重）。 */
    public List<String> violatingPaths(Scope scope, Iterable<String> changedRelativePaths) {
        if (scope == null || scope.unrestricted()) {
            return List.of();
        }
        List<String> violated = new ArrayList<>();
        for (String raw : changedRelativePaths) {
            String p = raw == null ? "" : raw.replace('\\', '/');
            if (!isAllowed(scope.writePatterns(), p) && !violated.contains(p)) {
                violated.add(p);
            }
        }
        return List.copyOf(violated);
    }

    private static boolean isAllowed(List<String> patterns, String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (pattern.equals(path)) {
                return true;
            }
            if (pattern.endsWith("/")) {
                String dirPrefix = pattern;
                if (lower.startsWith(dirPrefix.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Jackson 绑定用中间结构。 */
    private record JsonScope(List<String> read, List<String> write) {
    }

    /**
     * 归一化写范围。unrestricted=true 时写工具与封存均不做白名单限制（整副本）。
     * 写白名单为空列表时表示不允许写任何副本文件（只读/审阅任务）。
     */
    public record Scope(List<String> writePatterns) {

        public static final Scope UNRESTRICTED = new Scope(null);

        public boolean unrestricted() {
            return writePatterns == null;
        }
    }
}
