package com.dsh.orchestration.acceptance;

import com.dsh.agent.control.CancellationTokenRegistry;
import com.dsh.application.ArtifactStore;
import com.dsh.orchestration.event.OrchestrationEventBridge;
import com.dsh.orchestration.task.TaskEventJournal;
import com.dsh.storage.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Trusted internal final CAS. No HTTP/tool/worker wiring or downstream success authority. */
@Service
public class TaskCheckCompletionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;
    private final TaskCheckSelectionAssessment assessment;
    private final TaskCheckAttemptAuthority authority;
    private final TaskCheckLaunchJournal launches;
    private final ArtifactStore artifacts;
    private final TaskResultRepository results;
    private final RootTaskAcceptanceModes modes;
    private final ObjectProvider<TaskEventJournal> events;
    private final ObjectProvider<OrchestrationEventBridge> bridge;
    private final CancellationTokenRegistry cancellations;
    private final ObjectMapper mapper;

    public TaskCheckCompletionService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            TaskCheckSelectionAssessment assessment, TaskCheckAttemptAuthority authority,
            TaskCheckLaunchJournal launches, ArtifactStore artifacts,
            TaskResultRepository results, RootTaskAcceptanceModes modes, ObjectProvider<TaskEventJournal> events,
            ObjectProvider<OrchestrationEventBridge> bridge, CancellationTokenRegistry cancellations, ObjectMapper mapper) {
        this.jdbc=jdbc;this.assessment=assessment;this.authority=authority;
        this.launches=launches;this.artifacts=artifacts;this.results=results;this.modes=modes;
        this.events=events;this.bridge=bridge;this.cancellations=cancellations;this.mapper=mapper;
        writes=new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);writes.setTimeout(5);
    }
    public enum Reason { INVALID_INPUT, CALLER_TRANSACTION, NOT_OBSERVED, INPUTS_STALE,
        EVIDENCE_INVALID, NOT_STRONG, ALREADY_COMMITTED, EVENTS_UNAVAILABLE }
    public static final class Unavailable extends RuntimeException {
        private final Reason reason;
        private Unavailable(Reason reason){super("Check completion refused: "+reason);this.reason=reason;}
        public Reason reason(){return reason;}
    }
    /** Reference to a committed receipt; consumers must revalidate it under M1E. */
    public record Committed(String executionId,String attemptId,String artifactId,String receiptDigest) { }
    private static void require(boolean ok,Reason reason){if(!ok)throw new Unavailable(reason);}
    private boolean stopped(String root){var token=cancellations.get(root);
        return Thread.currentThread().isInterrupted()||(token!=null&&token.isCancelled());}
    private String canonical(Object value){return new String(CanonicalTaskJson.encode(mapper.valueToTree(value)),StandardCharsets.UTF_8);}

    /** Only the real coordinator constructs Result. Caller flags, partial evidence and SubagentResult are rejected. */
    public Committed commit(TaskCheckExecutionCoordinator.Result result) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),Reason.CALLER_TRANSACTION);
        require(result!=null,Reason.INVALID_INPUT);
        require("OBSERVED".equals(result.state())&&result.reason()==null&&result.observation().isPresent()
                &&!result.checks().isEmpty()&&result.checks().size()<=8,Reason.NOT_OBSERVED);
        var pairs=result.checks();var first=pairs.getFirst().prepared();var o=first.observed();var a=o.attempt();
        var projects=projectInputs();
        require(projects.stream().anyMatch(p->p.id().equals(o.projectId())&&p.trusted()),Reason.INPUTS_STALE);
        require(!stopped(o.rootRunId()),Reason.INPUTS_STALE);
        require(jdbc.queryForObject("SELECT count(*) FROM task_check_completions WHERE attempt_id=?",Integer.class,a.id())==0,
                Reason.ALREADY_COMMITTED);
        var journal=events.getIfAvailable();var emitter=bridge.getIfAvailable();
        require(journal!=null&&emitter!=null,Reason.EVENTS_UNAVAILABLE);
        // All large IO is outside the write transaction. This also re-parses actual XML/report closure.
        var observed=assessment.assess(pairs);
        require(observed.digest().equals(result.observation().orElseThrow().digest()),Reason.EVIDENCE_INVALID);
        var refs=new TreeMap<String,TaskCheckEvidenceService.ArtifactRef>();
        var entries=new ArrayList<TaskCheckLaunchJournal.Entry>();
        for(var pair:pairs){
            refs.put(pair.sealed().manifest().id(),pair.sealed().manifest());
            for(var ref:pair.sealed().members())require(refs.putIfAbsent(ref.id(),ref)==null,Reason.EVIDENCE_INVALID);
            var rows=jdbc.queryForList("SELECT id FROM task_check_launches WHERE attempt_id=? AND fencing_token=? AND check_id=?",
                    String.class,a.id(),a.fencingToken(),pair.prepared().request().check().checkId());
            require(rows.size()==1,Reason.EVIDENCE_INVALID);entries.add(launches.inspect(rows.getFirst()));
        }
        var selected=pairs.stream().map(p->p.prepared().request().check().checkId()).sorted().toList();
        var receipt=new LinkedHashMap<String,Object>();receipt.put("version","dsh-task-check-completion-v1");
        receipt.put("executionId",result.executionId());receipt.put("binding",TaskCheckEvidenceService.binding(first));
        receipt.put("checks",observed.checks());receipt.put("assessmentDigest",observed.digest());
        receipt.put("projectInputsDigest",CanonicalTaskJson.digest(mapper.valueToTree(projects)));
        receipt.put("launchIds",entries.stream().map(TaskCheckLaunchJournal.Entry::id).sorted().toList());
        receipt.put("artifacts",List.copyOf(refs.values()));receipt.put("deliveryDigest",first.delivery().digest());
        receipt.put("inputDigest",CanonicalTaskJson.digest(mapper.valueToTree(Map.of("input",o.input(),"upstream",o.upstream()))));
        byte[] bytes=CanonicalTaskJson.encode(mapper.valueToTree(receipt));
        require(bytes.length<=2*1048576,Reason.EVIDENCE_INVALID);
        var artifact=artifacts.saveCheckEvidence(a.childRunId(),o.projectId(),first.request().workspaceId(),
                "check-completion.json","application/json",bytes);
        require(Arrays.equals(bytes,artifacts.readVerifiedContent(artifact.getId(),2*1048576)),Reason.EVIDENCE_INVALID);
        // A slow receipt write cannot make the previous file/Host observation a final permission.
        assessment.requireCurrent(observed);
        require(projects.equals(projectInputs()),Reason.INPUTS_STALE);
        require(!stopped(o.rootRunId()),Reason.INPUTS_STALE);
        return writes.execute(tx->{
            lockSources(o,result.executionId(),first.request().registrationId(),refs.keySet(),artifact.getId());
            require(projects.equals(projectInputs()),Reason.INPUTS_STALE);
            require(!stopped(o.rootRunId()),Reason.INPUTS_STALE);
            require(modes.isStrong(o.rootRunId()),Reason.NOT_STRONG);
            require(jdbc.queryForObject("SELECT count(*) FROM task_check_completions WHERE attempt_id=?",Integer.class,a.id())==0,
                    Reason.ALREADY_COMMITTED);
            authority.requireCurrentJoined(o);
            require(o.turnId().equals(jdbc.queryForObject("SELECT active_turn_id FROM threads WHERE id=?",String.class,o.threadId())),Reason.INPUTS_STALE);
            requireOriginals(first);
            requireRegistration(first);
            var slot=jdbc.query("SELECT root_run_id,attempt_id,fencing_token,registration_id,check_refs_json,lease_until,root_deadline,state,launch_ids_json FROM task_check_executions WHERE id=?",
                    (r,n)->new Slot(r.getString(1),r.getString(2),r.getLong(3),r.getString(4),r.getString(5),r.getTimestamp(6).toInstant(),r.getTimestamp(7).toInstant(),r.getString(8),r.getString(9)),result.executionId());
            require(slot.size()==1,Reason.EVIDENCE_INVALID);var s=slot.getFirst();
            Instant deadline=jdbc.queryForObject("SELECT budget_deadline_at FROM runs WHERE id=?",Timestamp.class,o.rootRunId()).toInstant();
            require(s.root().equals(o.rootRunId())&&s.attempt().equals(a.id())&&s.fence()==a.fencingToken()
                    &&s.registration().equals(first.request().registrationId())&&s.checks().equals(canonical(selected))
                    &&s.lease().equals(a.leaseUntil())&&s.deadline().equals(deadline)&&"OBSERVED".equals(s.state()),Reason.INPUTS_STALE);
            require(new TreeSet<>(decodeIds(s.launches())).equals(new TreeSet<>(entries.stream().map(TaskCheckLaunchJournal.Entry::id).toList())),Reason.EVIDENCE_INVALID);
            require(jdbc.queryForObject("SELECT count(*) FROM task_check_launches WHERE attempt_id=?",Integer.class,a.id())==pairs.size()
                    &&jdbc.queryForObject("SELECT count(*) FROM task_check_executions WHERE attempt_id=?",Integer.class,a.id())==1,Reason.EVIDENCE_INVALID);
            for(int i=0;i<pairs.size();i++)requireLaunch(result.executionId(),pairs.get(i),entries.get(i));
            for(var ref:refs.values())requireArtifact(ref);
            requireArtifact(new TaskCheckEvidenceService.ArtifactRef(artifact.getId(),artifact.getRunId(),artifact.getProjectId(),
                    artifact.getName(),artifact.getMediaType(),artifact.getSizeBytes(),artifact.getSha256()));
            // Last DB clock check, after all lock waits and validation. Never revive expired work or extend budgets.
            int changed=jdbc.update("""
                UPDATE agent_task_attempts SET state='SUCCEEDED',lease_owner=NULL,lease_until=NULL,updated_at=now()
                WHERE attempt_id=? AND state='RUNNING' AND fencing_token=? AND lease_owner=? AND lease_until=?
                  AND lease_until>clock_timestamp() AND EXISTS(SELECT 1 FROM runs WHERE id=? AND state='RUNNING'
                    AND cancel_requested_at IS NULL AND budget_deadline_at=? AND budget_deadline_at>clock_timestamp())
                """,a.id(),a.fencingToken(),a.leaseOwner(),Timestamp.from(a.leaseUntil()),o.rootRunId(),Timestamp.from(deadline));
            require(changed==1,Reason.INPUTS_STALE);
            require(jdbc.update("UPDATE agent_tasks SET state='SUCCEEDED',updated_at=now() WHERE id=? AND task_version=? AND state='VERIFYING'",
                    o.task().rowId(),o.task().version())==1,Reason.INPUTS_STALE);
            require(jdbc.update("UPDATE task_check_executions SET state='COMMITTED',updated_at=now() WHERE id=? AND state='OBSERVED'",result.executionId())==1,Reason.INPUTS_STALE);
            jdbc.update("""
                INSERT INTO task_check_completions(execution_id,attempt_id,root_run_id,fencing_token,artifact_id,receipt_digest,receipt_json,committed_at)
                VALUES(?,?,?,?,?,?,?,now())
                """,result.executionId(),a.id(),o.rootRunId(),a.fencingToken(),artifact.getId(),artifact.getSha256(),new String(bytes,StandardCharsets.UTF_8));
            var usage=jdbc.queryForMap("SELECT budget_model_requests_used,budget_tool_calls_used,budget_settled_tokens,created_at,updated_at FROM runs WHERE id=?",a.childRunId());
            // Public result references stay bounded; raw reports/logs remain in the receipt closure.
            var ids=new TreeSet<String>();ids.add(artifact.getId());
            for(var pair:pairs){ids.add(pair.sealed().manifest().id());
                for(var ref:pair.sealed().members())if("application/zip".equals(ref.mediaType()))ids.add(ref.id());}
            int requests=((Number)usage.get("budget_model_requests_used")).intValue(),tools=((Number)usage.get("budget_tool_calls_used")).intValue();
            Integer tokens=usage.get("budget_settled_tokens")==null?null:((Number)usage.get("budget_settled_tokens")).intValue();
            long duration=Duration.between(((Timestamp)usage.get("created_at")).toInstant(),((Timestamp)usage.get("updated_at")).toInstant()).toMillis();
            require(requests>=0&&tools>=0&&(tokens==null||tokens>=0)&&duration>=0,Reason.INPUTS_STALE);
            var row=new TaskResultEntity(a.id(),o.planId(),o.task().id(),o.task().version(),a.childRunId(),"SUCCEEDED",null,null,
                    canonical(ids),null,first.delivery().digest(),(String)receipt.get("inputDigest"),null,requests,tools,tokens,duration,Instant.now());
            results.saveAndFlush(row);
            emitter.taskResultReady(o.rootRunId(),row,a.childRunId());
            journal.append(o.rootRunId(),o.planId(),o.task().id(),o.task().version(),a.id(),a.childRunId(),"VERIFYING","SUCCEEDED","EXECUTABLE_CHECKS_COMMITTED");
            require(!stopped(o.rootRunId())&&jdbc.queryForObject("SELECT count(*) FROM task_check_executions WHERE id=? AND state='COMMITTED' AND lease_until>clock_timestamp() AND root_deadline>clock_timestamp()",
                    Integer.class,result.executionId())==1,Reason.INPUTS_STALE);
            return new Committed(result.executionId(),a.id(),artifact.getId(),artifact.getSha256());
        });
    }
    private void requireOriginals(TaskCheckAttemptPreparation.Prepared p){
        // Do not invoke humanInputs/readJoined: feedback-origin filtering may read a sealed file.
        // For now roots containing synthetic feedback are refused, rather than inferred from metadata.
        var expected=p.request().sourceSnapshot();
        var originals=jdbc.query("SELECT id,sequence,content FROM messages WHERE turn_id=? AND thread_id=? AND role='user' ORDER BY sequence,id",
                (r,n)->new com.dsh.api.dto.TaskCheckPreview.Requirement(r.getString(1),Long.toString(r.getLong(2)),r.getString(3)),expected.turnId(),p.observed().threadId());
        require(originals.size()<=32&&originals.equals(expected.requirements())
                &&originals.stream().noneMatch(r->r.content()==null||r.content().startsWith("[verification-fix:")),Reason.INPUTS_STALE);
        require(!originals.isEmpty()&&originals.getFirst().content().equals(jdbc.queryForObject("SELECT input FROM turns WHERE id=?",String.class,expected.turnId())),Reason.INPUTS_STALE);
    }
    private record Slot(String root,String attempt,long fence,String registration,String checks,Instant lease,Instant deadline,String state,String launches) { }
    /** Full DB exclusion/authorization identity; paths remain private and only its digest is sealed. */
    private record ProjectInput(String id,String root,boolean trusted,int version) { }
    private List<ProjectInput> projectInputs(){
        var rows=jdbc.query("SELECT id,root_path,trusted,version FROM projects ORDER BY id LIMIT 4097",
                (r,n)->new ProjectInput(r.getString(1),r.getString(2),r.getBoolean(3),r.getInt(4)));
        require(!rows.isEmpty()&&rows.size()<=4096&&rows.stream().allMatch(p->p.id()!=null
                &&p.id().matches("[A-Za-z0-9_-]{1,128}")&&p.root()!=null&&!p.root().isBlank()&&p.version()>=0),Reason.INPUTS_STALE);
        return List.copyOf(rows);
    }
    private List<String> decodeIds(String json){try{var node=TaskCheckEvidenceService.parseManifest(json.getBytes(StandardCharsets.UTF_8));
        require(node.isArray()&&node.size()>0&&node.size()<=8,Reason.EVIDENCE_INVALID);var ids=new ArrayList<String>();
        for(var n:node){require(n.isTextual()&&n.asText().matches("[A-Za-z0-9_-]{1,128}"),Reason.EVIDENCE_INVALID);ids.add(n.asText());}
        require(new HashSet<>(ids).size()==ids.size(),Reason.EVIDENCE_INVALID);return ids;
    }catch(RuntimeException invalid){throw new Unavailable(Reason.EVIDENCE_INVALID);}}
    private void lock(String sql,Object... args){jdbc.queryForList(sql,args);}
    private void lockSources(TaskCheckAttemptAuthority.Observation o,String execution,String registration,Set<String> refs,String receipt){
        TaskCheckLifecycleGuard.lockRootSource(jdbc,o.rootRunId());
        // Host authorization excludes every project root. SHARE also blocks inserts/deletes (phantoms),
        // unlike a target-project row lock. No directory or byte IO is performed while holding it.
        jdbc.execute("LOCK TABLE projects IN SHARE MODE");
        lock("SELECT root_run_id FROM root_task_acceptance_modes WHERE root_run_id=? FOR UPDATE",o.rootRunId());
        lock("SELECT id FROM agent_plans WHERE id=? FOR UPDATE",o.planId());
        lock("SELECT id FROM agent_tasks WHERE plan_id=? ORDER BY id FOR UPDATE",o.planId());
        lock("SELECT attempt_id FROM agent_task_attempts WHERE plan_id=? ORDER BY attempt_id FOR UPDATE",o.planId());
        var runs=new TreeSet<String>();runs.add(o.child().id());for(var u:o.upstream())runs.add(u.childRunId());
        for(var id:runs)lock("SELECT id FROM runs WHERE id=? FOR UPDATE",id);
        lock("SELECT run_id FROM agent_input_snapshots WHERE run_id=? FOR UPDATE",o.child().id());
        // Production human-input insertion shares the thread source lock; lock existing originals by ID.
        lock("SELECT id FROM messages WHERE turn_id=? AND role='user' ORDER BY id FOR UPDATE",o.turnId());
        lock("SELECT id FROM task_check_registrations WHERE id=? FOR UPDATE",registration);
        lock("SELECT id FROM task_check_executions WHERE attempt_id=? ORDER BY id FOR UPDATE",o.attempt().id());
        lock("SELECT id FROM task_check_launches WHERE attempt_id=? ORDER BY id FOR UPDATE",o.attempt().id());
        for(var u:o.upstream()){
            lock("SELECT attempt_id FROM agent_task_results WHERE attempt_id=? FOR UPDATE",u.attemptId());
            if(u.verificationRef()!=null)lock("SELECT id FROM verifications WHERE id=? FOR UPDATE",u.verificationRef());
        }
        var all=new TreeSet<>(refs);all.add(receipt);for(var u:o.upstream())for(var a:u.artifacts())all.add(a.id());
        for(var id:all)lock("SELECT id FROM artifacts WHERE id=? FOR UPDATE",id);
    }
    private void requireRegistration(TaskCheckAttemptPreparation.Prepared p){
        var rows=jdbc.queryForList("SELECT * FROM task_check_registrations WHERE id=?",p.request().registrationId());
        require(rows.size()==1,Reason.EVIDENCE_INVALID);var r=rows.getFirst();
        try{
            var bound=TaskCheckRegistrationBinding.decode((String)r.get("snapshot_json"));
            var o=p.observed();
            require(bound.canonicalBinding.equals(r.get("binding_json"))&&bound.bindingDigest.equals(r.get("binding_digest"))
                    &&bound.snapshotDigest.equals(r.get("snapshot_digest"))&&bound.bindingDigest.equals(p.request().bindingDigest())
                    &&bound.projectId.equals(o.projectId())&&bound.rootRunId.equals(o.rootRunId())&&bound.turnId.equals(o.turnId())
                    &&bound.planId.equals(o.planId())&&bound.taskId.equals(o.task().id())&&bound.taskVersion==o.task().version()
                    &&bound.projectId.equals(r.get("project_id"))&&bound.rootRunId.equals(r.get("root_run_id"))
                    &&bound.turnId.equals(r.get("turn_id"))&&bound.planId.equals(r.get("plan_id"))&&bound.taskId.equals(r.get("task_id"))
                    &&bound.taskVersion==((Number)r.get("task_version")).intValue()
                    &&bound.observedPlanRevision==((Number)r.get("observed_plan_revision")).intValue()&&r.get("recorded_at")!=null,
                    Reason.EVIDENCE_INVALID);
        }catch(RuntimeException invalid){throw new Unavailable(Reason.EVIDENCE_INVALID);}
    }
    private void requireLaunch(String execution,TaskCheckSelectionAssessment.CheckEvidence pair,TaskCheckLaunchJournal.Entry entry){
        var p=pair.prepared();String expected=launches.intent(p);
        var rows=jdbc.queryForList("SELECT root_run_id,attempt_id,fencing_token,check_id,operation_id,intent_json,intent_digest,state,claim_owner,cancel_requested,manifest_json FROM task_check_launches WHERE id=?",entry.id());
        require(rows.size()==1,Reason.EVIDENCE_INVALID);var r=rows.getFirst();
        require(p.observed().rootRunId().equals(r.get("root_run_id"))&&p.observed().attempt().id().equals(r.get("attempt_id"))
                &&((Number)r.get("fencing_token")).longValue()==p.observed().attempt().fencingToken()
                &&p.request().check().checkId().equals(r.get("check_id"))&&p.request().operationId().equals(r.get("operation_id"))
                &&expected.equals(r.get("intent_json"))&&CanonicalTaskJson.sha256(expected.getBytes(StandardCharsets.UTF_8)).equals(r.get("intent_digest"))
                &&"SEALED".equals(r.get("state"))&&execution.equals(r.get("claim_owner"))&&Boolean.FALSE.equals(r.get("cancel_requested"))
                &&canonical(pair.sealed().manifest()).equals(r.get("manifest_json")),Reason.EVIDENCE_INVALID);
    }
    private void requireArtifact(TaskCheckEvidenceService.ArtifactRef ref){
        var rows=jdbc.queryForList("SELECT run_id,project_id,name,media_type,size_bytes,sha256 FROM artifacts WHERE id=?",ref.id());
        require(rows.size()==1,Reason.EVIDENCE_INVALID);var r=rows.getFirst();
        require(ref.runId().equals(r.get("run_id"))&&ref.projectId().equals(r.get("project_id"))&&ref.name().equals(r.get("name"))
                &&ref.mediaType().equals(r.get("media_type"))&&ref.bytes()==((Number)r.get("size_bytes")).longValue()
                &&ref.sha256().equals(r.get("sha256")),Reason.EVIDENCE_INVALID);
    }
}
