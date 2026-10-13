package com.dsh.orchestration.acceptance;

import com.dsh.application.ArtifactStore;
import com.dsh.sandbox.SandboxRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** External control before the final root transaction. Unknown observations never clear pointers. */
@Service
public class TaskCheckRootRecovery {
    private final JdbcTemplate jdbc;private final TransactionTemplate writes;
    private final TaskCheckLifecycleGuard lifecycle;private final TaskCheckLaunchJournal journal;
    private final SandboxRunner runner;private final ArtifactStore artifacts;private final ObjectMapper mapper;
    public TaskCheckRootRecovery(JdbcTemplate jdbc,PlatformTransactionManager manager,TaskCheckLifecycleGuard lifecycle,
            TaskCheckLaunchJournal journal,SandboxRunner runner,ArtifactStore artifacts,ObjectMapper mapper){
        this.jdbc=jdbc;this.lifecycle=lifecycle;this.journal=journal;this.runner=runner;this.artifacts=artifacts;this.mapper=mapper;
        writes=new TransactionTemplate(manager);writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);writes.setTimeout(5);
    }
    public void recover(String root){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();
        writes.executeWithoutResult(tx->{lifecycle.lockRoot(root);
            if(jdbc.queryForObject("SELECT count(*) FROM runs WHERE id=? AND state IN ('BLOCKED','CANCELLING')",Integer.class,root)!=1)throw unavailable();
            lifecycle.cancelRootChecks(root);
        });
        var closure=closure(root);var entries=entries(root);var fences=new ArrayList<Map<String,Object>>();
        for(var entry:entries){
            var intent=TaskCheckEvidenceService.parseManifest(entry.intentJson().getBytes(StandardCharsets.UTF_8));
            String image=intent.path("binding").path("imageDigest").asText();
            String owner=writes.execute(tx->{lifecycle.lockRoot(root);
                if(!closure.equals(closure(root)))throw unavailable();
                jdbc.update("INSERT INTO task_check_recovery_fences(launch_id,root_run_id,operation_id,image_digest,owner_id) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                    entry.id(),root,entry.operationId(),image,UUID.randomUUID().toString().replace("-",""));
                var row=jdbc.queryForMap("SELECT * FROM task_check_recovery_fences WHERE launch_id=?",entry.id());
                if(!root.equals(row.get("root_run_id"))||!entry.operationId().equals(row.get("operation_id"))||!image.equals(row.get("image_digest")))throw unavailable();
                return (String)row.get("owner_id");
            });
            var actual=runner.quarantineCheck(entry.operationId(),image,owner);
            if(actual==null||!actual.fenced()||!entry.operationId().equals(actual.operationId())||!owner.equals(actual.ownerId())||!image.equals(actual.imageDigest()))throw unavailable();
            fences.add(Map.of("launchId",entry.id(),"operationId",entry.operationId(),"ownerId",owner,"imageDigest",image,"containerId",actual.containerId()));
        }
        if(!closure.equals(closure(root)))throw unavailable();
        var origin=jdbc.queryForMap("SELECT h.project_id,r.workspace_ref FROM runs r JOIN turns u ON u.id=r.turn_id JOIN threads h ON h.id=u.thread_id WHERE r.id=?",root);
        byte[] bytes=CanonicalTaskJson.encode(mapper.valueToTree(Map.of("version","dsh-task-check-root-cleanup-v1","rootRunId",root,"closure",closure,"fences",fences,"retained",true)));
        var receipt=artifacts.saveCheckEvidence(root,(String)origin.get("project_id"),(String)origin.get("workspace_ref"),"check-root-cleanup.json","application/json",bytes);
        if(!Arrays.equals(bytes,artifacts.readVerifiedContent(receipt.getId(),2*1048576)))throw unavailable();
        verifyFences(fences);
        writes.executeWithoutResult(tx->{lifecycle.lockRoot(root);
            if(!closure.equals(closure(root))||jdbc.queryForObject("SELECT count(*) FROM runs WHERE id=? AND state IN ('BLOCKED','CANCELLING')",Integer.class,root)!=1)throw unavailable();
            for(var f:fences)jdbc.update("UPDATE task_check_recovery_fences SET container_id=? WHERE launch_id=? AND owner_id=?",f.get("containerId"),f.get("launchId"),f.get("ownerId"));
            jdbc.update("INSERT INTO task_check_root_cleanups(root_run_id,closure_digest,artifact_id,receipt_digest) VALUES(?,?,?,?) ON CONFLICT(root_run_id) DO UPDATE SET closure_digest=excluded.closure_digest,artifact_id=excluded.artifact_id,receipt_digest=excluded.receipt_digest",
                root,CanonicalTaskJson.digest(mapper.valueToTree(closure)),receipt.getId(),receipt.getSha256());
        });
    }
    /** Called while the root source lock is held, immediately before releasing root state. */
    public boolean verified(String root){
        if(TransactionSynchronizationManager.isActualTransactionActive())return false;
        try{
            var rows=jdbc.queryForList("SELECT * FROM task_check_root_cleanups WHERE root_run_id=?",root);if(rows.size()!=1)return false;
            var row=rows.getFirst();byte[] bytes=artifacts.readVerifiedContent((String)row.get("artifact_id"),2*1048576);
            if(!CanonicalTaskJson.sha256(bytes).equals(row.get("receipt_digest")))return false;
            var receipt=TaskCheckEvidenceService.parseManifest(bytes);
            if(!"dsh-task-check-root-cleanup-v1".equals(receipt.path("version").asText())||!root.equals(receipt.path("rootRunId").asText())
                    ||!receipt.path("retained").asBoolean(false)||!CanonicalTaskJson.digest(receipt.path("closure")).equals(row.get("closure_digest"))
                    ||!CanonicalTaskJson.digest(receipt.path("closure")).equals(CanonicalTaskJson.digest(mapper.valueToTree(closure(root)))))return false;
            var fences=mapper.convertValue(receipt.path("fences"),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});
            var wanted=entries(root);if(fences.size()!=wanted.size())return false;
            for(int i=0;i<wanted.size();i++){var f=fences.get(i);var e=wanted.get(i);
                if(!e.id().equals(f.get("launchId"))||!e.operationId().equals(f.get("operationId")))return false;
                var durable=jdbc.queryForMap("SELECT * FROM task_check_recovery_fences WHERE launch_id=?",e.id());
                if(!root.equals(durable.get("root_run_id"))||!f.get("ownerId").equals(durable.get("owner_id"))||!f.get("containerId").equals(durable.get("container_id"))||!f.get("imageDigest").equals(durable.get("image_digest")))return false;
            }
            verifyFences(fences);return CanonicalTaskJson.digest(receipt.path("closure")).equals(CanonicalTaskJson.digest(mapper.valueToTree(closure(root))));
        }catch(RuntimeException unavailable){return false;}
    }
    public static final class Release {
        private final TaskCheckRootRecovery issuer;private final String root,rows,closure,fences;
        private final long expires;
        private Release(TaskCheckRootRecovery issuer,String root,String rows,String closure,String fences){this.issuer=issuer;this.root=root;this.rows=rows;this.closure=closure;this.fences=fences;expires=System.nanoTime()+10_000_000_000L;}
        public String rootRunId(){return root;}
    }
    public Release observeRelease(String root){
        String rows=canonical(jdbc.queryForList("SELECT * FROM task_check_root_cleanups WHERE root_run_id=?",root));
        String closure=canonical(closure(root));
        String fences=canonical(jdbc.queryForList("SELECT * FROM task_check_recovery_fences WHERE root_run_id=? ORDER BY launch_id",root));
        if(!verified(root))throw unavailable();
        if(!rows.equals(canonical(jdbc.queryForList("SELECT * FROM task_check_root_cleanups WHERE root_run_id=?",root)))
                ||!closure.equals(canonical(closure(root)))||!fences.equals(canonical(jdbc.queryForList("SELECT * FROM task_check_recovery_fences WHERE root_run_id=? ORDER BY launch_id",root))))throw unavailable();
        return new Release(this,root,rows,closure,fences);
    }
    public void requireCurrentJoined(Release release){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||release==null||release.issuer!=this||System.nanoTime()-release.expires>=0)throw unavailable();
        if(jdbc.queryForObject("SELECT count(*) FROM runs WHERE parent_run_id=? AND state NOT IN ('COMPLETED','FAILED','CANCELLED')",Integer.class,release.root)!=0)throw unavailable();
        if(jdbc.queryForObject("SELECT count(*) FROM runs WHERE id=? AND budget_reserved_tokens=0",Integer.class,release.root)!=1)throw unavailable();
        if(!release.rows.equals(canonical(jdbc.queryForList("SELECT * FROM task_check_root_cleanups WHERE root_run_id=? FOR UPDATE",release.root)))
            ||!release.closure.equals(canonical(closure(release.root)))
            ||!release.fences.equals(canonical(jdbc.queryForList("SELECT * FROM task_check_recovery_fences WHERE root_run_id=? ORDER BY launch_id FOR UPDATE",release.root))))throw unavailable();
    }
    private String canonical(Object value){return new String(CanonicalTaskJson.encode(mapper.valueToTree(value)),StandardCharsets.UTF_8);}
    private void verifyFences(List<Map<String,Object>> fences){for(var f:fences)if(!runner.verifyCheckQuarantine((String)f.get("operationId"),(String)f.get("containerId"),(String)f.get("ownerId"),(String)f.get("imageDigest")))throw unavailable();}
    private List<TaskCheckLaunchJournal.Entry> entries(String root){var result=new ArrayList<TaskCheckLaunchJournal.Entry>();String after=null;
        while(true){var page=TransactionSynchronizationManager.isActualTransactionActive()?journal.scanRootJoined(root,after,128):journal.scanRoot(root,after,128);result.addAll(page);if(result.size()>256)throw unavailable();if(page.size()<128)break;after=page.getLast().id();}
        // Include wrong-root orphan facts through their actual attempt/plan association too.
        var orphan=jdbc.queryForList("SELECT l.id FROM task_check_launches l JOIN agent_task_attempts a ON a.attempt_id=l.attempt_id JOIN agent_plans p ON p.id=a.plan_id WHERE p.root_run_id=? AND l.root_run_id<>?",String.class,root,root);
        if(!orphan.isEmpty())throw unavailable();return List.copyOf(result);
    }
    private Map<String,Object> closure(String root){
        var launches=entries(root).stream().map(e->Map.of("id",e.id(),"attempt",e.attemptId(),"fence",e.fencingToken(),"operation",e.operationId(),"intentDigest",e.intentDigest())).toList();
        var executions=jdbc.queryForList("SELECT id,root_run_id,attempt_id,fencing_token,registration_id,check_refs_json FROM task_check_executions WHERE root_run_id=? OR attempt_id IN(SELECT a.attempt_id FROM agent_task_attempts a JOIN agent_plans p ON p.id=a.plan_id WHERE p.root_run_id=?) ORDER BY id",root,root);
        if(executions.size()>256||executions.stream().anyMatch(e->!root.equals(e.get("root_run_id"))))throw unavailable();
        return Map.of("launches",launches,"executions",executions);
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("CHECK_RECOVERY_REQUIRED");}
}
