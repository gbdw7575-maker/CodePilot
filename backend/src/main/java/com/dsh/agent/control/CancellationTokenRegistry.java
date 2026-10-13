package com.dsh.agent.control;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 取消令牌注册表：Run 启动时注册，取消请求时置位，终态后摘除。
 * 同一 runId 的令牌在 Loop 执行线程与取消请求线程间提供可见性。
 */
@Component
public class CancellationTokenRegistry {

    private final ConcurrentHashMap<String, CancellationToken> tokens = new ConcurrentHashMap<>();

    public CancellationToken register(String runId) {
        return tokens.computeIfAbsent(runId, k -> new CancellationToken());
    }

    /**
     * DSH-010：注册子 Run 令牌并绑定父令牌：父 Run 取消时，子 Run 在下一个安全点立即观察到。
     * 父令牌尚未注册（例如直接由服务层发起）时仅注册子令牌。
     */
    public CancellationToken registerLinked(String parentRunId, String childRunId) {
        CancellationToken child = register(childRunId);
        CancellationToken parentToken = tokens.get(parentRunId);
        if (parentToken != null) {
            child.linkParent(parentToken);
        }
        return child;
    }

    public CancellationToken get(String runId) {
        return tokens.get(runId);
    }

    /** 标记取消；无活动令牌时返回 false（Loop 未在内存执行）。 */
    public boolean requestCancel(String runId) {
        CancellationToken token = tokens.get(runId);
        if (token == null) {
            return false;
        }
        token.cancel();
        return true;
    }

    public void release(String runId) {
        tokens.remove(runId);
    }
}
