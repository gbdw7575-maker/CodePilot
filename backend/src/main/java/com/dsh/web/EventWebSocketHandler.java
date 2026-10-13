package com.dsh.web;

import com.dsh.api.dto.event.EventEnvelope;
import com.dsh.api.dto.event.EventPage;
import com.dsh.application.event.EventStore;
import com.dsh.application.event.WebSocketEventBus;
import com.dsh.storage.ThreadRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket 实时事件推送处理器（事件协议 §1~§4）。
 *
 * 连接流程：
 * 1. 客户端连接后 5 秒内发送 subscribe 消息
 * 2. 服务端校验 threadId，订阅事件总线（建立实时屏障），读取高水位 H
 * 3. 发送 ready(H)，重放 (afterSequence, H]，发送 caught_up
 * 4. 排空重放期间到达的缓冲事件（sequence > H），进入实时推送
 *
 * 屏障顺序：先订阅总线再读 H，保证不丢失订阅瞬间的事件；
 * 缓冲事件按 sequence > H 过滤，避免与 DB 重放重复。
 */
@Component
public class EventWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EventWebSocketHandler.class);

    /** 单连接发送缓冲上限（慢消费者保护，超出关闭连接）。 */
    static final int BUFFER_LIMIT = 256;
    /** 连接后等待 subscribe 消息的超时（毫秒）。 */
    static final long SUBSCRIBE_TIMEOUT_MS = 5_000L;
    /** 协议级 ping 间隔（毫秒）。 */
    static final long PING_INTERVAL_MS = 15_000L;
    /** 无 pong 断开阈值（毫秒）。 */
    static final long PONG_TIMEOUT_MS = 45_000L;

    private final EventStore eventStore;
    private final WebSocketEventBus eventBus;
    private final ThreadRepository threadRepository;
    private final ObjectMapper objectMapper;

    private final Set<WebSocketSession> activeSessions = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService heartbeatScheduler;

    public EventWebSocketHandler(EventStore eventStore, WebSocketEventBus eventBus,
                                 ThreadRepository threadRepository, ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.eventBus = eventBus;
        this.threadRepository = threadRepository;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void startHeartbeat() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dsh-ws-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeats,
                PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
    }

    private void sendHeartbeats() {
        long now = System.currentTimeMillis();
        for (WebSocketSession session : activeSessions) {
            SessionState state = (SessionState) session.getAttributes().get(SessionState.KEY);
            if (state == null) continue;
            try {
                if (now - state.lastPongTime.get() > PONG_TIMEOUT_MS) {
                    log.debug("WebSocket pong 超时，关闭连接 {}", session.getId());
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                    continue;
                }
                if (session.isOpen()) {
                    session.sendMessage(new PingMessage(ByteBuffer.allocate(0)));
                }
            } catch (IOException e) {
                log.debug("发送 ping 失败，连接可能已断开 {}", session.getId());
            }
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        SessionState state = (SessionState) session.getAttributes().get(SessionState.KEY);
        if (state != null) {
            state.lastPongTime.set(System.currentTimeMillis());
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        activeSessions.add(session);
        SessionState state = new SessionState(session, objectMapper);
        session.getAttributes().put(SessionState.KEY, state);
        // 5 秒内未收到 subscribe 则关闭
        state.scheduleSubscribeTimeout(() -> handleSubscribeTimeout(session));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        SessionState state = (SessionState) session.getAttributes().get(SessionState.KEY);
        if (state == null) {
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (IOException e) {
            sendErrorAndClose(session, "无法解析消息为 JSON");
            return;
        }
        String type = root.path("type").asText(null);
        if (!"subscribe".equals(type)) {
            sendErrorAndClose(session, "首条消息必须是 subscribe");
            return;
        }
        handleSubscribe(session, state, root);
    }

    private void handleSubscribe(WebSocketSession session, SessionState state, JsonNode root) {
        String threadId = root.path("threadId").asText(null);
        String afterSeqStr = root.path("afterSequence").asText("0");
        if (threadId == null || threadId.isBlank()) {
            sendErrorAndClose(session, "subscribe 缺少 threadId");
            return;
        }
        // 校验 thread 存在
        if (!threadRepository.existsById(threadId)) {
            sendErrorAndClose(session, "threadId 不存在: " + threadId);
            return;
        }
        long afterSequence;
        try {
            afterSequence = Long.parseLong(afterSeqStr);
        } catch (NumberFormatException e) {
            sendErrorAndClose(session, "afterSequence 必须是整数: " + afterSeqStr);
            return;
        }
        if (afterSequence < 0) {
            sendErrorAndClose(session, "afterSequence 不能为负");
            return;
        }

        state.cancelSubscribeTimeout();
        state.threadId = threadId;

        try {
            // 1. 先订阅事件总线（建立实时屏障），缓冲实时事件
            eventBus.subscribe(threadId, state::onLiveEvent);

            // 2. 读取高水位 H
            long highWatermark = eventStore.highWatermark(threadId);
            if (afterSequence > highWatermark) {
                sendErrorAndClose(session, "afterSequence 超出当前高水位: " + afterSequence);
                eventBus.unsubscribe(threadId, state::onLiveEvent);
                return;
            }

            // 检查过期：DB 中最早的 sequence 是否大于 afterSequence + 1
            Long earliest = eventStore.findEarliestSequence(threadId);
            if (earliest != null && earliest > afterSequence + 1L) {
                sendResyncAndClose(session, threadId, earliest, highWatermark);
                eventBus.unsubscribe(threadId, state::onLiveEvent);
                return;
            }

            // 3. 发送 ready
            sendReady(session, threadId, highWatermark);

            // 4. 重放 (afterSequence, highWatermark]
            long cursor = afterSequence;
            while (true) {
                EventPage page = eventStore.readPage(threadId, cursor, highWatermark, 100);
                for (EventEnvelope env : page.items()) {
                    sendEvent(session, env);
                    cursor = Long.parseLong(env.sequence());
                }
                if (!page.hasMore()) {
                    break;
                }
            }

            // 5. 标记已追上并发送 caught_up（协议 §1：caught_up 在 H 之后事件之前）
            state.caughtUp.set(true);
            sendCaughtUp(session, threadId, highWatermark);

            // 6. 排空重放期间到达的缓冲事件（sequence > highWatermark）
            //    持 session 锁保证缓冲事件与后续实时事件的发送顺序
            state.drainBuffer(session, highWatermark);
        } catch (Exception e) {
            log.error("WebSocket 订阅处理失败 threadId={}", threadId, e);
            sendErrorAndClose(session, "订阅处理失败: " + e.getMessage());
            eventBus.unsubscribe(threadId, state::onLiveEvent);
        }
    }

    private void handleSubscribeTimeout(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.POLICY_VIOLATION.withReason("subscribe 超时"));
            }
        } catch (IOException e) {
            log.debug("关闭超时连接失败", e);
        }
    }

    private void sendReady(WebSocketSession session, String threadId, long highWatermark) throws IOException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "ready");
        node.put("threadId", threadId);
        node.put("highWatermark", Long.toString(highWatermark));
        sendJson(session, node);
    }

    private void sendCaughtUp(WebSocketSession session, String threadId, long through) throws IOException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "caught_up");
        node.put("threadId", threadId);
        node.put("throughSequence", Long.toString(through));
        sendJson(session, node);
    }

    private void sendEvent(WebSocketSession session, EventEnvelope envelope) throws IOException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "event");
        node.set("event", objectMapper.valueToTree(envelope));
        sendJson(session, node);
    }

    private void sendEventJson(WebSocketSession session, String envelopeJson) throws IOException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "event");
        node.set("event", objectMapper.readTree(envelopeJson));
        sendJson(session, node);
    }

    private void sendErrorAndClose(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("type", "error");
                node.put("message", message);
                sendJson(session, node);
                session.close(CloseStatus.POLICY_VIOLATION.withReason(message));
            }
        } catch (IOException e) {
            log.debug("发送 error 帧失败", e);
        }
    }

    private void sendResyncAndClose(WebSocketSession session, String threadId,
                                    long earliest, long highWatermark) {
        try {
            if (session.isOpen()) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("type", "resync_required");
                node.put("threadId", threadId);
                node.put("earliestAvailableSequence", Long.toString(earliest));
                node.put("highWatermark", Long.toString(highWatermark));
                sendJson(session, node);
                session.close(CloseStatus.POLICY_VIOLATION.withReason("cursor expired"));
            }
        } catch (IOException e) {
            log.debug("发送 resync_required 帧失败", e);
        }
    }

    private void sendJson(WebSocketSession session, ObjectNode node) throws IOException {
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(node)));
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        activeSessions.remove(session);
        SessionState state = (SessionState) session.getAttributes().get(SessionState.KEY);
        if (state != null && state.threadId != null) {
            eventBus.unsubscribe(state.threadId, state::onLiveEvent);
        }
    }

    /**
     * 每连接状态：缓冲队列、caughtUp 标记、threadId、subscribe 超时。
     */
    private static class SessionState {
        static final String KEY = "dsh.ws.state";

        final WebSocketSession session;
        final ObjectMapper objectMapper;
        final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();
        final AtomicBoolean caughtUp = new AtomicBoolean(false);
        final AtomicReference<Long> lastPongTime = new AtomicReference<>(System.currentTimeMillis());
        volatile String threadId;
        private final AtomicReference<Thread> timeoutThread = new AtomicReference<>();

        SessionState(WebSocketSession session, ObjectMapper objectMapper) {
            this.session = session;
            this.objectMapper = objectMapper;
        }

        void scheduleSubscribeTimeout(Runnable onTimeout) {
            // 简化实现：用虚拟线程 + sleep 实现 5 秒超时
            Thread t = Thread.startVirtualThread(() -> {
                try {
                    Thread.sleep(SUBSCRIBE_TIMEOUT_MS);
                    if (threadId == null && session.isOpen()) {
                        onTimeout.run();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            timeoutThread.set(t);
        }

        void cancelSubscribeTimeout() {
            Thread t = timeoutThread.getAndSet(null);
            if (t != null) {
                t.interrupt();
            }
        }

        /** 事件总线回调：缓冲实时事件，caught_up 后直接发送。 */
        boolean onLiveEvent(String envelopeJson) {
            if (!session.isOpen()) {
                return false; // 移除订阅者
            }
            if (caughtUp.get()) {
                // 已 caught_up：直接发送
                try {
                    synchronized (session) {
                        if (session.isOpen()) {
                            session.sendMessage(new TextMessage(
                                    "{\"type\":\"event\",\"event\":" + envelopeJson + "}"));
                        }
                    }
                    return true;
                } catch (IOException e) {
                    return false;
                }
            } else {
                // 重放期间：缓冲
                if (buffer.size() >= BUFFER_LIMIT) {
                    // 缓冲溢出：关闭连接，让客户端重连补拉
                    try {
                        session.close(CloseStatus.POLICY_VIOLATION.withReason("buffer overflow"));
                    } catch (IOException ignored) {
                    }
                    return false;
                }
                buffer.offer(envelopeJson);
                return true;
            }
        }

        /** 排空缓冲中 sequence > highWatermark 的事件，避免与 DB 重放重复。持 session 锁保证与实时事件的发送顺序。 */
        void drainBuffer(WebSocketSession session, long highWatermark) throws IOException {
            synchronized (session) {
                String item;
                while ((item = buffer.poll()) != null) {
                    try {
                        JsonNode env = objectMapper.readTree(item);
                        long seq = Long.parseLong(env.path("sequence").asText());
                        if (seq > highWatermark && session.isOpen()) {
                            session.sendMessage(new TextMessage(
                                    "{\"type\":\"event\",\"event\":" + item + "}"));
                        }
                    } catch (Exception e) {
                        log.warn("缓冲事件解析失败", e);
                    }
                }
            }
        }
    }
}
