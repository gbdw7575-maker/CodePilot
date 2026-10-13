package com.dsh.api;

import com.dsh.application.event.EventMeta;
import com.dsh.application.event.EventStore;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-008 WebSocket 实时事件推送验收：握手、订阅、重放、caught_up、实时推送、事务回滚不推送。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private EventStore eventStore;
    @Autowired
    private ThreadRepository threadRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private StandardWebSocketClient client;
    private final List<String> receivedFrames = new ArrayList<>();
    private WebSocketSession activeSession;
    private CompletableFuture<Void> frameArrived = new CompletableFuture<>();

    @BeforeEach
    void setUp() {
        client = new StandardWebSocketClient();
        receivedFrames.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (activeSession != null && activeSession.isOpen()) {
            activeSession.close(CloseStatus.NORMAL);
        }
        activeSession = null;
        client = null;
    }

    private String createThreadDirect() {
        String threadId = "thr_ws_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Instant now = Instant.now();
        ThreadEntity thread = new ThreadEntity(threadId, "proj_ws_test", "ws-test", "prof_ws_test", now, now);
        threadRepository.save(thread);
        eventStore.initCursor(threadId);
        return threadId;
    }

    private long appendEventCommitted(String threadId, String type) {
        return transactionTemplate.execute(status -> {
            EventMeta meta = new EventMeta("proj_ws_test", threadId, null, null, null, null, null);
            return eventStore.append(meta, type, java.util.Map.of());
        });
    }

    private void appendEventRollback(String threadId, String type) {
        transactionTemplate.execute(status -> {
            EventMeta meta = new EventMeta("proj_ws_test", threadId, null, null, null, null, null);
            eventStore.append(meta, type, java.util.Map.of());
            status.setRollbackOnly();
            return null;
        });
    }

    @Test
    void subscribeReceivesReadyReplayAndCaughtUp() throws Exception {
        String threadId = createThreadDirect();
        appendEventCommitted(threadId, "run.state_changed");
        appendEventCommitted(threadId, "run.completed");

        connectAndSubscribe(threadId, "0");
        waitForFrames(4, 5, TimeUnit.SECONDS); // ready + 2 events + caught_up

        List<String> frames = new ArrayList<>(receivedFrames);
        JsonNode ready = objectMapper.readTree(frames.get(0));
        assertEquals("ready", ready.path("type").asText());
        assertEquals("2", ready.path("highWatermark").asText());

        assertEquals("event", objectMapper.readTree(frames.get(1)).path("type").asText());
        assertEquals("run.state_changed", objectMapper.readTree(frames.get(1)).path("event").path("type").asText());
        assertEquals("1", objectMapper.readTree(frames.get(1)).path("event").path("sequence").asText());

        assertEquals("run.completed", objectMapper.readTree(frames.get(2)).path("event").path("type").asText());
        assertEquals("2", objectMapper.readTree(frames.get(2)).path("event").path("sequence").asText());

        JsonNode caughtUp = objectMapper.readTree(frames.get(3));
        assertEquals("caught_up", caughtUp.path("type").asText());
        assertEquals("2", caughtUp.path("throughSequence").asText());
    }

    @Test
    void liveEventsArePushedAfterCaughtUp() throws Exception {
        String threadId = createThreadDirect();
        connectAndSubscribe(threadId, "0");
        waitForFrames(2, 5, TimeUnit.SECONDS); // ready + caught_up

        long seq = appendEventCommitted(threadId, "tool.started");
        waitForFrames(3, 5, TimeUnit.SECONDS); // + 1 live event

        List<String> frames = new ArrayList<>(receivedFrames);
        assertEquals(3, frames.size());
        JsonNode event = objectMapper.readTree(frames.get(2));
        assertEquals("event", event.path("type").asText());
        assertEquals("tool.started", event.path("event").path("type").asText());
        assertEquals(String.valueOf(seq), event.path("event").path("sequence").asText());
    }

    @Test
    void rolledBackEventsAreNotPushed() throws Exception {
        String threadId = createThreadDirect();
        connectAndSubscribe(threadId, "0");
        waitForFrames(2, 5, TimeUnit.SECONDS); // ready + caught_up

        appendEventRollback(threadId, "run.state_changed");
        Thread.sleep(500);
        assertEquals(2, receivedFrames.size(), "回滚事件不应被推送");

        appendEventCommitted(threadId, "run.completed");
        waitForFrames(3, 5, TimeUnit.SECONDS);
        JsonNode event = objectMapper.readTree(receivedFrames.get(2));
        assertEquals("run.completed", event.path("event").path("type").asText());
    }

    @Test
    void invalidThreadIdReturnsErrorAndCloses() throws Exception {
        String threadId = "thr_nonexistent_" + UUID.randomUUID();
        connectAndSubscribe(threadId, "0");
        waitForFrames(1, 5, TimeUnit.SECONDS); // error frame

        boolean hasError = receivedFrames.stream().anyMatch(f -> {
            try {
                return "error".equals(objectMapper.readTree(f).path("type").asText());
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(hasError, "无效 threadId 应返回 error 帧");
    }

    // ---------------- WebSocket 客户端辅助 ----------------

    private synchronized void notifyFrame() {
        if (!frameArrived.isDone()) {
            frameArrived.complete(null);
        }
    }

    private void connectAndSubscribe(String threadId, String afterSequence) throws Exception {
        frameArrived = new CompletableFuture<>();
        URI uri = URI.create("ws://127.0.0.1:" + port + "/ws/v1/events");
        activeSession = client.execute(
                new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                        synchronized (receivedFrames) {
                            receivedFrames.add(message.getPayload());
                        }
                        notifyFrame();
                    }
                },
                new WebSocketHttpHeaders(),
                uri
        ).get(5, TimeUnit.SECONDS);

        activeSession.sendMessage(new TextMessage(
                "{\"type\":\"subscribe\",\"threadId\":\"" + threadId
                        + "\",\"afterSequence\":\"" + afterSequence + "\"}"));
    }

    private void waitForFrames(int minCount, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < deadline) {
            synchronized (receivedFrames) {
                if (receivedFrames.size() >= minCount) return;
            }
            frameArrived = new CompletableFuture<>();
            try {
                frameArrived.get(Math.min(500, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException ignored) {
            }
        }
    }
}
