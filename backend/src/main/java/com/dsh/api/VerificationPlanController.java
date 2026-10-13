package com.dsh.api;

import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.VerificationPlanView;
import com.dsh.application.VerificationPlan;
import com.dsh.storage.*;
import org.springframework.web.bind.annotation.*;
import java.nio.file.Path;

/** C29: inspect the effective requirements before submitting and during a Run. */
@RestController
@RequestMapping("/api/v1")
public class VerificationPlanController {
    private final ProjectRepository projects;
    private final RunRepository runs;
    private final TurnRepository turns;
    private final ThreadRepository threads;
    public VerificationPlanController(ProjectRepository projects, RunRepository runs,
            TurnRepository turns, ThreadRepository threads) {
        this.projects=projects; this.runs=runs; this.turns=turns; this.threads=threads;
    }
    private ProjectEntity project(String id) {
        var project=projects.findById(id).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,"项目不存在"));
        if (!project.isTrusted()) throw new ApiException(ErrorCode.PROJECT_NOT_TRUSTED,"项目未信任");
        return project;
    }
    @GetMapping("/projects/{projectId}/verification-plan")
    public VerificationPlanView preview(@PathVariable String projectId) {
        try { return VerificationPlanView.from(VerificationPlan.load(Path.of(project(projectId).getRootPath())),false); }
        catch (IllegalArgumentException invalid) { throw new ApiException(ErrorCode.VALIDATION_FAILED,"项目验收配置无效"); }
    }
    @GetMapping("/runs/{runId}/verification-plan")
    public VerificationPlanView effective(@PathVariable String runId) {
        var run=runs.findById(runId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,"运行不存在"));
        var turn=turns.findById(run.getTurnId()).orElseThrow();
        var thread=threads.findById(turn.getThreadId()).orElseThrow();
        try { return VerificationPlanView.from(VerificationPlan.forRun(run,Path.of(project(thread.getProjectId()).getRootPath())),
                run.getVerificationRequirement()!=null); }
        catch (IllegalArgumentException invalid) { throw new ApiException(ErrorCode.VALIDATION_FAILED,"运行验收配置无效"); }
    }
}
