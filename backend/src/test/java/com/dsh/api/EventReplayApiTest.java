package com.dsh.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DSH-003 EventReplay 验收：
 * 顺序序号、分页游标（nextAfterSequence/hasMore/highWatermark）、固定上界补拉、
 * 断线重连序列与 eventId 完全一致、重复读不重新生成、非法游标 400。
 */
class EventReplayApiTest extends ApiTestBase {

    private String completedToolThread() throws Exception {
        String projectId = createProject("Replay 项目-" + UUID.randomUUID(),
                "D:/code/MyAgent/DSH/backend/target/test-run");
        trustProject(projectId, 1);
        String profileId = createProfile("replay-" + UUID.randomUUID(), "fake-tool");
        String threadId = createThread(projectId, profileId);
        submitTurn(threadId, "工具场景用于补拉");
        return threadId;
    }

    private List<String> sequences(JsonNode page) {
        List<String> values = new ArrayList<>();
        page.get("items").forEach(e -> values.add(e.get("sequence").asText()));
        return values;
    }

    private List<String> eventIds(JsonNode page) {
        List<String> values = new ArrayList<>();
        page.get("items").forEach(e -> values.add(e.get("eventId").asText()));
        return values;
    }

    @Test
    void fullPageIsOrderedWithHighWatermark() throws Exception {
        String threadId = completedToolThread();
        JsonNode page = getEvents(threadId, "?limit=200");

        List<String> seqs = sequences(page);
        assertEquals(10, seqs.size());
        for (int i = 0; i < seqs.size(); i++) {
            assertEquals(Integer.toString(i + 1), seqs.get(i), "sequence 必须从 1 连续递增");
        }
        assertEquals("10", page.get("nextAfterSequence").asText());
        assertEquals("10", page.get("highWatermark").asText());
        assertFalse(page.get("hasMore").asBoolean());
    }

    @Test
    void pagedReplayRebuildsSameSequenceAndEventIds() throws Exception {
        String threadId = completedToolThread();
        JsonNode full = getEvents(threadId, "?limit=200");
        List<String> fullIds = eventIds(full);
        String highWatermark = full.get("highWatermark").asText();

        // 模拟断线补拉：首次读取捕获 H，后续页把 H 作为 throughSequence 固定上界
        List<String> rebuiltIds = new ArrayList<>();
        List<String> rebuiltSeqs = new ArrayList<>();
        String cursor = "0";
        for (int pageIndex = 0; pageIndex < 10; pageIndex++) {
            JsonNode page = getEvents(threadId,
                    "?afterSequence=" + cursor + "&throughSequence=" + highWatermark + "&limit=3");
            assertEquals(highWatermark, page.get("highWatermark").asText(),
                    "固定上界后 highWatermark 不应漂移");
            page.get("items").forEach(e -> {
                rebuiltIds.add(e.get("eventId").asText());
                rebuiltSeqs.add(e.get("sequence").asText());
            });
            cursor = page.get("nextAfterSequence").asText();
            if (!page.get("hasMore").asBoolean()) {
                break;
            }
        }

        assertEquals(sequences(full), rebuiltSeqs, "分页补拉的 sequence 必须与全量读取一致");
        assertEquals(fullIds, rebuiltIds, "分页补拉的 eventId 必须与全量读取一致");
        assertEquals("10", cursor);
    }

    @Test
    void repeatedReadsDoNotRegenerateEvents() throws Exception {
        String threadId = completedToolThread();
        JsonNode first = getEvents(threadId, "?afterSequence=0&limit=200");
        JsonNode second = getEvents(threadId, "?afterSequence=0&limit=200");

        assertEquals(first, second, "同一窗口重复读取必须返回字节级一致的事件（持久化不重复）");
    }

    @Test
    void emptyTailKeepsCursorAndReportsNoMore() throws Exception {
        String threadId = completedToolThread();
        JsonNode tail = getEvents(threadId, "?afterSequence=10&limit=50");
        assertEquals(0, tail.get("items").size());
        assertEquals("10", tail.get("nextAfterSequence").asText(), "无事件时游标保持原值");
        assertFalse(tail.get("hasMore").asBoolean());
        assertEquals("10", tail.get("highWatermark").asText());
    }

    @Test
    void secondTurnContinuesSharedThreadSequence() throws Exception {
        String threadId = completedToolThread();
        // 第二轮：历史中已有工具结果，fake-tool 脚本直接给最终回答
        JsonNode accepted = submitTurn(threadId, "同一个会话的第二轮");
        assertEquals("10", accepted.get("eventsAfterSequence").asText(),
                "eventsAfterSequence 必须是本 Turn 首事件之前的游标");

        JsonNode page = getEvents(threadId, "?afterSequence=10&limit=200");
        List<String> seqs = sequences(page);
        assertFalse(seqs.isEmpty());
        assertEquals("11", seqs.get(0), "新 Turn 首事件必须接在 thread 高水位之后");

        JsonNode firstEvent = page.get("items").get(0);
        assertEquals("run.state_changed", firstEvent.get("type").asText());
        assertTrue(firstEvent.get("payload").get("from").isNull());
        assertEquals("QUEUED", firstEvent.get("payload").get("to").asText());
    }

    @Test
    void invalidCursorsRejectedWith400() throws Exception {
        String threadId = completedToolThread();

        expectInvalidCursor(threadId, "?afterSequence=abc");
        expectInvalidCursor(threadId, "?afterSequence=01");
        expectInvalidCursor(threadId, "?afterSequence=-1");
        expectInvalidCursor(threadId, "?afterSequence=11");
        expectInvalidCursor(threadId, "?afterSequence=5&throughSequence=4");
        expectInvalidCursor(threadId, "?throughSequence=11");
        expectInvalidCursor(threadId, "?limit=0");
        expectInvalidCursor(threadId, "?limit=201");
    }

    @Test
    void unknownThreadReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/threads/thr_missing/events")
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private void expectInvalidCursor(String threadId, String query) throws Exception {
        mockMvc.perform(get("/api/v1/threads/" + threadId + "/events" + query)
                        .header(CLIENT_HEADER, CLIENT_VALUE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }
}
