package com.dsh.agent;

/**
 * D-07：单次模型请求上下文超出窗口且无法通过有界缩减压回窗口内时抛出。
 * AgentLoop 捕获后以 CONTEXT_LIMIT 失败终态收尾，不把截断上下文冒充成功请求。
 */
public class ContextLimitException extends RuntimeException {

    public ContextLimitException(String message) {
        super(message);
    }
}
