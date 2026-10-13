package com.dsh.memory;

import com.dsh.agent.LoopSteps;
import com.dsh.agent.model.ModelRequest;
import com.dsh.api.ApiTestBase;
import com.dsh.api.dto.event.EventPayloads;
import com.dsh.api.dto.event.EventType;
import com.dsh.application.event.EventMeta;
import com.dsh.application.event.EventStore;
import com.dsh.common.IdGen;
import com.dsh.storage.EventEntity;
import com.dsh.storage.EventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆功能端到端冒烟（六层记忆系统）：
 * L4 语义记忆 save→retrieve 可见；L5 情景记忆 事件→入库→召回 命中；
 * assemble 集成注入 L6 基线→L4→L5 段落顺序正确且 prefixHash 非空。
 */
class MemoryFeatureSmokeTest extends ApiTestBase {

    @Autowired
    private LoopSteps loopSteps;
    @Autowired
    private MemoryProvider memoryProvider;
    @Autowired
    private EpisodicMemoryRepository episodicRepository;
    @Autowired
    private EpisodicMemoryService episodicService;
    @Autowired
    private EventStore eventStore;
    @Autowired
    private EventRepository eventRepository;

    @TempDir
    Path projectRoot;

    private String bootstrap() throws Exception {
        String projectId = createProject("记忆冒烟-" + UUID.randomUUID(),
                projectRoot.toString().replace('\\', '/'));
        trustProject(projectId, 1);
        String threadId = createThread(projectId,
                createProfile("fake-answer-" + UUID.randomUUID(), "fake-answer"));
        return projectId + "|" + threadId;
    }

    @Test
    void l4SaveThenRetrieveVisibleInIndex() throws Exception {
        String[] ids = bootstrap().split("\\|");
        String projectId = ids[0];

        memoryProvider.save(projectId, "project", "生产库已迁移 PostgreSQL，连接池用 HikariCP",
                "全局", "smoke", 1);

        MemoryIndex index = memoryProvider.retrieve(projectId);
        assertTrue(index.enabled(), "Auto Memory 应启用");
        assertTrue(index.text().contains("HikariCP"), "L4 索引应含已存经验: " + index.text());
    }

    @Test
    void l5EventIngestThenRecallHits() throws Exception {
        String[] ids = bootstrap().split("\\|");
        String projectId = ids[0];
        String threadId = ids[1];
        String histRunId = seedHistoricalRun(projectId, threadId, "PostgreSQL 连接池泄漏修复");

        List<EpisodicMemoryEntry> hits = episodicService.recall(projectId, "PostgreSQL 连接池怎么修");
        assertFalse(hits.isEmpty(), "L5 应召回历史情景");
        assertTrue(hits.stream().anyMatch(e -> histRunId.equals(e.getSourceRunId())),
                "召回应命中历史 run: " + histRunId);
    }

    @Test
    void assembleInjectsL4AndL5InOrder() throws Exception {
        String[] ids = bootstrap().split("\\|");
        String projectId = ids[0];
        String threadId = ids[1];

        // L4 语义记忆
        memoryProvider.save(projectId, "project", "生产库已迁移 PostgreSQL，连接池用 HikariCP",
                "全局", "smoke", 1);
        // L5 情景记忆
        String histRunId = seedHistoricalRun(projectId, threadId, "PostgreSQL 连接池泄漏修复");

        JsonNode accepted = submitTurn(threadId, "PostgreSQL 连接池泄漏怎么修");
        String runId = accepted.get("rootRunId").asText();
        ModelRequest request = loopSteps.assemble(runId, loopSteps.snapshot(runId));

        String system = request.messages().get(0).content();
        assertTrue(system.contains("你是 DSH 编码 Agent"), "L6 基线应在: " + system);
        assertTrue(system.contains("相关经验记忆"), "L4 段落应注入: " + system);
        assertTrue(system.contains("HikariCP"), "L4 内容应可见: " + system);
        assertTrue(system.contains("相关历史情景"), "L5 段落应注入: " + system);
        assertTrue(system.contains(histRunId), "L5 应标注来源 run: " + system);
        // 注入顺序：L6 基线 → L4 → L5
        assertTrue(system.indexOf("你是 DSH 编码 Agent") < system.indexOf("相关经验记忆"),
                "L6 基线应在 L4 之前");
        assertTrue(system.indexOf("相关经验记忆") < system.indexOf("相关历史情景"),
                "L4 应在 L5 之前");
        assertNotNull(request.prefixHash(), "prefixHash 应非空");
        assertFalse(request.prefixHash().isBlank(), "prefixHash 应非空白");
    }

    @Test
    void runCompletionAutoIngestsEpisodicMemory() throws Exception {
        String[] ids = bootstrap().split("\\|");
        String projectId = ids[0];
        String threadId = ids[1];

        // 真实跑一次 Run（fake-answer 直接完成），终态钩子应自动触发 L5 入库
        JsonNode accepted = submitTurn(threadId, "修复连接池泄漏问题");
        String runId = accepted.get("rootRunId").asText();

        // @Async 异步入库，轮询等待（最多 10s）
        boolean ingested = false;
        for (int i = 0; i < 100; i++) {
            if (episodicRepository.existsBySourceRunId(runId)) {
                ingested = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(ingested, "Run 终态后应自动入库 L5 情景记忆: " + runId);
    }

    /** 造一条历史 Run 事件并同步落 episodic 库（绕 @Async，保证测试时序确定）。 */
    private String seedHistoricalRun(String projectId, String threadId, String reason) {
        eventStore.initCursor(threadId);
        String histRunId = IdGen.next("run_");
        EventMeta meta = new EventMeta(projectId, threadId, IdGen.next("turn_"), histRunId,
                null, null, null);
        eventStore.append(meta, EventType.RUN_CANCELLED,
                new EventPayloads.RunCancelled("CANCELLED", reason, 1));
        List<EventEntity> events = eventRepository.findByRunId(histRunId);
        EpisodicMemoryExtractor.Extraction ex = new EpisodicMemoryExtractor()
                .extract(events, "COMPLETED", "PASSED");
        episodicRepository.save(new EpisodicMemoryEntry(IdGen.next("ep_"), projectId, histRunId,
                null, ex.summaryText(), ex.keywords(), "COMPLETED", "PASSED",
                Instant.now().truncatedTo(ChronoUnit.MILLIS), 1.0));
        return histRunId;
    }
}
