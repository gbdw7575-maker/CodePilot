package com.dsh.agent.tool;

import com.dsh.application.ArtifactStore;
import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.storage.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReadArtifactToolTest {
    final ArtifactStore store = mock(ArtifactStore.class);
    final ProjectRepository projects = mock(ProjectRepository.class);
    final ObjectMapper mapper = new ObjectMapper();
    ReadArtifactTool tool() {
        when(projects.findById("p")).thenReturn(Optional.of(new ProjectEntity(
                "p", "project", "/project", true, Instant.now(), Instant.now())));
        return new ReadArtifactTool(store, projects);
    }
    ToolExecutionContext ctx() {
        return new ToolExecutionContext("r", "t", "turn", "call", "p", null, Instant.now().plusSeconds(10),
                null, null, mapper);
    }
    ArtifactEntity artifact(String run, String project, boolean visible, String media) {
        return new ArtifactEntity("art_log", run, project, "log", media, 100, "a".repeat(64), Instant.now(), visible);
    }
    @Test void pageIncludesBoundedCursorAndSourceMetadata() throws Exception {
        var tool = tool();
        when(store.get("art_log")).thenReturn(artifact("r", "p", true, "text/plain"));
        when(store.readTextPage("art_log", 0, 4096)).thenReturn(new ArtifactStore.TextPage("log", 0, 3, 100, true));
        var result = tool.execute(mapper.readTree("{\"artifactId\":\"art_log\"}"), ctx());
        assertEquals("SUCCEEDED", result.status()); assertTrue(result.truncated());
        assertEquals(3, result.structuredContent().path("nextOffset").asLong());
        assertTrue(result.structuredContent().path("hasMore").asBoolean());
        assertEquals("sealed-metadata", result.structuredContent().path("sha256Source").asText());
        assertFalse(tool.requiresWorkspace());
    }
    @Test void rejectsForeignProjectRunProjectLevelAndHiddenBeforeContentRead() throws Exception {
        var tool = tool();
        for (ArtifactEntity artifact : java.util.List.of(artifact("other", "p", true, "text/plain"),
                artifact("r", "other", true, "text/plain"), artifact(null, "p", true, "text/plain"),
                artifact("r", "p", false, "text/plain"))) {
            when(store.get("art_log")).thenReturn(artifact);
            var result = tool.execute(mapper.readTree("{\"artifactId\":\"art_log\"}"), ctx());
            assertEquals("DENIED", result.status()); assertEquals("PERMISSION_DENIED", result.errorCode());
            assertFalse(result.text().contains("other"));
        }
        verify(store, never()).readTextPage(anyString(), anyLong(), anyInt());
    }
    @Test void missingBinaryAndSizeMismatchHaveStableFailures() throws Exception {
        var tool = tool(); var args = mapper.readTree("{\"artifactId\":\"art_log\"}");
        when(store.get("art_log")).thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "missing"));
        assertEquals("ARTIFACT_NOT_FOUND", tool.execute(args, ctx()).errorCode());
        doReturn(artifact("r", "p", true, "application/octet-stream")).when(store).get("art_log");
        assertEquals("UNSUPPORTED_FILE", tool.execute(args, ctx()).errorCode());
        when(store.get("art_log")).thenReturn(artifact("r", "p", true, "application/json"));
        when(store.readTextPage("art_log", 0, 4096)).thenReturn(new ArtifactStore.TextPage("{}", 0, 2, 2, false));
        assertEquals("ARTIFACT_CHANGED", tool.execute(args, ctx()).errorCode());
    }
    @Test void revokedTrustIsCheckedBeforeLookingUpArtifact() throws Exception {
        var tool = tool();
        when(projects.findById("p")).thenReturn(Optional.of(new ProjectEntity(
                "p", "project", "/project", false, Instant.now(), Instant.now())));
        assertEquals("PROJECT_NOT_TRUSTED", tool.execute(mapper.readTree("{\"artifactId\":\"art_log\"}"), ctx()).errorCode());
        verify(store, never()).get(anyString());
    }
    @Test void directInvalidParametersNeverReachStorage() throws Exception {
        var tool = tool();
        for (String args : java.util.List.of("{\"artifactId\":\"../private\"}",
                "{\"artifactId\":\"art_log\",\"maxBytes\":999999}",
                "{\"artifactId\":\"art_log\",\"byteOffset\":-1}")) {
            assertEquals("INVALID_ARGUMENTS", tool.execute(mapper.readTree(args), ctx()).errorCode());
        }
        verify(store, never()).get(anyString());
    }
}
