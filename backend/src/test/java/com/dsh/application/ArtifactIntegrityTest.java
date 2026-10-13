package com.dsh.application;

import com.dsh.storage.*;
import com.dsh.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ArtifactIntegrityTest {
    @TempDir Path root;
    private ArtifactStore store() {
        ArtifactRepository repo=mock(ArtifactRepository.class);
        WorkspaceManager ws=mock(WorkspaceManager.class);
        when(ws.workRoot()).thenReturn(root);
        when(repo.save(any())).thenAnswer(inv -> {
            ArtifactEntity e=inv.getArgument(0);
            when(repo.findById(e.getId())).thenReturn(Optional.of(e)); return e;
        });
        return new ArtifactStore(repo,ws,mock(RunRepository.class));
    }
    @Test void streamsWholeLargeLogAndDetectsMiddleCorruption() throws Exception {
        var store=store(); byte[] bytes="中文🙂\n".repeat(60000).getBytes(StandardCharsets.UTF_8);
        var a=store.save(null,"p","log","text/plain",bytes,true);
        assertTrue(store.verifyIntegrity(a.getId(),bytes.length));
        bytes[bytes.length/2]='X'; Files.write(root.resolve("projects/p/artifacts/"+a.getId()),bytes);
        assertFalse(store.verifyIntegrity(a.getId(),bytes.length));
    }
    @Test void checksSizeLimitUnknownArtifactAndEmptyContent() {
        var store=store(); var a=store.save(null,"p","log","text/plain",new byte[]{1,2,3},true);
        assertFalse(store.verifyIntegrity(a.getId(),2)); assertTrue(store.verifyIntegrity(a.getId(),3));
        assertFalse(store.verifyIntegrity("unknown",3));
        var empty=store.save(null,"p","empty","text/plain",new byte[0],true);
        assertTrue(store.verifyIntegrity(empty.getId(),0));
        assertThrows(IllegalArgumentException.class,()->store.verifyIntegrity(a.getId(),-1));
    }
    @ParameterizedTest @ValueSource(strings={"missing","same-size","shorter","longer","leaf-link","directory-link"})
    void missingChangedOrLinkedBytesAreNotEvidence(String kind) throws Exception {
        var store=store(); var a=store.save(null,"p","log","text/plain","safe".getBytes(StandardCharsets.UTF_8),true);
        Path file=root.resolve("projects/p/artifacts/"+a.getId());
        switch(kind) {
            case "missing" -> Files.delete(file);
            case "same-size" -> Files.writeString(file,"fake");
            case "shorter" -> Files.writeString(file,"x");
            case "longer" -> Files.writeString(file,"safe plus");
            case "leaf-link" -> {Path target=Files.writeString(root.resolve("target"),"safe"); Files.delete(file); Files.createSymbolicLink(file,target);}
            default -> {Path dir=file.getParent(); Files.move(dir,root.resolve("moved")); Files.createSymbolicLink(dir,root.resolve("moved"));}
        }
        assertFalse(store.verifyIntegrity(a.getId(),100));
    }
}
