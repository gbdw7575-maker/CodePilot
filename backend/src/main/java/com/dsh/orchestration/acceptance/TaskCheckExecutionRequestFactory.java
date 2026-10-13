package com.dsh.orchestration.acceptance;

import com.dsh.api.ApiException;
import com.dsh.api.dto.TaskCheckSelectionPreview;
import com.dsh.workspace.SafePaths;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** No process execution or writes. File IO never runs inside a caller's DB transaction. */
@Service
public class TaskCheckExecutionRequestFactory {
    private final JdbcTemplate jdbc;
    private final TaskCheckPreviewSource sources;
    private final HostTaskCheckCatalog catalogs;
    private final WorkspaceManager workspaces;
    private final ObjectMapper mapper;

    public TaskCheckExecutionRequestFactory(JdbcTemplate jdbc, TaskCheckPreviewSource sources,
            HostTaskCheckCatalog catalogs, WorkspaceManager workspaces, ObjectMapper mapper) {
        this.jdbc = jdbc; this.sources = sources; this.catalogs = catalogs;
        this.workspaces = workspaces; this.mapper = mapper;
    }

    public enum Reason { INVALID_INPUT, MISSING_REGISTRATION, INVALID_REGISTRATION, LEGACY_REGISTRATION,
        CHECK_NOT_SELECTED, STALE_INPUTS, WORKSPACE_UNAVAILABLE, INVALID_SOURCE, CALLER_TRANSACTION }
    public static final class Unavailable extends RuntimeException {
        private final Reason reason;
        private Unavailable(Reason reason) { super("Task check preparation refused: " + reason); this.reason = reason; }
        public Reason reason() { return reason; }
    }
    private static Unavailable refuse(Reason reason) { return new Unavailable(reason); }

    /** IDs are references to existing server state; no caller controlled command/path/limit. */
    public TaskCheckExecutionRequest prepare(String registrationId, String checkId) {
        return prepare(registrationId,checkId,null);
    }

    /** Internal child preparation; observation is re-read, never treated as a launch token. */
    public TaskCheckExecutionRequest prepareForAttempt(String registrationId,String checkId,
                                                       TaskCheckAttemptAuthority.Observation observed) {
        if(observed==null) throw refuse(Reason.INVALID_INPUT);
        return prepare(registrationId,checkId,observed);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private TaskCheckAttemptAuthority attempts;
    private TaskCheckExecutionRequest prepare(String registrationId,String checkId,
                                               TaskCheckAttemptAuthority.Observation observed) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw refuse(Reason.CALLER_TRANSACTION);
        if (registrationId == null || !registrationId.matches("checkreg_[a-f0-9]{32}")
                || checkId == null || !checkId.matches("[A-Za-z0-9_-]{1,64}")) throw refuse(Reason.INVALID_INPUT);
        Row row = registration(registrationId);
        TaskCheckRegistrationBinding frozen;
        TaskCheckSelectionPreview selected;
        try {
            frozen = TaskCheckRegistrationBinding.decode(row.snapshot());
            if (!frozen.projectId.equals(row.project()) || !frozen.rootRunId.equals(row.root())
                    || !frozen.turnId.equals(row.turn()) || !frozen.planId.equals(row.plan())
                    || !frozen.taskId.equals(row.task()) || frozen.taskVersion != row.version()
                    || frozen.observedPlanRevision != row.revision() || row.time() == null
                    || !frozen.bindingDigest.equals(row.bindingDigest()) || !frozen.canonicalBinding.equals(row.binding())
                    || !frozen.snapshotDigest.equals(row.snapshotDigest())) throw new IllegalArgumentException();
            selected = mapper.readValue(row.snapshot(), TaskCheckSelectionPreview.class);
        } catch (Exception damaged) { throw refuse(Reason.INVALID_REGISTRATION); }
        if (selected.execution() == null) throw refuse(Reason.LEGACY_REGISTRATION);
        if (!selected.checkRefs().contains(checkId)) throw refuse(Reason.CHECK_NOT_SELECTED);
        if(observed!=null) {
            attempts.requireCurrent(observed);
            if(!row.project().equals(observed.projectId()) || !row.root().equals(observed.rootRunId())
                    || !row.plan().equals(observed.planId()) || !row.task().equals(observed.task().id())
                    || row.version()!=observed.task().version()) throw refuse(Reason.STALE_INPUTS);
        }
        try {
            var before = sources.read(row.root(), row.plan(), row.task());
            if (!"RUNNING".equals(before.rootState())) throw refuse(Reason.STALE_INPUTS);
            var catalog = catalogs.load(row.project());
            var execution = catalogs.loadExecution(row.project());
            var actual = TaskCheckSelectionPreviewService.withExecution(
                    TaskCheckSelectionPreviewService.select(TaskCheckPreviewService.compose(before, catalog), selected.checkRefs()), execution);
            if (!frozen.canonicalBinding.equals(TaskCheckRegistrationBinding.from(actual).canonicalBinding))
                throw refuse(Reason.STALE_INPUTS);
            var lease = workspaces.find(row.root());
            if (lease == null || !row.root().equals(lease.runId()) || !row.project().equals(lease.projectId()))
                throw refuse(Reason.WORKSPACE_UNAVAILABLE);
            String rootWorkspaceRef = workspaceRef(row.root());
            Path workRoot = safeDirectory(workspaces.workRoot());
            if(!lease.workspaceId().matches("ws_[a-f0-9]{32}") || !rootWorkspaceRef.equals(lease.workspaceId())
                    || !safeDirectory(lease.dir()).equals(workRoot.resolve(lease.workspaceId()))) throw refuse(Reason.WORKSPACE_UNAVAILABLE);
            String workspaceRef=observed==null?rootWorkspaceRef:observed.child().workspaceRef();
            Path candidate=observed==null?safeDirectory(lease.dir()):safeDirectory(workRoot.resolve(workspaceRef));
            if(observed!=null) requireChildWorkspace(observed,workspaceRef);
            var candidateFiles = sourceFiles(candidate);
            var check = execution.select(List.of(checkId)).checks().getFirst();
            var bytes = new TreeMap<String, byte[]>();
            for (var verifier : check.verifiers()) bytes.put(verifier.path(), catalog.verifierBytes(checkId, verifier.path()));
            catalogs.requireCurrent(catalog);
            catalogs.requireCurrentExecution(execution);
            if (!candidateFiles.equals(sourceFiles(candidate))) throw refuse(Reason.STALE_INPUTS);
            // Finish potentially slow file reads before the last fresh DB/lease observation.
            var after = sources.read(row.root(), row.plan(), row.task());
            if (!before.equals(after) || !row.equals(registration(registrationId))
                    || workspaces.find(row.root()) != lease || !rootWorkspaceRef.equals(workspaceRef(row.root()))
                    || !safeDirectory(lease.dir()).equals(workRoot.resolve(rootWorkspaceRef)))
                throw refuse(Reason.STALE_INPUTS);
            if(observed!=null) { attempts.requireCurrent(observed); requireChildWorkspace(observed,workspaceRef); }
            String digest = CanonicalTaskJson.digest(mapper.valueToTree(candidateFiles));
            return new TaskCheckExecutionRequest(UUID.randomUUID().toString().replace("-", ""), registrationId,
                    frozen.bindingDigest, workspaceRef, candidate, execution, check, before, candidateFiles, digest, bytes);
        } catch (HostTaskCheckCatalog.Unavailable | ApiException | IllegalArgumentException stale) {
            throw refuse(Reason.STALE_INPUTS);
        } catch (IOException invalid) { throw refuse(Reason.WORKSPACE_UNAVAILABLE); }
    }

    private void requireChildWorkspace(TaskCheckAttemptAuthority.Observation observed,String ref) {
        if(workspaces.find(observed.child().id())!=null || !ref.equals(workspaceRef(observed.child().id()))
                || jdbc.queryForObject("SELECT count(*) FROM runs WHERE workspace_ref=?",Integer.class,ref)!=1)
            throw refuse(Reason.WORKSPACE_UNAVAILABLE);
    }

    /** Re-authorizes Host/registration/requirements/lease/files; still not a launch CAS. */
    public void requireCurrent(TaskCheckExecutionRequest request) {
        if (request == null) throw refuse(Reason.INVALID_INPUT);
        var current = prepare(request.registrationId(), request.check().checkId());
        if (!request.sameInputs(current)) throw refuse(Reason.STALE_INPUTS);
    }

    private record Row(String id, String project, String root, String turn, String plan, String task,
            int version, int revision, String bindingDigest, String binding, String snapshotDigest,
            String snapshot, java.sql.Timestamp time) { }
    private Row registration(String id) {
        var rows = jdbc.query("""
            SELECT id,project_id,root_run_id,turn_id,plan_id,task_id,task_version,observed_plan_revision,
                   binding_digest,binding_json,snapshot_digest,snapshot_json,recorded_at
            FROM task_check_registrations WHERE id=?
            """, (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getTimestamp(13)), id);
        if (rows.isEmpty()) throw refuse(Reason.MISSING_REGISTRATION);
        if (rows.size() != 1) throw refuse(Reason.INVALID_REGISTRATION);
        return rows.getFirst();
    }
    private String workspaceRef(String runId) {
        var rows = jdbc.query("SELECT workspace_ref FROM runs WHERE id=?", (rs, n) -> rs.getString(1), runId);
        if (rows.size() != 1 || rows.getFirst() == null) throw refuse(Reason.WORKSPACE_UNAVAILABLE);
        return rows.getFirst();
    }
    static Path safeDirectory(Path path) throws IOException {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize())
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw refuse(Reason.WORKSPACE_UNAVAILABLE);
        Path segment = path.getRoot();
        for (Path part : path) {
            segment = segment.resolve(part);
            if (Files.isSymbolicLink(segment) || SafePaths.isReparseRedirect(segment)) throw refuse(Reason.WORKSPACE_UNAVAILABLE);
        }
        return path;
    }

    static List<TaskCheckExecutionRequest.SourceFile> sourceFiles(Path candidate) {
        try {
            Path source = safeDirectory(candidate.resolve("src/main/java"));
            var files = new TreeMap<String, TaskCheckExecutionRequest.SourceFile>();
            var aliases = new HashSet<String>();
            long[] bytes = {0}; int[] directories = {0};
            Files.walkFileTree(source, EnumSet.noneOf(FileVisitOption.class), 32, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                    if (++directories[0] > 8192 || SafePaths.isReparseRedirect(dir)) throw refuse(Reason.INVALID_SOURCE);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes before) throws IOException {
                    if (!before.isRegularFile() || SafePaths.isReparseRedirect(file)) throw refuse(Reason.INVALID_SOURCE);
                    String relative = candidate.relativize(file).toString().replace('\\', '/');
                    if (!relative.endsWith(".java")) return FileVisitResult.CONTINUE;
                    if (!relative.matches("src/main/java/(?:[A-Za-z0-9_$.-]+/)*[A-Za-z0-9_$.-]+\\.java")
                            || !aliases.add(relative.toLowerCase(Locale.ROOT)) || files.size() >= 4096
                            || before.size() > 1048576 || (bytes[0] += before.size()) > 32L * 1048576)
                        throw refuse(Reason.INVALID_SOURCE);
                    try {
                        var sha = MessageDigest.getInstance("SHA-256"); long readBytes = 0;
                        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                            byte[] buffer = new byte[8192]; int count;
                            while ((count = input.read(buffer)) != -1) {
                                readBytes += count;
                                if (readBytes > before.size() || readBytes > 1048576) throw refuse(Reason.INVALID_SOURCE);
                                sha.update(buffer, 0, count);
                            }
                        }
                        var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (!after.isRegularFile() || readBytes != before.size() || before.size() != after.size()
                                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                                || !Objects.equals(before.fileKey(), after.fileKey())) throw refuse(Reason.INVALID_SOURCE);
                        files.put(relative, new TaskCheckExecutionRequest.SourceFile(relative, readBytes, HexFormat.of().formatHex(sha.digest())));
                    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
                    return FileVisitResult.CONTINUE;
                }
            });
            if (files.isEmpty()) throw refuse(Reason.INVALID_SOURCE);
            return List.copyOf(files.values());
        } catch (IOException | SecurityException unsafe) { throw refuse(Reason.INVALID_SOURCE); }
    }
}
