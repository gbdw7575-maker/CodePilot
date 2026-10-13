package com.dsh.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DSH-003 FakeLoop 验收（开发准备与验收.md §2）：
 * 一个回答、一次工具调用、供应商错误、追问恢复四类可复现场景；
 * 断言终态、用量账本与事件序列（业务状态与事件同事务提交）。
 */
class FakeLoopApiTest extends ApiTestBase {

    private String bootstrapWithModel(String model) throws Exception {
        String projectId = createProject("Loop 项目-" + UUID.randomUUID(),
                "D:/code/MyAgent/DSH/backend/target/test-run");
        trustProject(projectId, 1);
        String profileId = createProfile("loop-" + model + "-" + UUID.randomUUID(), model);
        return createThread(projectId, profileId);
    }

    private List<String> typeList(JsonNode page) {
        List<String> types = new ArrayList<>();
        page.get("items").forEach(e -> types.add(e.get("type").asText()));
        return types;
    }

    private JsonNode findPayload(JsonNode page, String type, int occurrence) {
        int seen = 0;
        for (JsonNode event : page.get("items")) {
            if (type.equals(event.get("type").asText())) {
                if (seen == occurrence) {
                    return event.get("payload");
                }
                seen++;
            }
        }
        throw new AssertionError("缺少事件: " + type + "#" + occurrence);
    }

    @Test
    void plainAnswerCompletesWithNotRequired() throws Exception {
        String threadId = bootstrapWithModel("fake-answer");
        JsonNode accepted = submitTurn(threadId, "解释一下乐观锁");
        String runId = accepted.get("rootRunId").asText();

        mockMvc.perform(get("/api/v1/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.stopReason").value("ANSWER_COMPLETE"))
                .andExpect(jsonPath("$.verificationStatus").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.budget.modelRequestsUsed").value(1))
                .andExpect(jsonPath("$.budget.usageStatus").value("SETTLED"))
                .andExpect(jsonPath("$.summary").isNotEmpty());

        mockMvc.perform(get("/api/v1/threads/" + threadId))
                .andExpect(jsonPath("$.activeTurnId").doesNotExist());

        JsonNode page = getEvents(threadId, "?limit=200");
        assertEquals(List.of(
                "run.state_changed", "run.state_changed", "run.state_changed",
                "model.completed", "assistant.message.completed",
                "run.state_changed", "run.completed"), typeList(page));
        assertFalse(page.get("hasMore").asBoolean());

        JsonNode queued = page.get("items").get(0).get("payload");
        assertTrue(queued.get("from").isNull());
        assertEquals("QUEUED", queued.get("to").asText());
        assertEquals(1, queued.get("version").asInt());

        JsonModelAsserts.stateTransition(page.get("items").get(1), "QUEUED", "PREPARING");
        JsonModelAsserts.stateTransition(page.get("items").get(2), "PREPARING", "RUNNING");

        JsonNode completed = findPayload(page, "run.completed", 0);
        assertEquals("COMPLETED", completed.get("state").asText());
        assertEquals("NOT_REQUIRED", completed.get("verificationStatus").asText());

        // 终态两事件 version 相同
        JsonNode stateChanged = findPayload(page, "run.state_changed", 3);
        assertEquals(stateChanged.get("version").asInt(), completed.get("version").asInt());

        // 同步 fake 不发文本 delta
        assertTrue(typeList(page).stream().noneMatch("assistant.text.delta"::equals));
    }

    @Test
    void singleToolCallIsExecutedThenCompletes() throws Exception {
        String threadId = bootstrapWithModel("fake-tool");
        JsonNode accepted = submitTurn(threadId, "调用工具演示");
        String runId = accepted.get("rootRunId").asText();

        mockMvc.perform(get("/api/v1/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.stopReason").value("ANSWER_COMPLETE"))
                .andExpect(jsonPath("$.budget.modelRequestsUsed").value(2))
                .andExpect(jsonPath("$.budget.toolCallsUsed").value(1));

        JsonNode page = getEvents(threadId, "?limit=200");
        assertEquals(List.of(
                "run.state_changed", "run.state_changed", "run.state_changed",
                "model.completed", "tool.started", "tool.completed",
                "model.completed", "assistant.message.completed",
                "run.state_changed", "run.completed"), typeList(page));

        JsonNode firstModel = page.get("items").get(3).get("payload");
        assertEquals("tool_calls", firstModel.get("finishReason").asText());
        assertEquals("fake-tool", firstModel.get("modelRequested").asText());
        assertNotNull(firstModel.get("modelReturned").asText(null));
        assertEquals("KNOWN", firstModel.get("usage").get("status").asText());

        JsonNode started = page.get("items").get(4).get("payload");
        JsonNode toolCompleted = page.get("items").get(5).get("payload");
        assertEquals(started.get("callId").asText(), toolCompleted.get("callId").asText());
        assertEquals("echo", started.get("name").asText());
        assertEquals("SUCCEEDED", toolCompleted.get("result").get("status").asText());
        assertTrue(toolCompleted.get("result").get("summary").asText().contains("调用工具演示"));
        assertEquals("text", toolCompleted.get("result").get("content").get(0).get("type").asText());
        assertFalse(toolCompleted.get("result").get("truncated").asBoolean());

        JsonNode secondModel = page.get("items").get(6).get("payload");
        assertEquals("stop", secondModel.get("finishReason").asText());

        // 工具过程不落公开消息流，只有 user/assistant 两条
        mockMvc.perform(get("/api/v1/threads/" + threadId + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].role").value("user"))
                .andExpect(jsonPath("$.items[1].role").value("assistant"));
    }

    @Test
    void providerErrorFailsRunWithProviderError() throws Exception {
        String threadId = bootstrapWithModel("fake-error");
        JsonNode accepted = submitTurn(threadId, "触发供应商错误");
        String runId = accepted.get("rootRunId").asText();

        mockMvc.perform(get("/api/v1/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.stopReason").value("PROVIDER_ERROR"));

        mockMvc.perform(get("/api/v1/threads/" + threadId))
                .andExpect(jsonPath("$.activeTurnId").doesNotExist());

        JsonNode page = getEvents(threadId, "?limit=200");
        assertEquals(List.of(
                "run.state_changed", "run.state_changed", "run.state_changed",
                "run.state_changed", "run.failed"), typeList(page));
        // 供应商在返回完整响应前失败：不允许有 model.completed / 终态成功事件
        assertTrue(typeList(page).stream().noneMatch(t -> t.equals("model.completed")));
        assertTrue(typeList(page).stream().noneMatch(t -> t.equals("run.completed")));

        JsonNode failed = findPayload(page, "run.failed", 0);
        assertEquals("FAILED", failed.get("state").asText());
        assertEquals("PROVIDER_ERROR", failed.get("reason").asText());
        assertTrue(failed.get("message").asText().contains("fake"));

        JsonNode stateChanged = findPayload(page, "run.state_changed", 3);
        assertEquals("RUNNING", stateChanged.get("from").asText());
        assertEquals("FAILED", stateChanged.get("to").asText());
        assertEquals(stateChanged.get("version").asInt(), failed.get("version").asInt());

        // 终态后可以开新 Turn
        JsonNode second = submitTurn(threadId, "错误后的新轮次");
        assertNotNull(second.get("turnId").asText(null));
    }

    @Test
    void waitingInputResumesAfterAppendedInput() throws Exception {
        String threadId = bootstrapThread();
        JsonNode accepted = submitTurn(threadId, "需要追问的任务");
        String turnId = accepted.get("turnId").asText();

        JsonNode pausedPage = getEvents(threadId, "?limit=200");
        assertEquals("WAITING_INPUT",
                findPayload(pausedPage, "run.state_changed", 3).get("to").asText());

        mockMvc.perform(post("/api/v1/turns/" + turnId + "/inputs")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Idempotency-Key", uniqueKey("append"))
                        .content("{\"input\":\"补充信息如下\"}"))
                .andExpect(status().isAccepted());

        JsonNode full = getEvents(threadId, "?limit=200");
        List<String> types = typeList(full);
        assertEquals(List.of(
                "run.state_changed", "run.state_changed", "run.state_changed",
                "model.completed", "assistant.message.completed", "run.state_changed",
                "run.state_changed", "run.input_applied",
                "model.completed", "assistant.message.completed",
                "run.state_changed", "run.completed"), types);

        JsonNode inputApplied = findPayload(full, "run.input_applied", 0);
        assertEquals(1, inputApplied.get("attempt").asInt());
        assertNotNull(inputApplied.get("messageId").asText(null));

        mockMvc.perform(get("/api/v1/runs/" + accepted.get("rootRunId").asText()))
                .andExpect(jsonPath("$.state").value("COMPLETED"));
    }

    /** 小工具：集中断言 state_changed payload 的 from/to。 */
    private static final class JsonModelAsserts {
        static void stateTransition(JsonNode event, String from, String to) {
            JsonNode payload = event.get("payload");
            assertEquals(from, payload.get("from").asText(), "state_changed.from 不匹配");
            assertEquals(to, payload.get("to").asText(), "state_changed.to 不匹配");
        }
    }
}
