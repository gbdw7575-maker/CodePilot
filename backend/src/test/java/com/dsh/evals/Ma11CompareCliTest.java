package com.dsh.evals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ma11CompareCliTest {
    @Test
    void missingDatasetIsRejectedBeforeReadingReportsOrWritingResults() {
        var loader = mock(EvalDatasetLoader.class);
        var runner = mock(EvalRunner.class);
        var comparison = mock(EvalComparisonService.class);
        var writer = mock(EvalComparisonWriter.class);
        var preflight = mock(EvalPreflight.class);
        var cli = new EvalCliRunner(loader, runner, comparison, writer, preflight);
        var error = assertThrows(IllegalArgumentException.class, () -> cli.run(
                "--dsh.eval.compare-a=single.json", "--dsh.eval.compare-b=multi.json",
                "--dsh.eval.report-a=single-report.json", "--dsh.eval.report-b=multi-report.json",
                "--dsh.eval.dataset="));
        assertTrue(error.getMessage().contains("dsh.eval.dataset"));
        verifyNoInteractions(loader, runner, comparison, writer, preflight);
    }
}
