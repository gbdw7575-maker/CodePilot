package com.dsh.orchestration;

import com.dsh.agent.model.FakeModelGateway;
import com.dsh.application.ArtifactStore;
import com.dsh.application.ModelProfileService;
import com.dsh.application.WorkspaceDiffService;
import com.dsh.common.IdGen;
import com.dsh.api.dto.Diff;
import com.dsh.storage.ArtifactEntity;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-010 顺序编排集成（coding-review v1）：
 * 四角色流水线 + 种子工作区 + 三方汇合 APPLIED；角色失败短路且不汇合；
 * 报告 JSON 落父 Run 产物；总预算不突破父上限。
 */
@SpringBootTest
// coding-review 流水线非交互：模拟已预授权 WORKSPACE_WRITE 的部署（默认策略下 CODER 写操作
// 会停在 WAITING_APPROVAL，编排按决策 7 视为角色失败，该路径由 SubagentIntegrationTest 覆盖）
@TestPropertySource(properties = "dsh.policy.require-for-side-effects=NONE")
class OrchestrationIntegrationTest {

    @Autowired
    private OrchestrationService orchestrationService;
    @Autowired
    private WorkspaceManager workspaceManager;
    @Autowired
    private WorkspaceDiffService workspaceDiffService;
    @Autowired
    private ArtifactStore artifactStore;
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

    private Chain setup(String model) throws Exception {
        Instant now = Instant.now();
        Files.writeString(projectRoot.resolve("readme.txt"), "seed\n");
        String projectId = "proj-" + UUID.randomUUID();
        ProjectEntity project = new ProjectEntity(projectId, "编排集成", projectRoot.toString(),
                true, now, now);
        projectRepository.save(project);

        String profileId = "prof-" + UUID.randomUUID();
        ModelProfileEntity profile = new ModelProfileEntity(profileId, "fake-" + model,
                FakeModelGateway.PROVIDER, model, "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now);
        profileRepository.save(profile);

        String threadId = IdGen.next("thr_orc_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "编排父会话",
                profileId, now, now));

        String runId = IdGen.next("run_orc_");
        String turnId = IdGen.next("turn_orc_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "编排任务",
                "RUNNING", "PENDING", threadId, now, now));

        RunEntity run = new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), ModelProfileService.configDigest(profile),
                40, 40, 400_000, now.plusSeconds(600), now, now);
        runRepository.save(run);

        // CODER 以父工作区冻结副本为种子：先准备父工作区。
        // 与生产路径一致：Dispatcher 对 requiresWorkspace 工具 prepare 后会把 workspaceRef 回绑 Run。
        WorkspaceLease parentLease = workspaceManager.prepare(runId, project);
        run.setWorkspaceRef(parentLease.workspaceId());
        runRepository.save(run);
        return new Chain(project, runId, threadId, turnId, parentLease);
    }

    @Test
    void codingReviewPipelineCompletesAndMergesWriterOutput() throws Exception {
        Chain c = setup("fake-orchestration");

        OrchestrationReport report = orchestrationService.start(
                c.runId(), "coding-review", "完成 orc 修复任务");

        assertEquals("COMPLETED", report.overallStatus(), report.failureReason());
        assertEquals(List.of("ARCHITECT", "CODER", "TESTER", "REVIEWER"),
                report.stages().stream().map(OrchestrationReport.Stage::role).toList());
        for (OrchestrationReport.Stage stage : report.stages()) {
            assertEquals("COMPLETED", stage.state(), stage.role() + " 应完成");
            assertTrue(stage.runId().startsWith("sub_run_"), stage.role() + " 应有子 runId");
            assertTrue(stage.durationMs() >= 0);
        }

        OrchestrationReport.Stage coder = report.stages().get(1);
        assertNotNull(coder.patchArtifactId(), "CODER 改动应有 patch 产物");

        // 汇合：无冲突整批应用，orc.txt 进入父工作区
        assertTrue(report.merge().attempted());
        assertEquals("APPLIED", report.merge().status());
        assertTrue(report.merge().appliedPaths().contains("orc.txt"));
        assertTrue(Files.exists(c.parentLease().dir().resolve("orc.txt")),
                "汇合产物必须落到父工作区");
        assertEquals("orchestrated\n",
                Files.readString(c.parentLease().dir().resolve("orc.txt")));

        // 子工作区基于冻结基线，diff 也能看到改动
        Diff coderDiff = workspaceDiffService.compute(coder.runId());
        assertTrue(coderDiff.files().stream().anyMatch(f -> "orc.txt".equals(f.path())));

        // 总用量为四角色之和，成本口径可对照；父账三维未超上限
        assertTrue(report.totals().modelRequests() >= 4);
        assertTrue(report.totals().toolCalls() >= 1);
        assertTrue(report.totals().tokens() > 0);
        RunEntity parent = runRepository.findById(c.runId()).orElseThrow();
        assertEquals(0, parent.getBudgetReservedTokens(), "全部子切片实结后无悬挂预约");
        assertTrue(parent.getBudgetModelRequestsUsed() <= 40);
        assertTrue(parent.getBudgetToolCallsUsed() <= 40);

        // 报告落父 Run 产物（JSON，含每阶段 runId 与模板版本）
        assertNotNull(report.reportArtifactId());
        ArtifactEntity artifact = artifactStore.get(report.reportArtifactId());
        String body = new String(artifactStore.readContent(report.reportArtifactId()));
        assertEquals("application/json", artifact.getMediaType());
        assertTrue(body.contains("\"template\" : \"coding-review\""));
        assertTrue(body.contains(coder.runId()));
        assertTrue(body.contains("\"templateVersion\" : \"v1\""));
    }

    @Test
    void stageFailureShortCircuitsAndDoesNotMerge() throws Exception {
        Chain c = setup("fake-orchestration-fail");

        OrchestrationReport report = orchestrationService.start(
                c.runId(), "coding-review", "完成 orc 修复任务（含测试失败）");

        assertEquals("FAILED", report.overallStatus());
        assertNotNull(report.failureReason());
        assertTrue(report.failureReason().contains("TESTER"));

        OrchestrationReport.Stage tester = report.stages().get(2);
        assertEquals("TESTER", tester.role());
        assertEquals("FAILED", tester.state());
        assertEquals("PROVIDER_ERROR", tester.stopReason());

        OrchestrationReport.Stage reviewer = report.stages().get(3);
        assertEquals("SKIPPED", reviewer.state());
        assertFalse(reviewer.executed());

        // 未尝试汇合：CODER 产物不进父工作区
        assertFalse(report.merge().attempted());
        assertFalse(Files.exists(c.parentLease().dir().resolve("orc.txt")),
                "流水线失败时不得自动汇合");

        // 失败也产出报告供对照
        assertNotNull(report.reportArtifactId());
        ArtifactEntity artifact = artifactStore.get(report.reportArtifactId());
        assertEquals("application/json", artifact.getMediaType());
    }

    @Test
    void unknownTemplateRejectedBounded() throws Exception {
        Chain c = setup("fake-answer");
        OrchestrationReport report = orchestrationService.start(
                c.runId(), "waterfall-chaos", "任务");
        assertEquals("FAILED", report.overallStatus());
        assertTrue(report.failureReason().contains("INVALID_ARGUMENT"));
        assertNotNull(report.reportArtifactId(), "拒绝路径也应留报告产物");
    }

    private record Chain(ProjectEntity project, String runId, String threadId, String turnId,
                         WorkspaceLease parentLease) {
    }
}
