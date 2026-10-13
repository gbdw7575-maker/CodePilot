package com.dsh.application;

import com.dsh.storage.ArtifactRepository;
import com.dsh.storage.RunRepository;
import com.dsh.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ArtifactTextPageTest {
    @TempDir Path root;
    private ArtifactStore store() {
        ArtifactRepository repo = mock(ArtifactRepository.class);
        WorkspaceManager ws = mock(WorkspaceManager.class);
        when(ws.workRoot()).thenReturn(root);
        when(repo.save(any())).thenAnswer(inv -> {
            com.dsh.storage.ArtifactEntity e = inv.getArgument(0);
            when(repo.findById(e.getId())).thenReturn(Optional.of(e));
            return e;
        });
        return new ArtifactStore(repo, ws, mock(RunRepository.class));
    }
    @Test void smallPageIsExactAndHasExplicitEnd() {
        var store = store();
        var a = store.save(null, "p", "log", "text/plain", "中文🙂".getBytes(StandardCharsets.UTF_8), true);
        var page = store.readTextPage(a.getId(), 0, 4096);
        assertEquals("中文🙂", page.text());
        assertEquals(a.getSizeBytes(), page.nextOffset());
        assertFalse(page.hasMore());
    }
    @Test void tinyPagesReconstructUtf8WithoutLossOrReplacement() {
        var store = store();
        String text = "A中文🙂é\n".repeat(40);
        var a = store.save(null, "p", "log", "text/plain", text.getBytes(StandardCharsets.UTF_8), true);
        for (int limit = 4; limit < 12; limit++) {
            StringBuilder rebuilt = new StringBuilder();
            long offset = 0;
            do {
                var page = store.readTextPage(a.getId(), offset, limit);
                assertTrue(page.nextOffset() > offset);
                assertTrue(page.text().getBytes(StandardCharsets.UTF_8).length <= limit);
                assertFalse(page.text().contains("�"));
                assertEquals(offset, page.byteOffset());
                rebuilt.append(page.text());
                offset = page.nextOffset();
                assertEquals(offset < a.getSizeBytes(), page.hasMore());
            } while (offset < a.getSizeBytes());
            assertEquals(text, rebuilt.toString());
        }
    }
    @Test void canSeekToFailureInMiddleOfLargeLog() {
        var store = store();
        String text = "x".repeat(200000) + "AssertionError: actual != expected" + "z".repeat(200000);
        var a = store.save(null, "p", "log", "text/plain", text.getBytes(StandardCharsets.UTF_8), true);
        var page = store.readTextPage(a.getId(), 200000, 128);
        assertTrue(page.text().startsWith("AssertionError"));
        assertEquals(200128, page.nextOffset());
        assertTrue(page.hasMore());
    }
    @Test void eofAndEmptyArtifactDoNotInventMoreData() {
        var store = store();
        var a = store.save(null, "p", "log", "text/plain", new byte[0], true);
        var page = store.readTextPage(a.getId(), 0, 4);
        assertEquals("", page.text()); assertEquals(0, page.nextOffset()); assertFalse(page.hasMore());
        assertThrows(IllegalArgumentException.class, () -> store.readTextPage(a.getId(), 1, 4));
        assertThrows(IllegalArgumentException.class, () -> store.readTextPage(a.getId(), -1, 4));
        assertThrows(IllegalArgumentException.class, () -> store.readTextPage(a.getId(), 0, 3));
        assertThrows(IllegalArgumentException.class, () -> store.readTextPage(a.getId(), 0, 16385));
    }
    @Test void rejectsMalformedUtf8NulAndCursorInsideCharacter() {
        var store = store();
        for (byte[] bytes : java.util.List.of(new byte[]{(byte) 0xff}, new byte[]{65, 0, 66},
                "中".getBytes(StandardCharsets.UTF_8))) {
            var a = store.save(null, "p", "log", "text/plain", bytes, true);
            long offset = bytes.length == 3 && bytes[0] != 65 ? 1 : 0;
            assertThrows(RuntimeException.class, () -> store.readTextPage(a.getId(), offset, 4));
        }
    }
    @Test void refusesSymlinkLeafEvenIfItPointsToReadableText() throws Exception {
        var store = store();
        var a = store.save(null, "p", "log", "text/plain", "safe".getBytes(StandardCharsets.UTF_8), true);
        Path file = root.resolve("projects/p/artifacts/" + a.getId());
        Path outside = Files.writeString(root.resolve("outside"), "private");
        Files.delete(file); Files.createSymbolicLink(file, outside);
        assertThrows(RuntimeException.class, () -> store.readTextPage(a.getId(), 0, 4096));
    }
    @Test void refusesSymlinkDirectory() throws Exception {
        var store = store();
        var a = store.save(null, "p", "log", "text/plain", "safe".getBytes(StandardCharsets.UTF_8), true);
        Path dir = root.resolve("projects/p/artifacts");
        Files.move(dir, root.resolve("moved"));
        Files.createSymbolicLink(dir, root.resolve("moved"));
        assertThrows(RuntimeException.class, () -> store.readTextPage(a.getId(), 0, 4096));
    }
}
