package com.dsh.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceApplicationDataIsolationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void omitsHostApplicationDataWhenProjectContainsIt() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path appData = Files.createDirectories(project.resolve("backend/data"));
        Files.writeString(appData.resolve("credentials.properties"), "fake-key-only");
        Files.writeString(appData.resolve("credentials-123.tmp"), "fake-temporary-key-only");
        Files.writeString(project.resolve("README.md"), "ordinary project file");
        Path target = temporaryDirectory.resolve("snapshot");

        WorkspaceManager.copyTree(project, target, appData);

        assertTrue(Files.isRegularFile(target.resolve("README.md")));
        assertFalse(Files.exists(target.resolve("backend/data")));
    }

    @Test
    void rejectsApplicationDataAsProjectRoot() throws Exception {
        Path appData = Files.createDirectories(temporaryDirectory.resolve("data"));
        Path nested = Files.createDirectories(appData.resolve("nested"));
        Files.writeString(appData.resolve("credentials.properties"), "fake-key-only");

        assertThrows(IOException.class, () -> WorkspaceManager.copyTree(
                appData, temporaryDirectory.resolve("snapshot"), appData));
        assertThrows(IOException.class, () -> WorkspaceManager.copyTree(
                nested, temporaryDirectory.resolve("snapshot-2"), appData));
    }
}
