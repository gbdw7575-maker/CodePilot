package com.dsh.application;

import com.dsh.api.ApiException;
import com.dsh.api.dto.CreateThread;
import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.Message;
import com.dsh.api.dto.Thread;
import com.dsh.api.dto.ThreadPatch;
import com.dsh.application.event.EventStore;
import com.dsh.common.IdGen;
import com.dsh.storage.MessageEntity;
import com.dsh.storage.MessageRepository;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 会话：标题/模型变更；模型变更仅允许在无活动轮次时进行。 */
@Service
public class ThreadService {

    private final ThreadRepository threadRepository;
    private final ProjectRepository projectRepository;
    private final ModelProfileRepository profileRepository;
    private final MessageRepository messageRepository;
    private final EventStore eventStore;

    public ThreadService(ThreadRepository threadRepository, ProjectRepository projectRepository,
                         ModelProfileRepository profileRepository, MessageRepository messageRepository,
                         EventStore eventStore) {
        this.threadRepository = threadRepository;
        this.projectRepository = projectRepository;
        this.profileRepository = profileRepository;
        this.messageRepository = messageRepository;
        this.eventStore = eventStore;
    }

    @Transactional
    public Thread create(String projectId, CreateThread request) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在"));
        if (request == null || request.modelProfileId() == null || request.modelProfileId().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "modelProfileId 必填");
        }
        profileRepository.findById(request.modelProfileId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "模型配置不存在"));
        String title = "新会话";
        if (request.title() != null) {
            if (request.title().isBlank() || request.title().length() > 200) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "title 长度必须在 1~200 之间");
            }
            title = request.title();
        }
        Instant now = Instant.now();
        ThreadEntity saved = threadRepository.save(
                new ThreadEntity(IdGen.next("thr_"), projectId, title, request.modelProfileId(), now, now));
        // 事件高水位从 0 开始，与会话同事务创建
        eventStore.initCursor(saved.getId());
        return toDto(saved);
    }

    public Thread get(String threadId) {
        return toDto(load(threadId));
    }

    public List<ThreadEntity> listByProject(String projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在"));
        return threadRepository.listByProject(projectId);
    }

    @Transactional
    public Thread patch(String threadId, ThreadPatch patch) {
        ThreadEntity thread = threadRepository.findByIdForUpdate(threadId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在"));
        if (patch == null || (patch.title() == null && patch.modelProfileId() == null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "至少提供一个待更新字段");
        }
        if (patch.title() != null) {
            if (patch.title().isBlank() || patch.title().length() > 200) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "title 长度必须在 1~200 之间");
            }
            thread.setTitle(patch.title());
        }
        if (patch.modelProfileId() != null && !patch.modelProfileId().equals(thread.getModelProfileId())) {
            if (thread.getActiveTurnId() != null) {
                throw new ApiException(ErrorCode.INVALID_STATE, "存在活动轮次时不能更换模型");
            }
            profileRepository.findById(patch.modelProfileId())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "模型配置不存在"));
            thread.setModelProfileId(patch.modelProfileId());
        }
        thread.setUpdatedAt(Instant.now());
        // 事务内先 flush 使 @Version 递增，保证返回的 ETag 是提交后的版本
        return toDto(threadRepository.saveAndFlush(thread));
    }

    public List<MessageEntity> listMessages(String threadId) {
        load(threadId);
        return messageRepository.listPublicByThread(threadId);
    }

    public ThreadEntity load(String threadId) {
        return threadRepository.findById(threadId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在"));
    }

    public static Thread toDto(ThreadEntity entity) {
        return new Thread(entity.getId(), entity.getProjectId(), entity.getTitle(), entity.getModelProfileId(),
                entity.getActiveTurnId(), entity.getVersion() + 1, entity.getCreatedAt(), entity.getUpdatedAt());
    }

    public static Message toDto(MessageEntity entity) {
        return new Message(entity.getId(), entity.getThreadId(), entity.getTurnId(), entity.getRole(),
                entity.getContent(), Long.toString(entity.getSequence()), entity.getCreatedAt());
    }
}
