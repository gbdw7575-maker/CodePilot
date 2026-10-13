package com.dsh.api;

import com.dsh.api.dto.Verification;
import com.dsh.api.dto.VerificationPage;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.VerificationEntity;
import com.dsh.storage.VerificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 验证查询：按 Run 列出验证记录，查询时实时计算 stale。
 */
@RestController
@RequestMapping("/api/v1/runs")
public class VerificationController {

    private final VerificationRepository verificationRepository;
    private final RunRepository runRepository;
    private final com.dsh.application.VerificationEvidenceContext evidenceContext;

    public VerificationController(VerificationRepository verificationRepository,
                                  RunRepository runRepository,
                                  com.dsh.application.VerificationEvidenceContext evidenceContext) {
        this.verificationRepository = verificationRepository;
        this.runRepository = runRepository;
        this.evidenceContext = evidenceContext;
    }

    @GetMapping("/{runId}/verifications")
    public VerificationPage list(@PathVariable String runId,
                                 @RequestParam(defaultValue = "20") int limit,
                                 @RequestParam(required = false) String cursor) {
        int page = 0;
        if (cursor != null && !cursor.isBlank()) {
            try { page = Integer.parseInt(cursor); } catch (NumberFormatException ignored) {}
        }
        limit = Math.min(Math.max(1, limit), 100);

        Page<VerificationEntity> pageResult = verificationRepository
                .findByRunIdOrderByStartedAtDesc(runId, PageRequest.of(page, limit));

        // 计算当前工作区摘要（用于 stale 判定）
        RunEntity run = runRepository.findById(runId).orElse(null);
        com.dsh.application.VerificationEvidenceContext.Snapshot snapshot = null;
        if (run != null && run.getWorkspaceRef() != null) {
            try { snapshot = evidenceContext.current(run); }
            catch (RuntimeException unavailable) { /* 无法证明新鲜，按过期返回。 */ }
        }
        final var current = snapshot;

        List<Verification> items = pageResult.getContent().stream()
                .map(e -> toDto(e, run == null || !evidenceContext.isFresh(run, e, current)))
                .toList();

        String nextCursor = pageResult.hasNext() ? String.valueOf(page + 1) : null;
        return new VerificationPage(items, nextCursor, pageResult.hasNext());
    }

    private static Verification toDto(VerificationEntity e, boolean stale) {
        List<String> logIds = e.getLogArtifactIds() == null || e.getLogArtifactIds().isBlank()
                ? List.of()
                : Arrays.asList(e.getLogArtifactIds().split(","));
        String preDigest = e.getPreVerificationDigest();
        boolean workspaceModified = preDigest != null && !preDigest.equals(e.getWorkspaceDigest());
        return new Verification(
                e.getId(), e.getRunId(), e.getPlanVersion(), e.getWorkspaceDigest(),
                preDigest, e.getImageDigest(), e.getCommandSummary(), e.getStatus(), e.getExitCode(),
                e.getTestsDiscovered(), e.getTestsPassed(), e.getTestsSkipped(), workspaceModified, stale,
                logIds, e.getStartedAt(), e.getFinishedAt());
    }
}
