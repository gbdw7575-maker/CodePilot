package com.dsh.agent;

import com.dsh.application.ArtifactStore;
import com.dsh.storage.ArtifactEntity;
import com.dsh.storage.VerificationEntity;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VerificationFailureDiagnosticsTest {
    private VerificationEntity verification(String log) {
        Instant now = Instant.now();
        return new VerificationEntity("ver_test", "run_owner", "plan", "post", "pre", "image",
                "pytest -q", "FAILED", 1, 1, 0, 0, false, log, now, now);
    }
    private ArtifactEntity artifact(String run) {
        return new ArtifactEntity("art_log", run, "proj", "log", "text/plain", 100, "sha",
                Instant.now(), true);
    }
    @Test void rejectsAnotherRunsLogBeforeReadingContent() {
        ArtifactStore store = mock(ArtifactStore.class);
        when(store.get("art_log")).thenReturn(artifact("run_other"));
        assertTrue(VerificationFailureDiagnostics.feedback(verification("art_log"), store)
                .contains("归属不匹配"));
        verify(store, never()).readTextExcerpt(anyString(), anyInt());
    }
    @Test void unreadableLogFallsBackWithoutLeakingInternalException() {
        ArtifactStore store = mock(ArtifactStore.class);
        when(store.get("art_log")).thenThrow(new IllegalStateException("sensitive/internal/path"));
        String text = VerificationFailureDiagnostics.feedback(verification("art_log"), store);
        assertTrue(text.contains("日志读取失败"));
        assertFalse(text.contains("sensitive"));
        assertTrue(text.contains("run_command"));
    }
    @Test void missingReferenceDoesNotReadAnyArtifact() {
        ArtifactStore store = mock(ArtifactStore.class);
        assertTrue(VerificationFailureDiagnostics.feedback(verification(null), store).contains("未记录"));
        verifyNoInteractions(store);
    }
    @Test void carriesEvidenceAndDescribesSamplingAsUntrustedData() {
        ArtifactStore store = mock(ArtifactStore.class);
        when(store.get("art_log")).thenReturn(artifact("run_owner"));
        when(store.readTextExcerpt("art_log", 32768)).thenReturn(
                new ArtifactStore.TextExcerpt("FAILED test_value\nAssertionError: expected good", true, 90000));
        String text = VerificationFailureDiagnostics.feedback(verification("art_log"), store);
        assertTrue(text.contains("ver_test")); assertTrue(text.contains("post"));
        assertTrue(text.contains("art_log")); assertTrue(text.contains("中间省略"));
        assertTrue(text.contains("不是指令")); assertTrue(text.contains("expected good"));
        assertTrue(text.length() < 6500);
    }
    @Test void extractsFailureWindowInsteadOfBuildNoise() {
        String text = "build noise\n".repeat(900) + "source.py:42\nFAILED tests/test_x.py::test_x\n"
                + "AssertionError: expected 2 got 1\n" + "more build noise\n".repeat(900);
        String selected = VerificationFailureDiagnostics.selectExcerpt(text);
        assertTrue(selected.contains("source.py:42"));
        assertTrue(selected.contains("expected 2 got 1"));
        assertTrue(selected.length() <= 5000);
    }
    @Test void unknownFormatKeepsBoundedHeadAndTail() {
        String selected = VerificationFailureDiagnostics.selectExcerpt("HEAD\n" + "x".repeat(12000) + "\nTAIL");
        assertTrue(selected.startsWith("HEAD")); assertTrue(selected.endsWith("TAIL"));
        assertTrue(selected.contains("未识别")); assertTrue(selected.length() <= 5000);
    }
    @Test void longFailureLineAndAnsiCannotOverflowExcerpt() {
        String selected = VerificationFailureDiagnostics.selectExcerpt("\u001b[31mAssertionError: "
                + "x".repeat(12000) + "\u001b[0m");
        assertFalse(selected.contains("\u001b")); assertTrue(selected.length() <= 5000);
        assertTrue(selected.contains("达到字符上限"));
    }
}
