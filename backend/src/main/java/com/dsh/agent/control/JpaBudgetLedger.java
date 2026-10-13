package com.dsh.agent.control;

import com.dsh.storage.RunRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * RunRepository 条件 UPDATE 上的薄封装：原子预约不透支。
 * REQUIRES_NEW：可从 LoopSteps 事务内或子运行独立事务调用。
 */
@Component
public class JpaBudgetLedger implements BudgetLedger {

    private final RunRepository runRepository;

    public JpaBudgetLedger(RunRepository runRepository) {
        this.runRepository = runRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserveModelRequests(String runId, int n) {
        return runRepository.reserveModelRequests(runId, n, Instant.now()) > 0;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserveModelCall(String runId, int tokens) {
        if (tokens <= 0) throw new IllegalArgumentException("预约 Token 必须为正数");
        return runRepository.reserveModelCall(runId, tokens, Instant.now()) > 0;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserveToolCalls(String runId, int n) {
        return runRepository.reserveToolCalls(runId, n, Instant.now()) > 0;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseReservedTokens(String runId) {
        runRepository.releaseReservedTokens(runId, Instant.now());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean reserveChildBudget(String parentRunId, int modelRequests, int toolCalls, int tokens) {
        return reserveChildBudget(parentRunId, modelRequests, toolCalls, tokens, 0, 0, 0);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean reserveChildBudget(String parentRunId, int modelRequests, int toolCalls, int tokens,
                                      int reserveModelRequests, int reserveToolCalls, int reserveTokens) {
        // DSH-010：必须加入父 Loop 事务。父事务此前已 flush 过 Run 行（持有写锁），
        // 若用 REQUIRES_NEW 会在同一行上自锁超时；原子条件 UPDATE 仍在同事务立即执行。
        // MA-04：WHERE 含主任务保留额，失败不抬升任何计数器（单条 UPDATE 原子）。
        return runRepository.reserveChildBudgetWithReserve(parentRunId, modelRequests, toolCalls,
                tokens, reserveModelRequests, reserveToolCalls, reserveTokens,
                Instant.now()) > 0;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void settleChildBudget(String parentRunId, int reservedModelRequests, int usedModelRequests,
                                  int reservedToolCalls, int usedToolCalls,
                                  int reservedTokens, int usedTokens) {
        runRepository.settleChildBudget(parentRunId, reservedModelRequests, usedModelRequests,
                reservedToolCalls, usedToolCalls, reservedTokens, usedTokens, Instant.now());
    }
}
