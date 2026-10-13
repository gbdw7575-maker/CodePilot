package com.dsh.evals;

import com.dsh.api.dto.CreateProject;
import com.dsh.api.dto.ModelOptions;
import com.dsh.api.dto.ModelProfile;
import com.dsh.api.dto.ModelProfileWrite;
import com.dsh.api.dto.Provider;
import com.dsh.application.ModelProfileService;
import com.dsh.application.ProjectService;
import com.dsh.evals.model.EvalBudget;
import com.dsh.evals.model.EvalConfig;
import com.dsh.evals.model.EvalDataset;
import com.dsh.evals.model.EvalFailureType;
import com.dsh.evals.model.EvalGraderSpec;
import com.dsh.evals.model.EvalReport;
import com.dsh.evals.model.EvalSample;
import com.dsh.evals.model.EvalTrial;
import com.dsh.evals.model.EvalSampleResult;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DSH-008 EvalRunner 集成验收（开发准备与验收.md §2 / EVAL-01）：
 * 复用真实 Agent 路径（TurnService.submitEval + 同步调度 + WorkspaceManager），
 * 独立评分器在 Agent 终态后运行；pass@1 非 best-of-retries；每样本独立干净工作区。
 */
@SpringBootTest
class EvalRunnerIntegrationTest {

    @Autowired
    EvalRunner evalRunner;
    @Autowired
    ModelProfileService modelProfileService;
    @Autowired
    ModelProfileRepository modelProfileRepository;
    @Autowired
    ProjectRepository projectRepository;

    @TempDir
    Path tempDir;

    private static final String GRADER_SOURCE = """
            import java.nio.file.*;
            public class Grader {
                public static void main(String[] a) throws Exception {
                    Path fix = Paths.get("fix.txt");
                    if (!Files.exists(fix)) {
                        System.out.println("Tests run: 1, Failures: 1, Skipped: 0");
                        System.out.println("fix.txt 缺失");
                        System.exit(1);
                    }
                    String content = Files.readString(fix).trim();
                    if ("patched".equals(content)) {
                        System.out.println("Tests run: 1, Failures: 0, Skipped: 0");
                        System.exit(0);
                    }
                    System.out.println("Tests run: 1, Failures: 1, Skipped: 0");
                    System.exit(1);
                }
            }
            """;

    @Test
    void rejectsStaleFrozenModelAndConfigBeforeCreatingRuns() throws Exception {
        EvalBudget budget = new EvalBudget(2, 2, 1000, 1);
        assertThrows(IllegalArgumentException.class, () -> new EvalConfig(
                "d", "v1", "dev", Set.of(), "profile", "digest", budget,
                null, "image", 1, "wrong"));

        Path baseline = tempDir.resolve("stale-baseline");
        Files.createDirectories(baseline);
        EvalDataset dataset = new EvalDataset("d", "v1", List.of(), Set.of(), Set.of());
        String digest = EvalConfig.computeDigest("d", "v1", "dev", "stale", budget, "image", 1, null);
        EvalConfig stale = new EvalConfig("d", "v1", "dev", Set.of(), "missing-profile",
                "stale", budget, null, "image", 1, digest);
        assertThrows(IllegalArgumentException.class,
                () -> evalRunner.evaluate(stale, dataset, baseline, null));
    }

    @Test
    void approvalInMultiToolResponsePairsEveryToolCallBeforeNextModelRequest() throws Exception {
        Path baseline = tempDir.resolve("batch-baseline");
        Files.createDirectories(baseline);
        Files.writeString(baseline.resolve("README.md"), "batch approval fixture\n");
        ModelProfile profile = modelProfileService.create(new ModelProfileWrite(
                "eval-batch-approval", Provider.fake, "fake-batch-approval", "http://127.0.0.1:9",
                null, new ModelOptions(ModelOptions.Thinking.disabled, null, 4096, false)));
        String profileDigest = ModelProfileService.configDigest(
                modelProfileRepository.findById(profile.id()).orElseThrow());
        EvalBudget budget = new EvalBudget(4, 4, 20000, 2);
        String digest = EvalConfig.computeDigest("batch", "v1", "dev", profileDigest,
                budget, "local-eval", 1, null);
        EvalConfig config = new EvalConfig("batch", "v1", "dev", Set.of(), profile.id(),
                profileDigest, budget, null, "local-eval", 1, digest);
        EvalGraderSpec grader = new EvalGraderSpec("echo Tests run: 1, Failures: 0", ".", 30, true, 1);
        EvalDataset dataset = new EvalDataset("batch", "v1",
                List.of(new EvalSample("batch-1", "检查审批批次续接", grader)),
                Set.of("batch-1"), Set.of());

        EvalReport report = evalRunner.evaluate(config, dataset, baseline, null);
        EvalTrial trial = report.trials().get(0);
        assertEquals("COMPLETED", trial.terminalState());
        assertEquals(EvalFailureType.AGENT_NO_CHANGES, trial.failureType());
        assertEquals(2, trial.toolCalls());
    }

    @Test
    void evalProducesTraceableReportWithPassAndNoChanges() throws Exception {
        // 1. 冻结基线目录：仅 README，不含 fix.txt（Agent 从未直接写入）
        Path baselineDir = tempDir.resolve("baseline");
        Files.createDirectories(baselineDir);
        Files.writeString(baselineDir.resolve("README.md"), "eval baseline\n");
        // Agent 自身的验收计划；独立 grader 仍负责判断补丁内容。
        Files.createDirectories(baselineDir.resolve(".dsh"));
        Files.writeString(baselineDir.resolve(".dsh/verify.yaml"),
                "command: echo Tests run: 1\nexpectTests: true\n");

        // 2. 编译独立评分器到基线外目录（被测 Agent 不可见：留出保护）
        Path graderClassDir = tempDir.resolve("grader-classes");
        compileGrader(graderClassDir);
        String graderCommand = "java -cp \"" + graderClassDir.toAbsolutePath() + "\" Grader";
        EvalGraderSpec grader = new EvalGraderSpec(graderCommand, ".", 60, true, 1);

        // 3. 创建 fake-patch 模型配置 + 计算配置摘要
        ModelProfile profile = modelProfileService.create(new ModelProfileWrite(
                "eval-fake-patch", Provider.fake, "fake-patch", "http://127.0.0.1:9",
                null, new ModelOptions(ModelOptions.Thinking.disabled, null, 4096, false)));
        ModelProfileEntity profileEntity = modelProfileRepository.findById(profile.id()).orElseThrow();
        String modelConfigDigest = ModelProfileService.configDigest(profileEntity);

        // 4. 数据集：两个样本（fix→PASS / noop→NO_CHANGES），dev 集
        EvalSample fixSample = new EvalSample("fix", "请修复 broken 模块", grader);
        EvalSample noopSample = new EvalSample("noop", "解释一下乐观锁的概念", grader);
        EvalDataset dataset = new EvalDataset("dsh-fix", "v1",
                List.of(fixSample, noopSample), Set.of("fix", "noop"), Set.of());

        // 5. 评测配置：repeatCount=2，冻结 configDigest
        EvalBudget budget = new EvalBudget(24, 80, 200000, 20);
        String configDigest = EvalConfig.computeDigest(dataset.name(), dataset.version(), "dev",
                modelConfigDigest, budget, "local-eval", 2, null);
        EvalConfig config = new EvalConfig(dataset.name(), dataset.version(), "dev", Set.of(),
                profile.id(), modelConfigDigest, budget, null, "local-eval", 2, configDigest);

        // 6. 运行评测
        Path reportsDir = tempDir.resolve("reports");
        EvalReport report = evalRunner.evaluate(config, dataset, baselineDir, reportsDir);

        // 7. 断言：4 trials（2 样本 × 2 轮），fix 两轮均 PASS、noop 两轮均 NO_CHANGES
        assertEquals(4, report.totalTrials());
        assertEquals(2, report.passedTrials());
        assertEquals(2, report.failureBreakdown().get(EvalFailureType.PASS.name()));
        assertEquals(2, report.failureBreakdown().get(EvalFailureType.AGENT_NO_CHANGES.name()));
        assertEquals(0, report.failureBreakdown().get(EvalFailureType.GRADER_FAILED.name()));

        // pass@1 = 首轮通过样本占比 = 1/2 = 0.5（非 best-of-retries）
        assertEquals(0.5, report.passAt1(), 1e-9);
        assertEquals(0.5, report.passAtK(), 1e-9);

        // 每样本汇总：fix 首次通过=第 1 轮；noop 从未通过
        EvalSampleResult fixResult = report.sampleResults().stream()
                .filter(r -> r.sampleId().equals("fix")).findFirst().orElseThrow();
        assertEquals(2, fixResult.attempts());
        assertEquals(1, fixResult.passedAttempt());
        assertEquals(EvalFailureType.PASS, fixResult.failureType());
        EvalSampleResult noopResult = report.sampleResults().stream()
                .filter(r -> r.sampleId().equals("noop")).findFirst().orElseThrow();
        assertEquals(2, noopResult.attempts());
        assertNull(noopResult.passedAttempt());
        assertEquals(EvalFailureType.AGENT_NO_CHANGES, noopResult.failureType());

        // 所有 trial 携带可追溯引用
        for (EvalTrial t : report.trials()) {
            assertNotNull(t.runId());
            assertNotNull(t.trialId());
            assertTrue(t.latencyMs() >= 0);
            assertTrue(t.modelRequests() >= 1);
        }
        // fix 样本 trial：评分器执行（非 SKIPPED）、testsDiscovered=1、passed=1、有日志产物
        EvalTrial fixTrial = report.trials().stream()
                .filter(t -> t.sampleId().equals("fix") && t.attempt() == 1).findFirst().orElseThrow();
        assertEquals("PASSED", fixTrial.graderStatus());
        assertEquals(1, fixTrial.testsDiscovered());
        assertEquals(1, fixTrial.testsPassed());
        assertNotNull(fixTrial.graderLogArtifactId());
        assertNotNull(fixTrial.patchArtifactId());
        // noop 样本 trial：无补丁→评分器跳过
        EvalTrial noopTrial = report.trials().stream()
                .filter(t -> t.sampleId().equals("noop") && t.attempt() == 1).findFirst().orElseThrow();
        assertEquals("SKIPPED", noopTrial.graderStatus());
        assertEquals(0, noopTrial.testsDiscovered());

        // 报告文件已写入
        assertTrue(Files.isDirectory(reportsDir));
        assertTrue(Files.list(reportsDir).count() >= 1);

        // configDigest 冻结可对照（EVAL-01）
        assertEquals(configDigest, report.configDigest());
        String again = EvalConfig.computeDigest(dataset.name(), dataset.version(), "dev",
                modelConfigDigest, budget, "local-eval", 2, null);
        assertEquals(configDigest, again);

        // 基线未被污染：fix.txt 不在基线目录（项目隔离）
        assertFalse(Files.exists(baselineDir.resolve("fix.txt")),
                "Agent 不得直接写入冻结基线目录");
        // 评分器规格不进 Agent 工作区（留出保护）：Grader.class 不在基线目录
        assertFalse(Files.exists(baselineDir.resolve("Grader.class")),
                "评分器不得进入被测 Agent 可见的工作区");
    }

    private void compileGrader(Path classDir) throws IOException {
        Files.createDirectories(classDir);
        Path src = classDir.resolve("Grader.java");
        Files.writeString(src, GRADER_SOURCE, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("当前 JDK 无可用编译器，无法编译评分器");
        }
        int rc = compiler.run(null, null, null, "-d", classDir.toString(), src.toString());
        if (rc != 0) {
            throw new UncheckedIOException(new IOException("评分器编译失败 rc=" + rc));
        }
    }
}
