package com.dsh.orchestration.acceptance;

import com.dsh.agent.control.CancellationTokenRegistry;
import com.dsh.common.IdGen;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Durable internal launch facts, never an execution/success capability. No production scheduler consumer. */
@Service
public class TaskCheckLaunchJournal {
    private static final Set<String> STATES=Set.of("PENDING","STARTING","UNKNOWN","SEALED","CANCELLED");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;
    private final TaskCheckAttemptPreparation preparation;
    private final TaskCheckEvidenceService evidence;
    private final ObjectMapper mapper;
    private final CancellationTokenRegistry cancellations;

    public TaskCheckLaunchJournal(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            TaskCheckAttemptPreparation preparation, TaskCheckEvidenceService evidence,
            ObjectMapper mapper, CancellationTokenRegistry cancellations) {
        this.jdbc=jdbc;this.preparation=preparation;this.evidence=evidence;this.mapper=mapper;this.cancellations=cancellations;
        writes=new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
    }
    public enum Reason { INVALID_INPUT, CALLER_TRANSACTION, INPUTS_STALE, CONFLICT, MISSING, CORRUPT, EVIDENCE_INVALID }
    public static final class Unavailable extends RuntimeException {
        private final Reason reason;
        private Unavailable(Reason reason){super("Check launch journal refused: "+reason);this.reason=reason;}
        public Reason reason(){return reason;}
    }
    private static void require(boolean ok,Reason reason){if(!ok)throw new Unavailable(reason);}
    /** Read-only fact. STARTING is not proof that a process started or stopped. */
    public record Entry(String id,String rootRunId,String attemptId,long fencingToken,String checkId,
            String operationId,String intentDigest,String intentJson,String state,String claimOwner,
            boolean cancelRequested,String manifestJson,Instant createdAt,Instant updatedAt) {
        public boolean launchAuthority(){return false;}
        public boolean successAuthority(){return false;}
    }
    private void boundary(){require(!TransactionSynchronizationManager.isActualTransactionActive(),Reason.CALLER_TRANSACTION);}
    private static boolean id(String s){return s!=null && s.matches("[A-Za-z0-9_-]{1,128}");}
    private void current(TaskCheckAttemptPreparation.Prepared p) {
        require(p!=null,Reason.INVALID_INPUT);
        var token=cancellations.get(p.observed().rootRunId());
        require(!Thread.currentThread().isInterrupted() && (token==null || !token.isCancelled()),Reason.INPUTS_STALE);
        try {preparation.requireCurrent(p);}catch(RuntimeException stale){throw new Unavailable(Reason.INPUTS_STALE);}
        token=cancellations.get(p.observed().rootRunId());
        require(!Thread.currentThread().isInterrupted() && (token==null || !token.isCancelled()),Reason.INPUTS_STALE);
    }
    String intent(TaskCheckAttemptPreparation.Prepared p) {
        var value=new LinkedHashMap<String,Object>();
        value.put("version","dsh-task-check-launch-v1");value.put("operationId",p.request().operationId());
        value.put("binding",TaskCheckEvidenceService.binding(p));
        value.put("delivery",Map.of("digest",p.delivery().digest(),"baselineDigest",p.delivery().baselineDigest(),
                "files",p.delivery().files(),"zipDigest",CanonicalTaskJson.sha256(p.delivery().zip())));
        byte[] bytes=CanonicalTaskJson.encode(mapper.valueToTree(value));
        require(bytes.length<=2*1048576,Reason.INVALID_INPUT);
        // Same strict, bounded parser as persisted execution evidence.
        try {TaskCheckEvidenceService.parseManifest(bytes);}catch(RuntimeException invalid){throw new Unavailable(Reason.INVALID_INPUT);}
        return new String(bytes,StandardCharsets.UTF_8);
    }
    /** Current Prepared binds one immutable operation. A newly generated operation cannot replace the slot. */
    public Entry register(TaskCheckAttemptPreparation.Prepared p) {
        boundary();current(p);String json=intent(p);var a=p.observed().attempt();
        return writes.execute(tx->{
            // Serialize the last registration predicate with persistent root cancellation.
            TaskCheckLifecycleGuard.lockRootSource(jdbc,p.observed().rootRunId());
            // Serialize registration with one-time verification lease reservation. Old Prepared may not cross that CAS.
            var leases=jdbc.query("SELECT lease_until FROM agent_task_attempts WHERE attempt_id=? FOR UPDATE",
                    (r,n)->r.getTimestamp(1)==null?null:r.getTimestamp(1).toInstant(),a.id());
            require(leases.size()==1 && a.leaseUntil().equals(leases.getFirst())
                    && a.leaseUntil().isAfter(Instant.now()),Reason.INPUTS_STALE);
            require(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM runs r JOIN turns u ON u.id=r.turn_id
                  WHERE r.id=? AND r.state='RUNNING' AND r.cancel_requested_at IS NULL
                  AND u.id=? AND u.state='RUNNING' AND u.active_key=?)
                """,Boolean.class,p.observed().rootRunId(),p.observed().turnId(),p.observed().threadId())),
                    Reason.INPUTS_STALE);
            jdbc.update("""
                INSERT INTO task_check_launches
                (id,root_run_id,attempt_id,fencing_token,check_id,operation_id,intent_digest,intent_json,
                 state,cancel_requested,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,'PENDING',false,now(),now())
                ON CONFLICT DO NOTHING
                """,IdGen.next("checklaunch_"),p.observed().rootRunId(),a.id(),a.fencingToken(),p.request().check().checkId(),
                    p.request().operationId(),CanonicalTaskJson.sha256(json.getBytes(StandardCharsets.UTF_8)),json);
            var rows=query("WHERE attempt_id=? AND fencing_token=? AND check_id=?",a.id(),a.fencingToken(),p.request().check().checkId());
            require(rows.size()==1,Reason.CONFLICT);var result=rows.getFirst();
            require(result.intentJson().equals(json),Reason.CONFLICT);
            return result;
        });
    }
    /** Journal CAS only. Future coordinator must separately revalidate all inputs before actual launch. */
    public Optional<Entry> claim(String launchId,String owner,TaskCheckAttemptPreparation.Prepared p) {
        boundary();require(id(launchId)&&id(owner),Reason.INVALID_INPUT);current(p);String json=intent(p);
        return writes.execute(tx->{
            Entry row=locked(launchId);require(row.intentJson().equals(json),Reason.CONFLICT);
            var a=p.observed().attempt();var o=p.observed();
            // Last DB predicate prevents an already committed cancellation/state/lease change winning before this CAS.
            int changed=jdbc.update("""
                UPDATE task_check_launches SET state='STARTING',claim_owner=?,updated_at=now()
                WHERE id=? AND state='PENDING' AND cancel_requested=false
                AND EXISTS (SELECT 1 FROM agent_task_attempts a
                  JOIN agent_tasks t ON t.plan_id=a.plan_id AND t.task_id=a.task_id
                  JOIN agent_plans p ON p.id=a.plan_id JOIN runs r ON r.id=p.root_run_id
                  JOIN runs c ON c.id=a.child_run_id JOIN turns u ON u.id=r.turn_id
                  WHERE a.attempt_id=? AND a.state='RUNNING' AND a.fencing_token=?
                  AND a.attempt_no=? AND a.task_version=? AND a.child_run_id=? AND a.lease_owner=?
                  AND a.lease_until=? AND a.lease_until>clock_timestamp()
                  AND t.id=? AND t.task_version=? AND t.state='VERIFYING'
                  AND p.id=? AND p.revision=? AND r.id=? AND r.state='RUNNING' AND r.cancel_requested_at IS NULL
                  AND c.state='COMPLETED' AND c.cancel_requested_at IS NULL
                  AND u.id=? AND u.state='RUNNING' AND u.active_key=?)
                """,owner,launchId,a.id(),a.fencingToken(),a.attemptNo(),a.taskVersion(),a.childRunId(),a.leaseOwner(),
                    Timestamp.from(a.leaseUntil()),o.task().rowId(),o.task().version(),o.planId(),o.planRevision(),o.rootRunId(),o.turnId(),o.threadId());
            if(changed==0)return Optional.empty();return Optional.of(locked(launchId));
        });
    }
    /** Sticky request. PENDING cancellation wins over claim; STARTING does not pretend the process stopped. */
    public Entry requestCancel(String launchId) {
        boundary();require(id(launchId),Reason.INVALID_INPUT);
        return writes.execute(tx->{var row=locked(launchId);
            if(!row.cancelRequested() && !Set.of("SEALED","CANCELLED").contains(row.state())) jdbc.update("""
                UPDATE task_check_launches SET cancel_requested=true,
                state=CASE WHEN state='PENDING' THEN 'CANCELLED' ELSE state END,updated_at=now() WHERE id=?
                """,launchId);
            return locked(launchId);
        });
    }
    /** No automatic replay of a claimed operation, including after service reconstruction. */
    public Entry markUnknown(String launchId,String owner) {
        boundary();require(id(launchId)&&id(owner),Reason.INVALID_INPUT);
        return writes.execute(tx->{var row=locked(launchId);require(owner.equals(row.claimOwner()),Reason.CONFLICT);
            require(Set.of("STARTING","UNKNOWN").contains(row.state()),Reason.CONFLICT);
            jdbc.update("UPDATE task_check_launches SET state='UNKNOWN',updated_at=now() WHERE id=? AND state='STARTING'",launchId);
            return locked(launchId);
        });
    }
    /** Persist actual sealed manifest identity, even for failed/stale/cancelled execution; never task success. */
    public Entry recordSealed(String launchId,String owner,TaskCheckAttemptPreparation.Prepared p,TaskCheckEvidenceService.Sealed sealed) {
        boundary();require(id(launchId)&&id(owner)&&p!=null&&sealed!=null,Reason.INVALID_INPUT);
        String json=intent(p);String manifest;
        try {
            var tree=TaskCheckEvidenceService.parseManifest(evidence.verify(sealed));
            require(Arrays.equals(CanonicalTaskJson.encode(tree.path("binding")),
                    CanonicalTaskJson.encode(mapper.valueToTree(TaskCheckEvidenceService.binding(p)))),Reason.EVIDENCE_INVALID);
            require(p.request().operationId().equals(tree.path("execution").path("operationId").asText())
                    && p.observed().child().id().equals(sealed.manifest().runId())
                    && p.observed().projectId().equals(sealed.manifest().projectId()),Reason.EVIDENCE_INVALID);
            manifest=new String(CanonicalTaskJson.encode(mapper.valueToTree(sealed.manifest())),StandardCharsets.UTF_8);
        }catch(RuntimeException invalid){throw new Unavailable(Reason.EVIDENCE_INVALID);}
        return writes.execute(tx->{var row=locked(launchId);
            require(row.intentJson().equals(json)&&owner.equals(row.claimOwner()),Reason.CONFLICT);
            if("SEALED".equals(row.state())){require(manifest.equals(row.manifestJson()),Reason.CONFLICT);return row;}
            require(Set.of("STARTING","UNKNOWN").contains(row.state()),Reason.CONFLICT);
            jdbc.update("UPDATE task_check_launches SET state='SEALED',manifest_json=?,updated_at=now() WHERE id=?",manifest,launchId);
            return locked(launchId);
        });
    }
    public Entry inspect(String launchId){boundary();require(id(launchId),Reason.INVALID_INPUT);return one(query("WHERE id=?",launchId));}
    /** Internal recovery inspection only; keyset pagination avoids hiding records behind a fixed first page. */
    public List<Entry> scanRoot(String rootRunId,String afterId,int limit) {
        boundary();require(id(rootRunId)&&(afterId==null||id(afterId))&&limit>=1&&limit<=128,Reason.INVALID_INPUT);
        return List.copyOf(query("WHERE root_run_id=? AND id>? ORDER BY id LIMIT ?",rootRunId,afterId==null?"":afterId,limit));
    }
    /** DB facts only, used by source-locked recovery closure comparison; no file/process IO. */
    List<Entry> scanRootJoined(String rootRunId,String afterId,int limit) {
        require(TransactionSynchronizationManager.isActualTransactionActive(),Reason.CALLER_TRANSACTION);
        require(id(rootRunId)&&(afterId==null||id(afterId))&&limit>=1&&limit<=128,Reason.INVALID_INPUT);
        return List.copyOf(query("WHERE root_run_id=? AND id>? ORDER BY id LIMIT ?",rootRunId,afterId==null?"":afterId,limit));
    }
    private Entry locked(String id){return one(query("WHERE id=? FOR UPDATE",id));}
    private static Entry one(List<Entry> rows){require(rows.size()==1,Reason.MISSING);return rows.getFirst();}
    private List<Entry> query(String suffix,Object... args) {
        return jdbc.query("SELECT * FROM task_check_launches "+suffix,(r,n)->{
            var entry=new Entry(r.getString("id"),r.getString("root_run_id"),r.getString("attempt_id"),r.getLong("fencing_token"),
                r.getString("check_id"),r.getString("operation_id"),r.getString("intent_digest"),r.getString("intent_json"),
                r.getString("state"),r.getString("claim_owner"),r.getBoolean("cancel_requested"),r.getString("manifest_json"),
                r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
            validate(entry);return entry;
        },args);
    }
    private void validate(Entry e) {
        try {
            byte[] bytes=e.intentJson().getBytes(StandardCharsets.UTF_8);require(bytes.length<=2*1048576,Reason.CORRUPT);
            var tree=TaskCheckEvidenceService.parseManifest(bytes);var a=tree.path("binding").path("attemptObservation");
            require(id(e.id()) && id(e.rootRunId()) && id(e.attemptId()) && e.fencingToken()>0 && id(e.checkId())
                && e.operationId().matches("[a-f0-9]{32}") && STATES.contains(e.state())
                && CanonicalTaskJson.sha256(bytes).equals(e.intentDigest())
                && "dsh-task-check-launch-v1".equals(tree.path("version").asText())
                && e.operationId().equals(tree.path("operationId").asText())
                && e.rootRunId().equals(a.path("rootRunId").asText()) && e.attemptId().equals(a.path("attempt").path("id").asText())
                && e.fencingToken()==a.path("attempt").path("fencingToken").asLong()
                && e.checkId().equals(tree.path("binding").path("check").path("checkId").asText()),Reason.CORRUPT);
            require(Set.of("PENDING","CANCELLED").contains(e.state()) ? e.claimOwner()==null : id(e.claimOwner()),Reason.CORRUPT);
            require(!"PENDING".equals(e.state()) || !e.cancelRequested(),Reason.CORRUPT);
            require(!"CANCELLED".equals(e.state()) || e.cancelRequested(),Reason.CORRUPT);
            require("SEALED".equals(e.state()) ? e.manifestJson()!=null : e.manifestJson()==null,Reason.CORRUPT);
            if(e.manifestJson()!=null) {
                var ref=TaskCheckEvidenceService.parseManifest(e.manifestJson().getBytes(StandardCharsets.UTF_8));
                require(ref.isObject() && ref.size()==7 && id(ref.path("id").asText())
                    && a.path("child").path("id").asText().equals(ref.path("runId").asText())
                    && a.path("projectId").asText().equals(ref.path("projectId").asText())
                    && "application/json".equals(ref.path("mediaType").asText())
                    && ref.path("bytes").isIntegralNumber() && ref.path("bytes").asLong()>0
                    && ref.path("bytes").asLong()<=2*1048576 && ref.path("sha256").asText().matches("[a-f0-9]{64}"),Reason.CORRUPT);
            }
            require(!e.updatedAt().isBefore(e.createdAt()),Reason.CORRUPT);
        }catch(RuntimeException invalid){throw new Unavailable(Reason.CORRUPT);}
    }
}
