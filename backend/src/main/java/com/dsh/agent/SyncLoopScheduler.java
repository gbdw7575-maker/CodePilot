package com.dsh.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 提交后在同一处理链路内跑完 Loop：submit 202 返回时终态与事件已落定（测试/离线）。 */
@Component
@ConditionalOnProperty(prefix = "dsh.loop", name = "synchronous", havingValue = "true")
public class SyncLoopScheduler implements LoopScheduler {

    private final AgentLoop agentLoop;

    public SyncLoopScheduler(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    @Override
    public void scheduleStart(String runId) {
        agentLoop.startRootRun(runId);
    }

    @Override
    public void scheduleResume(String runId) {
        agentLoop.resumeRootRun(runId);
    }

    @Override
    public void scheduleResumeFromApproval(String turnId, String runId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    agentLoop.resumeFromApproval(turnId, runId);
                }
            });
        } else {
            agentLoop.resumeFromApproval(turnId, runId);
        }
    }
}
