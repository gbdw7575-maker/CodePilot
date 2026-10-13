package com.dsh.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 具体工具 SPI（内部接口契约 §1/§5）：只实现动作本身，
 * 不重复实现挂载/Schema 校验/路径授权/预算/审批（由 ToolDispatcher 统一处理）。
 * 核心工具的 name/definition 必须存在于 tools.schema.json x-tools。
 */
public interface Tool {

    /** 与 tools.schema.json x-tools 同名。 */
    String name();

    /** 非 null 表示内部工具（不出现在契约 x-tools 中，如 echo）；核心工具返回 null 由契约装载定义。 */
    default ToolDefinition customDefinition() {
        return null;
    }

    /**
     * 非 null 表示扩展工具（DSH-009）：注册进目录但不默认挂载，
     * 仅经 tool_search 发现并挂载到本 Run 后可执行；执行前实时复查 authorized。
     * whenToUse 进入检索索引；authorized=false 的扩展工具对发现与执行均不可见。
     */
    default ExtensionMeta extensionMeta() {
        return null;
    }

    /** 扩展工具元数据：检索索引文本与授权标记（定义不是权限系统，授权过滤以本标记+实时复查为准）。 */
    record ExtensionMeta(String whenToUse, boolean authorized) {
    }

    /** 是否需要授权任务副本；纯内部确定性工具（echo）返回 false。 */
    default boolean requiresWorkspace() {
        return true;
    }

    /** 参数已通过 Schema 校验；路径/沙箱异常用 ToolException 抛出，由 Dispatcher 映射。 */
    ToolOutcome execute(JsonNode arguments, ToolExecutionContext context) throws Exception;
}
