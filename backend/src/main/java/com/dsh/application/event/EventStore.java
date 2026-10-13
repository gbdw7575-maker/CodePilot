package com.dsh.application.event;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.event.EventEnvelope;
import com.dsh.api.dto.event.EventPage;
import com.dsh.common.IdGen;
import com.dsh.storage.EventCursorEntity;
import com.dsh.storage.EventCursorRepository;
import com.dsh.storage.EventEntity;
import com.dsh.storage.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一事件存储：append 必须在业务事务内调用（状态与事件同事务提交）。
 * sequence 由 thread 游标行加行锁分配；读侧支持断线补拉分页与历史缺口检测（410）。
 */
@Service
public class EventStore {

    public static final String SCHEMA_VERSION = "1.0";

    private final EventRepository eventRepository;
    private final EventCursorRepository cursorRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public EventStore(EventRepository eventRepository, EventCursorRepository cursorRepository,
                      ObjectMapper objectMapper, ApplicationEventPublisher eventPublisher) {
        this.eventRepository = eventRepository;
        this.cursorRepository = cursorRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /** Thread 创建时初始化高水位 0。 */
    @Transactional
    public void initCursor(String threadId) {
        cursorRepository.findById(threadId).orElseGet(() ->
                cursorRepository.save(new EventCursorEntity(threadId, 0L)));
    }

    /** 当前 thread 高水位（无游标视为 0）。 */
    public long highWatermark(String threadId) {
        return cursorRepository.findById(threadId).map(EventCursorEntity::getLastSequence).orElse(0L);
    }

    /**
     * 在当前事务内追加一个事件：行锁分配 sequence，构造信封并持久化。
     * 返回分配到的 sequence（正整数）。
     */
    @Transactional
    public long append(EventMeta meta, String type, Object payload) {
        EventCursorEntity cursor = cursorRepository.findByIdForUpdate(meta.threadId()).orElse(null);
        if (cursor == null) {
            // 兼容 DSH-003 之前创建的会话：首次写事件时补建游标。
            cursor = cursorRepository.saveAndFlush(new EventCursorEntity(meta.threadId(), 0L));
            cursor = cursorRepository.findByIdForUpdate(meta.threadId()).orElseThrow();
        }
        long sequence = cursor.getLastSequence() + 1L;
        cursor.setLastSequence(sequence);

        Instant timestamp = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        EventEnvelope envelope = new EventEnvelope(
                IdGen.next("evt_"),
                meta.projectId(),
                meta.threadId(),
                meta.turnId(),
                meta.runId(),
                meta.parentRunId(),
                meta.traceId(),
                meta.spanId(),
                Long.toString(sequence),
                timestamp,
                SCHEMA_VERSION,
                type,
                objectMapper.valueToTree(payload));
        String envelopeJson = writeEnvelope(envelope);
        eventRepository.save(new EventEntity(
                envelope.eventId(),
                envelope.projectId(),
                envelope.threadId(),
                envelope.turnId(),
                envelope.runId(),
                envelope.parentRunId(),
                envelope.traceId(),
                envelope.spanId(),
                sequence,
                type,
                envelopeJson,
                timestamp,
                SCHEMA_VERSION));
        // 在事务内发布；监听器使用 AFTER_COMMIT 确保仅提交后推送
        eventPublisher.publishEvent(new EventCommittedEvent(this, meta.threadId(), sequence, envelopeJson));
        return sequence;
    }

    /** Thread 内最早可用事件的 sequence；无事件返回 null。 */
    public Long findEarliestSequence(String threadId) {
        return eventRepository.findEarliestSequence(threadId);
    }

    /**
     * 区间补拉：只返回 after < sequence <= H 的事件。
     * 不传 throughSequence 时 H 取首次读取捕获的当前高水位。
     */
    public EventPage readPage(String threadId, long after, Long through, int limit) {
        long hw = highWatermark(threadId);
        if (after < 0L || after > hw) {
            throw new ApiException(ErrorCode.INVALID_CURSOR,
                    "afterSequence 超出当前高水位范围: " + after);
        }
        long bound = hw;
        if (through != null) {
            if (through < after) {
                throw new ApiException(ErrorCode.INVALID_CURSOR,
                        "throughSequence 不能小于 afterSequence");
            }
            if (through > hw) {
                throw new ApiException(ErrorCode.INVALID_CURSOR,
                        "throughSequence 不能超过当前 highWatermark");
            }
            bound = through;
        }
        Long earliest = eventRepository.findEarliestSequence(threadId);
        if (earliest != null && earliest > after + 1L) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("earliestAvailableSequence", Long.toString(earliest));
            details.put("highWatermark", Long.toString(hw));
            throw new ApiException(ErrorCode.EVENT_CURSOR_EXPIRED,
                    "请求的历史事件区间已被清理，无法完整补拉", details);
        }

        List<EventEntity> rows = eventRepository.findRange(threadId, after, bound,
                PageRequest.of(0, limit));
        long nextAfter = after;
        if (!rows.isEmpty()) {
            nextAfter = rows.get(rows.size() - 1).getSequence();
        }
        List<EventEnvelope> items = rows.stream().map(this::readEnvelope).toList();
        return new EventPage(items, Long.toString(nextAfter), nextAfter < bound, Long.toString(hw));
    }

    private String writeEnvelope(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件信封序列化失败: " + envelope.type(), e);
        }
    }

    private EventEnvelope readEnvelope(EventEntity entity) {
        try {
            return objectMapper.readValue(entity.getEnvelopeJson(), EventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件信封反序列化失败: " + entity.getId(), e);
        }
    }
}
