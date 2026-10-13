package com.dsh.application.event;

import org.springframework.context.ApplicationEvent;

/**
 * 事件持久化后发布的内部 Spring 事件，携带已序列化的事件信封 JSON。
 * 由 {@link EventStore#append} 在事务内发布，监听器必须使用
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 确保仅在事务提交后推送。
 */
public class EventCommittedEvent extends ApplicationEvent {

    private final String threadId;
    private final long sequence;
    private final String envelopeJson;

    public EventCommittedEvent(Object source, String threadId, long sequence, String envelopeJson) {
        super(source);
        this.threadId = threadId;
        this.sequence = sequence;
        this.envelopeJson = envelopeJson;
    }

    public String getThreadId() {
        return threadId;
    }

    public long getSequence() {
        return sequence;
    }

    public String getEnvelopeJson() {
        return envelopeJson;
    }
}
