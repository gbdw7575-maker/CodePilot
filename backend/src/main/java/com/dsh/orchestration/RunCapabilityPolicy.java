package com.dsh.orchestration;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Run 级工具能力策略（Agent 编排升级规范 §5.2/§7，MA-03）：
 * 计划改写与一切派生/调度能力只属于主 Agent（MAIN Run）。worker 子角色
 * （ARCHITECT/CODER/TESTER/REVIEWER 及任何非 MAIN 角色）在三层被同一集合拦截：
 *
 * <ol>
 *   <li>可见性：{@code LoopSteps.assemble} 装配模型请求工具目录时过滤，worker 看不到定义；</li>
 *   <li>发现侧：{@code tool_search} 不向 worker 暴露这些扩展工具；</li>
 *   <li>执行点：{@code ToolDispatcher} 实时复查 Run 角色，猜到名字直调也拒绝；
 *       另有 {@link SubagentService} 服务入口复查，禁止绕过工具层直调。</li>
 * </ol>
 *
 * <p>集合同时包含 MA-01 fixtures 冻结的未来调度工具名（dispatch/await/read/cancel），
 * 使后续切片发布时不必再补 worker 拦截。Schema 隐藏从不作为唯一防线。</p>
 */
@Component
public class RunCapabilityPolicy {

    /** worker 一律不可见、不可发现、不可执行的工具（计划 + 派生 + 任务调度）。 */
    private static final Set<String> MAIN_ONLY_TOOLS = Set.of(
            // 既有派生/编排类（DSH-010）
            "run_subagent",
            "start_orchestration",
            "fanout_subagents",
            // 计划改写：计划版本/任务图是主 Agent 职责，worker 不得改写同根族计划（MA-03）
            "update_plan",
            // MA-01 fixtures 冻结、MA-05 发布的调度工具：先占位，发布即自动受限
            "dispatch_tasks",
            "await_tasks",
            "read_task_result",
            "cancel_tasks");

    /** 唯一可派生/改写计划的角色；根 Run 为 MAIN，worker 为受信工作角色。 */
    public boolean isMainRole(String role) {
        return "MAIN".equals(role);
    }

    public boolean isMainOnlyTool(String toolName) {
        return toolName != null && MAIN_ONLY_TOOLS.contains(toolName);
    }

    /** MAIN 专属工具名集合（不可变视图，供目录/发现侧过滤）。 */
    public Set<String> mainOnlyTools() {
        return MAIN_ONLY_TOOLS;
    }
}
