package com.dsh.orchestration.acceptance;

import com.dsh.agent.control.CancellationTokenRegistry;
import com.dsh.common.IdGen;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Internal, one-shot complete-selection execution. No worker wiring or task-success CAS. */
@Service
public class TaskCheckExecutionCoordinator {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;
    private final TaskCheckAttemptPreparation preparation;
    private final TaskCheckLaunchJournal journal;
    private final TaskCheckEvidenceService evidence;
    private final TaskCheckSelectionAssessment assessment;
    private final ObjectMapper mapper;
    private final CancellationTokenRegistry cancellations;
    private final TaskCheckControllerFence controllers;

    public TaskCheckExecutionCoordinator(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            TaskCheckAttemptPreparation preparation, TaskCheckLaunchJournal journal,
            TaskCheckEvidenceService evidence, TaskCheckSelectionAssessment assessment,
            ObjectMapper mapper, CancellationTokenRegistry cancellations) {
        this.jdbc=jdbc;this.preparation=preparation;this.journal=journal;this.evidence=evidence;
        this.assessment=assessment;this.mapper=mapper;this.cancellations=cancellations;
        writes=new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
        controllers=new TaskCheckControllerFence(jdbc,writes);
    }
    public enum Reason { INVALID_INPUT, CALLER_TRANSACTION, SELECTION_INVALID, INPUTS_STALE,
        DEADLINE_INSUFFICIENT, ALREADY_RESERVED, EXECUTION_ABORTED, EXECUTION_UNKNOWN, CHECK_REJECTED }
    public static final class Unavailable extends RuntimeException {
        private final Reason reason;
        private Unavailable(Reason reason){super("Check execution refused: "+reason);this.reason=reason;}
        public Reason reason(){return reason;}
    }
    public static final class Result {
        private final String executionId,state;
        private final List<TaskCheckSelectionAssessment.CheckEvidence> checks;
        private final TaskCheckSelectionAssessment.Observation observation;
        private final Reason reason;
        private Result(String id,String state,List<TaskCheckSelectionAssessment.CheckEvidence> checks,
                TaskCheckSelectionAssessment.Observation observation,Reason reason) {
            executionId=id;this.state=state;this.checks=List.copyOf(checks);this.observation=observation;this.reason=reason;
        }
        public String executionId(){return executionId;}
        public String state(){return state;}
        public List<TaskCheckSelectionAssessment.CheckEvidence> checks(){return checks;}
        public Optional<TaskCheckSelectionAssessment.Observation> observation(){return Optional.ofNullable(observation);}
        public Reason reason(){return reason;}
        public boolean successAuthority(){return false;}
    }
    private record Reservation(String id,String root,Instant lease,Instant deadline,TaskCheckControllerFence.Gate controller) { }
    private static void require(boolean ok,Reason reason){if(!ok)throw new Unavailable(reason);}
    private static boolean id(String s){return s!=null&&s.matches("[A-Za-z0-9_-]{1,128}");}

    /** Identity supplied by a trusted controller. The selected checks come only from actual confirmation. */
    public Result execute(String registrationId,String attemptId,String childRunId,long fencingToken) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),Reason.CALLER_TRANSACTION);
        require(id(registrationId)&&id(attemptId)&&id(childRunId)&&fencingToken>0,Reason.INVALID_INPUT);
        // A durable previous operation blocks replay even when its input/lease has since changed.
        // Do not spend another full cache scan, or permit a changed fence to hide an unresolved operation.
        require(!previousExecution(attemptId),Reason.ALREADY_RESERVED);
        var selected=selected(registrationId);
        var before=prepareAll(registrationId,attemptId,childRunId,fencingToken,selected);
        for(var p:before)preparation.requireCurrent(p);
        require(!cancelled(before.getFirst().observed().rootRunId()),Reason.INPUTS_STALE);
        var reserved=reserve(before,selected);
        var launches=new ArrayList<TaskCheckLaunchJournal.Entry>();
        var completed=new ArrayList<TaskCheckSelectionAssessment.CheckEvidence>();
        TaskCheckLaunchJournal.Entry active=null;
        try {
            // No previously Prepared/evidence survives lease adjustment. Freeze a new whole-set observation.
            var prepared=prepareAll(registrationId,attemptId,childRunId,fencingToken,selected);
            require(selected.equals(selected(registrationId)),Reason.SELECTION_INVALID);
            for(var p:prepared) {
                require(p.observed().attempt().leaseUntil().equals(reserved.lease()),Reason.INPUTS_STALE);
                preparation.requireCurrent(p);
                require(!stopped(reserved,null),Reason.INPUTS_STALE);
                launches.add(journal.register(p));
            }
            String launchIds=canonical(launches.stream().map(TaskCheckLaunchJournal.Entry::id).toList());
            require(jdbc.update("""
                UPDATE task_check_executions SET launch_ids_json=?,updated_at=now()
                WHERE id=? AND state='RESERVED' AND launch_ids_json IS NULL
                """,launchIds,reserved.id())==1,Reason.INPUTS_STALE);
            for(int i=0;i<prepared.size();i++) {
                var p=prepared.get(i);var row=launches.get(i);
                require(!stopped(reserved,row.id()),Reason.INPUTS_STALE);
                active=journal.claim(row.id(),reserved.id(),p).orElseThrow(()->new Unavailable(Reason.INPUTS_STALE));
                // The durable claim is a fact, so independently validate current full bytes immediately before Runner.
                preparation.requireCurrent(p);
                require(!stopped(reserved,row.id()),Reason.INPUTS_STALE);
                TaskCheckEvidenceService.Sealed sealed;
                try(var permit=reserved.controller().enter()) {
                    sealed=evidence.executeAndSeal(p,new StopSignal(reserved,row.id()));
                }
                journal.recordSealed(row.id(),reserved.id(),p,sealed);
                completed.add(new TaskCheckSelectionAssessment.CheckEvidence(p,sealed));active=null;
                var manifest=TaskCheckEvidenceService.parseManifest(evidence.verify(sealed));
                // Cleanup uncertainty blocks every subsequent launch, even if Maven returned zero.
                require(sealed.currentAtReturn()&&!stopped(reserved,row.id())
                    && "ABSENT".equals(manifest.path("execution").path("cleanup").asText()),Reason.CHECK_REJECTED);
            }
            var observed=assessment.assess(completed);
            require(!stopped(reserved,null),Reason.INPUTS_STALE);
            finish(reserved,"OBSERVED");
            return new Result(reserved.id(),"OBSERVED",completed,observed,null);
        } catch(RuntimeException failed) {
            boolean unknown=active!=null;
            if(active!=null) {
                try {journal.markUnknown(active.id(),reserved.id());}catch(RuntimeException unavailable){unknown=true;}
            }
            // Sticky cancellation of unclaimed items; never change STARTING/UNKNOWN into a stopped claim.
            for(var row:launches)try{journal.requestCancel(row.id());}catch(RuntimeException unavailable){unknown=true;}
            String state=unknown?"UNKNOWN":completed.isEmpty()?"ABORTED":"REJECTED";
            finish(reserved,state);
            return new Result(reserved.id(),state,completed,null,unknown?Reason.EXECUTION_UNKNOWN:
                completed.isEmpty()?Reason.EXECUTION_ABORTED:Reason.CHECK_REJECTED);
        } finally {
            // The synchronous Runner call has exited before admission is permanently sealed.
            // Local quiescence does NOT establish that Docker has finished a previously sent request.
            controllers.quiesce(reserved.controller());
        }
    }
    private List<String> selected(String registrationId) {
        var rows=jdbc.query("SELECT binding_json,binding_digest,snapshot_json,snapshot_digest FROM task_check_registrations WHERE id=?",
            (r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4)},registrationId);
        require(rows.size()==1,Reason.SELECTION_INVALID);
        try {
            var row=rows.getFirst();var bound=TaskCheckRegistrationBinding.decode(row[2]);
            require(bound.canonicalBinding.equals(row[0])&&bound.bindingDigest.equals(row[1])&&bound.snapshotDigest.equals(row[3]),Reason.SELECTION_INVALID);
            var preview=mapper.readValue(row[2],com.dsh.api.dto.TaskCheckSelectionPreview.class);
            require(preview.execution()!=null&&preview.checkRefs()!=null&&!preview.checkRefs().isEmpty()
                    &&preview.checkRefs().size()<=8,Reason.SELECTION_INVALID);
            var sorted=new TreeSet<>(preview.checkRefs());
            require(sorted.size()==preview.checkRefs().size()&&sorted.stream().allMatch(TaskCheckExecutionCoordinator::id),Reason.SELECTION_INVALID);
            return List.copyOf(sorted);
        }catch(Unavailable refused){throw refused;}catch(Exception invalid){throw new Unavailable(Reason.SELECTION_INVALID);}
    }
    private List<TaskCheckAttemptPreparation.Prepared> prepareAll(String reg,String attempt,String child,long fence,List<String> selected) {
        var result=new ArrayList<TaskCheckAttemptPreparation.Prepared>();String common=null;
        for(String check:selected) {
            var p=preparation.prepare(reg,check,attempt,child,fence);
            var binding=new LinkedHashMap<>(TaskCheckEvidenceService.binding(p));binding.remove("check");
            binding.put("candidateRoot",p.request().candidateRoot().toString());binding.put("cacheRoot",p.request().execution().cacheRoot().toString());
            binding.put("deliveryDigest",p.delivery().digest());binding.put("zipDigest",CanonicalTaskJson.sha256(p.delivery().zip()));
            String actual=canonical(binding);
            require(common==null||common.equals(actual),Reason.INPUTS_STALE);common=actual;result.add(p);
        }
        return List.copyOf(result);
    }
    private Reservation reserve(List<TaskCheckAttemptPreparation.Prepared> before,List<String> selected) {
        var first=before.getFirst();var o=first.observed();var a=o.attempt();
        long seconds=180;
        for(var p:before)seconds=Math.addExact(seconds,p.request().check().timeoutSeconds()+60L);
        final long duration=seconds;
        return writes.execute(tx->{
            var leases=jdbc.query("SELECT lease_until FROM agent_task_attempts WHERE attempt_id=? FOR UPDATE",
                (r,n)->r.getTimestamp(1)==null?null:r.getTimestamp(1).toInstant(),a.id());
            require(leases.size()==1&&a.leaseUntil().equals(leases.getFirst()),Reason.INPUTS_STALE);
            require(!previousExecution(a.id()),Reason.ALREADY_RESERVED);
            var deadlines=jdbc.query("SELECT budget_deadline_at FROM runs WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),o.rootRunId());
            require(deadlines.size()==1,Reason.INPUTS_STALE);Instant deadline=deadlines.getFirst();
            Instant now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
            require(a.leaseUntil().isAfter(now),Reason.INPUTS_STALE);
            Instant desired=now.plusSeconds(duration);Instant lease=desired.isAfter(a.leaseUntil())?desired:a.leaseUntil();
            require(!lease.isAfter(deadline),Reason.DEADLINE_INSUFFICIENT);
            // Whole verification phase may spend most of its time re-reading trusted bytes. The floor above
            // rejects obviously insufficient budgets; fixed IO margins cannot guarantee completion on Windows.
            // Use only the remaining, already granted root time. No heartbeat, root-budget raise or expired revival.
            lease=deadline;
            // PostgreSQL timestamps have microsecond precision. Bind exactly what is persisted, never Java nanoseconds.
            lease=Instant.ofEpochSecond(lease.getEpochSecond(),(lease.getNano()/1000)*1000L);
            int changed=jdbc.update("""
                UPDATE agent_task_attempts a SET lease_until=?,updated_at=now()
                WHERE a.attempt_id=? AND a.state='RUNNING' AND a.fencing_token=? AND a.attempt_no=?
                  AND a.task_version=? AND a.child_run_id=? AND a.lease_owner=? AND a.lease_until=?
                  AND a.lease_until>clock_timestamp()
                  AND EXISTS (SELECT 1 FROM agent_tasks t JOIN agent_plans p ON p.id=t.plan_id
                    JOIN runs r ON r.id=p.root_run_id JOIN runs c ON c.id=a.child_run_id JOIN turns u ON u.id=r.turn_id
                    WHERE t.plan_id=a.plan_id AND t.task_id=a.task_id AND t.id=? AND t.task_version=? AND t.state='VERIFYING'
                    AND p.id=? AND p.revision=? AND r.id=? AND r.state='RUNNING' AND r.cancel_requested_at IS NULL
                    AND r.budget_deadline_at=? AND r.budget_deadline_at>=? AND c.state='COMPLETED' AND c.cancel_requested_at IS NULL
                    AND u.id=? AND u.state='RUNNING' AND u.active_key=?)
                """,Timestamp.from(lease),a.id(),a.fencingToken(),a.attemptNo(),a.taskVersion(),a.childRunId(),a.leaseOwner(),Timestamp.from(a.leaseUntil()),
                o.task().rowId(),o.task().version(),o.planId(),o.planRevision(),o.rootRunId(),Timestamp.from(deadline),Timestamp.from(lease),o.turnId(),o.threadId());
            require(changed==1,Reason.INPUTS_STALE);
            String executionId=IdGen.next("checkexec_");
            jdbc.update("""
                INSERT INTO task_check_executions (id,root_run_id,attempt_id,fencing_token,registration_id,check_refs_json,
                    lease_until,root_deadline,state,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,'RESERVED',now(),now())
                """,executionId,o.rootRunId(),a.id(),a.fencingToken(),first.request().registrationId(),canonical(selected),Timestamp.from(lease),Timestamp.from(deadline));
            var controller=controllers.register(executionId,o.rootRunId(),a.id(),a.fencingToken());
            return new Reservation(executionId,o.rootRunId(),lease,deadline,controller);
        });
    }
    private boolean cancelled(String root) {
        var token=cancellations.get(root);return Thread.currentThread().isInterrupted()||(token!=null&&token.isCancelled());
    }
    private boolean previousExecution(String attempt) {
        return jdbc.queryForObject("SELECT count(*) FROM task_check_executions WHERE attempt_id=?",Integer.class,attempt)!=0
            ||jdbc.queryForObject("SELECT count(*) FROM task_check_launches WHERE attempt_id=?",Integer.class,attempt)!=0;
    }
    private boolean stopped(Reservation r,String launchId) {
        if(cancelled(r.root())||!r.lease().isAfter(Instant.now())||!r.deadline().isAfter(Instant.now()))return true;
        try {
            boolean live=jdbc.queryForObject("""
                SELECT count(*) FROM task_check_executions e JOIN runs r ON r.id=e.root_run_id
                WHERE e.id=? AND e.state='RESERVED' AND e.lease_until=? AND e.root_deadline=?
                  AND r.state='RUNNING' AND r.cancel_requested_at IS NULL AND r.budget_deadline_at=?
                """,Integer.class,r.id(),Timestamp.from(r.lease()),Timestamp.from(r.deadline()),Timestamp.from(r.deadline()))==1;
            if(!live)return true;
            return launchId!=null&&jdbc.queryForObject("SELECT count(*) FROM task_check_launches WHERE id=? AND cancel_requested=false AND state IN ('PENDING','STARTING','SEALED')",Integer.class,launchId)!=1;
        }catch(RuntimeException unavailable){return true;}
    }
    private final class StopSignal implements BooleanSupplier {
        private final Reservation reservation;private final String launchId;private long next;private boolean stopped;
        private StopSignal(Reservation r,String id){reservation=r;launchId=id;}
        public boolean getAsBoolean(){
            if(stopped)return true;
            if(cancelled(reservation.root())||!reservation.lease().isAfter(Instant.now())||!reservation.deadline().isAfter(Instant.now()))return stopped=true;
            long now=System.nanoTime();if(now>=next){next=now+500_000_000L;stopped=stopped(reservation,launchId);}return stopped;
        }
    }
    private void finish(Reservation r,String state) {
        require(jdbc.update("UPDATE task_check_executions SET state=?,updated_at=now() WHERE id=? AND state='RESERVED'",state,r.id())==1,Reason.EXECUTION_UNKNOWN);
    }
    private String canonical(Object value){return new String(CanonicalTaskJson.encode(mapper.valueToTree(value)),java.nio.charset.StandardCharsets.UTF_8);}
}
