package com.dsh.application.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 监听 {@link EventCommittedEvent}，在事务提交后将已持久化的事件转发到 {@link WebSocketEventBus}。
 * 使用 AFTER_COMMIT 保证事务回滚时不推送，确保状态与事件一致性。
 */
@Component
public class EventCommitListener {

    private final WebSocketEventBus eventBus;

    public EventCommitListener(WebSocketEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventCommitted(EventCommittedEvent event) {
        eventBus.publish(event.getThreadId(), event.getEnvelopeJson());
    }
}
