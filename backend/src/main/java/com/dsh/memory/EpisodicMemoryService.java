package com.dsh.memory;

import com.dsh.application.DshProperties;
import com.dsh.common.IdGen;
import com.dsh.storage.EventEntity;
import com.dsh.storage.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L5 情景记忆服务（S2 新增）：跨会话召回与当前用户意图相关的历史 Run 摘要。
 * <p>
 * 召回：{@link #recall(String, String)} 按当前用户意图分词→逐词 LIKE 查询→
 * 按 minKeywords 过滤→按命中数+decayWeight 排序→取 top-N。
 * <p>
 * 入库：{@link #ingest(String, String, String, String, String)} 在 Run 终态后异步执行，
 * 从 EventRepository 拉取该 Run 的事件，用 {@link EpisodicMemoryExtractor} 提取摘要后入库。
 * <p>
 * 隔离：按 projectId 隔离，不跨项目召回。
 * 衰减：decayWeight = 1 - min(decayFloor, ageDays * decayPerDay)，近期事件权重高。
 */
@Service
public class EpisodicMemoryService {

    private static final Logger log = LoggerFactory.getLogger(EpisodicMemoryService.class);

    private final EpisodicMemoryRepository repository;
    private final EpisodicMemoryExtractor extractor;
    private final EventRepository eventRepository;
    private final DshProperties.Episodic config;

    public EpisodicMemoryService(EpisodicMemoryRepository repository,
                                  EpisodicMemoryExtractor extractor,
                                  EventRepository eventRepository,
                                  DshProperties properties) {
        this.repository = repository;
        this.extractor = extractor;
        this.eventRepository = eventRepository;
        this.config = properties.episodic() == null
                ? new DshProperties.Episodic(true, 3, 2, 10, 0.05, 0.5, 200)
                : properties.episodic();
    }

    /**
     * 召回与当前用户意图相关的历史情景摘要。返回 top-N 条已按命中数+衰减权重排序的情景。
     * 配置关闭时返回空列表（fail-open，不阻断主 Loop）。
     */
    public List<EpisodicMemoryEntry> recall(String projectId, String currentIntent) {
        if (!config.enabled()) {
            return List.of();
        }
        if (projectId == null || projectId.isBlank() || currentIntent == null || currentIntent.isBlank()) {
            return List.of();
        }
        Set<String> keywords = tokenize(currentIntent);
        if (keywords.size() < config.minKeywords()) {
            // 关键词不足：召回噪声大，直接返回空，引导模型走 L4 语义记忆兜底
            return List.of();
        }

        // 逐词 LIKE 召回 + 命中计数 + 衰减重算
        Map<String, EpisodicMemoryEntry> entryById = new LinkedHashMap<>();
        Map<String, Integer> hitCount = new LinkedHashMap<>();
        for (String kw : keywords) {
            List<EpisodicMemoryEntry> hits = repository.findByProjectAndKeyword(projectId, kw);
            for (EpisodicMemoryEntry e : hits) {
                entryById.put(e.getId(), e);
                hitCount.merge(e.getId(), 1, Integer::sum);
            }
        }

        // 过滤命中数 < minKeywords 的候选（噪声过滤）
        int minHits = Math.max(1, config.minKeywords() / 2);
        List<EpisodicMemoryEntry> candidates = new ArrayList<>();
        for (EpisodicMemoryEntry e : entryById.values()) {
            int hits = hitCount.getOrDefault(e.getId(), 0);
            if (hits >= minHits) {
                candidates.add(e);
            }
        }

        // 按命中数 desc → 衰减后权重 desc → createdAt desc 排序，取 top-N
        // 用 lambda 比较器避免 Comparator 链式 reversed() 的反转语义陷阱
        Instant now = Instant.now();
        candidates.sort((a, b) -> {
            int hitA = hitCount.getOrDefault(a.getId(), 0);
            int hitB = hitCount.getOrDefault(b.getId(), 0);
            if (hitA != hitB) {
                return Integer.compare(hitB, hitA); // desc：命中多在前
            }
            double decA = computeDecayWeight(a.getCreatedAt(), now);
            double decB = computeDecayWeight(b.getCreatedAt(), now);
            if (Double.compare(decA, decB) != 0) {
                return Double.compare(decB, decA); // desc：权重高在前
            }
            return b.getCreatedAt().compareTo(a.getCreatedAt()); // desc：近的在前
        });

        return candidates.size() <= config.recallTopN()
                ? candidates
                : new ArrayList<>(candidates.subList(0, config.recallTopN()));
    }

    /**
     * 异步入库：在 Run 终态后从事件流摘要入库。失败仅记录日志，不阻塞主 Loop。
     */
    @Async
    public void ingest(String projectId, String runId, String traceId,
                       String finalState, String verificationStatus) {
        if (!config.enabled() || projectId == null || runId == null) {
            return;
        }
        try {
            if (repository.existsBySourceRunId(runId)) {
                return; // 幂等：终态钩子可能被 start/resume 多入口 finally 重复触发
            }
            List<EventEntity> events = eventRepository.findByRunId(runId);
            if (events.isEmpty()) {
                return;
            }
            EpisodicMemoryExtractor.Extraction ex = extractor.extract(events, finalState, verificationStatus);
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            double decay = computeDecayWeight(now, now); // 入库时权重为 1.0
            repository.save(new EpisodicMemoryEntry(
                    IdGen.next("ep_"),
                    projectId,
                    runId,
                    traceId,
                    ex.summaryText(),
                    ex.keywords(),
                    finalState == null ? "UNKNOWN" : finalState,
                    verificationStatus,
                    now,
                    decay));
        } catch (Exception e) {
            log.warn("episodic memory ingest failed for run {}: {}", runId, e.getMessage());
        }
    }

    /**
     * 计算时间衰减权重：1 - min(decayFloor, ageDays * decayPerDay)。
     * ageDays=0 时为 1.0（新事件权重最高）；ageDays 超过阈值后跌至 decayFloor。
     */
    double computeDecayWeight(Instant createdAt, Instant now) {
        long ageDays = Duration.between(createdAt, now).toDays();
        if (ageDays <= 0) {
            return 1.0;
        }
        double decay = ageDays * config.decayPerDay();
        return Math.max(config.decayFloor(), 1.0 - decay);
    }

    /**
     * 用户意图关键词分词：复用与 Extractor 一致的 CJK 二元组 + 英文单词策略。
     * 在召回侧独立实现以避免对 Extractor 的耦合（Extractor 是入库语义，recall 是召回语义）。
     */
    private Set<String> tokenize(String text) {
        Set<String> sink = new HashSet<>();
        int n = text.length();
        int i = 0;
        StringBuilder englishWord = new StringBuilder();
        while (i < n) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushEnglish(englishWord, sink);
                if (i + 1 < n && isCjk(text.charAt(i + 1))) {
                    sink.add(text.substring(i, i + 2));
                    i += 2;
                } else {
                    sink.add(String.valueOf(c));
                    i += 1;
                }
            } else if (Character.isLetterOrDigit(c)) {
                englishWord.append(c);
                i += 1;
            } else {
                flushEnglish(englishWord, sink);
                i += 1;
            }
        }
        flushEnglish(englishWord, sink);
        return sink;
    }

    private void flushEnglish(StringBuilder word, Set<String> sink) {
        if (word.length() == 0) {
            return;
        }
        String w = word.toString().toLowerCase();
        word.setLength(0);
        if (w.length() >= 2) {
            sink.add(w);
        }
    }

    private boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    DshProperties.Episodic config() {
        return config;
    }
}
