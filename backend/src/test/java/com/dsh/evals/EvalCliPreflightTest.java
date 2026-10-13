package com.dsh.evals;
import com.dsh.evals.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EvalCliPreflightTest {
    @TempDir Path root;
    EvalDatasetLoader loader=mock(EvalDatasetLoader.class);EvalRunner runner=mock(EvalRunner.class);
    EvalPreflight check=mock(EvalPreflight.class);
    EvalComparisonService compare=mock(EvalComparisonService.class);EvalComparisonWriter writer=mock(EvalComparisonWriter.class);
    EvalCliRunner cli=new EvalCliRunner(loader,runner,compare,writer,check);
    EvalConfig config=mock(EvalConfig.class);EvalDataset dataset=mock(EvalDataset.class);
    String[] args;
    @BeforeEach void setup(){
        args=new String[]{"--dsh.eval.config=c.json","--dsh.eval.dataset=d.json","--dsh.eval.baseline="+root,"--dsh.eval.reports-dir="+root.resolve("out")};
        when(loader.loadConfig(any())).thenReturn(config);when(loader.loadDataset(any())).thenReturn(dataset);
        var manifest=mock(EvalExecutionManifest.class);when(manifest.executionDigest()).thenReturn("fixture");
        when(runner.preflight(any(),any(),any(),any())).thenReturn(new EvalRunner.PreflightResult(root.resolve("manifest.json"),manifest));
        when(runner.evaluateGuarded(any(),any(),any(),any(),any())).thenReturn(mock(EvalReport.class));
    }
    String[] add(String... extra){var list=new ArrayList<>(List.of(args));list.addAll(List.of(extra));return list.toArray(String[]::new);}
    @Test void configDefaultsToPreflight(){assertTrue(cli.execute(args));verify(runner).preflight(any(),any(),any(),any());verify(runner,never()).evaluateGuarded(any(),any(),any(),any(),any());}
    @Test void explicitPreflightDoesNotExecute(){cli.execute(add("--dsh.eval.preflight-only=true"));verify(runner).preflight(any(),any(),any(),any());}
    @Test void explicitExecutionRoutesThroughGuard(){cli.execute(add("--dsh.eval.preflight-only=false","--dsh.eval.expected-manifest=e.json"));verify(runner).evaluateGuarded(any(),any(),any(),any(),eq(Path.of("e.json")));verify(runner,never()).preflight(any(),any(),any(),any());}
    @Test void executionWithoutExpectedFailsBeforeLoading(){assertThrows(IllegalArgumentException.class,()->cli.execute(add("--dsh.eval.preflight-only=false")));verifyNoInteractions(loader,runner,check);}
    @Test void blankExpectedFails(){assertThrows(IllegalArgumentException.class,()->cli.execute(add("--dsh.eval.preflight-only=false","--dsh.eval.expected-manifest=")));verifyNoInteractions(runner);}
    @Test void invalidBooleanFailsClosed(){for(String v:List.of("0","FALSE","","yes"))assertThrows(IllegalArgumentException.class,()->cli.execute(add("--dsh.eval.preflight-only="+v)));verifyNoInteractions(runner);}
    @Test void bareBooleanRejected(){assertThrows(IllegalArgumentException.class,()->cli.execute(add("--dsh.eval.preflight-only")));verifyNoInteractions(runner);}
    @Test void duplicateModeRejected(){assertThrows(IllegalArgumentException.class,()->cli.execute(add("--dsh.eval.preflight-only=true","--dsh.eval.preflight-only=false","--dsh.eval.expected-manifest=e.json")));verifyNoInteractions(runner);}
    @Test void expectedCannotImplicitlySelectExecution(){assertThrows(IllegalArgumentException.class,()->cli.execute(add("--dsh.eval.expected-manifest=e.json")));verifyNoInteractions(runner);}
    @Test void compareAndEvaluationCannotMix(){assertThrows(IllegalArgumentException.class,()->cli.execute(add("--dsh.eval.compare-a=a.json")));verifyNoInteractions(runner);}
    @Test void reportsRequired(){args=Arrays.copyOf(args,3);assertThrows(IllegalArgumentException.class,()->cli.execute(args));verifyNoInteractions(runner);}
    @Test void legacyFourArgEvaluateNeverUsed(){cli.execute(args);verify(runner,never()).evaluate(any(),any(),any(),any());}
    @Test void failedExecutionPreflightDoesNotCapture(){doThrow(new IllegalArgumentException("runtime mismatch")).when(check).validate(any());assertThrows(IllegalArgumentException.class,()->cli.execute(args));verifyNoInteractions(runner);}
    @Test void noEvaluationConfigurationDoesNothing(){assertFalse(cli.execute());verifyNoInteractions(loader,runner,check);}
}
