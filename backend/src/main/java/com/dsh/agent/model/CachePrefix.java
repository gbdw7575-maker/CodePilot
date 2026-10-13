package com.dsh.agent.model;

import com.dsh.agent.tool.ToolDefinition;
import com.dsh.common.Digest;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Comparator;
import java.util.List;

/**
 * D-09 前缀缓存布局：计算稳定前缀（system 内容 + 工具定义）的哈希，
 * 用于验证同一线程/任务跨请求的前缀一致性，从而命中 DeepSeek 前缀缓存。
 * 工具按 name 排序后序列化为 function schema JSON，与发往供应商的顺序一致。
 */
public final class CachePrefix {

    private CachePrefix() {
    }

    /**
     * 计算前缀哈希：SHA-256(systemContent + "|" + sortedToolsJson) 取前 16 位。
     * 同 system 同工具集合（顺序无关）返回相同哈希；不同则不同。
     */
    public static String compute(String systemContent, List<ToolDefinition> tools) {
        String toolsJson = serializeToolsSorted(tools);
        String input = (systemContent == null ? "" : systemContent) + "|" + toolsJson;
        return Digest.sha256Hex(input).substring(0, 16);
    }

    /** 工具按 name 排序后序列化为 function schema JSON 数组字符串（顺序确定，跨请求稳定）。 */
    static String serializeToolsSorted(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return "[]";
        }
        List<ToolDefinition> sorted = tools.stream()
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            ObjectNode schema = ToolDefinitionMapper.toFunctionSchema(sorted.get(i));
            sb.append(schema.toString());
        }
        sb.append(']');
        return sb.toString();
    }
}
