package com.dsh.orchestration;

import com.dsh.agent.model.FakeModelGateway;
import com.dsh.application.ModelProfileService;
import com.dsh.common.IdGen;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-16 有界并行子 Agent 集成（真实子 Loop + 真实工作区复制/三方汇合）：
 * 独立任务并行执行并 N+1 方汇合；同文件不同内容冲突整批驳回；
 * 同文件同内容不冲突；超扇出/预算不足整体拒绝（不部分启动）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "dsh.orchestration.enabled=true",
        "dsh.policy.require-for-side-effects=NONE",
        "dsh.orchestration.max-fanout=2"
})
class FanoutDsh016Test {

    @Autowired
    private FanoutService fanoutService;
    @Autowired
    private WorkspaceManager workspaceManager;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ModelProfileRepository profileRepository;
    @Autowired
    private ThreadRepository threadRepository;
    @Autowired
    private TurnRepository turnRepository;
    @Autowired
    private RunRepository runRepository;

    @TempDir
    Path projectRoot;

    private Chain buildChain(int maxMr, int maxTc, int maxTok) throws Exception {
        Instant now = Instant.now();
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("README.md"), "基线内容\n");

        String projectId = "proj-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "fanout集成", projectRoot.toString(),
                true, now, now));

        String profileId = "prof-" + UUID.randomUUID();
        ModelProfileEntity profile = new ModelProfileEntity(profileId, "fake-write",
                FakeModelGateway.PROVIDER, "write", "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now);
        profileRepository.save(profile);

        String threadId = IdGen.next("thr_parent_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "父会话", profileId, now, now));

        String runId = IdGen.next("run_parent_");
        String turnId = IdGen.next("turn_parent_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));

        RunEntity run = new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), ModelProfileService.configDigest(profile),
                maxMr, maxTc, maxTok, now.plusSeconds(600), now, now);
        runRepository.save(run);

        // 真实父工作区（子工作区的共同种子快照）
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        WorkspaceLease lease = workspaceManager.prepare(runId, project);
        run.setWorkspaceRef(lease.workspaceId());
        runRepository.save(run);
        return new Chain(projectId, runId, lease.dir());
    }

    @Test
    void independentTasksRunInParallelAndMergeToParent() throws Exception {
        Chain c = buildChain(24, 80, 200_000);

        FanoutService.FanoutReport report = fanoutService.fanout(c.runId(), List.of(
                "请执行：fanout-file module-a/A.txt :: alpha-content",
                "请执行：fanout-file module-b/B.txt :: beta-content"));

        assertEquals("COMPLETED", report.state(), report.reason());
        assertEquals(2, report.taskCount());
        assertEquals(2, report.parallelism());
        assertEquals(2, report.children().size());
        assertEquals("APPLIED", report.merge().status());
        assertEquals(2, report.merge().writersMerged());
        assertTrue(report.merge().appliedPaths().contains("module-a/A.txt"));
        assertTrue(report.merge().appliedPaths().contains("module-b/B.txt"));

        // 汇合进父工作区
        assertTrue(Files.exists(c.parentDir().resolve("module-a/A.txt")));
        assertTrue(Files.exists(c.parentDir().resolve("module-b/B.txt")));
        assertEquals("alpha-content\n",
                Files.readString(c.parentDir().resolve("module-a/A.txt")));

        // 两个子 Run 各自独立且完成
        for (var child : report.children()) {
            assertEquals("COMPLETED", child.state());
            assertTrue(child.runId().startsWith("sub_run_"));
            assertTrue(child.executionThread().startsWith("fanout-worker-"));
            assertTrue(child.durationMs() >= 0);
        }

        // 并行墙钟证据：墙钟不超过子任务串行耗时合计（独立线程执行）
        assertTrue(report.wallClockMs() <= report.totals().sumDurationMs() + 500,
                "墙钟 " + report.wallClockMs() + "ms 应不高于串行合计 "
                        + report.totals().sumDurationMs() + "ms（含调度宽限）");
    }

    @Test
    void conflictingChangesRejectWholeBatchWithOwnerInfo() throws Exception {
        Chain c = buildChain(24, 80, 200_000);

        FanoutService.FanoutReport report = fanoutService.fanout(c.runId(), List.of(
                "请执行：fanout-file shared.txt :: version-from-worker-1",
                "请执行：fanout-file shared.txt :: version-from-worker-2"));

        assertEquals("CONFLICTS", report.state());
        assertEquals("CONFLICTS", report.merge().status());
        assertEquals(1, report.merge().conflicts().size());
        var conflict = report.merge().conflicts().get(0);
        assertEquals("shared.txt", conflict.path());
        assertEquals(2, conflict.writerRunIds().size(), "冲突应归属到两个子 Run");
        assertTrue(conflict.changes().stream().allMatch(ch -> ch.endsWith(":ADDED")));

        // 整批不应用：父工作区不得出现任一方产物
        assertFalse(Files.exists(c.parentDir().resolve("shared.txt")));
        assertEquals(0, report.merge().appliedPaths().size());
    }

    @Test
    void sameFileSameContentFromTwoWritersIsNotConflict() throws Exception {
        Chain c = buildChain(24, 80, 200_000);

        FanoutService.FanoutReport report = fanoutService.fanout(c.runId(), List.of(
                "请执行：fanout-file twin.txt :: identical-body",
                "请执行：fanout-file twin.txt :: identical-body"));

        assertEquals("COMPLETED", report.state(), report.reason());
        assertEquals("APPLIED", report.merge().status());
        assertTrue(Files.exists(c.parentDir().resolve("twin.txt")));
        assertEquals("identical-body\n", Files.readString(c.parentDir().resolve("twin.txt")));
    }

    @Test
    void fanoutOverLimitIsRejectedWithoutStartingAnyChild() throws Exception {
        Chain c = buildChain(24, 80, 200_000);
        long childrenBefore = countChildren(c.runId());

        FanoutService.FanoutReport report = fanoutService.fanout(c.runId(), List.of(
                "fanout-file x1.txt :: 1", "fanout-file x2.txt :: 2", "fanout-file x3.txt :: 3"));

        assertEquals("FAILED", report.state());
        assertEquals("FANOUT_LIMIT", report.reason().split(":")[0]);
        assertEquals(childrenBefore, countChildren(c.runId()), "超限不得启动任何子 Run");
    }

    @Test
    void insufficientBudgetRejectsAllBeforeDispatch() throws Exception {
        // 父只剩 1 次模型请求额度，2 个任务各需至少 1 → 整体拒绝
        Chain c = buildChain(1, 80, 200_000);
        long childrenBefore = countChildren(c.runId());

        FanoutService.FanoutReport report = fanoutService.fanout(c.runId(), List.of(
                "fanout-file y1.txt :: 1", "fanout-file y2.txt :: 2"));

        assertEquals("FAILED", report.state());
        assertEquals("FANOUT_BUDGET_LIMIT", report.reason().split(":")[0]);
        assertEquals(childrenBefore, countChildren(c.runId()), "预算不足不得部分启动");
    }

    @Test
    void emptyTasksRejectedBounded() throws Exception {
        Chain c = buildChain(24, 80, 200_000);
        FanoutService.FanoutReport report = fanoutService.fanout(c.runId(), List.of(" ", ""));
        assertEquals("FAILED", report.state());
        assertEquals("INVALID_ARGUMENT", report.reason().split(":")[0]);
    }

    private long countChildren(String parentRunId) {
        return runRepository.findAll().stream()
                .filter(r -> parentRunId.equals(r.getParentRunId()))
                .count();
    }

    private record Chain(String projectId, String runId, Path parentDir) {
    }
}
