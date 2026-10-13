package com.dsh.api;

import com.dsh.api.dto.RunRole;
import com.dsh.application.ArtifactStore;
import com.dsh.application.DeletionService;
import com.dsh.application.event.EventMeta;
import com.dsh.application.event.EventStore;
import com.dsh.api.dto.event.EventType;
import com.dsh.common.IdGen;
import com.dsh.storage.ArtifactRepository;
import com.dsh.storage.EventCursorRepository;
import com.dsh.storage.EventRepository;
import com.dsh.storage.MessageRepository;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话/项目删除验收：
 * 级联清除 run/turn/message/event/cursor/artifact 等全部数据并回收工作区副本磁盘；
 * 存在活动 Run 时拒绝删除。
 */
class DeletionIntegrationTest extends ApiTestBase {

    @Autowired
    private DeletionService deletionService;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ThreadRepository threadRepository;
    @Autowired
    private TurnRepository turnRepository;
    @Autowired
    private RunRepository runRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private EventCursorRepository eventCursorRepository;
    @Autowired
    private ArtifactRepository artifactRepository;
    @Autowired
    private ArtifactStore artifactStore;
    @Autowired
    private EventStore eventStore;
    @Autowired
    private WorkspaceManager workspaceManager;

    @Test
    void deleteThreadCascadesAllDataAndWorkspaceCopy() throws Exception {
        String projectId = createProject("del-thread-" + UUID.randomUUID(), createSourceDir());
        trustProject(projectId, 1);
        String profileId = createProfile("del-profile-" + UUID.randomUUID());
        String threadId = createThread(projectId, profileId);
        ThreadEntity thread = threadRepository.findById(threadId).orElseThrow();

        Instant now = Instant.now();
        String runId = IdGen.next("run_");
        String turnId = IdGen.next("turn_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "删除测试", "COMPLETED",
                "PASSED", null, now, now));

        // 真实工作区副本 + 产物 + 事件（含游标）
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        WorkspaceLease lease = workspaceManager.prepare(runId, project);
        RunEntity run = new RunEntity(runId, turnId, null, RunRole.MAIN.name(), "COMPLETED",
                "PASSED", 1, lease.workspaceId(), "config_test",
                24, 80, 200_000, now.plusSeconds(1200), now, now);
        runRepository.save(run);

        Files.writeString(lease.dir().resolve("note.txt"), "delete me");
        artifactStore.save(runId, projectId, "evidence.txt", "text/plain", "x".getBytes(), true);
        eventStore.initCursor(threadId);
        EventMeta meta = new EventMeta(projectId, threadId, turnId, runId, null, null, null);
        eventStore.append(meta, EventType.RUN_COMPLETED,
                new com.dsh.api.dto.event.EventPayloads.RunCancelled("COMPLETED", "OK", 1));

        deletionService.deleteThread(projectId, threadId);

        assertTrue(runRepository.findById(runId).isEmpty(), "Run 必须删除");
        assertTrue(turnRepository.findById(turnId).isEmpty(), "Turn 必须删除");
        assertTrue(threadRepository.findById(threadId).isEmpty(), "会话必须删除");
        assertTrue(artifactRepository.findAll().stream().noneMatch(a -> runId.equals(a.getRunId())),
                "产物必须级联删除");
        assertEquals(0, messageRepository.listByThread(threadId).size(), "消息必须删除");
        assertEquals(0, eventRepository.findRange(threadId, 0, Long.MAX_VALUE,
                org.springframework.data.domain.Pageable.ofSize(1000)).size(), "事件必须删除");
        assertFalse(Files.exists(lease.dir()), "工作区副本目录必须回收");
        assertFalse(Files.exists(lease.metaDir()), "工作区元数据目录必须回收");
    }

    @Test
    void deleteProjectRemovesAllThreads() throws Exception {
        String projectId = createProject("del-project-" + UUID.randomUUID(), createSourceDir());
        trustProject(projectId, 1);
        String profileId = createProfile("del-profile2-" + UUID.randomUUID());
        String t1 = createThread(projectId, profileId);
        String t2 = createThread(projectId, profileId);

        deletionService.deleteProject(projectId);

        assertTrue(projectRepository.findById(projectId).isEmpty());
        assertTrue(threadRepository.findById(t1).isEmpty());
        assertTrue(threadRepository.findById(t2).isEmpty());
    }

    @Test
    void rejectsDeleteWhenRunActive() throws Exception {
        String projectId = createProject("del-active-" + UUID.randomUUID(), createSourceDir());
        trustProject(projectId, 1);
        String profileId = createProfile("del-profile3-" + UUID.randomUUID());
        String threadId = createThread(projectId, profileId);

        Instant now = Instant.now();
        String runId = IdGen.next("run_");
        String turnId = IdGen.next("turn_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "活动测试", "RUNNING",
                "PENDING", "run_1", now, now));
        runRepository.save(new RunEntity(runId, turnId, null, RunRole.MAIN.name(), "WAITING_APPROVAL",
                "PENDING", 1, "ws-active-" + UUID.randomUUID(), "config_test",
                24, 80, 200_000, now.plusSeconds(1200), now, now));

        ApiException ex = assertThrows(ApiException.class,
                () -> deletionService.deleteThread(projectId, threadId));
        assertEquals(com.dsh.api.dto.ErrorCode.INVALID_STATE, ex.code());
        assertTrue(threadRepository.findById(threadId).isPresent(), "拒绝删除时数据必须保留");
    }

    private String createSourceDir() throws Exception {
        Path dir = Path.of("target").toAbsolutePath()
                .resolve("test-delete-" + UUID.randomUUID());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("README.md"), "source");
        return dir.toString().replace('\\', '/');
    }
}
