package com.dsh.orchestration.acceptance;

import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.dsh.orchestration.acceptance.HostTaskCheckCatalog.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostTaskCheckCatalogTest {
    @TempDir Path temp;
    private Path control, project, workspace, catalog, verifier;
    private ProjectRepository projects;
    private WorkspaceManager workspaces;
    private ProjectEntity selected;
    private HostTaskCheckCatalog host;

    @BeforeEach void setup() throws Exception {
        control = Files.createDirectory(temp.resolve("control"));
        project = Files.createDirectory(temp.resolve("project"));
        workspace = Files.createDirectory(temp.resolve("workspace"));
        Path privateProject = Files.createDirectory(control.resolve("project_fixture"));
        catalog = privateProject.resolve("task-checks.json");
        verifier = privateProject.resolve("check_slugify.py");
        try (var input = getClass().getResourceAsStream("/m1c1/catalog.json")) { Files.copy(input, catalog); }
        Files.writeString(verifier, "assert 2 + 2 == 4\n");
        projects = mock(ProjectRepository.class); workspaces = mock(WorkspaceManager.class);
        selected = entity("project_fixture", project, true);
        when(projects.listOrdered()).thenReturn(List.of(selected));
        when(workspaces.workRoot()).thenReturn(workspace);
        host = new HostTaskCheckCatalog(control.toString(), projects, workspaces);
    }
    private ProjectEntity entity(String id, Path root, boolean trusted) {
        return new ProjectEntity(id, id, root == null ? null : root.toString(), trusted, Instant.EPOCH, Instant.EPOCH);
    }
    private void rejects(HostTaskCheckCatalog.Reason reason, Runnable action) {
        var exception = assertThrows(HostTaskCheckCatalog.Unavailable.class, action::run);
        assertEquals(reason, exception.reason());
        assertFalse(exception.getMessage().contains(temp.toString()));
        assertNull(exception.getCause());
    }

    @Test void registeredTrustedProjectReturnsActualImmutableBytes() {
        var snapshot = host.load("project_fixture");
        assertEquals("project_fixture", snapshot.projectId());
        assertArrayEquals("assert 2 + 2 == 4\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                snapshot.verifierBytes("slugify-semantic", "check_slugify.py"));
        byte[] copy = snapshot.verifierBytes("slugify-semantic", "check_slugify.py"); copy[0] = 0;
        host.requireCurrent(snapshot);
        assertEquals('a', snapshot.verifierBytes("slugify-semantic", "check_slugify.py")[0]);
        verify(projects, times(4)).listOrdered();
        verify(workspaces, never()).prepare(anyString(), any());
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t"})
    void blankConfigurationIsDisabledWithoutDatabaseOrFilesystemWork(String root) {
        var disabled = new HostTaskCheckCatalog(root, projects, workspaces);
        rejects(DISABLED, () -> disabled.load("project_fixture"));
        verifyNoInteractions(projects, workspaces);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"missing", "../project_fixture", "/tmp", "project_fixture/other"})
    void unknownOrPathProjectIdCannotSelectAFile(String projectId) {
        rejects(PROJECT_NOT_FOUND, () -> host.load(projectId));
    }

    @Test void untrustedTargetDoesNotReadItsCatalog() throws Exception {
        selected.setTrusted(false); Files.delete(catalog);
        rejects(PROJECT_NOT_TRUSTED, () -> host.load("project_fixture"));
    }

    @Test void unregisteredCatalogDoesNotBecomeAProject() {
        when(projects.listOrdered()).thenReturn(List.of());
        rejects(PROJECT_NOT_FOUND, () -> host.load("project_fixture"));
    }

    @Test void completeSetIncludesUntrustedOtherProjects() {
        when(projects.listOrdered()).thenReturn(List.of(selected, entity("other", control, false)));
        rejects(CATALOG_INVALID, () -> host.load("project_fixture"));
    }

    @Test void completeSetIncludesTrustedOtherProjects() {
        when(projects.listOrdered()).thenReturn(List.of(selected, entity("other", temp, true)));
        rejects(CATALOG_INVALID, () -> host.load("project_fixture"));
    }

    @Test void nestedControlInsideCurrentProjectIsRejected() {
        when(projects.listOrdered()).thenReturn(List.of(entity("project_fixture", temp, true)));
        rejects(CATALOG_INVALID, () -> host.load("project_fixture"));
    }

    @Test void controlInsideReleasedWorkspaceIsRejectedWithoutLookingAtLiveLeases() {
        when(workspaces.workRoot()).thenReturn(temp);
        rejects(CATALOG_INVALID, () -> host.load("project_fixture"));
        verify(workspaces, never()).find(anyString());
    }

    @Test void workspaceInsideControlIsRejected() throws Exception {
        when(workspaces.workRoot()).thenReturn(Files.createDirectory(control.resolve("released")));
        rejects(CATALOG_INVALID, () -> host.load("project_fixture"));
    }

    @Test void missingOtherProjectIsNotSilentlyDropped() {
        when(projects.listOrdered()).thenReturn(List.of(selected, entity("removed", temp.resolve("missing"), false)));
        rejects(INVALID_CONTROL_LAYOUT, () -> host.load("project_fixture"));
    }

    @Test void missingWorkspaceIsRejectedAndNotAutomaticallyCreated() {
        Path missing = temp.resolve("unprovisioned-workspaces");
        when(workspaces.workRoot()).thenReturn(missing);
        rejects(INVALID_CONTROL_LAYOUT, () -> host.load("project_fixture"));
        assertFalse(Files.exists(missing));
    }

    @Test void relativeNonNormalizedMissingAndNonDirectoryControlAreRejectedLazily() throws Exception {
        Path file = Files.writeString(temp.resolve("regular-file"), "not a root");
        for (String root : List.of("control", temp.resolve("missing").toString(), file.toString(),
                control.resolve("..").resolve("control").toString(), temp + "/invalid\u0000")) {
            var invalid = new HostTaskCheckCatalog(root, projects, workspaces);
            rejects(INVALID_CONTROL_LAYOUT, () -> invalid.load("project_fixture"));
        }
    }

    @Test void symbolicControlOrAncestorCannotActAsHostAuthority() throws Exception {
        Path alias = temp.resolve("alias"); Files.createSymbolicLink(alias, control);
        rejects(INVALID_CONTROL_LAYOUT, () -> new HostTaskCheckCatalog(alias.toString(), projects, workspaces).load("project_fixture"));
        Path ancestor = temp.resolve("ancestor"); Files.createSymbolicLink(ancestor, temp);
        rejects(INVALID_CONTROL_LAYOUT, () -> new HostTaskCheckCatalog(ancestor.resolve("control").toString(), projects, workspaces).load("project_fixture"));
    }

    @Test void symbolicExcludedProjectIsRejected() throws Exception {
        Path alias = temp.resolve("project-alias"); Files.createSymbolicLink(alias, project);
        when(projects.listOrdered()).thenReturn(List.of(entity("project_fixture", alias, true)));
        rejects(INVALID_CONTROL_LAYOUT, () -> host.load("project_fixture"));
    }

    @Test void symbolicWorkspaceIsRejected() throws Exception {
        Path alias = temp.resolve("workspace-alias"); Files.createSymbolicLink(alias, workspace);
        when(workspaces.workRoot()).thenReturn(alias);
        rejects(INVALID_CONTROL_LAYOUT, () -> host.load("project_fixture"));
    }

    @Test void projectSetAdditionDuringReadIsRejected() throws Exception {
        Path other = Files.createDirectory(temp.resolve("other"));
        when(projects.listOrdered()).thenReturn(List.of(selected), List.of(selected, entity("other", other, false)));
        rejects(PROJECT_SET_CHANGED, () -> host.load("project_fixture"));
    }

    @Test void projectSetRemovalDuringReadIsRejected() throws Exception {
        Path other = Files.createDirectory(temp.resolve("other"));
        when(projects.listOrdered()).thenReturn(List.of(selected, entity("other", other, false)), List.of(selected));
        rejects(PROJECT_SET_CHANGED, () -> host.load("project_fixture"));
    }

    @Test void inPlaceTrustRevocationDuringReadDoesNotMutateTheBeforeSnapshot() {
        when(projects.listOrdered()).thenReturn(List.of(selected)).thenAnswer(invocation -> {
            selected.setTrusted(false); return List.of(selected);
        });
        rejects(PROJECT_SET_CHANGED, () -> host.load("project_fixture"));
    }

    @Test void rootChangeDuringReadIsRejected() throws Exception {
        Path other = Files.createDirectory(temp.resolve("other"));
        when(projects.listOrdered()).thenReturn(List.of(selected), List.of(entity("project_fixture", other, true)));
        rejects(PROJECT_SET_CHANGED, () -> host.load("project_fixture"));
    }

    @Test void rowOrderingAndDuplicatePhysicalRootsDoNotCreateFalseDrift() throws Exception {
        var other = entity("other", project, false);
        when(projects.listOrdered()).thenReturn(List.of(selected, other), List.of(other, selected));
        assertNotNull(host.load("project_fixture"));
    }

    @Test void corruptRepositoryRowsOrUnknownRootsAreRejected() {
        var rows = new ArrayList<ProjectEntity>(); rows.add(selected); rows.add(null);
        for (List<ProjectEntity> invalid : List.of(rows, List.of(selected, selected), List.of(entity("project_fixture", null, true)))) {
            when(projects.listOrdered()).thenReturn(invalid);
            rejects(INVALID_CONTROL_LAYOUT, () -> host.load("project_fixture"));
        }
        when(projects.listOrdered()).thenReturn(null);
        rejects(INVALID_CONTROL_LAYOUT, () -> host.load("project_fixture"));
    }

    @Test void catalogMutationDuringSecondRepositoryReadIsRejected() {
        when(projects.listOrdered()).thenReturn(List.of(selected)).thenAnswer(invocation -> {
            Files.writeString(catalog, Files.readString(catalog) + "\n"); return List.of(selected);
        });
        rejects(STALE_CATALOG, () -> host.load("project_fixture"));
    }

    @Test void verifierMutationDuringSecondRepositoryReadIsRejected() {
        when(projects.listOrdered()).thenReturn(List.of(selected)).thenAnswer(invocation -> {
            Files.writeString(verifier, "assert False\n"); return List.of(selected);
        });
        rejects(STALE_CATALOG, () -> host.load("project_fixture"));
    }

    @Test void noCachedTrustOrLayoutOnRequireCurrent() {
        var frozen = host.load("project_fixture"); selected.setTrusted(false);
        rejects(PROJECT_NOT_TRUSTED, () -> host.requireCurrent(frozen));
        selected.setTrusted(true);
        when(projects.listOrdered()).thenReturn(List.of(selected, entity("new-project", control, false)));
        rejects(CATALOG_INVALID, () -> host.requireCurrent(frozen));
    }

    @Test void noCachedRegistryIdentityOnRequireCurrent() throws Exception {
        var frozen = host.load("project_fixture");
        Files.writeString(catalog, Files.readString(catalog) + "\n");
        rejects(STALE_CATALOG, () -> host.requireCurrent(frozen));
    }

    @Test void noCachedDependencyBytesOnRequireCurrent() throws Exception {
        var frozen = host.load("project_fixture"); Files.writeString(verifier, "assert False\n");
        rejects(CATALOG_INVALID, () -> host.requireCurrent(frozen));
    }

    @Test void nullSnapshotIsRejected() { rejects(STALE_CATALOG, () -> host.requireCurrent(null)); }
}
