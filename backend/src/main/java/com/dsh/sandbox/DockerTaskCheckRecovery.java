package com.dsh.sandbox;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.LongSupplier;
import static com.dsh.sandbox.TaskCheckExecutionOutcome.Presence;

/** Bounded recovery of one trusted journal operation. No launch, report, task state or root release. */
final class DockerTaskCheckRecovery {
    private final DockerTaskCheckExecutor.Client client;
    private final LongSupplier clock;
    private final ObjectMapper json = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    DockerTaskCheckRecovery() { this(new CheckDockerProcess(), System::nanoTime); }
    DockerTaskCheckRecovery(DockerTaskCheckExecutor.Client client, LongSupplier clock) {
        this.client = Objects.requireNonNull(client);
        this.clock = Objects.requireNonNull(clock);
    }

    TaskCheckCleanupObservation cleanup(String operationId, String expectedContainerId) {
        if (operationId == null || !operationId.matches("[a-f0-9]{32}")
                || expectedContainerId != null && !expectedContainerId.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid internal check cleanup identity");
        long started = clock.getAsLong(), deadline = started + 15_000_000_000L;
        String actualId = expectedContainerId, diagnostic = "CONTROL_UNAVAILABLE";
        Presence presence = Presence.UNKNOWN;
        boolean interrupted = Thread.interrupted();
        try {
            var before = candidates(operationId, expectedContainerId, deadline);
            if (before.isEmpty()) {
                presence = Presence.ABSENT;
                diagnostic = "ABSENCE_OBSERVED";
            } else {
                require(before.size() == 1, "IDENTITY_CONFLICT");
                actualId = before.iterator().next();
                require(expectedContainerId == null || expectedContainerId.equals(actualId), "IDENTITY_CONFLICT");
                var inspected = call(List.of("docker", "inspect", actualId), 1_048_576, deadline);
                if (inspected.code() != 0) {
                    // inspect exit=1 can mean daemon failure. Only complete successful lists prove absence.
                    require(candidates(operationId, actualId, deadline).isEmpty(), "INSPECT_UNAVAILABLE");
                    presence = Presence.ABSENT;
                    diagnostic = "ABSENCE_OBSERVED";
                } else {
                    var array = json.readTree(inspected.stdout());
                    require(array != null && array.isArray() && array.size() == 1 && array.get(0).isObject(), "INVALID_INSPECT");
                    var node = array.get(0);
                    require(node.path("Id").isTextual() && actualId.equals(node.path("Id").asText())
                            && node.path("Name").isTextual()
                            && ("/dsh-check-" + operationId).equals(node.path("Name").asText())
                            && node.path("Config").path("Labels").path("dsh.check-operation").isTextual()
                            && operationId.equals(node.path("Config").path("Labels").path("dsh.check-operation").asText()),
                            "OWNERSHIP_MISMATCH");
                    // Never remove by name: a newly created replacement must not inherit the old operation's deletion.
                    call(List.of("docker", "rm", "-f", actualId), 4096, deadline);
                    var after = candidates(operationId, actualId, deadline);
                    require(after.isEmpty() || after.equals(Set.of(actualId)), "IDENTITY_CONFLICT");
                    presence = after.isEmpty() ? Presence.ABSENT : Presence.PRESENT;
                    diagnostic = after.isEmpty() ? "ABSENCE_OBSERVED" : "REMOVE_NOT_CONFIRMED";
                }
            }
        } catch (InterruptedException stopped) {
            interrupted = true;
            diagnostic = "CONTROL_INTERRUPTED";
        } catch (Refusal refused) {
            diagnostic = refused.getMessage();
        } catch (DockerTaskCheckExecutor.Stop stopped) {
            diagnostic = "CONTROL_" + stopped.termination.name();
        } catch (Exception unavailable) {
            diagnostic = "CONTROL_UNAVAILABLE";
        } finally {
            interrupted |= Thread.interrupted();
            if (interrupted) Thread.currentThread().interrupt();
        }
        return new TaskCheckCleanupObservation(operationId, actualId, presence, diagnostic,
                Math.max(0, (clock.getAsLong() - started) / 1_000_000L));
    }

    private Set<String> candidates(String operation, String expected, long deadline) throws Exception {
        var names = listed("name=^/dsh-check-" + operation + "$", deadline);
        var labels = listed("label=dsh.check-operation=" + operation, deadline);
        // A shared operation label on a different name is not a deletion permit.
        require(names.equals(labels), "IDENTITY_CONFLICT");
        if (expected != null) {
            var ids = listed("id=" + expected, deadline);
            require(ids.isEmpty() || ids.equals(Set.of(expected)), "IDENTITY_CONFLICT");
            require(names.equals(ids), "IDENTITY_CONFLICT");
        }
        require(names.size() <= 1, "IDENTITY_CONFLICT");
        return names;
    }

    private Set<String> listed(String selector, long deadline) throws Exception {
        var reply = call(List.of("docker", "ps", "-a", "--no-trunc", "--filter", selector,
                "--format", "{{.ID}}"), 4096, deadline);
        require(reply.code() == 0, "LIST_UNAVAILABLE");
        var ids = new HashSet<String>();
        for (String line : reply.text().lines().toList()) {
            require(line.matches("[a-f0-9]{64}") && ids.add(line), "INVALID_LIST");
        }
        return Set.copyOf(ids);
    }

    private DockerTaskCheckExecutor.Reply call(List<String> argv, int limit, long deadline) throws Exception {
        guard(deadline);
        var reply = client.run(List.copyOf(argv), new byte[0], limit, deadline, () -> false);
        guard(deadline);
        require(reply != null && !reply.truncated() && reply.dropped() == 0
                && reply.stdout().length <= limit && reply.stderr().length <= limit, "INCOMPLETE_CONTROL");
        return reply;
    }

    private void guard(long deadline) throws DockerTaskCheckExecutor.Stop {
        if (Thread.currentThread().isInterrupted())
            throw new DockerTaskCheckExecutor.Stop(TaskCheckExecutionOutcome.Termination.CANCELLED);
        if (clock.getAsLong() - deadline >= 0)
            throw new DockerTaskCheckExecutor.Stop(TaskCheckExecutionOutcome.Termination.TIMEOUT);
    }
    private static final class Refusal extends Exception {
        Refusal(String diagnostic) { super(diagnostic); }
    }
    private static void require(boolean ok, String diagnostic) throws Refusal {
        if (!ok) throw new Refusal(diagnostic);
    }
}
