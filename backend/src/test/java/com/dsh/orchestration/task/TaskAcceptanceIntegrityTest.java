package com.dsh.orchestration.task;

import com.dsh.orchestration.SubagentResult;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.VerificationEntity;
import com.dsh.storage.VerificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskAcceptanceIntegrityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Stream<Arguments> damagedAcceptance() {
        List<String> invalid = java.util.Arrays.asList(null, "", " \t", "null", "[]", "{}",
                "\"check\"", "[\"check\"", "[null]", "[\"\"]", "[\" \t\"]", "[1]", "[true]",
                "[{}]", "[[]]", "[\"check\",null]", "[\"check\"] []", "[\"check\"] null",
                "[\"check\"] garbage", json(List.of("x".repeat(201))),
                json(java.util.Collections.nCopies(9, "check")));
        return Stream.of("KEYWORD", "IDENTIFIER").flatMap(mode -> invalid.stream()
                .flatMap(raw -> Stream.of(false, true).map(record -> Arguments.of(mode, raw, record))));
    }

    @ParameterizedTest(name = "mode={0}, acceptance={1}, verificationRecord={2}")
    @MethodSource("damagedAcceptance")
    void damagedAcceptanceCannotPassWithPatchOrPassedRecord(String mode, String raw, boolean record) {
        VerificationRepository repository = repository(record ? verification("PASSED", false) : null);
        var outcome = new TaskVerificationService(repository, MAPPER, mode)
                .verify(task(raw), result("check 1 true x"));
        assertFalse(outcome.passed(), "Damaged frozen requirements must never become empty coverage");
        assertEquals("EVIDENCE_MISSING", outcome.reasonCode());
        assertNull(outcome.verificationRef());
        verifyNoInteractions(repository);
    }

    static Stream<Arguments> validAcceptance() {
        return Stream.of("KEYWORD", "IDENTIFIER").flatMap(mode -> Stream.of(
                List.of("check"), List.of("验收通过"), List.of("check \"中文\" \\ 😀"),
                List.of("x".repeat(200)), java.util.Collections.nCopies(8, "check"))
                .flatMap(items -> Stream.of(false, true)
                        .map(record -> Arguments.of(mode, items, record))));
    }

    @ParameterizedTest
    @MethodSource("validAcceptance")
    void validOriginalRequirementsAndBoundaryCasesKeepTheirBehavior(String mode, List<String> items,
                                                                   boolean record) {
        VerificationRepository repository = repository(record ? verification("PASSED", false) : null);
        var outcome = new TaskVerificationService(repository, MAPPER, mode)
                .verify(task(" \n" + json(items) + " \t"), result(String.join(" ", items)));
        assertTrue(outcome.passed(), outcome.detail());
        assertEquals(record ? "verification-child" : null, outcome.verificationRef());
    }

    static Stream<Arguments> failedEvidence() {
        return Stream.of("KEYWORD", "IDENTIFIER").flatMap(mode -> Stream.of(
                Arguments.of(mode, "PASSED", true), Arguments.of(mode, "FAILED", false)));
    }

    @ParameterizedTest
    @MethodSource("failedEvidence")
    void validAcceptanceCannotOverrideFailedOrStaleVerification(String mode, String status, boolean stale) {
        var outcome = new TaskVerificationService(repository(verification(status, stale)), MAPPER, mode)
                .verify(task(json(List.of("check"))), result("check"));
        assertFalse(outcome.passed());
        assertEquals("VERIFICATION_FAILED", outcome.reasonCode());
    }

    static Stream<String> modes() { return Stream.of("KEYWORD", "IDENTIFIER"); }

    @ParameterizedTest
    @MethodSource("modes")
    void validUncoveredRequirementStillFails(String mode) {
        var outcome = new TaskVerificationService(repository(null), MAPPER, mode)
                .verify(task(json(List.of("distinct_required_behavior"))), result("other change"));
        assertFalse(outcome.passed());
        assertEquals("VERIFICATION_FAILED", outcome.reasonCode());
    }

    private static VerificationRepository repository(VerificationEntity value) {
        var repository = mock(VerificationRepository.class);
        when(repository.findByRunIdOrderByStartedAtDesc(eq("child"), any()))
                .thenReturn(new PageImpl<>(value == null ? List.of() : List.of(value)));
        clearInvocations(repository);
        return repository;
    }

    private static VerificationEntity verification(String status, boolean stale) {
        Instant now = Instant.now();
        return new VerificationEntity("verification-child", "child", "v1", "workspace", "workspace",
                "image", "check", status, status.equals("PASSED") ? 0 : 1,
                1, status.equals("PASSED") ? 1 : 0, 0, stale, "[]", now, now);
    }

    private static TaskEntity task(String raw) {
        Instant now = Instant.now();
        return new TaskEntity("task-row", "plan", "task", 1, "goal", "[]", "[]", "deliverable",
                raw, null, "DELEGATED", true, "VERIFYING", now, now);
    }

    private static SubagentResult result(String summary) {
        return new SubagentResult("child", "worker", "COMPLETED", "MODEL_FINISHED", summary,
                List.of("patch"), "patch", "workspace", "digest", 1, 1, 100, 1);
    }

    private static String json(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
