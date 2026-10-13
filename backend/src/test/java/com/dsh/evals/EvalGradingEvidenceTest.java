package com.dsh.evals;

import com.dsh.application.ArtifactStore;
import com.dsh.evals.model.*;
import com.dsh.sandbox.*;
import com.dsh.workspace.WorkspaceLease;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EvalGradingEvidenceTest {
    @TempDir Path clean;
    private final ObjectMapper mapper = new ObjectMapper();

    private EvalGraderResult grade(EvalGraderSpec spec, Integer exit, String output, boolean cleanup) {
        SandboxRunner runner = new SandboxRunner() {
            @Override public String mode() { return "fake"; }
            @Override public CommandOutcome execute(CommandSpec command, WorkspaceLease lease) {
                return new CommandOutcome(command.operationId(), exit, CommandSpec.Termination.COMPLETED,
                        output, "", false, 1, cleanup);
            }
        };
        return new EvalGrader(runner, mock(ArtifactStore.class)).run(spec, clean, "project");
    }

    @Test void legacyNonTestPlaceholderIsUnscored() throws Exception {
        EvalGraderSpec spec = mapper.readValue("""
                {"command":"true","expectTests":false,"minTests":0,"timeoutSeconds":5}
                """, EvalGraderSpec.class);
        assertEquals(EvalGradingMode.SMOKE, spec.gradingMode());
        assertEquals("UNSCORED", grade(spec, 0, "", true).status());
        assertEquals("UNSCORED", grade(spec, 0, "7 passed in 0.2s", true).status());
        assertEquals(spec, mapper.readValue(mapper.writeValueAsString(spec), EvalGraderSpec.class));
    }

    @Test void explicitProtectedExitCodeAssertionsRemainSupported() {
        EvalGraderSpec spec = new EvalGraderSpec("python .grader-meta/check.py", ".", 5,
                false, 0, null, EvalGradingMode.ASSERTIONS);
        assertEquals("PASSED", grade(spec, 0, "", true).status());
        assertEquals("FAILED", grade(spec, 1, "assertion failed", true).status());
    }

    @Test void inconsistentModeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EvalGraderSpec("test", ".", 5,
                false, 0, null, EvalGradingMode.TESTS));
        assertThrows(IllegalArgumentException.class, () -> new EvalGraderSpec("test", ".", 5,
                true, 1, null, EvalGradingMode.ASSERTIONS));
    }

    @Test void skippedTestsCannotSatisfyExecutionThreshold() {
        EvalGraderSpec spec = new EvalGraderSpec("pytest", ".", 5, true, 2);
        assertEquals("NO_TESTS", grade(spec, 0, "10 skipped in 0.2s", true).status());
        assertEquals("FAILED", grade(spec, 0, "1 passed, 10 skipped in 0.2s", true).status());
        assertEquals("PASSED", grade(spec, 0, "2 passed, 10 skipped in 0.2s", true).status());
    }

    @Test void cleanupUncertaintyCannotPassAnyMode() {
        for (EvalGraderSpec spec : List.of(new EvalGraderSpec("pytest", ".", 5, true, 1),
                new EvalGraderSpec("true", ".", 5, false, 0),
                new EvalGraderSpec("assert", ".", 5, false, 0, null, EvalGradingMode.ASSERTIONS))) {
            assertEquals("ERROR", grade(spec, 0, "1 passed in 0.2s", false).status());
        }
    }

    @Test void unknownExitCodeIsGraderError() {
        assertEquals("ERROR", grade(new EvalGraderSpec("test", ".", 5, true, 1),
                null, "1 passed in 0.2s", true).status());
    }

    private EvalConfig config(EvalStrategy strategy) {
        EvalBudget budget = new EvalBudget(2, 3, 10000, 1);
        return new EvalConfig("c20", "v1", "dev", Set.of(), "profile", "profile-digest", budget,
                null, "image", 1, strategy, EvalConfig.computeDigest("c20", "v1", "dev", Set.of(),
                "profile-digest", budget, "image", 1, null, strategy));
    }

    private EvalTrial trial(EvalStrategy strategy, String status, EvalFailureType failure) throws Exception {
        EvalTrial legacy = new EvalTrial("trial-" + strategy, "s", "dev", 1, "run-" + strategy,
                "COMPLETED", "VERIFICATION_PASSED", "patch", status, 0, 0, 0, 0,
                "log", failure, 10, 1, 1, 100, null, null);
        var node = mapper.valueToTree(legacy);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("strategy", strategy.name()).put("category", "SIMPLE");
        return mapper.treeToValue(node, EvalTrial.class);
    }

    private EvalSample sample(boolean smoke) {
        return new EvalSample("s", "goal", EvalCategory.SIMPLE,
                new EvalGraderSpec(smoke ? "true" : "pytest", ".", 5, !smoke, smoke ? 0 : 1));
    }

    @Test void unscoredReportKeepsRunAndResourceFactsButNoSolveRateAndRoundTrips() throws Exception {
        EvalTrial trial = trial(EvalStrategy.SINGLE, "UNSCORED", EvalFailureType.GRADER_UNSCORED);
        EvalReport report = EvalRunner.aggregate(config(EvalStrategy.SINGLE), List.of(trial), List.of(sample(true)));
        assertNull(report.passAt1());
        assertNull(report.passAtK());
        assertEquals(0, report.passedTrials());
        assertEquals(100, report.totalTokens());
        assertEquals("COMPLETED", report.trials().getFirst().terminalState());
        assertEquals(1, report.failureBreakdown().get("GRADER_UNSCORED"));
        assertEquals(report, mapper.readValue(mapper.writeValueAsString(report), EvalReport.class));
    }

    @Test void comparisonRejectsUnscoredTrials() throws Exception {
        EvalConfig single = config(EvalStrategy.SINGLE), multi = config(EvalStrategy.MULTI);
        EvalReport a = EvalRunner.aggregate(single, List.of(trial(EvalStrategy.SINGLE,"UNSCORED",EvalFailureType.GRADER_UNSCORED)),List.of(sample(false)));
        EvalReport b = EvalRunner.aggregate(multi, List.of(trial(EvalStrategy.MULTI,"PASSED",EvalFailureType.PASS)),List.of(sample(false)));
        assertThrows(IllegalArgumentException.class, () -> new EvalComparisonService().compare(single,multi,a,b));
    }

    @Test void smokeDatasetDoesNotHaveSolveRateEvenWhenGradingIsSkipped() throws Exception {
        EvalTrial trial = trial(EvalStrategy.SINGLE, "SKIPPED", EvalFailureType.AGENT_NO_CHANGES);
        EvalReport report = EvalRunner.aggregate(config(EvalStrategy.SINGLE), List.of(trial), List.of(sample(true)));
        assertNull(report.passAt1());
        assertNull(report.passAtK());
        assertEquals(EvalFailureType.AGENT_NO_CHANGES, report.trials().getFirst().failureType());
    }

    @Test void productionComparisonRejectsLegacyPassedRowsWithSmokeDataset() throws Exception {
        EvalConfig single = config(EvalStrategy.SINGLE), multi = config(EvalStrategy.MULTI);
        EvalReport a = EvalRunner.aggregate(single,List.of(trial(EvalStrategy.SINGLE,"PASSED",EvalFailureType.PASS)),List.of(sample(true)));
        EvalReport b = EvalRunner.aggregate(multi,List.of(trial(EvalStrategy.MULTI,"PASSED",EvalFailureType.PASS)),List.of(sample(true)));
        EvalDataset dataset = new EvalDataset("c20","v1",List.of(sample(true)),Set.of("s"),Set.of());
        assertThrows(IllegalArgumentException.class, () -> new EvalComparisonService().compare(single,multi,a,b,dataset));
    }
}
