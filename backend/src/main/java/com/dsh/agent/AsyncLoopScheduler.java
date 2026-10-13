package com.dsh.agent;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 单线程后台执行根 Run（默认）：submit 仅承诺输入/起始事件已提交（202）。
 * 恢复器、取消竞争与多 Run 并发属于后续阶段。
 */
@Component
@ConditionalOnProperty(prefix = "dsh.loop", name = "synchronous", havingValue = "false",
        matchIfMissing = true)
public class AsyncLoopScheduler implements LoopScheduler {

    private final AgentLoop agentLoop;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "dsh-agent-loop");
        thread.setDaemon(true);
        return thread;
    });

    public AsyncLoopScheduler(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    @Override
    public void scheduleStart(String runId) {
        executor.execute(() -> agentLoop.startRootRun(runId));
    }

    @Override
    public void scheduleResume(String runId) {
        executor.execute(() -> agentLoop.resumeRootRun(runId));
    }

    @Override
    public void scheduleResumeFromApproval(String turnId, String runId) {
        Runnable resume = () -> executor.execute(() -> agentLoop.resumeFromApproval(turnId, runId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    resume.run();
                }
            });
        } else {
            resume.run();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
