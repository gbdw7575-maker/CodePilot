package com.dsh.orchestration.acceptance;

import com.dsh.orchestration.WriteScopePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Fresh, coherent DB observation for the trusted check coordinator. Not a launch capability:
 * caller must separately bind registration, live workspace, actual bytes and final CAS.
 * No filesystem/process IO, entities, writes, HTTP or model tools here.
 */
@Service
public class TaskCheckAttemptAuthority {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate reads;
    private final WriteScopePolicy scopes;

    public TaskCheckAttemptAuthority(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                     WriteScopePolicy scopes) {
        this.jdbc = jdbc; this.scopes = scopes;
        reads = new TransactionTemplate(transactions);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
        reads.setTimeout(5);
    }

    public enum Reason { INVALID_INPUT, CALLER_TRANSACTION, MISSING_ATTEMPT, IDENTITY_MISMATCH,
        INVALID_STATE, LEASE_EXPIRED, STALE_ATTEMPT, INVALID_SNAPSHOT, STALE_UPSTREAM, STALE_INPUTS }
    public static final class Unavailable extends RuntimeException {
        private final Reason reason;
        private Unavailable(Reason reason) { super("Task attempt observation refused: " + reason); this.reason = reason; }
        public Reason reason() { return reason; }
    }
    private static void require(boolean ok, Reason reason) { if (!ok) throw new Unavailable(reason); }

    /** Request identity comes from the coordinator's claim, never a model-supplied authority flag. */
    public Observation observe(String attemptId, String childRunId, long fencingToken) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(), Reason.CALLER_TRANSACTION);
        require(id(attemptId) && id(childRunId) && fencingToken > 0, Reason.INVALID_INPUT);
        Observation observed = reads.execute(status -> read(attemptId, childRunId, fencingToken));
        // A slow read must not hand back a lease that expired during this short transaction.
        require(observed != null && observed.attempt().leaseUntil().isAfter(Instant.now()), Reason.LEASE_EXPIRED);
        return observed;
    }

    public void requireCurrent(Observation observed) {
        require(observed != null && observed.attempt() != null, Reason.INVALID_INPUT);
        var attempt = observed.attempt();
        var actual = observe(attempt.id(), attempt.childRunId(), attempt.fencingToken());
        require(observed.equals(actual), Reason.STALE_INPUTS);
    }

    /** DB-only final comparison. The internal committer owns all business-source locks. */
    void requireCurrentJoined(Observation observed) {
        require(TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly(), Reason.CALLER_TRANSACTION);
        require(observed != null && observed.attempt() != null, Reason.INVALID_INPUT);
        var a = observed.attempt();
        require(observed.equals(read(a.id(), a.childRunId(), a.fencingToken())), Reason.STALE_INPUTS);
    }

    public record Attempt(String id, String planId, String taskId, int taskVersion, String childRunId,
            int attemptNo, String leaseOwner, Instant leaseUntil, long fencingToken, String budgetReservationId) { }
    public record Task(String rowId, String id, int version, String state, String goal, String dependsOn,
            String inputRefs, String deliverable, String acceptance, String scope, String executionMode, boolean required) { }
    public record Run(String id, String turnId, String parentId, String role, String state, String workspaceRef,
            Instant cancelRequestedAt) { }
    public record Artifact(String id, String runId, String projectId, String mediaType, long bytes, String sha256) { }
    public record Upstream(String taskId, int taskVersion, String attemptId, int attemptNo, long fencingToken,
            String childRunId, String outputDigest, String inputDigest, String verificationRef, List<Artifact> artifacts) {
        public Upstream { artifacts = List.copyOf(artifacts); }
    }
    public record Input(String id, String seedWorkspaceRef, String baseDigest, String writeScope, List<Artifact> artifacts) {
        public Input { artifacts = List.copyOf(artifacts); }
    }
    public record Observation(String projectId, String turnId, String threadId, String rootRunId,
            String planId, int planRevision, Task task, Attempt attempt, Run root, Run child,
            Input input, List<Upstream> upstream) {
        public Observation { upstream = List.copyOf(upstream); }
    }

    /** Receipt consumers only: terminal observation can never authorize another launch. */
    public Observation observeCommitted(String attemptId, String childId, long token) {
        require(id(attemptId) && id(childId) && token > 0, Reason.INVALID_INPUT);
        return reads.execute(status -> read(attemptId, childId, token, true));
    }
    private Observation read(String attemptId, String childId, long token) {
        return read(attemptId,childId,token,false);
    }
    private Observation read(String attemptId, String childId, long token, boolean committed) {
        var a = attempts(attemptId);
        require(a.childRunId() != null && a.childRunId().equals(childId) && a.fencingToken() == token,
                Reason.IDENTITY_MISMATCH);
        require((committed ? "SUCCEEDED" : "RUNNING").equals(attemptState(attemptId)), Reason.INVALID_STATE);
        require(committed ? a.leaseOwner()==null && a.leaseUntil()==null
                : id(a.leaseOwner()) && a.leaseUntil()!=null && a.leaseUntil().isAfter(Instant.now()), Reason.LEASE_EXPIRED);
        var task = task(a.planId(), a.taskId());
        require(task.version() == a.taskVersion() && (committed ? "SUCCEEDED" : "VERIFYING").equals(task.state())
                && "DELEGATED".equals(task.executionMode()), Reason.INVALID_STATE);
        currentAttempt(a, committed);
        var plans = jdbc.query("SELECT root_run_id,project_id,revision FROM agent_plans WHERE id=?",
                (r,n) -> new String[]{r.getString(1),r.getString(2),Integer.toString(r.getInt(3))}, a.planId());
        require(plans.size() == 1, Reason.IDENTITY_MISMATCH);
        var plan = plans.getFirst();
        var root = run(plan[0]); var child = run(childId);
        require(jdbc.queryForObject("SELECT count(*) FROM agent_task_attempts WHERE child_run_id=?",Integer.class,childId)==1,
                Reason.IDENTITY_MISMATCH);
        require("MAIN".equals(root.role()) && root.parentId() == null && (committed
                ? Set.of("RUNNING","WAITING_INPUT","WAITING_APPROVAL","VERIFYING","COMPLETED").contains(root.state()) : "RUNNING".equals(root.state()))
                && root.cancelRequestedAt() == null, Reason.INVALID_STATE);
        require(root.id().equals(child.parentId()) && "CODER".equals(child.role()), Reason.IDENTITY_MISMATCH);
        requireChildOrigin(root,child,plan[1]);
        require("COMPLETED".equals(child.state()) && child.cancelRequestedAt() == null, Reason.INVALID_STATE);
        require(workspace(root.workspaceRef()) && workspace(child.workspaceRef())
                && !root.workspaceRef().equals(child.workspaceRef()), Reason.IDENTITY_MISMATCH);
        var owners = jdbc.query("""
            SELECT t.thread_id,t.root_run_id,h.project_id,t.active_key,t.state
            FROM turns t JOIN threads h ON h.id=t.thread_id WHERE t.id=?
            """, (r,n) -> new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)}, root.turnId());
        require(owners.size() == 1 && root.id().equals(owners.getFirst()[1])
                && plan[1].equals(owners.getFirst()[2])
                && (committed ? Set.of("RUNNING","WAITING_INPUT","WAITING_APPROVAL","VERIFYING","COMPLETED").contains(owners.getFirst()[4])
                    && ("COMPLETED".equals(owners.getFirst()[4]) ? owners.getFirst()[3]==null : owners.getFirst()[0].equals(owners.getFirst()[3]))
                    : owners.getFirst()[0].equals(owners.getFirst()[3]) && "RUNNING".equals(owners.getFirst()[4]))
                && Integer.parseInt(plan[2]) > 0, Reason.IDENTITY_MISMATCH);
        var inputRows = jdbc.query("""
            SELECT snapshot_id,root_run_id,project_id,seed_workspace_ref,base_digest,input_artifacts,write_scope
            FROM agent_input_snapshots WHERE run_id=?
            """, (r,n) -> new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7)}, childId);
        require(inputRows.size() == 1, Reason.INVALID_SNAPSHOT);
        var frozen = inputRows.getFirst();
        require(id(frozen[0]) && root.id().equals(frozen[1]) && plan[1].equals(frozen[2])
                // Legacy producer records a preallocated child reference here, not source authority.
                && ("".equals(frozen[3]) || workspace(frozen[3]))
                && ("".equals(frozen[4]) || digest(frozen[4])), Reason.INVALID_SNAPSHOT);
        try { require(scopes.parse(task.scope()).equals(scopes.parse(frozen[6])), Reason.INVALID_SNAPSHOT); }
        catch (IllegalArgumentException invalid) { throw new Unavailable(Reason.INVALID_SNAPSHOT); }

        var depends = stringArray(task.dependsOn(), 6, Reason.INVALID_SNAPSHOT);
        require(!depends.contains(task.id()), Reason.INVALID_SNAPSHOT);
        var refs = array(task.inputRefs(), 8, Reason.INVALID_SNAPSHOT);
        var wanted = new LinkedHashSet<String>();
        for (var ref : refs) {
            fields(ref, Set.of("taskId", "output"), Reason.INVALID_SNAPSHOT);
            String dep = text(ref.get("taskId"),64,Reason.INVALID_SNAPSHOT);
            text(ref.get("output"),200,Reason.INVALID_SNAPSHOT);
            require(depends.contains(dep), Reason.INVALID_SNAPSHOT); wanted.add(dep);
        }
        var upstream = new ArrayList<Upstream>();
        var byTask = new LinkedHashMap<String,Upstream>();
        for (String dep : depends) {
            var value = upstream(a.planId(), dep, plan[1]); upstream.add(value); byTask.put(dep,value);
        }
        var expected = new LinkedHashMap<String,Artifact>();
        for (String dep : wanted) for (Artifact artifact : byTask.get(dep).artifacts())
            require(expected.putIfAbsent(artifact.id(),artifact) == null || expected.get(artifact.id()).equals(artifact), Reason.STALE_UPSTREAM);
        var actual = array(frozen[5], 256, Reason.INVALID_SNAPSHOT);
        require(actual.size() == expected.size(), Reason.STALE_UPSTREAM);
        int index = 0;
        for (Artifact artifact : expected.values()) {
            var entry = actual.get(index++);
            fields(entry, Set.of("artifactId","mediaType","sha256"), Reason.INVALID_SNAPSHOT);
            require(artifact.id().equals(text(entry.get("artifactId"),128,Reason.INVALID_SNAPSHOT))
                    && artifact.mediaType().equals(text(entry.get("mediaType"),200,Reason.INVALID_SNAPSHOT))
                    && artifact.sha256().equals(text(entry.get("sha256"),64,Reason.INVALID_SNAPSHOT)), Reason.STALE_UPSTREAM);
        }
        return new Observation(plan[1],root.turnId(),owners.getFirst()[0],root.id(),a.planId(),Integer.parseInt(plan[2]),
                task,a,root,child,new Input(frozen[0],frozen[3],frozen[4],frozen[6],List.copyOf(expected.values())),upstream);
    }

    private Upstream upstream(String plan, String taskId, String project) {
        var task = task(plan,taskId);
        require("SUCCEEDED".equals(task.state()), Reason.STALE_UPSTREAM);
        var ids = jdbc.query("SELECT attempt_id FROM agent_task_attempts WHERE plan_id=? AND task_id=? ORDER BY attempt_no DESC LIMIT 1",
                (r,n) -> r.getString(1), plan,taskId);
        require(!ids.isEmpty(), Reason.STALE_UPSTREAM);
        var a = attempts(ids.getFirst()); currentAttempt(a,true);
        require(a.taskVersion() == task.version() && a.fencingToken()>0
                && "SUCCEEDED".equals(attemptState(a.id())) && id(a.childRunId()), Reason.STALE_UPSTREAM);
        var results = jdbc.query("""
            SELECT task_version,child_run_id,state,output_digest,input_digest,verification_ref,artifact_refs_json
            FROM agent_task_results WHERE attempt_id=? AND plan_id=? AND task_id=?
            """, (r,n) -> new String[]{Integer.toString(r.getInt(1)),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7)},a.id(),plan,taskId);
        require(results.size()==1,Reason.STALE_UPSTREAM); var result=results.getFirst();
        require(task.version()==Integer.parseInt(result[0]) && a.childRunId().equals(result[1])
                && "SUCCEEDED".equals(result[2]) && digest(result[3])
                && (result[4]==null || digest(result[4])),Reason.STALE_UPSTREAM);
        var child = run(a.childRunId());
        var roots=jdbc.queryForList("SELECT root_run_id FROM agent_plans WHERE id=?",String.class,plan);
        var root=run(roots.getFirst());
        require(root.id().equals(child.parentId())
                && "CODER".equals(child.role()) && "COMPLETED".equals(child.state())
                && child.cancelRequestedAt()==null,Reason.STALE_UPSTREAM);
        requireChildOrigin(root,child,project);
        if(result[5]!=null) {
            require(id(result[5]),Reason.STALE_UPSTREAM);
            var verified=jdbc.queryForList("SELECT run_id FROM verifications WHERE id=?",String.class,result[5]);
            require(verified.size()==1 && child.id().equals(verified.getFirst()),Reason.STALE_UPSTREAM);
        }
        var artifacts = new ArrayList<Artifact>();
        for(String artifactId:stringArray(result[6],256,Reason.STALE_UPSTREAM)) {
            var rows=jdbc.query("SELECT id,run_id,project_id,media_type,size_bytes,sha256 FROM artifacts WHERE id=?",
                    (r,n)->new Artifact(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getString(6)),artifactId);
            require(rows.size()==1,Reason.STALE_UPSTREAM); var artifact=rows.getFirst();
            require(child.id().equals(artifact.runId()) && project.equals(artifact.projectId())
                    && artifact.mediaType()!=null && !artifact.mediaType().isBlank()
                    && artifact.bytes()>=0 && artifact.bytes()<=32L*1048576 && digest(artifact.sha256()),Reason.STALE_UPSTREAM);
            artifacts.add(artifact);
        }
        return new Upstream(taskId,task.version(),a.id(),a.attemptNo(),a.fencingToken(),child.id(),result[3],result[4],result[5],artifacts);
    }

    private void currentAttempt(Attempt a, boolean upstream) {
        Reason reason=upstream?Reason.STALE_UPSTREAM:Reason.STALE_ATTEMPT;
        int conflicts=jdbc.queryForObject("""
            SELECT count(*) FROM agent_task_attempts WHERE plan_id=? AND task_id=? AND attempt_id<>?
            AND (attempt_no>=? OR task_version>? OR state NOT IN ('SUCCEEDED','FAILED','CANCELLED'))
            """,Integer.class,a.planId(),a.taskId(),a.id(),a.attemptNo(),a.taskVersion());
        require(a.attemptNo()>0 && conflicts==0,reason);
        require(jdbc.queryForObject("SELECT count(*) FROM agent_task_results WHERE attempt_id=?",Integer.class,a.id())==0 || upstream,reason);
    }
    private void requireChildOrigin(Run root,Run child,String project){
        // Historic internal fixtures share the root Turn. Real ChildRunFactory creates an isolated Turn.
        if(root.turnId().equals(child.turnId()))return;
        require(jdbc.queryForObject("SELECT count(*) FROM turns t JOIN threads h ON h.id=t.thread_id WHERE t.id=? AND t.root_run_id=? AND h.project_id=? AND t.state='COMPLETED' AND t.active_key IS NULL",Integer.class,child.turnId(),child.id(),project)==1,Reason.IDENTITY_MISMATCH);
    }
    private Attempt attempts(String id) {
        var rows=jdbc.query("""
            SELECT attempt_id,plan_id,task_id,task_version,child_run_id,attempt_no,lease_owner,lease_until,fencing_token,budget_reservation_id
            FROM agent_task_attempts WHERE attempt_id=?
            """,(r,n)->new Attempt(r.getString(1),r.getString(2),r.getString(3),r.getInt(4),r.getString(5),r.getInt(6),r.getString(7),
                    r.getTimestamp(8)==null?null:r.getTimestamp(8).toInstant(),r.getObject(9)==null?0:r.getLong(9),r.getString(10)),id);
        require(rows.size()==1,Reason.MISSING_ATTEMPT);return rows.getFirst();
    }
    private String attemptState(String id) { return jdbc.queryForObject("SELECT state FROM agent_task_attempts WHERE attempt_id=?",String.class,id); }
    private Task task(String plan,String id) {
        var rows=jdbc.query("""
            SELECT id,task_id,task_version,state,goal,depends_on,input_refs,deliverable,acceptance,resource_scope,execution_mode,required
            FROM agent_tasks WHERE plan_id=? AND task_id=?
            """,(r,n)->new Task(r.getString(1),r.getString(2),r.getInt(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),
                    r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getBoolean(12)),plan,id);
        require(rows.size()==1,Reason.IDENTITY_MISMATCH);return rows.getFirst();
    }
    private Run run(String id) {
        var rows=jdbc.query("SELECT id,turn_id,parent_run_id,role,state,workspace_ref,cancel_requested_at FROM runs WHERE id=?",
                (r,n)->new Run(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),
                        r.getTimestamp(7)==null?null:r.getTimestamp(7).toInstant()),id);
        require(rows.size()==1,Reason.IDENTITY_MISMATCH);return rows.getFirst();
    }
    private static JsonNode array(String raw,int limit,Reason reason) {
        try {
            require(raw!=null && raw.length()<=131072,reason);
            var parsed=CanonicalTaskJson.parse(raw.getBytes(StandardCharsets.UTF_8));
            require(parsed.isArray() && parsed.size()<=limit,reason);return parsed;
        } catch(IllegalArgumentException invalid) { throw new Unavailable(reason); }
    }
    private static List<String> stringArray(String raw,int limit,Reason reason) {
        var values=new ArrayList<String>();
        for(var node:array(raw,limit,reason)){String value=text(node,128,reason);require(id(value)&&!values.contains(value),reason);values.add(value);}
        return List.copyOf(values);
    }
    private static void fields(JsonNode node,Set<String> expected,Reason reason) {
        require(node.isObject(),reason);var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);require(keys.equals(expected),reason);
    }
    private static String text(JsonNode node,int limit,Reason reason) {
        require(node!=null && node.isTextual() && !node.textValue().isBlank() && node.textValue().length()<=limit,reason);return node.textValue();
    }
    private static boolean id(String id) { return id!=null && id.matches("[A-Za-z0-9_-]{1,128}"); }
    private static boolean workspace(String value) { return value!=null && value.matches("ws_[a-f0-9]{32}"); }
    private static boolean digest(String value) { return value!=null && value.matches("[a-f0-9]{64}"); }
}
