package com.dsh.contract;

import com.dsh.agent.tool.Tool;
import com.dsh.agent.tool.ToolDefinition;
import com.dsh.application.DshProperties;
import com.dsh.api.dto.event.EventType;
import com.dsh.memory.MemoryEntry;
import com.dsh.memory.RulesView;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-009 契约对齐（Agent项目开发文档.md §7/§10 + 内部接口契约 §1/§18.1）：
 * RRF 冻结常数与 top-5 走配置、扩展工具授权语义、记忆条目字段齐全、
 * 索引上限 200 行/25,000 UTF-8 字节、规则文件可配置且记忆目录隔离、事件类型复用不新增。
 */
class ContractAlignmentDsh009Test {

    @Test
    void toolSearchDefaultsMatchContract() {
        // 缺省配置即契约值：RRF k=60、top-5、重复查询限制 5、默认关闭（有收益再启用）
        DshProperties properties = new DshProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        DshProperties.ToolSearch search = properties.toolSearch();
        assertEquals(60, search.rrfK(), "RRF 冻结常数 k=60");
        assertEquals(5, search.maxResults(), "单次返回 top-5");
        assertEquals(5, search.maxSearchesPerRun(), "限制无进展重复查询");
        assertFalse(search.enabled(), "tool_search 默认关闭，相同配置对照有收益再启用");
        assertFalse(search.vectorEnabled(), "D-11 默认以 BM25 排序，向量腿仅作显式实验对照");
    }

    @Test
    void memoryDefaultsMatchContract() {
        // §10.3：索引读取上限 200 行 / 25,000 UTF-8 字节先到者；规则文件 AGENTS.md；经验目录项目隔离
        DshProperties properties = new DshProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        DshProperties.Memory memory = properties.memory();
        assertTrue(memory.enabled());
        assertEquals(200, memory.indexMaxLines());
        assertEquals(25000, memory.indexMaxBytes());
        assertEquals("AGENTS.md", memory.rulesFile(), "规则文件默认 AGENTS.md（可配置选 CLAUDE.md）");
        assertEquals(".myagent/memory", memory.memoryDir(), "经验层位于项目根 .myagent/memory/<projectId>/");
    }

    @Test
    void extensionToolAuthorizationSemantics() {
        Tool tool = new Tool() {
            @Override
            public String name() {
                return "x";
            }

            @Override
            public ToolDefinition customDefinition() {
                return new ToolDefinition("x", "d", "1.0.0", "EXTENSION", "NONE",
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
            }

            @Override
            public ExtensionMeta extensionMeta() {
                return new ExtensionMeta("when-to-use 文本", false);
            }

            @Override
            public com.dsh.agent.tool.ToolOutcome execute(com.fasterxml.jackson.databind.JsonNode arguments,
                                                          com.dsh.agent.tool.ToolExecutionContext context) {
                throw new UnsupportedOperationException();
            }
        };
        Tool.ExtensionMeta meta = tool.extensionMeta();
        assertNotNull(meta.whenToUse(), "whenToUse 进入检索索引文本");
        assertFalse(meta.authorized(), "authorized=false 的扩展工具对发现与执行均不可见");
        // 索引仅为工具目录文本（名称/描述/whenToUse），不涉及代码向量库
        assertTrue(meta.whenToUse() instanceof String);
    }

    @Test
    void memoryEntryCarriesAllContractFields() {
        // §10.3：每条经验带 category、正文、来源引用、更新时间和有效范围
        MemoryEntry entry = new MemoryEntry("mem_1", "project", "构建命令", "全局",
                "run-1/turn-2", Instant.parse("2026-09-23T12:00:00Z"));
        assertEquals("mem_1", entry.id());
        assertEquals("project", entry.category());
        assertEquals("构建命令", entry.content());
        assertEquals("run-1/turn-2", entry.sourceRef());
        assertEquals("全局", entry.scope());
        assertNotNull(entry.updatedAt());
    }

    @Test
    void rulesViewRecordsPathHashAndVersion() {
        // §10.2：启动读取规则文件，记录路径、哈希和版本；过大截断并标记
        RulesView view = new RulesView("proj-1", "D:/repo/AGENTS.md", "a".repeat(64), "b".repeat(12),
                true, false, 1024, "# 规则");
        assertTrue(view.exists());
        assertEquals(64, view.sha256().length());
        assertEquals(12, view.version().length());
        assertNotNull(view.path());
        assertFalse(view.truncated());
    }

    @Test
    void toolSearchReusesExistingToolEventTypes() {
        // 复用 tool.started/tool.completed 记录 tool_search 与记忆工具调用，不新增事件类型
        assertEquals("tool.started", EventType.TOOL_STARTED);
        assertEquals("tool.completed", EventType.TOOL_COMPLETED);
    }
}
