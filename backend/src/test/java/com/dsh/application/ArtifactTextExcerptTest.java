package com.dsh.application;

import com.dsh.storage.ArtifactRepository;
import com.dsh.storage.RunRepository;
import com.dsh.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArtifactTextExcerptTest {
    @TempDir Path root;
    private ArtifactStore store() {
        ArtifactRepository repo = mock(ArtifactRepository.class);
        WorkspaceManager workspaces = mock(WorkspaceManager.class);
        when(workspaces.workRoot()).thenReturn(root);
        when(repo.save(any())).thenAnswer(invocation -> {
            com.dsh.storage.ArtifactEntity entity = invocation.getArgument(0);
            when(repo.findById(entity.getId())).thenReturn(Optional.of(entity));
            return entity;
        });
        return new ArtifactStore(repo, workspaces, mock(RunRepository.class));
    }
    @Test void smallUtf8LogIsPreservedExactly() {
        var store = store(); String text = "测试失败\nAssertionError: 1 != 2";
        var artifact = store.save(null, "proj", "log", "text/plain", text.getBytes(StandardCharsets.UTF_8), true);
        var excerpt = store.readTextExcerpt(artifact.getId(), 1024);
        assertEquals(text, excerpt.text()); assertFalse(excerpt.truncated());
        assertEquals(artifact.getSizeBytes(), excerpt.sizeBytes());
    }
    @Test void largeLogReturnsOnlyHeadTailWithExplicitOmission() {
        var store = store(); String text = "HEAD" + "x".repeat(200000) + "TAIL";
        var artifact = store.save(null, "proj", "log", "text/plain", text.getBytes(StandardCharsets.UTF_8), true);
        var excerpt = store.readTextExcerpt(artifact.getId(), 1000);
        assertTrue(excerpt.truncated()); assertEquals(200008, excerpt.sizeBytes());
        assertTrue(excerpt.text().startsWith("HEAD")); assertTrue(excerpt.text().endsWith("TAIL"));
        assertTrue(excerpt.text().contains("中间部分省略")); assertTrue(excerpt.text().length() < 1100);
        assertEquals(text, new String(store.readContent(artifact.getId()), StandardCharsets.UTF_8));
    }
    @Test void rejectsUnboundedOrInvalidReadLimits() {
        var store = store();
        assertThrows(IllegalArgumentException.class, () -> store.readTextExcerpt("unused", 1));
        assertThrows(IllegalArgumentException.class, () -> store.readTextExcerpt("unused", Integer.MAX_VALUE));
    }
}
