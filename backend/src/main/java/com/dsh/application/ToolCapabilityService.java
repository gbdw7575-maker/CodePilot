package com.dsh.application;

import com.dsh.agent.tool.RunToolMountStore;
import com.dsh.agent.tool.Tool;
import com.dsh.agent.tool.ToolDefinition;
import com.dsh.agent.tool.ToolRegistry;
import com.dsh.api.dto.PageDto;
import com.dsh.api.dto.RunToolSnapshot;
import com.dsh.api.dto.ToolCapabilityEntry;
import com.dsh.api.dto.ToolCapabilitySnapshot;
import com.dsh.api.dto.ToolDefinitionDto;
import com.dsh.api.Pages;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * D-10 工具可达性：提供能力清单（区分 tool_search 开关两态）与单 Run 模型可见工具快照。
 * <p>核心工具始终 reachable；扩展工具 reachable = authorized && toolSearchEnabled。
 * 未授权或 tool_search 关闭时不宣称扩展可用，模型可见列表也不含它们。</p>
 */
@Service
public class ToolCapabilityService {

    private final ToolRegistry registry;
    private final RunToolMountStore mountStore;
    private final DshProperties properties;

    public ToolCapabilityService(ToolRegistry registry, RunToolMountStore mountStore, DshProperties properties) {
        this.registry = registry;
        this.mountStore = mountStore;
        this.properties = properties;
    }

    /** 全局能力清单：核心工具 + 所有扩展工具（含 authorized/reachable 标记）。 */
    public ToolCapabilitySnapshot snapshot() {
        boolean toolSearchEnabled = properties.toolSearch().enabled();
        List<ToolCapabilityEntry> core = new ArrayList<>();
        for (ToolDefinition def : registry.mountedDefinitions()) {
            core.add(new ToolCapabilityEntry(def.name(), def.description(), def.category(),
                    def.sideEffect(), false, true, true));
        }
        List<ToolCapabilityEntry> extensions = new ArrayList<>();
        for (Tool tool : registry.extensionTools()) {
            Tool.ExtensionMeta meta = tool.extensionMeta();
            boolean authorized = meta != null && meta.authorized();
            boolean reachable = authorized && toolSearchEnabled;
            ToolDefinition def = registry.definition(tool.name()).orElseThrow();
            extensions.add(new ToolCapabilityEntry(def.name(), def.description(), def.category(),
                    def.sideEffect(), true, authorized, reachable));
        }
        return new ToolCapabilitySnapshot(toolSearchEnabled, core, extensions);
    }

    /** 指定 Run 的模型可见工具快照：核心工具 + 本 Run 已挂载且仍授权的扩展工具。 */
    public RunToolSnapshot runSnapshot(String runId) {
        boolean toolSearchEnabled = properties.toolSearch().enabled();
        List<ToolCapabilityEntry> visible = new ArrayList<>();
        // 核心工具始终可见
        for (ToolDefinition def : registry.mountedDefinitions()) {
            visible.add(new ToolCapabilityEntry(def.name(), def.description(), def.category(),
                    def.sideEffect(), false, true, true));
        }
        // 扩展工具：本 Run 已挂载且授权且 tool_search 开启才可见
        for (ToolDefinition def : mountStore.mountedFor(runId, registry)) {
            Tool tool = registry.lookup(def.name()).orElse(null);
            Tool.ExtensionMeta meta = tool == null ? null : tool.extensionMeta();
            boolean authorized = meta != null && meta.authorized();
            boolean reachable = authorized && toolSearchEnabled;
            visible.add(new ToolCapabilityEntry(def.name(), def.description(), def.category(),
                    def.sideEffect(), true, authorized, reachable));
        }
        return new RunToolSnapshot(runId, visible);
    }

    /** 指定 Run 的模型可见工具定义分页（对应 openapi ToolDefinitionPage）。 */
    public PageDto<ToolDefinitionDto> runToolsPage(String runId, String cursor, Integer limit) {
        List<ToolDefinitionDto> items = new ArrayList<>();
        for (ToolDefinition def : registry.mountedDefinitions()) {
            items.add(toDto(def, true));
        }
        for (ToolDefinition def : mountStore.mountedFor(runId, registry)) {
            Tool tool = registry.lookup(def.name()).orElse(null);
            Tool.ExtensionMeta meta = tool == null ? null : tool.extensionMeta();
            boolean authorized = meta != null && meta.authorized();
            // 已挂载且授权的扩展工具对模型可见
            items.add(toDto(def, authorized));
        }
        return Pages.slice(items, Pages.parse(cursor, limit));
    }

    private ToolDefinitionDto toDto(ToolDefinition def, boolean mounted) {
        return new ToolDefinitionDto(def.name(), def.description(), def.inputSchema(),
                def.version(), def.category(), def.sideEffect(), mounted);
    }
}
