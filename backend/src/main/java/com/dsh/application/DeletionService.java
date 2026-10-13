package com.dsh.application;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.storage.ApprovalRepository;
import com.dsh.storage.ArtifactRepository;
import com.dsh.storage.CheckpointRepository;
import com.dsh.storage.EventCursorRepository;
import com.dsh.storage.EventRepository;
import com.dsh.storage.MessageRepository;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.dsh.storage.VerificationRepository;
import com.dsh.workspace.WorkspaceManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 会话/项目删除：级联清除对话全部数据并回收工作区副本磁盘。
 * 存在活动（非终态）Run 时拒绝删除，要求用户先取消或等待完成。
 */
@Service
public class DeletionService {

    /** 非终态/未安定状态：队列中、运行中、暂停中、取消中、阻塞待恢复、验证中。 */
    private static final Set<String> ACTIVE_STATES = Set.of(
            "QUEUED", "PREPARING", "RUNNING", "WAITING_INPUT", "WAITING_APPROVAL",
            "CANCELLING", "BLOCKED", "RECOVERING", "VERIFYING");

    private final ProjectRepository projectRepository;
    private final ThreadRepository threadRepository;
    private final TurnRepository turnRepository;
    private final RunRepository runRepository;
    private final MessageRepository messageRepository;
    private final EventRepository eventRepository;
    private final EventCursorRepository eventCursorRepository;
    private final ApprovalRepository approvalRepository;
    private final ArtifactRepository artifactRepository;
    private final CheckpointRepository checkpointRepository;
    private final VerificationRepository verificationRepository;
    private final WorkspaceManager workspaceManager;

    public DeletionService(ProjectRepository projectRepository, ThreadRepository threadRepository,
                           TurnRepository turnRepository, RunRepository runRepository,
                           MessageRepository messageRepository, EventRepository eventRepository,
                           EventCursorRepository eventCursorRepository,
                           ApprovalRepository approvalRepository, ArtifactRepository artifactRepository,
                           CheckpointRepository checkpointRepository,
                           VerificationRepository verificationRepository,
                           WorkspaceManager workspaceManager) {
        this.projectRepository = projectRepository;
        this.threadRepository = threadRepository;
        this.turnRepository = turnRepository;
        this.runRepository = runRepository;
        this.messageRepository = messageRepository;
        this.eventRepository = eventRepository;
        this.eventCursorRepository = eventCursorRepository;
        this.approvalRepository = approvalRepository;
        this.artifactRepository = artifactRepository;
        this.checkpointRepository = checkpointRepository;
        this.verificationRepository = verificationRepository;
        this.workspaceManager = workspaceManager;
    }

    /** 删除单个会话及其全部轮次/运行/事件/产物/工作区副本。 */
    @Transactional
    public void deleteThread(String projectId, String threadId) {
        ThreadEntity thread = threadRepository.findById(threadId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在"));
        if (!thread.getProjectId().equals(projectId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "会话不属于该项目");
        }
        purgeThread(thread);
    }

    /** 删除项目及其全部会话。 */
    @Transactional
    public void deleteProject(String projectId) {
        if (projectRepository.findById(projectId).isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在");
        }
        for (ThreadEntity thread : threadRepository.listByProject(projectId)) {
            purgeThread(thread);
        }
        threadRepository.deleteByProjectId(projectId);
        projectRepository.deleteById(projectId);
    }

    private void purgeThread(ThreadEntity thread) {
        List<TurnEntity> turns = turnRepository.findByThreadId(thread.getId());
        List<String> turnIds = turns.stream().map(TurnEntity::getId).toList();
        List<RunEntity> runs = turnIds.isEmpty() ? List.of() : runRepository.findByTurnIdIn(turnIds);

        for (RunEntity run : runs) {
            if (ACTIVE_STATES.contains(run.getState())) {
                throw new ApiException(ErrorCode.INVALID_STATE,
                        "会话存在未结束的运行（" + run.getState() + "），请先取消后再删除");
            }
        }
        // 先回收磁盘副本（目录操作失败不应残留租约/阻断数据清理），再级联删除库内数据
        for (RunEntity run : runs) {
            workspaceManager.discardRun(run.getId(), run.getWorkspaceRef());
            approvalRepository.deleteByRunId(run.getId());
            artifactRepository.deleteByRunId(run.getId());
            checkpointRepository.deleteByRunId(run.getId());
            verificationRepository.deleteByRunId(run.getId());
        }
        for (RunEntity run : runs) {
            runRepository.delete(run);
        }
        for (TurnEntity turn : turns) {
            turnRepository.delete(turn);
        }
        messageRepository.deleteByThreadId(thread.getId());
        eventRepository.deleteByThreadId(thread.getId());
        eventCursorRepository.deleteByThreadId(thread.getId());
        threadRepository.delete(thread);
    }
}
