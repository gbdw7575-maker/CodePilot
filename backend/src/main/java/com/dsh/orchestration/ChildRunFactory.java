package com.dsh.orchestration;

import com.dsh.agent.LoopSteps;
import com.dsh.application.ModelProfileService;
import com.dsh.common.IdGen;
import com.dsh.storage.MessageEntity;
import com.dsh.storage.MessageRepository;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 子 Run 实体工厂（DSH-010）：在独立短事务内创建子 thread/turn/run 与首条 user 消息，
 * 使随后 AgentLoop.begin()（REQUIRES_NEW）立即可见；子上下文与父会话消息天然隔离。
 */
@Component
public class ChildRunFactory {

    private final ThreadRepository threadRepository;
    private final TurnRepository turnRepository;
    private final RunRepository runRepository;
    private final MessageRepository messageRepository;

    public ChildRunFactory(ThreadRepository threadRepository, TurnRepository turnRepository,
                           RunRepository runRepository, MessageRepository messageRepository) {
        this.threadRepository = threadRepository;
        this.turnRepository = turnRepository;
        this.runRepository = runRepository;
        this.messageRepository = messageRepository;
    }

    /** 创建子 Run 全链路实体（childRunId 由调用方预生成，以便先准备种子工作区）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String create(String childRunId, RunEntity parent, ThreadEntity parentThread,
                         ModelProfileEntity profile,
                         String role, String firstUserMessage, String workspaceRef,
                         int maxModelRequests, int maxToolCalls, int maxTokens,
                         Instant deadline) {
        Instant now = Instant.now();
        String childThreadId = IdGen.next("sub_thr_");
        String childTurnId = IdGen.next("sub_turn_");

        ThreadEntity childThread = new ThreadEntity(childThreadId, parentThread.getProjectId(),
                "子Agent/" + role, parentThread.getModelProfileId(), now, now);
        threadRepository.save(childThread);

        TurnEntity childTurn = new TurnEntity(childTurnId, childThreadId, childRunId,
                firstUserMessage, LoopSteps.ST_QUEUED, "PENDING", childThreadId, now, now);
        turnRepository.save(childTurn);

        RunEntity child = new RunEntity(childRunId, childTurnId, parent.getId(), role,
                LoopSteps.ST_QUEUED, "PENDING", 1, workspaceRef,
                ModelProfileService.configDigest(profile),
                maxModelRequests, maxToolCalls, maxTokens, deadline, now, now);
        child.setTraceId(parent.getTraceId());
        // Root defect acceptance belongs to the integrated result, not independent worker completion.
        if (parent.getVerificationRequirement() != null)
            child.registerVerificationRequirement(com.dsh.application.RunVerificationRequirement.general());
        runRepository.save(child);

        MessageEntity message = new MessageEntity(IdGen.next("msg_"), childThreadId, childTurnId,
                "user", firstUserMessage, 1L, now);
        messageRepository.save(message);
        return childRunId;
    }

    /** 读取子 Run 终态视图（独立事务，供子 Loop 结束后结算）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RunEntity load(String runId) {
        return runRepository.findById(runId).orElseThrow();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ThreadEntity threadOf(String runId) {
        RunEntity run = runRepository.findById(runId).orElseThrow();
        TurnEntity turn = turnRepository.findById(run.getTurnId()).orElseThrow();
        return threadRepository.findById(turn.getThreadId()).orElseThrow();
    }
}
