package com.dsh.agent.model;

import com.dsh.agent.tool.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-09 缓存布局：稳定前缀哈希一致性验证。
 * 同 system+工具集合（顺序无关）哈希相同；system 或工具变化时哈希不同。
 */
class CachePrefixTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolDefinition tool(String name) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        return new ToolDefinition(name, "desc-" + name, "1.0", "core", "READ_ONLY", schema);
    }

    @Test
    void sameSystemAndToolsProducesSameHash() {
        ToolDefinition a = tool("alpha");
        ToolDefinition b = tool("beta");
        String h1 = CachePrefix.compute("system-prompt", List.of(a, b));
        String h2 = CachePrefix.compute("system-prompt", List.of(a, b));
        assertEquals(h1, h2, "相同输入必须产生相同前缀哈希");
    }

    @Test
    void toolOrderDoesNotAffectHash() {
        ToolDefinition a = tool("alpha");
        ToolDefinition b = tool("beta");
        String h1 = CachePrefix.compute("sys", List.of(a, b));
        String h2 = CachePrefix.compute("sys", List.of(b, a));
        assertEquals(h1, h2, "工具顺序不应影响前缀哈希（内部按 name 排序）");
    }

    @Test
    void differentSystemProducesDifferentHash() {
        ToolDefinition a = tool("alpha");
        String h1 = CachePrefix.compute("system-v1", List.of(a));
        String h2 = CachePrefix.compute("system-v2", List.of(a));
        assertNotEquals(h1, h2, "system 变化应产生不同前缀哈希");
    }

    @Test
    void differentToolsProduceDifferentHash() {
        String h1 = CachePrefix.compute("sys", List.of(tool("alpha")));
        String h2 = CachePrefix.compute("sys", List.of(tool("beta")));
        assertNotEquals(h1, h2, "工具集合变化应产生不同前缀哈希");
    }

    @Test
    void emptyToolsProducesStableHash() {
        String h1 = CachePrefix.compute("sys", List.of());
        String h2 = CachePrefix.compute("sys", null);
        assertEquals(h1, h2, "空工具集合与 null 应产生相同哈希");
        assertNotNull(h1);
        assertEquals(16, h1.length(), "哈希取前 16 位");
    }

    @Test
    void serializeToolsSortedIsDeterministic() {
        ToolDefinition a = tool("zeta");
        ToolDefinition b = tool("alpha");
        String json = CachePrefix.serializeToolsSorted(List.of(a, b));
        // 按 name 排序后 alpha 应在 zeta 之前
        int alphaIdx = json.indexOf("\"name\":\"alpha\"");
        int zetaIdx = json.indexOf("\"name\":\"zeta\"");
        assertTrue(alphaIdx >= 0 && zetaIdx >= 0);
        assertTrue(alphaIdx < zetaIdx, "工具序列化应按 name 排序");
    }
}
