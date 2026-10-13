package com.dsh.orchestration.acceptance;

import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.workspace.SafePaths;
import com.dsh.workspace.WorkspaceManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Host-only catalog entry. Configuration is supplied at server startup, never by a tool/API.
 * Reading authenticates neither a human confirmation nor a task registration.
 * Local previews/confirmation and refusal gates consume it; it never enables check execution.
 */
@Component
public final class HostTaskCheckCatalog {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;
    private final String configuredRoot;
    private final ProjectRepository projects;
    private final WorkspaceManager workspaces;
    // Pure observation only: counts and times host revalidation. Never alters any decision or outcome.
    private static final org.slf4j.Logger timingLog = org.slf4j.LoggerFactory.getLogger(HostTaskCheckCatalog.class);
    private static final java.util.concurrent.atomic.AtomicLong timingSeq = new java.util.concurrent.atomic.AtomicLong();

    public HostTaskCheckCatalog(
            @Value("${dsh.orchestration.task-checks.control-root:}") String configuredRoot,
            ProjectRepository projects, WorkspaceManager workspaces) {
        // Validate lazily: disabled/misconfigured optional catalogs must not prevent legacy startup.
        this.configuredRoot = configuredRoot;
        this.projects = projects;
        this.workspaces = workspaces;
    }

    public TrustedTaskCheckCatalog.Snapshot load(String projectId) {
        long timingStart=System.nanoTime();long seq=timingSeq.incrementAndGet();String outcome="OK";
        try{
        if (configuredRoot == null || configuredRoot.isBlank()) throw unavailable(Reason.DISABLED);
        if (projectId == null || !projectId.matches("[A-Za-z0-9_-]{1,128}"))
            throw unavailable(Reason.PROJECT_NOT_FOUND);
        Path control = directory(configuredRoot);
        List<ProjectIdentity> before = identities();
        authorize(before, projectId);
        List<Path> excluded = exclusions(before);
        TrustedTaskCheckCatalog.Snapshot frozen;
        try {
            frozen = TrustedTaskCheckCatalog.load(control, projectId, excluded);
        } catch (IllegalArgumentException invalid) {
            throw unavailable(Reason.CATALOG_INVALID);
        }
        // Copy scalar identities before IO; JPA entities may otherwise mutate in-place.
        List<ProjectIdentity> after = identities();
        if (!before.equals(after)) throw unavailable(Reason.PROJECT_SET_CHANGED);
        // Revalidate the whole layout and bytes, not just selected project / cached leases.
        directory(configuredRoot);
        try {
            TrustedTaskCheckCatalog.requireCurrent(control, exclusions(after), frozen);
        } catch (IllegalArgumentException changed) {
            throw unavailable(Reason.STALE_CATALOG);
        }
        return frozen;
        }catch(Unavailable refused){outcome=refused.reason().name();throw refused;}
        finally{
            timingLog.info("check-catalog-timing op=load seq={} elapsedMs={} outcome={} project={}",
                seq,(System.nanoTime()-timingStart)/1_000_000L,outcome,projectId);
        }
    }

    public void requireCurrent(TrustedTaskCheckCatalog.Snapshot frozen) {
        long timingStart=System.nanoTime();long seq=timingSeq.incrementAndGet();String outcome="OK";
        try{
        if (frozen == null) throw unavailable(Reason.STALE_CATALOG);
        // Reload also reauthorizes current trust and all exclusion roots on every call.
        var current = load(frozen.projectId());
        if (!current.registryDigest().equals(frozen.registryDigest()))
            throw unavailable(Reason.STALE_CATALOG);
        }catch(Unavailable refused){outcome=refused.reason().name();throw refused;}
        finally{
            timingLog.info("check-catalog-timing op=requireCurrent seq={} elapsedMs={} outcome={} project={}",
                seq,(System.nanoTime()-timingStart)/1_000_000L,outcome,frozen==null?"-":frozen.projectId());
        }
    }

    /** Actual execution files under this same startup-configured root; never a caller supplied root. */
    public TrustedTaskExecutionSpec.Snapshot loadExecution(String projectId) {
        long timingStart=System.nanoTime();long seq=timingSeq.incrementAndGet();String outcome="OK";
        try{
        if (configuredRoot == null || configuredRoot.isBlank()) throw unavailable(Reason.DISABLED);
        if (projectId == null || !projectId.matches("[A-Za-z0-9_-]{1,128}")) throw unavailable(Reason.PROJECT_NOT_FOUND);
        Path control=directory(configuredRoot); var before=identities(); authorize(before,projectId);
        TrustedTaskExecutionSpec.Snapshot frozen;
        try { frozen=TrustedTaskExecutionSpec.load(control,projectId,exclusions(before)); }
        catch(IllegalArgumentException invalid) { throw unavailable(Reason.CATALOG_INVALID); }
        var after=identities();if(!before.equals(after))throw unavailable(Reason.PROJECT_SET_CHANGED);
        authorize(after,projectId);directory(configuredRoot);
        try { TrustedTaskExecutionSpec.requireCurrent(control,exclusions(after),frozen); }
        catch(IllegalArgumentException changed) { throw unavailable(Reason.STALE_CATALOG); }
        return frozen;
        }catch(Unavailable refused){outcome=refused.reason().name();throw refused;}
        finally{
            timingLog.info("check-catalog-timing op=loadExecution seq={} elapsedMs={} outcome={} project={}",
                seq,(System.nanoTime()-timingStart)/1_000_000L,outcome,projectId);
        }
    }

    public void requireCurrentExecution(TrustedTaskExecutionSpec.Snapshot frozen) {
        long timingStart=System.nanoTime();long seq=timingSeq.incrementAndGet();String outcome="OK";
        try{
        if(frozen==null)throw unavailable(Reason.STALE_CATALOG);
        if(configuredRoot==null||configuredRoot.isBlank())throw unavailable(Reason.DISABLED);
        Path control=directory(configuredRoot);var before=identities();authorize(before,frozen.projectId());
        TrustedTaskExecutionSpec.Snapshot current;
        try{current=TrustedTaskExecutionSpec.load(control,frozen.projectId(),exclusions(before));}
        catch(IllegalArgumentException invalid){throw unavailable(Reason.CATALOG_INVALID);}
        var after=identities();if(!before.equals(after))throw unavailable(Reason.PROJECT_SET_CHANGED);
        authorize(after,frozen.projectId());directory(configuredRoot);
        // Compare one fresh complete byte read directly with the already-frozen snapshot.
        // loadExecution would first compare TWO new reads with each other, then compare with frozen.
        // No metadata cache: every invocation still hashes all cache/verifier bytes and checks paths.
        if(!current.executionSpecDigest().equals(frozen.executionSpecDigest())||!current.cacheRoot().equals(frozen.cacheRoot()))
            throw unavailable(Reason.STALE_CATALOG);
        }catch(Unavailable refused){outcome=refused.reason().name();throw refused;}
        finally{
            timingLog.info("check-catalog-timing op=requireCurrentExecution seq={} elapsedMs={} outcome={} project={}",
                seq,(System.nanoTime()-timingStart)/1_000_000L,outcome,frozen==null?"-":frozen.projectId());
        }
    }

    private List<ProjectIdentity> identities() {
        List<ProjectEntity> rows = projects.listOrdered();
        if (rows == null) throw unavailable(Reason.INVALID_CONTROL_LAYOUT);
        Set<String> ids = new HashSet<>();
        return rows.stream().map(row -> {
            // A confirmation transaction can already have managed Project rows: bypass stale L1 values.
            if(entityManagerFactory!=null && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                var entityManager=org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
                if(entityManager!=null && entityManager.contains(row)) entityManager.refresh(row);
            }
            if (row == null || row.getId() == null || !ids.add(row.getId()))
                throw unavailable(Reason.INVALID_CONTROL_LAYOUT);
            return new ProjectIdentity(row.getId(), row.getRootPath(), row.isTrusted(), row.getVersion());
        }).sorted(Comparator.comparing(ProjectIdentity::id)).toList();
    }

    private static void authorize(List<ProjectIdentity> identities, String projectId) {
        ProjectIdentity project = identities.stream().filter(p -> projectId.equals(p.id())).findFirst()
                .orElseThrow(() -> unavailable(Reason.PROJECT_NOT_FOUND));
        if (!project.trusted()) throw unavailable(Reason.PROJECT_NOT_TRUSTED);
    }

    private List<Path> exclusions(List<ProjectIdentity> identities) {
        // The entire workspace root includes released, persisted and not-yet-created leases.
        // Do not discover exclusions from only live leases, nor skip untrusted projects.
        Set<Path> roots = new HashSet<>();
        Path workspace = workspaces.workRoot();
        roots.add(directory(workspace == null ? null : workspace.toString()));
        for (var project : identities) roots.add(directory(project.root()));
        return roots.stream().sorted().toList();
    }

    private static Path directory(String configured) {
        try {
            if (configured == null || configured.isBlank()) throw unavailable(Reason.INVALID_CONTROL_LAYOUT);
            Path path = Path.of(configured);
            if (!path.isAbsolute() || !path.equals(path.normalize())
                    || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                throw unavailable(Reason.INVALID_CONTROL_LAYOUT);
            Path segment = path.getRoot();
            for (Path part : path) {
                segment = segment.resolve(part);
                if (Files.isSymbolicLink(segment) || SafePaths.isReparseRedirect(segment))
                    throw unavailable(Reason.INVALID_CONTROL_LAYOUT);
            }
            return path;
        } catch (IOException | IllegalArgumentException | SecurityException invalid) {
            // Do not leak host paths, IO messages or catalog contents into future API errors.
            throw unavailable(Reason.INVALID_CONTROL_LAYOUT);
        }
    }

    private record ProjectIdentity(String id, String root, boolean trusted, int version) { }

    /** Internal diagnostics only; these are not API/tool reason codes. */
    public enum Reason {
        DISABLED, PROJECT_NOT_FOUND, PROJECT_NOT_TRUSTED, INVALID_CONTROL_LAYOUT,
        CATALOG_INVALID, PROJECT_SET_CHANGED, STALE_CATALOG
    }
    public static final class Unavailable extends RuntimeException {
        private final Reason reason;
        private Unavailable(Reason reason) { super("Task check catalog unavailable: " + reason); this.reason = reason; }
        public Reason reason() { return reason; }
    }
    private static Unavailable unavailable(Reason reason) { return new Unavailable(reason); }
}
