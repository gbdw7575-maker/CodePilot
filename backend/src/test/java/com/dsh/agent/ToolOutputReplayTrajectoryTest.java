package com.dsh.agent;

import com.dsh.agent.model.ModelMessage;
import com.dsh.application.ArtifactStore;
import com.dsh.storage.*;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Public C24 tool prefixes only: not original ModelRequests or supplier token usage. */
class ToolOutputReplayTrajectoryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> REPORTS = new TreeMap<>();
    @TempDir Path root;

    record Replay(JsonNode fixture, List<MessageEntity> stored, LoopSteps steps, Path artifacts) {}

    Replay load(String arm) throws Exception {
        JsonNode fixture;
        try (var in = getClass().getResourceAsStream("/replay-c25/" + arm + ".json")) {
            assertNotNull(in);
            fixture = MAPPER.readTree(in);
        }
        assertEquals(1, fixture.path("schemaVersion").asInt());
        assertEquals("PUBLIC_TOOL_HISTORY_BEFORE_WINDOW_WATERMARK", fixture.path("scope").asText());
        String run = fixture.path("runId").asText();
        var repository = mock(ArtifactRepository.class);
        var workspaces = mock(WorkspaceManager.class);
        when(workspaces.workRoot()).thenReturn(root);
        var runs = mock(RunRepository.class);
        var entity = mock(RunEntity.class);
        when(entity.getWorkspaceRef()).thenReturn("replay");
        when(runs.findById(run)).thenReturn(Optional.of(entity));
        when(workspaces.resolveByRef("replay")).thenReturn(new WorkspaceManager.WorkspacePaths(root, root.resolve("meta")));
        Path dir = root.resolve("meta/artifacts");
        Files.createDirectories(dir);
        Map<String, ArtifactEntity> metadata = new HashMap<>();
        for (JsonNode a : fixture.path("artifacts")) {
            String id = a.path("id").asText();
            metadata.put(id, new ArtifactEntity(id, a.path("runId").asText(), a.path("projectId").asText(),
                    a.path("name").asText(), a.path("mediaType").asText(), a.path("sizeBytes").asLong(),
                    a.path("sha256").asText(), Instant.EPOCH, a.path("downloadable").asBoolean()));
            try (var in = getClass().getResourceAsStream("/replay-c25/artifacts/" + id)) {
                assertNotNull(in);
                Files.copy(in, dir.resolve(id));
            }
        }
        when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(metadata.get(i.getArgument(0))));
        // Only repository/workspace lookup are mocked. Formatting, projection and streamed integrity are real.
        var store = new ArtifactStore(repository, workspaces, runs);
        for (String id : metadata.keySet()) assertTrue(store.verifyIntegrity(id, HistoryCheckpoint.MAX_RECOVERY_BYTES));
        var steps = mock(LoopSteps.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(steps, "objectMapper", MAPPER);
        ReflectionTestUtils.setField(steps, "artifactStore", store);
        List<MessageEntity> stored = new ArrayList<>();
        long previous = 0;
        for (JsonNode m : fixture.path("messages")) {
            long sequence = m.path("sequence").asLong();
            assertTrue(sequence > previous); previous = sequence;
            String role = m.path("role").asText();
            assertTrue(Set.of("assistant_tool", "tool").contains(role));
            JsonNode content;
            if (role.equals("assistant_tool")) {
                ObjectNode batch = MAPPER.createObjectNode();
                batch.set("calls", m.path("calls")); content = batch;
            } else content = m.path("result");
            stored.add(new MessageEntity("msg_" + sequence, "thread", "turn", role,
                    content.toString(), sequence, Instant.EPOCH));
        }
        return new Replay(fixture, stored, steps, dir);
    }

    List<ModelMessage> full(Replay r, List<MessageEntity> prefix, HistoryCheckpoint.Context context) {
        return ReflectionTestUtils.invokeMethod(r.steps(), "buildMessagesFromStored", prefix, false, 0, context);
    }

    List<ModelMessage> fold(Replay r, List<ModelMessage> history, HistoryCheckpoint.Context context, int tail) {
        return r.steps().foldReplayOutputs(r.fixture().path("runId").asText(),
                r.fixture().path("projectId").asText(), history, context, tail);
    }

    @ParameterizedTest @ValueSource(strings = {"A", "B"})
    void replayEveryPublicPrefixWithIdenticalTrajectory(String arm) throws Exception {
        Replay r = load(arm);
        List<String> original = r.stored().stream().map(MessageEntity::getContent).toList();
        Map<String, byte[]> bytes = new HashMap<>();
        for (JsonNode a : r.fixture().path("artifacts")) {
            String id = a.path("id").asText(); bytes.put(id, Files.readAllBytes(r.artifacts().resolve(id)));
        }
        List<Integer> bounds = new ArrayList<>();
        for (int i = 0; i < r.stored().size(); i++)
            if (r.stored().get(i).getRole().equals("assistant_tool")) bounds.add(i);
        bounds.add(r.stored().size());
        assertEquals(r.fixture().path("requestCount").asInt(), bounds.size());
        List<Map<String, Object>> samples = new ArrayList<>();
        long offSum = 0, onSum = 0, charOffSum = 0, charOnSum = 0;
        int affected = 0, foldedSum = 0;
        for (int bound : bounds) {
            var prefix = r.stored().subList(0, bound);
            var context = new HistoryCheckpoint.Context(MAPPER, prefix);
            var baseline = full(r, prefix, context);
            assertSame(baseline, fold(r, baseline, context, 0));
            var projected = fold(r, baseline, context, 5);
            assertEquals(projected, fold(r, baseline, context, 5));
            assertEquals(baseline.size(), projected.size());
            List<Integer> batchStarts = new ArrayList<>();
            for (int i = 0; i < baseline.size(); i++)
                if (baseline.get(i).role().equals("assistant")) batchStarts.add(i);
            int protectedStart = batchStarts.size() > 5 ? batchStarts.get(batchStarts.size() - 5) : 0;
            int folded = 0;
            List<Long> changedSequences = new ArrayList<>();
            for (int i = 0; i < baseline.size(); i++) {
                var before = baseline.get(i); var after = projected.get(i);
                assertEquals(before.role(), after.role());
                assertEquals(before.toolCallId(), after.toolCallId());
                assertEquals(before.toolName(), after.toolName());
                assertEquals(before.toolCalls(), after.toolCalls());
                assertEquals(before.reasoningContent(), after.reasoningContent());
                if (i >= protectedStart || !before.role().equals("tool")) assertSame(before, after);
                if (before != after) {
                    folded++; changedSequences.add(prefix.get(i).getSequence());
                    assertTrue(TokenEstimator.estimateMessage(after) < TokenEstimator.estimateMessage(before));
                    JsonNode raw = MAPPER.readTree(prefix.get(i).getContent());
                    assertTrue(after.content().contains(raw.path("fullOutputArtifactId").asText()));
                    if (raw.hasNonNull("structuredContent")) assertTrue(after.content().contains(raw.get("structuredContent").toString()));
                }
            }
            int off = TokenEstimator.estimateMessages(baseline), on = TokenEstimator.estimateMessages(projected);
            long offChars = baseline.stream().mapToLong(m -> m.content().length()).sum();
            long onChars = projected.stream().mapToLong(m -> m.content().length()).sum();
            assertTrue(on <= off);
            offSum += off; onSum += on; charOffSum += offChars; charOnSum += onChars;
            foldedSum += folded; if (folded > 0) affected++;
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("prefix", samples.size() + 1); sample.put("publicMessages", bound);
            sample.put("lastSequence", bound == 0 ? null : prefix.getLast().getSequence());
            sample.put("rawEstimatedTokensOff", off); sample.put("rawEstimatedTokensOn", on);
            sample.put("contentCharsOff", offChars); sample.put("contentCharsOn", onChars);
            sample.put("foldedResultCount", folded); sample.put("changedSequences", changedSequences);
            samples.add(sample);
        }
        if (arm.equals("A")) { assertEquals(0, affected); assertEquals(offSum, onSum); }
        else { assertTrue(affected > 0); assertTrue(onSum < offSum); }
        assertEquals(original, r.stored().stream().map(MessageEntity::getContent).toList());
        for (var e : bytes.entrySet()) assertArrayEquals(e.getValue(), Files.readAllBytes(r.artifacts().resolve(e.getKey())));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", r.fixture().path("scope").asText());
        report.put("sourceSha256", r.fixture().path("sourceSha256").asText());
        report.put("prefixCount", bounds.size()); report.put("affectedPrefixes", affected);
        report.put("foldedResultOccurrences", foldedSum);
        report.put("rawEstimatedTokensOffSum", offSum); report.put("rawEstimatedTokensOnSum", onSum);
        report.put("rawEstimatedTokenSavingSum", offSum - onSum);
        report.put("contentCharsOffSum", charOffSum); report.put("contentCharsOnSum", charOnSum);
        report.put("prefixes", samples); REPORTS.put(arm, report);
        System.out.println("C25_PUBLIC_REPLAY " + arm + " prefixes=" + bounds.size() + " affected=" + affected
                + " raw_estimate_off=" + offSum + " on=" + onSum + " saved=" + (offSum - onSum));
    }

    @ParameterizedTest @ValueSource(strings = {"missing-text", "tampered-text", "missing-structured", "tampered-structured"})
    void unusableRealArtifactFallsBackWithoutLosingOriginalObservation(String fault) throws Exception {
        Replay r = load("B");
        int index = -1;
        for (int i = 0; i < r.stored().size(); i++) {
            if (r.stored().get(i).getRole().equals("tool") && MAPPER.readTree(r.stored().get(i).getContent()).hasNonNull("fullOutputArtifactId")) {
                index = i; break;
            }
        }
        assertTrue(index >= 0);
        JsonNode raw = MAPPER.readTree(r.stored().get(index).getContent());
        String id = raw.path(fault.endsWith("structured") ? "structuredArtifactId" : "fullOutputArtifactId").asText();
        assertFalse(id.isEmpty());
        var context = new HistoryCheckpoint.Context(MAPPER, r.stored());
        var baseline = full(r, r.stored(), context);
        assertNotEquals(baseline.get(index), fold(r, baseline, context, 5).get(index));
        Path file = r.artifacts().resolve(id);
        if (fault.startsWith("missing")) Files.delete(file);
        else { byte[] bytes = Files.readAllBytes(file); bytes[0] ^= 1; Files.write(file, bytes); }
        assertSame(baseline.get(index), fold(r, baseline, context, 5).get(index));
        assertEquals(raw.toString(), r.stored().get(index).getContent());
    }

    @AfterAll static void writeOptionalReport() throws Exception {
        String output = System.getProperty("c25.replay.report");
        if (output != null) {
            assertEquals(Set.of("A", "B"), REPORTS.keySet());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(Path.of(output).toFile(), REPORTS);
        }
    }
}
