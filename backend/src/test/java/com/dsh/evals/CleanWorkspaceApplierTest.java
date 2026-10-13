package com.dsh.evals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dsh.api.dto.DiffFile;

/**
 * CleanWorkspaceApplier: retry on transient denial + checked-exception propagation.
 *
 * <p>Two defects fixed on 2026-10-06, both found by reading the code rather than by a
 * reproducer (the AccessDeniedException seen in ext-sphinx-doc__sphinx-10466 was on the
 * agent's write_file path, not proven to be here):</p>
 * <ol>
 *   <li>visitFileFailed threw UncheckedIOException, which bypasses apply()'s catch (IOException)
 *       - the half-built clean workspace was never removed and the message carried no path.</li>
 *   <li>Single-copy failures had no retry, so a transiently held file failed the whole grading prep.</li>
 * </ol>
 */
class CleanWorkspaceApplierTest {

    @Test
    void copiesBaselineThenAppliesOnlyFinalDelta(@TempDir Path tmp) throws Exception {
        Path baseline = tmp.resolve("baseline");
        Path agent = tmp.resolve("agent");
        Files.createDirectories(baseline.resolve("pkg"));
        Files.writeString(baseline.resolve("pkg/a.py"), "old-a");
        Files.writeString(baseline.resolve("pkg/b.py"), "old-b");
        Files.createDirectories(agent.resolve("pkg"));
        Files.writeString(agent.resolve("pkg/a.py"), "new-a");
        Files.writeString(agent.resolve("pkg/c.py"), "new-c");

        CleanWorkspaceApplier applier = new CleanWorkspaceApplier(tmp.resolve("work"));
        Path clean = applier.apply(baseline, agent, List.of(
                new DiffFile("pkg/a.py", "MODIFIED", null, false),
                new DiffFile("pkg/c.py", "ADDED", null, false),
                new DiffFile("pkg/b.py", "DELETED", null, false)));

        assertEquals("new-a", Files.readString(clean.resolve("pkg/a.py")));
        assertEquals("new-c", Files.readString(clean.resolve("pkg/c.py")));
        assertFalse(Files.exists(clean.resolve("pkg/b.py")), "DELETED must not survive from the baseline");
    }

    @Test
    void failureIsCheckedAndWorkspaceIsNotLeftBehind(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope");
        Path agent = tmp.resolve("agent");
        CleanWorkspaceApplier applier = new CleanWorkspaceApplier(tmp.resolve("work"));

        // apply() rethrows as UncheckedIOException; what matters is that it got there through the
        // catch (IOException) branch, i.e. cleanup ran and the partial workspace is gone.
        UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                () -> applier.apply(missing, agent, List.of()));
        assertTrue(thrown.getMessage().contains("evalws_"), () -> "unexpected message: " + thrown.getMessage());
        assertEquals(0, countEntries(tmp.resolve("work")), "clean workspace must be removed on failure");
    }

    @Test
    void missingSourceFailsImmediately(@TempDir Path tmp) {
        Path source = tmp.resolve("src.txt");
        Path target = tmp.resolve("out/dst.txt");
        assertThrows(NoSuchFileException.class, () -> CleanWorkspaceApplier.copyFile(source, target),
                "a genuinely missing source must fail immediately, not be retried into slowness");
    }

    @Test
    void copyFileSucceedsOnFirstTry(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve("src.txt");
        Files.write(source, "payload".getBytes(StandardCharsets.UTF_8));
        Path target = tmp.resolve("nested/dst.txt");

        CleanWorkspaceApplier.copyFile(source, target);

        assertEquals("payload", Files.readString(target), "nested target directories must be created");
    }

    private static long countEntries(Path dir) {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return stream.count();
        } catch (IOException e) {
            return -1;
        }
    }
}
