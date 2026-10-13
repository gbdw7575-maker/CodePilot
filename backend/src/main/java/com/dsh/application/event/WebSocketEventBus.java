package com.dsh.application.event;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * WebSocket 事件总线：维护 threadId → 订阅者集合的映射。
 * 由 {@link EventCommitListener} 在事务提交后调用 publish，将已持久化的事件
 * 转发给所有订阅该 Thread 的 WebSocket 连接。
 */
@Service
public class WebSocketEventBus {

    /** 订阅者：接收已序列化的事件信封 JSON。 */
    @FunctionalInterface
    public interface Subscriber {
        /** 返回 false 表示订阅者已失效，应被移除。 */
        boolean onEvent(String envelopeJson);
    }

    private final ConcurrentHashMap<String, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();

    public void subscribe(String threadId, Subscriber subscriber) {
        subscribers.computeIfAbsent(threadId, k -> ConcurrentHashMap.newKeySet()).add(subscriber);
    }

    public void unsubscribe(String threadId, Subscriber subscriber) {
        Set<Subscriber> set = subscribers.get(threadId);
        if (set != null) {
            set.remove(subscriber);
            if (set.isEmpty()) {
                subscribers.remove(threadId, set);
            }
        }
    }

    /**
     * 向指定 threadId 的所有订阅者广播事件信封 JSON。
     * 订阅者返回 false 时自动移除。
     */
    public void publish(String threadId, String envelopeJson) {
        Set<Subscriber> set = subscribers.get(threadId);
        if (set == null || set.isEmpty()) {
            return;
        }
        // 复制一份避免迭代时移除导致的 ConcurrentModification
        for (Subscriber sub : Set.copyOf(set)) {
            try {
                if (!sub.onEvent(envelopeJson)) {
                    set.remove(sub);
                }
            } catch (Exception e) {
                // 订阅者异常不应影响其他订阅者；移除失效订阅者
                set.remove(sub);
            }
        }
        if (set.isEmpty()) {
            subscribers.remove(threadId, set);
        }
    }

    public int subscriberCount(String threadId) {
        Set<Subscriber> set = subscribers.get(threadId);
        return set == null ? 0 : set.size();
    }

    public int totalConnections() {
        return subscribers.values().stream().mapToInt(Set::size).sum();
    }
}
