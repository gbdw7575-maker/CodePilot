package com.dsh.memory;

import com.dsh.api.ApiTestBase;
import com.dsh.application.DshProperties;
import com.dsh.application.event.EventMeta;
import com.dsh.application.event.EventStore;
import com.dsh.api.dto.event.EventPayloads;
import com.dsh.api.dto.event.EventType;
import com.dsh.common.IdGen;
import com.dsh.storage.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2 L5 情景记忆召回验收：入库→召回→衰减→隔离→禁用兜底。
 */
class EpisodicMemoryServiceTest extends ApiTestBase {

    @Autowired
    private EpisodicMemoryService service;
    @Autowired
    private EpisodicMemoryRepository repository;
    @Autowired
    private EventStore eventStore;
    @Autowired
    private EventRepository eventRepository;

    @Test
    void recallReturnsEmptyWhenDisabled() {
        DshProperties.Episodic disabled = new DshProperties.Episodic(false, 3, 2, 10, 0.05, 0.5, 200);
        EpisodicMemoryService svc = new EpisodicMemoryService(repository,
                new EpisodicMemoryExtractor(), eventRepository,
                new DshProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, null, disabled, null));
        List<EpisodicMemoryEntry> result = svc.recall("any-project", "any intent");
        assertTrue(result.isEmpty(), "enabled=false 时召回应返回空");
    }

    @Test
    void recallReturnsEmptyForBlankIntent() {
        List<EpisodicMemoryEntry> result = service.recall("proj-" + UUID.randomUUID(), "  ");
        assertTrue(result.isEmpty(), "空意图召回应返回空");
    }

    @Test
    void ingestThenRecallFindsEntryByKeyword() throws Exception {
        String projectId = createProject("s2-recall-" + UUID.randomUUID(), createSourceDir());
        trustProject(projectId, 1);
        String threadId = createThread(projectId, createProfile("fake 配置-" + UUID.randomUUID()));
        eventStore.initCursor(threadId);

        String runId = IdGen.next("run_");
        String turnId = IdGen.next("turn_");
        String traceId = IdGen.next("trace_");
        EventMeta meta = new EventMeta(projectId, threadId, turnId, runId, null, traceId, null);
        // 用 RUN_CANCELLED 事件让 extractor 提取 reason 字段（RunCancelled 的 reason 字段会被命中）
        eventStore.append(meta, EventType.RUN_CANCELLED,
                new EventPayloads.RunCancelled("CANCELLED", "修复内存泄漏问题", 1));
        eventStore.append(meta, EventType.RUN_COMPLETED,
                new EventPayloads.RunCancelled("COMPLETED", "OK", 1));

        // ingest 同步调用（异步会带来时序问题；测试直接同步调用底层 ingest）
        ingestSync(projectId, runId, traceId, "COMPLETED", "PASSED");

        List<EpisodicMemoryEntry> result = service.recall(projectId, "内存泄漏 怎么修复");
        assertFalse(result.isEmpty(), "召回应命中刚入库的情景");
        EpisodicMemoryEntry hit = result.get(0);
        assertEquals(runId, hit.getSourceRunId(), "命中情景的 sourceRunId 应匹配");
        assertEquals(projectId, hit.getProjectId(), "应按 projectId 隔离");
        // 衰减权重应为 1.0（刚入库）
        assertEquals(1.0, hit.getDecayWeight(), 0.001);
    }

    @Test
    void recallIsolatesByProject() throws Exception {
        String projectIdA = createProject("s2-isoA-" + UUID.randomUUID(), createSourceDir());
        String projectIdB = createProject("s2-isoB-" + UUID.randomUUID(), createSourceDir());
        trustProject(projectIdA, 1);
        trustProject(projectIdB, 1);
        String threadA = createThread(projectIdA, createProfile("fake 配置-A-" + UUID.randomUUID()));
        String threadB = createThread(projectIdB, createProfile("fake 配置-B-" + UUID.randomUUID()));
        eventStore.initCursor(threadA);
        eventStore.initCursor(threadB);

        String runIdA = IdGen.next("run_");
        String runIdB = IdGen.next("run_");
        EventMeta metaA = new EventMeta(projectIdA, threadA, IdGen.next("turn_"), runIdA, null, null, null);
        EventMeta metaB = new EventMeta(projectIdB, threadB, IdGen.next("turn_"), runIdB, null, null, null);
        // 用 RUN_CANCELLED 让 extractor 提取 reason 字段
        eventStore.append(metaA, EventType.RUN_CANCELLED,
                new EventPayloads.RunCancelled("CANCELLED", "PostgreSQL 迁移", 1));
        eventStore.append(metaB, EventType.RUN_CANCELLED,
                new EventPayloads.RunCancelled("CANCELLED", "PostgreSQL 迁移", 1));

        ingestSync(projectIdA, runIdA, null, "COMPLETED", "PASSED");
        ingestSync(projectIdB, runIdB, null, "COMPLETED", "PASSED");

        List<EpisodicMemoryEntry> hitsA = service.recall(projectIdA, "PostgreSQL 迁移");
        assertEquals(1, hitsA.size(), "项目 A 应只召回项目 A 的情景");
        assertEquals(runIdA, hitsA.get(0).getSourceRunId());

        List<EpisodicMemoryEntry> hitsB = service.recall(projectIdB, "PostgreSQL 迁移");
        assertEquals(1, hitsB.size(), "项目 B 应只召回项目 B 的情景");
        assertEquals(runIdB, hitsB.get(0).getSourceRunId());
    }

    @Test
    void recallHonorsTopN() throws Exception {
        String projectId = createProject("s2-topn-" + UUID.randomUUID(), createSourceDir());
        trustProject(projectId, 1);
        String threadId = createThread(projectId, createProfile("fake 配置-" + UUID.randomUUID()));
        eventStore.initCursor(threadId);

        // 入库 5 条情景，全部命中关键词 "verification"
        for (int i = 0; i < 5; i++) {
            String runId = IdGen.next("run_");
            String turnId = IdGen.next("turn_");
            EventMeta meta = new EventMeta(projectId, threadId, turnId, runId, null, null, null);
            // 使用 RUN_CANCELLED 让 extractor 的 run.cancelled 分支提取 reason 字段
            eventStore.append(meta, EventType.RUN_CANCELLED,
                    new EventPayloads.RunCancelled("CANCELLED", "verification run " + i, i + 1));
            ingestSync(projectId, runId, null, "CANCELLED", "FAILED");
        }

        List<EpisodicMemoryEntry> hits = service.recall(projectId, "verification test");
        // recallTopN 默认 3
        assertTrue(hits.size() <= 3, "召回结果应受 recallTopN=3 上限约束");
    }

    @Test
    void decayWeightFallsOverTime() {
        Instant old = Instant.now().minus(20, ChronoUnit.DAYS);
        Instant now = Instant.now();
        double weight = service.computeDecayWeight(old, now);
        // 20 天 * 0.05 = 1.0 衰减，但 floor=0.5
        assertEquals(0.5, weight, 0.001, "20 天后应跌至 floor=0.5");
    }

    @Test
    void extractorProducesSummaryAndKeywords() {
        // 直接测试 Extractor 不依赖 Spring 上下文
        EpisodicMemoryExtractor extractor = new EpisodicMemoryExtractor();
        // 用 RUN_CANCELLED 让 run.cancelled 分支提取 reason 字段
        com.dsh.storage.EventEntity mockEvent = mockEventWithEnvelope(
                EventType.RUN_CANCELLED,
                "{\"reason\":\"修复 PostgreSQL 连接池泄漏\",\"state\":\"CANCELLED\",\"version\":1}");
        EpisodicMemoryExtractor.Extraction ex = extractor.extract(
                List.of(mockEvent), "COMPLETED", "PASSED");
        assertFalse(ex.summaryText().isEmpty(), "摘要不应为空");
        assertFalse(ex.keywords().isEmpty(), "关键词不应为空");
        assertTrue(ex.keywords().contains("postgresql"), "英文单词应被切分");
        // CJK 二元组分词：连接池 → "连接" + "接池"
        assertTrue(ex.keywords().contains("连接") || ex.keywords().contains("接池"),
                "CJK 二元组应被切分，实际 keywords=" + ex.keywords());
    }

    private void ingestSync(String projectId, String runId, String traceId,
                            String finalState, String verificationStatus) {
        // 直接调底层 repository 写入，模拟 ingest 已完成（@Async 在测试中难以同步等待）
        List<com.dsh.storage.EventEntity> events = eventRepository.findByRunId(runId);
        EpisodicMemoryExtractor extractor = new EpisodicMemoryExtractor();
        EpisodicMemoryExtractor.Extraction ex = extractor.extract(events, finalState, verificationStatus);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repository.save(new EpisodicMemoryEntry(
                IdGen.next("ep_"), projectId, runId, traceId,
                ex.summaryText(), ex.keywords(),
                finalState, verificationStatus, now, 1.0));
    }

    private com.dsh.storage.EventEntity mockEventWithEnvelope(String type, String envelope) {
        Instant now = Instant.now();
        return new com.dsh.storage.EventEntity(
                IdGen.next("evt_"), "proj-test", "thr-test", "turn-test",
                "run-test", null, null, null, 1L, type, envelope, now, "1.0");
    }

    private String createSourceDir() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("target")
                .toAbsolutePath().resolve("test-episodic-" + UUID.randomUUID());
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("README.md"), "source");
        return dir.toString().replace('\\', '/');
    }
}
