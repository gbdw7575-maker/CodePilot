package com.dsh.orchestration.acceptance;

import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostTaskCheckCatalogContextTest {
    @TempDir Path temp;
    private ApplicationContextRunner context(ProjectRepository projects, WorkspaceManager workspaces) {
        return new ApplicationContextRunner().withBean(ProjectRepository.class, () -> projects)
                .withBean(WorkspaceManager.class, () -> workspaces).withBean(HostTaskCheckCatalog.class);
    }
    @Test void absentPropertyStartsDisabledWithoutFilesystemOrDatabaseSideEffects() {
        var projects = mock(ProjectRepository.class); var workspaces = mock(WorkspaceManager.class);
        context(projects, workspaces).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            var failure = assertThrows(HostTaskCheckCatalog.Unavailable.class,
                    () -> ctx.getBean(HostTaskCheckCatalog.class).load("project_fixture"));
            assertEquals(HostTaskCheckCatalog.Reason.DISABLED, failure.reason());
            verifyNoInteractions(projects, workspaces);
        });
    }
    @Test void invalidPropertyDoesNotBreakLegacyStartupAndCannotReadCatalog() {
        var projects = mock(ProjectRepository.class); var workspaces = mock(WorkspaceManager.class);
        context(projects, workspaces).withPropertyValues("dsh.orchestration.task-checks.control-root=relative")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    var failure = assertThrows(HostTaskCheckCatalog.Unavailable.class,
                            () -> ctx.getBean(HostTaskCheckCatalog.class).load("project_fixture"));
                    assertEquals(HostTaskCheckCatalog.Reason.INVALID_CONTROL_LAYOUT, failure.reason());
                    verifyNoInteractions(projects, workspaces);
                });
    }
    @Test void startupPropertyActuallyInjectsHostRoot() throws Exception {
        Path control = Files.createDirectory(temp.resolve("host"));
        Path project = Files.createDirectory(temp.resolve("project"));
        Path workspace = Files.createDirectory(temp.resolve("workspaces"));
        Path checks = Files.createDirectory(control.resolve("project_fixture"));
        try (var input = getClass().getResourceAsStream("/m1c1/catalog.json")) { Files.copy(input, checks.resolve("task-checks.json")); }
        Files.writeString(checks.resolve("check_slugify.py"), "assert 2 + 2 == 4\n");
        var projects = mock(ProjectRepository.class); var workspaces = mock(WorkspaceManager.class);
        when(projects.listOrdered()).thenReturn(List.of(new ProjectEntity("project_fixture", "fixture", project.toString(),
                true, Instant.EPOCH, Instant.EPOCH)));
        when(workspaces.workRoot()).thenReturn(workspace);
        context(projects, workspaces).withPropertyValues("dsh.orchestration.task-checks.control-root=" + control)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertEquals("project_fixture", ctx.getBean(HostTaskCheckCatalog.class).load("project_fixture").projectId());
                });
    }
}
