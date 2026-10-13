package com.dsh.orchestration;

import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-010：文件级三方汇合。基线=写者派生时父工作区快照；
 * 无冲突整批应用（新增/修改/删除）；双方都改同一路径→CONFLICTS 且整批不动。
 */
@SpringBootTest
class WorkspaceMergeTest {

    @Autowired
    private WorkspaceManager workspaceManager;
    @Autowired
    private WorkspaceMerge workspaceMerge;
    @Autowired
    private ProjectRepository projectRepository;

    @TempDir
    Path projectRoot;

    private String persistProject() {
        String id = "proj-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(id, "汇合测试", projectRoot.toString(),
                true, Instant.now(), Instant.now()));
        return id;
    }

    /** 准备：项目种子 a.txt；父 Run 副本（写者基线来源）；写者从父副本派生。 */
    private Setup prepare(String suffix) throws Exception {
        Files.writeString(projectRoot.resolve("a.txt"), "base-a\n");
        String projectId = persistProject();
        String parentRun = "merge-parent-" + suffix;
        String writerRun = "merge-writer-" + suffix;

        WorkspaceLease parent = workspaceManager.prepare(parentRun,
                projectRepository.findById(projectId).orElseThrow());
        WorkspaceLease writer = workspaceManager.prepareFromWorkspace(writerRun,
                projectRepository.findById(projectId).orElseThrow(), parent.workspaceId());
        return new Setup(parent, writer);
    }

    private static void write(Path dir, String rel, String content) throws Exception {
        Path target = dir.resolve(rel);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    @Test
    @Transactional
    void nonConflictingChangesApplyWholeBatchIncludingDelete() throws Exception {
        Setup s = prepare("ok");
        // 写者：新增 b.txt、修改 a.txt、暂不删除（删除用另一场景）
        write(s.writer.dir(), "b.txt", "new-b\n");
        write(s.writer.dir(), "a.txt", "writer-a-v2\n");

        WorkspaceMerge.Result result =
                workspaceMerge.merge(s.writer.workspaceId(), s.parent.runId(), workspaceManager);

        assertTrue(result.applied());
        assertEquals(2, result.appliedPaths().size());
        assertTrue(result.conflicts().isEmpty());
        assertEquals("new-b\n", Files.readString(s.parent.dir().resolve("b.txt")));
        assertEquals("writer-a-v2\n", Files.readString(s.parent.dir().resolve("a.txt")));
    }

    @Test
    @Transactional
    void writerDeletionPropagatesAndUntouchedFileStays() throws Exception {
        Setup s = prepare("del");
        Files.delete(s.writer.dir().resolve("a.txt"));

        WorkspaceMerge.Result result =
                workspaceMerge.merge(s.writer.workspaceId(), s.parent.runId(), workspaceManager);

        assertTrue(result.applied());
        assertEquals(1, result.appliedPaths().size());
        assertFalse(Files.exists(s.parent.dir().resolve("a.txt")), "删除必须应用到父副本");
    }

    @Test
    @Transactional
    void bothSidesEditingSameFileIsConflictAndNothingApplies() throws Exception {
        Setup s = prepare("conflict");
        // 写者改 a.txt；父（汇合目标）也独立改了 a.txt → 冲突
        write(s.writer.dir(), "a.txt", "writer-side\n");
        write(s.writer.dir(), "c.txt", "writer-only-c\n");
        write(s.parent.dir(), "a.txt", "parent-side\n");

        WorkspaceMerge.Result result =
                workspaceMerge.merge(s.writer.workspaceId(), s.parent.runId(), workspaceManager);

        assertFalse(result.applied());
        assertEquals(1, result.conflicts().size());
        assertEquals("a.txt", result.conflicts().get(0).path());
        // 整批语义：写者独有的 c.txt 也不得应用
        assertFalse(Files.exists(s.parent.dir().resolve("c.txt")));
        assertEquals("parent-side\n", Files.readString(s.parent.dir().resolve("a.txt")),
                "冲突时父副本保持原样");
    }

    @Test
    @Transactional
    void sameContentOnBothSidesIsNotConflict() throws Exception {
        Setup s = prepare("same");
        // 双方都新增同名同内容文件（基线无）→ 不冲突
        write(s.writer.dir(), "d.txt", "identical\n");
        write(s.parent.dir(), "d.txt", "identical\n");

        WorkspaceMerge.Result result =
                workspaceMerge.merge(s.writer.workspaceId(), s.parent.runId(), workspaceManager);

        assertTrue(result.applied());
        assertTrue(result.conflicts().isEmpty());
    }

    private record Setup(WorkspaceLease parent, WorkspaceLease writer) {
    }
}
