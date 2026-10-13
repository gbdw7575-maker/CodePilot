package com.dsh.sandbox;

import com.dsh.workspace.WorkspaceLease;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.dsh.sandbox.TaskCheckExecutionOutcome.Presence.*;
import static org.junit.jupiter.api.Assertions.*;

class DockerTaskCheckRecoveryTest {
    final String operation = "1".repeat(32), id = "a".repeat(64), other = "b".repeat(64);
    final AtomicLong time = new AtomicLong(100);
    final Fake client = new Fake();
    TaskCheckCleanupObservation run(String expected) {
        return new DockerTaskCheckRecovery(client, time::get).cleanup(operation, expected);
    }
    @AfterEach void clearInterrupted() { Thread.interrupted(); }

    final class Fake implements DockerTaskCheckExecutor.Client {
        final List<List<String>> calls = new ArrayList<>();
        final List<Long> deadlines = new ArrayList<>();
        boolean present = true, remove = true, nameMatch = true, labelMatch = true;
        String inspectId = id, inspectName = "/dsh-check-" + operation, inspectLabel = operation;
        String inspectOverride;
        int failAt, rmCode, inspectCode;
        String failure = "exit";
        boolean duplicateLabel, lostBeforeInspect, replacementAfterRemove, renameAfterRemove;
        DockerTaskCheckExecutor.Reply text(int code, String value) {
            var bytes = value.getBytes(StandardCharsets.UTF_8);
            return new DockerTaskCheckExecutor.Reply(code, bytes, new byte[0], bytes.length, 0, false);
        }
        @Override public DockerTaskCheckExecutor.Reply run(List<String> argv, byte[] input, int limit,
                long deadline, java.util.function.BooleanSupplier cancelled) throws Exception {
            calls.add(argv); deadlines.add(deadline);
            assertEquals(0, input.length); assertFalse(cancelled.getAsBoolean());
            if (calls.size() == failAt) {
                return switch (failure) {
                    case "exit" -> text(1, "daemon failure");
                    case "truncated" -> new DockerTaskCheckExecutor.Reply(0, new byte[0], new byte[0], 1, 1, true);
                    case "dropped" -> new DockerTaskCheckExecutor.Reply(0, new byte[0], new byte[0], 1, 1, false);
                    case "malformed" -> text(0, "a\n");
                    case "duplicate" -> text(0, id + "\n" + id + "\n");
                    case "timeout" -> { time.addAndGet(15_000_000_000L); yield text(0, ""); }
                    case "interrupted" -> throw new InterruptedException();
                    default -> throw new java.io.IOException("unavailable");
                };
            }
            if (argv.get(1).equals("ps")) {
                String filter = argv.get(5); String result = "";
                if (filter.startsWith("name=") && present && nameMatch) result = id + "\n";
                if (filter.startsWith("label=") && present && labelMatch)
                    result = id + "\n" + (duplicateLabel ? other + "\n" : "");
                if (filter.startsWith("id=") && present && filter.equals("id=" + id)) result = id + "\n";
                if (replacementAfterRemove && !present && !filter.startsWith("id=")) result = other + "\n";
                return text(0, result);
            }
            if (argv.get(1).equals("inspect")) {
                if (lostBeforeInspect) { present = false; return text(1, "no container"); }
                if (inspectOverride != null) return text(0, inspectOverride);
                return text(inspectCode, new ObjectMapper().writeValueAsString(List.of(Map.of(
                        "Id", inspectId, "Name", inspectName, "Config", Map.of("Labels", Map.of("dsh.check-operation", inspectLabel))))));
            }
            if (argv.get(1).equals("rm")) {
                assertEquals(List.of("docker", "rm", "-f", id), argv);
                if (remove) present = false;
                if (renameAfterRemove) { present = true; nameMatch = false; labelMatch = false; }
                return text(rmCode, id + "\n");
            }
            fail("Unapproved recovery command: " + argv); return null;
        }
        long removals() { return calls.stream().filter(c -> c.get(1).equals("rm")).count(); }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void removesByVerifiedFullIdAndRechecks(boolean known) {
        var result = run(known ? id : null);
        assertEquals(ABSENT, result.presence()); assertEquals(id, result.containerId());
        assertEquals(operation, result.operationId()); assertEquals("ABSENCE_OBSERVED", result.diagnostic());
        assertEquals(1, client.removals()); assertEquals(1, new HashSet<>(client.deadlines).size());
        assertEquals(15_000_000_100L, client.deadlines.getFirst());
        assertFalse(result.successAuthority()); assertFalse(result.releaseAuthority());
        assertTrue(client.calls.stream().allMatch(c -> Set.of("ps", "inspect", "rm").contains(c.get(1))));
    }
    @Test void absentDoesNotInspectOrDelete() {
        client.present = false; assertEquals(ABSENT, run(id).presence());
        assertEquals(3, client.calls.size()); assertEquals(0, client.removals());
    }
    @Test void disappearingBeforeInspectIsRechecked() {
        client.lostBeforeInspect = true; assertEquals(ABSENT, run(null).presence());
        assertEquals(6, client.calls.size()); assertEquals(0, client.removals());
    }
    @Test void inspectFailureWithLiveContainerIsUnknown() {
        client.inspectCode = 1; assertEquals(UNKNOWN, run(id).presence()); assertEquals(0, client.removals());
    }
    @Test void rmZeroWithoutRemovalIsPresent() {
        client.remove = false; assertEquals(PRESENT, run(id).presence()); assertEquals(1, client.removals());
    }
    @Test void rmFailureWithConfirmedAbsenceIsOnlyAnObservation() {
        client.rmCode = 1; assertEquals(ABSENT, run(id).presence()); assertEquals(1, client.removals());
    }
    @ParameterizedTest @ValueSource(strings = {"name", "label", "id", "duplicate", "alias", "expected"})
    void refusesWrongIdentityWithoutDeleting(String mutation) {
        switch (mutation) {
            case "name" -> client.inspectName = "/foreign";
            case "label" -> client.inspectLabel = "2".repeat(32);
            case "id" -> client.inspectId = other;
            case "duplicate" -> client.duplicateLabel = true;
            case "alias" -> client.nameMatch = false;
            case "expected" -> { }
        }
        assertEquals(UNKNOWN, run(mutation.equals("expected") ? other : id).presence());
        assertEquals(0, client.removals());
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "[]", "[{},{}]", "[null]", "null", "[{}]{}", "[{\"Id\":\"a\",\"Id\":\"b\"}]"})
    void invalidInspectDoesNotDelete(String raw) {
        client.inspectOverride = raw; assertEquals(UNKNOWN, run(id).presence()); assertEquals(0, client.removals());
    }
    @ParameterizedTest @CsvSource({"1,exit", "2,exit", "3,exit", "1,malformed", "1,duplicate", "1,truncated",
            "1,dropped", "4,truncated", "5,truncated", "6,exit", "7,exit", "8,exit", "8,truncated", "1,timeout", "8,timeout", "1,io"})
    void failedTransportNeverProvesAbsence(int at, String failure) {
        client.failAt = at; client.failure = failure;
        assertEquals(UNKNOWN, run(id).presence());
        assertEquals(at < 5 ? 0 : 1, client.removals());
    }
    @Test void replacementAfterDeletionIsNotDeleted() {
        client.replacementAfterRemove = true; assertEquals(UNKNOWN, run(null).presence());
        assertEquals(1, client.removals());
    }
    @Test void renamingDuringRemovalDoesNotProveAbsence() {
        client.renameAfterRemove = true; assertEquals(UNKNOWN, run(null).presence());
        assertEquals(1, client.removals());
    }
    @Test void preexistingInterruptDoesNotSkipCleanupAndIsRestored() {
        Thread.currentThread().interrupt(); var result = run(id);
        assertEquals(ABSENT, result.presence()); assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(1, client.removals());
    }
    @Test void newInterruptIsUnknownAndPreserved() {
        client.failAt = 1; client.failure = "interrupted";
        assertEquals(UNKNOWN, run(id).presence()); assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(0, client.removals());
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"../bad", "x", "A"})
    void rejectsInvalidOperationBeforeCli(String operation) {
        assertThrows(IllegalArgumentException.class, () -> new DockerTaskCheckRecovery(client, time::get).cleanup(operation, id));
        assertTrue(client.calls.isEmpty());
    }
    @Test void rejectsShortContainerIdAndDefaultRunnerNeverFallsBack() {
        assertThrows(IllegalArgumentException.class, () -> run("a")); assertTrue(client.calls.isEmpty());
        SandboxRunner runner = new SandboxRunner() {
            public String mode() { return "fake"; }
            public CommandOutcome execute(CommandSpec spec, WorkspaceLease lease) { fail("No command fallback"); return null; }
        };
        assertThrows(UnsupportedOperationException.class, () -> runner.cleanupCheck(operation, id));
    }
}
