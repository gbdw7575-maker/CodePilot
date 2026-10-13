package com.dsh.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-10 工具可达性：能力清单区分 tool_search 开关两态，模型可见工具快照正确。
 * 测试配置 tool-search.enabled=true：扩展工具 reachable = authorized（memory 可达、orchestration 不可达）。
 */
class ToolReachabilityApiTest extends ApiTestBase {

    @Test
    void capabilityListRespectsReachableLogicWhenToolSearchOn() throws Exception {
        JsonNode caps = mapper.readTree(
                mockMvc.perform(get("/api/v1/capabilities"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertTrue(caps.path("toolSearchEnabled").asBoolean(), "测试配置 tool-search 应开启");
        assertTrue(caps.path("coreTools").isArray() && caps.path("coreTools").size() > 0,
                "核心工具应常驻且非空");
        for (JsonNode core : caps.path("coreTools")) {
            assertTrue(core.path("reachable").asBoolean(), "核心工具应 reachable=true");
            assertFalse(core.path("extension").asBoolean(), "核心工具 extension=false");
        }
        // 扩展工具 reachable 必须等于 authorized（tool_search 开启时）
        for (JsonNode ext : caps.path("extensions")) {
            boolean authorized = ext.path("authorized").asBoolean();
            boolean reachable = ext.path("reachable").asBoolean();
            assertEquals(authorized, reachable,
                    "tool_search 开启时扩展 reachable 应等于 authorized: " + ext.path("name").asText());
        }
    }

    @Test
    void runToolsSnapshotContainsOnlyCoreToolsByDefault() throws Exception {
        String threadId = bootstrapThread();
        JsonNode turn = submitTurn(threadId, "你好");
        String runId = turn.path("rootRunId").asText();

        // 模型可见工具 = 核心工具（默认无扩展挂载）
        JsonNode tools = mapper.readTree(
                mockMvc.perform(get("/api/v1/runs/" + runId + "/tools"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertTrue(tools.path("items").isArray() && tools.path("items").size() > 0);
        for (JsonNode t : tools.path("items")) {
            assertTrue(t.path("mounted").asBoolean(), "快照中工具应 mounted=true");
            assertNotNull(t.path("name").asText());
            assertNotNull(t.path("inputSchema"));
        }
        // 不应包含未挂载的扩展工具（如 memory_recall）
        boolean hasMemoryRecall = false;
        for (JsonNode t : tools.path("items")) {
            if ("memory_recall".equals(t.path("name").asText())) {
                hasMemoryRecall = true;
            }
        }
        assertFalse(hasMemoryRecall, "未挂载的扩展工具不应出现在模型可见快照中");
    }

    @Test
    void capabilityEntryFieldsAreComplete() throws Exception {
        mockMvc.perform(get("/api/v1/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolSearchEnabled").exists())
                .andExpect(jsonPath("$.coreTools").isArray())
                .andExpect(jsonPath("$.extensions").isArray())
                .andExpect(jsonPath("$.coreTools[0].name").exists())
                .andExpect(jsonPath("$.coreTools[0].description").exists())
                .andExpect(jsonPath("$.coreTools[0].category").exists())
                .andExpect(jsonPath("$.coreTools[0].sideEffect").exists())
                .andExpect(jsonPath("$.coreTools[0].extension").exists())
                .andExpect(jsonPath("$.coreTools[0].authorized").exists())
                .andExpect(jsonPath("$.coreTools[0].reachable").exists());
    }
}
