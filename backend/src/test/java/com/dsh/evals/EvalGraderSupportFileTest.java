package com.dsh.evals;

import com.dsh.application.ArtifactStore;
import com.dsh.evals.model.EvalGraderSpec;
import com.dsh.sandbox.CommandOutcome;
import com.dsh.sandbox.CommandSpec;
import com.dsh.sandbox.SandboxRunner;
import com.dsh.workspace.WorkspaceLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class EvalGraderSupportFileTest {
    @TempDir Path tempDir;

    @Test
    void stagesHarnessOnlyInGradingWorkspace() throws Exception {
        Path source = tempDir.resolve("RunTests.java");
        Files.writeString(source, "class RunTests {}\n");
        Path clean = tempDir.resolve("clean");
        Files.createDirectories(clean);
        SandboxRunner sandbox = new SandboxRunner() {
            @Override public String mode() { return "test"; }

            @Override public CommandOutcome execute(CommandSpec spec, WorkspaceLease lease) {
                assertEquals(com.dsh.sandbox.StrictShellCommand.wrap("java .grader-meta/RunTests.java s01"), spec.command());
                assertEquals(clean, lease.dir());
                assertTrue(Files.isRegularFile(clean.resolve(".grader-meta/RunTests.java")));
                assertFalse(Files.exists(clean.resolve("RunTests.java")));
                return new CommandOutcome(spec.operationId(), 0, CommandSpec.Termination.COMPLETED,
                        "Tests run: 1, Failures: 0, Skipped: 0", "", false, 1, true);
            }
        };
        var grader = new EvalGrader(sandbox, mock(ArtifactStore.class));
        var result = grader.run(new EvalGraderSpec("java .grader-meta/RunTests.java s01",
                ".", 60, true, 1, source.toString()), clean, "project");
        assertEquals("PASSED", result.status());
    }

    @Test
    void pytestSummaryIsParsedLikeSurefire() throws Exception {
        // Python 评分数据集：评分器输出 pytest 收尾行而非 Maven「Tests run:」
        Path clean = tempDir.resolve("clean-py");
        Files.createDirectories(clean);
        SandboxRunner sandbox = new SandboxRunner() {
            @Override public String mode() { return "test"; }

            @Override public CommandOutcome execute(CommandSpec spec, WorkspaceLease lease) {
                return new CommandOutcome(spec.operationId(), 0, CommandSpec.Termination.COMPLETED,
                        "===== 7 passed, 1 skipped in 0.31s =====", "", false, 1, true);
            }
        };
        var grader = new EvalGrader(sandbox, mock(ArtifactStore.class));
        var result = grader.run(
                new EvalGraderSpec("python -m pytest -q", ".", 60, true, 1), clean, "project");
        assertEquals("PASSED", result.status());
        assertEquals(8, result.testsDiscovered());
        assertEquals(7, result.testsPassed());
    }

    @Test
    void missingHarnessFailsBeforeSandboxExecution() throws Exception {
        Path clean = tempDir.resolve("clean");
        Files.createDirectories(clean);
        SandboxRunner sandbox = new SandboxRunner() {
            @Override public String mode() { return "test"; }
            @Override public CommandOutcome execute(CommandSpec spec, WorkspaceLease lease) {
                throw new AssertionError("不能在评分器缺失时执行");
            }
        };
        var grader = new EvalGrader(sandbox, mock(ArtifactStore.class));
        assertThrows(IllegalStateException.class,
                () -> grader.run(new EvalGraderSpec("java .grader-meta/RunTests.java s01",
                        ".", 60, true, 1, tempDir.resolve("missing.java").toString()), clean, "project"));
    }

    @Test
    void maskedFailuresCannotPassIndependentGrading() throws Exception {
        Path clean = Files.createDirectories(tempDir.resolve("masked"));
        for (String output : java.util.List.of(
                "Tests run: 3, Failures: 1, Errors: 0, Skipped: 0",
                "Tests run: 3, Failures: 0, Errors: 1, Skipped: 0",
                "1 failed, 2 passed in 0.10s")) {
            SandboxRunner sandbox = new SandboxRunner() {
                @Override public String mode() { return "test"; }
                @Override public CommandOutcome execute(CommandSpec spec, WorkspaceLease lease) {
                    return new CommandOutcome(spec.operationId(), 0, CommandSpec.Termination.COMPLETED,
                            output, "", false, 1, true);
                }
            };
            var grader = new EvalGrader(sandbox, mock(ArtifactStore.class));
            assertEquals("FAILED", grader.run(new EvalGraderSpec("test", ".", 60, true, 1),
                    clean, "project").status());
            assertEquals("FAILED", grader.run(new EvalGraderSpec("test", ".", 60, false, 0),
                    clean, "project").status());
        }
    }
}
