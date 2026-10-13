package com.dsh.agent.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每 Run 的扩展工具挂载集合（内部接口契约 §1 ToolDiscovery / 开发文档 §7.3）：
 * 挂载按 Run 隔离，不污染其他会话；核心工具不在此管理（始终挂载）。
 * 进程重启后挂载丢失，模型可再次 tool_search 恢复；searches 计数限制无进展重复查询。
 */
@Component
public class RunToolMountStore {

    private final Map<String, MountedRun> runs = new ConcurrentHashMap<>();

    private static final class MountedRun {
        final Set<String> tools = new LinkedHashSet<>();
        int searches;
        int ragSearches;
    }

    /** 挂载一个扩展工具；重复挂载幂等。返回挂载后该 Run 的扩展工具数。 */
    public synchronized int mount(String runId, String toolName) {
        MountedRun run = runs.computeIfAbsent(runId, k -> new MountedRun());
        run.tools.add(toolName);
        return run.tools.size();
    }

    public boolean isMounted(String runId, String toolName) {
        MountedRun run = runs.get(runId);
        return run != null && run.tools.contains(toolName);
    }

    /** 本 Run 已挂载的扩展工具定义（保持挂载顺序）。 */
    public List<ToolDefinition> mountedFor(String runId, ToolRegistry registry) {
        MountedRun run = runs.get(runId);
        if (run == null || run.tools.isEmpty()) {
            return List.of();
        }
        List<ToolDefinition> defs = new ArrayList<>();
        for (String name : run.tools) {
            registry.definition(name).ifPresent(defs::add);
        }
        return defs;
    }

    /** 记录一次 tool_search 调用，返回含本次的累计次数。 */
    public synchronized int recordSearch(String runId) {
        MountedRun run = runs.computeIfAbsent(runId, k -> new MountedRun());
        run.searches = run.searches + 1;
        return run.searches;
    }

    public int searchCount(String runId) {
        MountedRun run = runs.get(runId);
        return run == null ? 0 : run.searches;
    }

    /** 记录一次 rag_search 调用，返回含本次的累计次数。 */
    public synchronized int recordRagSearch(String runId) {
        MountedRun run = runs.computeIfAbsent(runId, k -> new MountedRun());
        run.ragSearches = run.ragSearches + 1;
        return run.ragSearches;
    }

    public int ragSearchCount(String runId) {
        MountedRun run = runs.get(runId);
        return run == null ? 0 : run.ragSearches;
    }

    /** Run 终态后清理（幂等）。 */
    public void release(String runId) {
        runs.remove(runId);
    }

    /** 仅供运维/诊断快照。 */
    public Map<String, List<String>> snapshot() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        runs.forEach((runId, run) -> result.put(runId, List.copyOf(run.tools)));
        return result;
    }
}
