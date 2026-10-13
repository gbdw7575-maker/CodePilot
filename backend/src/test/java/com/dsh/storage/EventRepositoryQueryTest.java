package com.dsh.storage;

import com.dsh.api.ApiTestBase;
import com.dsh.application.event.EventMeta;
import com.dsh.application.event.EventStore;
import com.dsh.api.dto.event.EventPayloads;
import com.dsh.api.dto.event.EventType;
import com.dsh.common.IdGen;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 L1 账本增强验收：EventRepository 新增 findByRunId / findByProjectId 查询，
 * 支持任务级回放与 L5 情景记忆召回的数据源。验收不破坏既有 findRange/findEarliestSequence/findByTraceId。
 */
class EventRepositoryQueryTest extends ApiTestBase {

    @Autowired
    private EventStore eventStore;
    @Autowired
    private EventRepository eventRepository;

    @Test
    void findByRunIdReturnsEventsInSequenceOrder() throws Exception {
        String projectId = createProject("s1-run-" + UUID.randomUUID(), createSourceDir());
        trustProject(projectId, 1);
        String threadId = createThread(projectId, createProfile("fake 配置-" + UUID.randomUUID()));
        eventStore.initCursor(threadId);

        String runId = IdGen.next("run_");
        String turnId = IdGen.next("turn_");
        EventMeta meta = new EventMeta(projectId, threadId, turnId, runId, null, null, null);

        long seq1 = eventStore.append(meta, EventType.RUN_COMPLETED,
                new EventPayloads.RunCancelled("COMPLETED", "step1", 1));
        long seq2 = eventStore.append(meta, EventType.RUN_CANCELLED,
                new EventPayloads.RunCancelled("CANCELLED", "step2", 2));

        List<EventEntity> events = eventRepository.findByRunId(runId);

        assertEquals(2, events.size(), "findByRunId 应返回该 runId 下全部事件");
        assertEquals(seq1, events.get(0).getSequence(), "应按 sequence 升序排列");
        assertEquals(seq2, events.get(1).getSequence());
        assertTrue(events.stream().allMatch(e -> runId.equals(e.getRunId())),
                "返回的事件必须全部匹配该 runId");
    }

    @Test
    void findByRunIdReturnsEmptyForUnknownRun() {
        List<EventEntity> events = eventRepository.findByRunId("run_nonexistent_" + UUID.randomUUID());
        assertTrue(events.isEmpty(), "未知 runId 应返回空列表");
    }

    @Test
    void findByProjectIdReturnsRecentEventsInDescOrder() throws Exception {
        String projectId = createProject("s1-proj-" + UUID.randomUUID(), createSourceDir());
        trustProject(projectId, 1);
        String threadId = createThread(projectId, createProfile("fake 配置-" + UUID.randomUUID()));
        eventStore.initCursor(threadId);

        String runId = IdGen.next("run_");
        String turnId = IdGen.next("turn_");
        EventMeta meta = new EventMeta(projectId, threadId, turnId, runId, null, null, null);
        eventStore.append(meta, EventType.RUN_COMPLETED,
                new EventPayloads.RunCancelled("COMPLETED", "first", 1));
        eventStore.append(meta, EventType.RUN_CANCELLED,
                new EventPayloads.RunCancelled("CANCELLED", "second", 2));

        List<EventEntity> recent = eventRepository.findByProjectId(projectId, PageRequest.of(0, 10));

        assertEquals(2, recent.size(), "应返回该项目下的全部事件");
        assertTrue(recent.get(0).getSequence() > recent.get(1).getSequence(),
                "应按 sequence desc 返回：最近的事件在前");
        assertTrue(recent.stream().allMatch(e -> projectId.equals(e.getProjectId())),
                "返回的事件必须全部匹配该项目");
    }

    @Test
    void findByProjectIdRespectsLimit() throws Exception {
        String projectId = createProject("s1-limit-" + UUID.randomUUID(), createSourceDir());
        trustProject(projectId, 1);
        String threadId = createThread(projectId, createProfile("fake 配置-" + UUID.randomUUID()));
        eventStore.initCursor(threadId);

        String runId = IdGen.next("run_");
        String turnId = IdGen.next("turn_");
        EventMeta meta = new EventMeta(projectId, threadId, turnId, runId, null, null, null);
        for (int i = 0; i < 5; i++) {
            eventStore.append(meta, EventType.RUN_COMPLETED,
                    new EventPayloads.RunCancelled("COMPLETED", "n" + i, i + 1));
        }

        List<EventEntity> top3 = eventRepository.findByProjectId(projectId, PageRequest.of(0, 3));
        assertEquals(3, top3.size(), "limit=3 应只返回 3 条");
    }

    @Test
    void existingFindByTraceIdStillWorks() throws Exception {
        String projectId = createProject("s1-trace-" + UUID.randomUUID(), createSourceDir());
        trustProject(projectId, 1);
        String threadId = createThread(projectId, createProfile("fake 配置-" + UUID.randomUUID()));
        eventStore.initCursor(threadId);

        String runId = IdGen.next("run_");
        String turnId = IdGen.next("turn_");
        String traceId = IdGen.next("trace_");
        EventMeta meta = new EventMeta(projectId, threadId, turnId, runId, null, traceId, null);
        eventStore.append(meta, EventType.RUN_COMPLETED,
                new EventPayloads.RunCancelled("COMPLETED", "trace", 1));

        List<EventEntity> byTrace = eventRepository.findByTraceId(traceId);
        assertEquals(1, byTrace.size(), "既有 findByTraceId 不应被破坏");
    }

    private String createSourceDir() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("target")
                .toAbsolutePath().resolve("test-event-" + UUID.randomUUID());
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("README.md"), "source");
        return dir.toString().replace('\\', '/');
    }
}
