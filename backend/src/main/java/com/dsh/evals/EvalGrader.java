package com.dsh.evals;

import com.dsh.application.ArtifactStore;
import com.dsh.common.IdGen;
import com.dsh.evals.model.EvalGraderResult;
import com.dsh.evals.model.EvalGraderSpec;
import com.dsh.sandbox.CommandOutcome;
import com.dsh.sandbox.CommandSpec;
import com.dsh.sandbox.SandboxRunner;
import com.dsh.workspace.WorkspaceLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * 独立评分器（doc/内部接口契约.md §8：评分器在 Agent 结束后独立执行受保护的验收规则；
 * 被测 Agent 不可改评分器或读取留出答案）。
 *
 * <p>在「干净起点 + Agent 最终补丁」的工作区副本执行 grader 命令，解析测试统计，
 * 日志存为项目级产物。零测试（expectTests 时）不算通过（对齐 VER-02）。</p>
 */
@Service
public class EvalGrader {

    private static final Logger log = LoggerFactory.getLogger(EvalGrader.class);

    private final SandboxRunner sandboxRunner;
    private final ArtifactStore artifactStore;

    public EvalGrader(SandboxRunner sandboxRunner, ArtifactStore artifactStore) {
        this.sandboxRunner = sandboxRunner;
        this.artifactStore = artifactStore;
    }

    /**
     * @param graderSpec    评分器规格（来自数据集文件，不进 Agent 工作区）
     * @param cleanDir      干净评分工作区（基线 + Agent 最终补丁）
     * @param projectId     产物归属项目（项目级产物，不绑定具体 Run 工作区租约）
     */
    public EvalGraderResult run(EvalGraderSpec graderSpec, Path cleanDir, String projectId) {
        Path workdir = ".".equals(graderSpec.workdir()) ? cleanDir : cleanDir.resolve(graderSpec.workdir());
        Path metaDir = cleanDir.resolve(".grader-meta");
        try {
            Files.createDirectories(metaDir);
            if (graderSpec.supportFile() != null && !graderSpec.supportFile().isBlank()) {
                Path source = Path.of(graderSpec.supportFile()).toAbsolutePath().normalize();
                if (!Files.isRegularFile(source)) {
                    throw new IllegalArgumentException("评分器支持文件不存在: " + source);
                }
                Files.copy(source, metaDir.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("准备评分器支持文件失败", e);
        }
        String workspaceDigest;
        try {
            workspaceDigest = com.dsh.workspace.WorkspaceManager.digestOf(cleanDir);
        } catch (Exception e) {
            workspaceDigest = "";
        }

        String operationId = IdGen.next("grade_");
        WorkspaceLease lease = new WorkspaceLease(
                "eval-grader-" + operationId, "eval-grader", projectId, cleanDir,
                cleanDir, metaDir, workspaceDigest, Instant.now());

        CommandSpec spec = new CommandSpec(operationId, com.dsh.sandbox.StrictShellCommand.wrap(graderSpec.command()), workdir,
                graderSpec.timeoutSeconds());
        CommandOutcome outcome = sandboxRunner.execute(spec, lease);

        int exitCode = outcome.exitCode() != null ? outcome.exitCode() : -1;
        String combined = (outcome.stdout() == null ? "" : outcome.stdout())
                + "\n[stderr]\n" + (outcome.stderr() == null ? "" : outcome.stderr());

        // 复用验证子系统的输出解析：pytest 收尾行优先，回退 Maven surefire
        // （Python 评分数据集此前会因只认 surefire 而 discovered=0 → 误判 NO_TESTS）
        com.dsh.application.TestCounts counts =
                com.dsh.application.VerificationService.parseTestCounts(combined);
        int discovered = counts.discovered();
        int failures = counts.failures();
        int skipped = counts.skipped();
        int passed = counts.passed();

        String logArtifactId = null;
        try {
            byte[] logBytes = combined.getBytes(StandardCharsets.UTF_8);
            var art = artifactStore.save(null, projectId, "grader-" + operationId + ".log",
                    "text/plain;charset=utf-8", logBytes, true);
            logArtifactId = art.getId();
        } catch (Exception e) {
            log.warn("评分日志产物保存失败 op={}: {}", operationId, e.getMessage());
        }

        String status = classify(graderSpec, outcome, exitCode, discovered, failures, passed);
        return new EvalGraderResult(status, outcome.exitCode(), discovered, passed, skipped, logArtifactId);
    }

    private static String classify(EvalGraderSpec spec, CommandOutcome outcome, int exitCode,
                                   int discovered, int failures, int passed) {
        if (outcome.termination() != CommandSpec.Termination.COMPLETED || !outcome.cleanupConfirmed()
                || outcome.exitCode() == null) {
            return "ERROR";
        }
        int min = spec.expectTests() && spec.minTests() == 0 ? 1 : spec.minTests();
        if (failures > 0) return "FAILED";
        if (exitCode == 0) {
            if (spec.gradingMode() == com.dsh.evals.model.EvalGradingMode.SMOKE) return "UNSCORED";
            if (spec.expectTests() && passed == 0) {
                return "NO_TESTS";
            }
            if (spec.expectTests() && passed < min) {
                return "FAILED";
            }
            return "PASSED";
        }
        // 非零退出：若要求测试但发现 0，归 NO_TESTS；否则 FAILED
        if (spec.expectTests() && discovered == 0) {
            return "NO_TESTS";
        }
        return "FAILED";
    }
}
