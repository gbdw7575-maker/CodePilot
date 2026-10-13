package com.dsh.orchestration;

import com.dsh.application.ArtifactStore;
import com.dsh.common.IdGen;
import com.dsh.storage.ArtifactEntity;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 顺序编排服务（DSH-010，开发文档 §12）。
 *
 * <p>coding-review v1 固定流水线：ARCHITECT → CODER（种子=父工作区）→
 * TESTER（种子=CODER 快照）→ REVIEWER（种子=CODER 快照）→ 文件级三方汇合。
 * 任一角色未 COMPLETED 即短路（CANCELLED→取消；WAITING_*→PAUSED 不自动批准；其余→FAILED）；
 * 汇合有冲突整批不应用。无论成败都把 {@link OrchestrationReport} 落为父 Run 的 JSON 产物。</p>
 *
 * <p>预算：编排本身不额外记账，每个阶段是一次 run_subagent 式预算切片，
 * 总用量在报告 totals 中汇总，三维总额始终不突破父 Run 上限。</p>
 */
@Service
public class OrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);

    public static final String TEMPLATE_CODING_REVIEW = "coding-review";
    public static final String TEMPLATE_VERSION = "v1";
    private static final List<String> STAGES =
            List.of("ARCHITECT", "CODER", "TESTER", "REVIEWER");

    private final SubagentService subagentService;
    private final WorkspaceMerge workspaceMerge;
    private final WorkspaceManager workspaceManager;
    private final ArtifactStore artifactStore;
    private final RunRepository runRepository;
    private final TurnRepository turnRepository;
    private final ThreadRepository threadRepository;
    private final ObjectMapper objectMapper;

    public OrchestrationService(SubagentService subagentService, WorkspaceMerge workspaceMerge,
                                WorkspaceManager workspaceManager, ArtifactStore artifactStore,
                                RunRepository runRepository, TurnRepository turnRepository,
                                ThreadRepository threadRepository,
                                ObjectMapper objectMapper) {
        this.subagentService = subagentService;
        this.workspaceMerge = workspaceMerge;
        this.workspaceManager = workspaceManager;
        this.artifactStore = artifactStore;
        this.runRepository = runRepository;
        this.turnRepository = turnRepository;
        this.threadRepository = threadRepository;
        this.objectMapper = objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /** 同步执行一次顺序编排，返回完整报告（报告同时落父 Run 产物）。 */
    public OrchestrationReport start(String parentRunId, String template, String task) {
        Instant startedAt = Instant.now();
        String tpl = template == null ? "" : template.trim().toLowerCase();
        if (!TEMPLATE_CODING_REVIEW.equals(tpl)) {
            return reportFailure(parentRunId, template, task, startedAt,
                    "INVALID_ARGUMENT", "不支持的编排模板: " + template + "（当前仅 coding-review）",
                    List.of(), OrchestrationReport.Merge.notAttempted(), zeroTotals());
        }

        RunEntity parent = runRepository.findById(parentRunId).orElse(null);
        if (parent == null) {
            return reportFailure(parentRunId, tpl, task, startedAt,
                    "NOT_FOUND", "父 Run 不存在: " + parentRunId,
                    List.of(), OrchestrationReport.Merge.notAttempted(), zeroTotals());
        }

        List<OrchestrationReport.Stage> stages = new ArrayList<>();
        SubagentResult architect = null;
        SubagentResult coder = null;
        SubagentResult tester = null;
        String overallStatus = "COMPLETED";
        String failureReason = null;

        for (String role : STAGES) {
            SubagentResult r;
            switch (role) {
                case "ARCHITECT" -> r = runStage(parentRunId, role,
                        "【架构阶段】针对以下任务给出实现方案（模块划分、关键接口、改动点、风险）：\n" + task,
                        null);
                case "CODER" -> {
                    WorkspaceLease parentLease = workspaceManager.find(parentRunId);
                    String seed = parentLease != null ? parentLease.workspaceId()
                            : parent.getWorkspaceRef();
                    String coderTask = "【编码阶段】完成以下任务并自检（编译/测试），做最小必要改动：\n"
                            + task;
                    if (architect != null && architect.succeeded()) {
                        coderTask += "\n\n上游架构方案（参考，不替代任务要求）：\n"
                                + safe(architect.summary());
                    }
                    r = runStage(parentRunId, role, coderTask, seed);
                }
                case "TESTER" -> r = runStage(parentRunId, role,
                        "【测试阶段】验证工程师对以下任务的实现：阅读改动并运行/编写测试，"
                                + "给出通过/不通过结论与证据：\n" + task
                                + "\n\n工程师自述：\n" + (coder == null ? "" : safe(coder.summary())),
                        coder == null ? null : coder.workspaceRef());
                default -> r = runStage(parentRunId, role,
                        "【评审阶段】审查工程师对以下任务的改动（正确性/可维护性/安全/需求一致），"
                                + "给出 APPROVE 或 REQUEST_CHANGES 与分级问题清单：\n" + task
                                + "\n\n工程师自述：\n" + (coder == null ? "" : safe(coder.summary()))
                                + "\n\n测试结论：\n" + (tester == null ? "" : safe(tester.summary())),
                        coder == null ? null : coder.workspaceRef());
            }
            stages.add(toStage(r, true));

            if ("ARCHITECT".equals(role)) {
                architect = r;
            } else if ("CODER".equals(role)) {
                coder = r;
            } else if ("TESTER".equals(role)) {
                tester = r;
            }

            if (!r.succeeded()) {
                overallStatus = r.unfinished() ? "PAUSED"
                        : ("CANCELLED".equals(r.state()) ? "CANCELLED" : "FAILED");
                failureReason = role + " 阶段未完成: " + r.state()
                        + (r.stopReason() == null ? "" : " (" + r.stopReason() + ")");
                // 未执行阶段以占位记录补齐
                for (String rest : STAGES.subList(STAGES.indexOf(role) + 1, STAGES.size())) {
                    stages.add(skippedStage(rest));
                }
                break;
            }
        }

        // 汇合：仅 CODER 完成且流水线未因取消/暂停/失败中断时尝试
        OrchestrationReport.Merge merge = OrchestrationReport.Merge.notAttempted();
        if ("COMPLETED".equals(overallStatus) && coder != null) {
            merge = doMerge(parentRunId, coder);
            if (!"APPLIED".equals(merge.status())) {
                overallStatus = "FAILED";
                failureReason = "工作区汇合存在冲突，整批未应用，交回主 Agent 决策";
            }
        } else if (coder != null && coder.succeeded()) {
            // CODER 完成但后续阶段阻断：仍给出 CONFLICTS/SKIPPED 状态而不自动汇合
            merge = OrchestrationReport.Merge.notAttempted();
        }

        OrchestrationReport.Totals totals = sumStages(stages);
        return finalizeReport(parentRunId, parent, tpl, task, startedAt, overallStatus,
                failureReason, stages, merge, totals);
    }

    private SubagentResult runStage(String parentRunId, String role, String roleTask,
                                    String seedRef) {
        SubagentRequest req = new SubagentRequest(parentRunId, role, roleTask, List.of(),
                seedRef == null || seedRef.isBlank() ? null : seedRef);
        try {
            return subagentService.run(req);
        } catch (RuntimeException e) {
            log.error("编排阶段执行异常 parent={} role={}", parentRunId, role, e);
            return new SubagentResult("", role, "FAILED", "ENVIRONMENT_ERROR",
                    "编排阶段异常: " + safe(e.getMessage()), List.of(), null,
                    IdGen.next("ws_"), "", 0, 0, null, 0);
        }
    }

    private OrchestrationReport.Merge doMerge(String parentRunId, SubagentResult coder) {
        try {
            WorkspaceMerge.Result result =
                    workspaceMerge.merge(coder.workspaceRef(), parentRunId, workspaceManager);
            OrchestrationReport.Merge merge = new OrchestrationReport.Merge(
                    true, result.status(), coder.workspaceRef(),
                    List.copyOf(result.appliedPaths()), List.copyOf(result.conflicts()));
            return merge;
        } catch (RuntimeException e) {
            log.warn("工作区汇合异常 parent={} coder={}: {}",
                    parentRunId, coder.workspaceRef(), e.getMessage());
            return new OrchestrationReport.Merge(true, "ERROR", coder.workspaceRef(),
                    List.of(), List.of(new WorkspaceMerge.Conflict("*", "ERROR",
                    safe(e.getMessage()))));
        }
    }

    private OrchestrationReport.Stage toStage(SubagentResult r, boolean executed) {
        return new OrchestrationReport.Stage(r.role(), r.runId(), executed, r.state(),
                r.stopReason(), abbreviate(r.summary(), 2000), r.workspaceRef(),
                r.patchArtifactId(), r.modelRequestsUsed(), r.toolCallsUsed(),
                r.settledTokens(), r.durationMs());
    }

    private OrchestrationReport.Stage skippedStage(String role) {
        return new OrchestrationReport.Stage(role, "", false, "SKIPPED", null,
                "上游阶段未完成，本阶段未执行", "", null, 0, 0, null, 0);
    }

    private OrchestrationReport.Totals sumStages(List<OrchestrationReport.Stage> stages) {
        int mr = 0, tc = 0, tok = 0;
        long dur = 0;
        for (OrchestrationReport.Stage s : stages) {
            mr += s.modelRequests();
            tc += s.toolCalls();
            tok += s.tokens() == null ? 0 : s.tokens();
            dur += s.durationMs();
        }
        return new OrchestrationReport.Totals(mr, tc, tok, dur);
    }

    private OrchestrationReport.Totals zeroTotals() {
        return new OrchestrationReport.Totals(0, 0, 0, 0);
    }

    private OrchestrationReport finalizeReport(String parentRunId, RunEntity parent,
                                               String template, String task, Instant startedAt,
                                               String overallStatus, String failureReason,
                                               List<OrchestrationReport.Stage> stages,
                                               OrchestrationReport.Merge merge,
                                               OrchestrationReport.Totals totals) {
        String projectId = projectIdOf(parent);
        OrchestrationReport report = new OrchestrationReport(template, TEMPLATE_VERSION,
                parentRunId, task, startedAt, Instant.now(), overallStatus, failureReason,
                List.copyOf(stages), merge, totals, "");
        try {
            byte[] body = objectMapper.writeValueAsBytes(report);
            ArtifactEntity artifact = artifactStore.save(parentRunId, projectId,
                    "orchestration-report.json", "application/json", body, true);
            report = new OrchestrationReport(template, TEMPLATE_VERSION, parentRunId, task,
                    startedAt, Instant.now(), overallStatus, failureReason,
                    List.copyOf(stages), merge, totals, artifact.getId());
        } catch (Exception e) {
            log.warn("编排报告落盘失败 parent={}: {}", parentRunId, e.getMessage());
        }
        return report;
    }

    private OrchestrationReport reportFailure(String parentRunId, String template, String task,
                                              Instant startedAt, String reason, String message,
                                              List<OrchestrationReport.Stage> stages,
                                              OrchestrationReport.Merge merge,
                                              OrchestrationReport.Totals totals) {
        log.warn("编排启动失败 parent={}: {}", parentRunId, message);
        OrchestrationReport report = new OrchestrationReport(
                template == null ? "" : template, TEMPLATE_VERSION, parentRunId, task,
                startedAt, Instant.now(), "FAILED", reason + ": " + message,
                stages, merge, totals, "");
        try {
            byte[] body = objectMapper.writeValueAsBytes(report);
            ArtifactEntity artifact = artifactStore.save(parentRunId, "",
                    "orchestration-report.json", "application/json", body, true);
            return new OrchestrationReport(template == null ? "" : template, TEMPLATE_VERSION,
                    parentRunId, task, startedAt, Instant.now(), "FAILED",
                    reason + ": " + message, stages, merge, totals, artifact.getId());
        } catch (Exception e) {
            return report;
        }
    }

    private String projectIdOf(RunEntity run) {
        try {
            TurnEntity turn = turnRepository.findById(run.getTurnId()).orElseThrow();
            ThreadEntity thread = threadRepository.findById(turn.getThreadId()).orElseThrow();
            return thread.getProjectId() == null ? "" : thread.getProjectId();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
