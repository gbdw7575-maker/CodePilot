package com.dsh.api;

import com.dsh.orchestration.acceptance.TaskCheckAttemptAuthority;
import com.dsh.storage.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG relationship/transaction tests; no filesystem, process, fake model or task success. */
@SpringBootTest(properties = "dsh.orchestration.enabled=false")
class TaskCheckAttemptAuthorityTest {
    @Autowired TaskCheckAttemptAuthority authority;
    @Autowired ProjectRepository projects; @Autowired ModelProfileRepository profiles;
    @Autowired ThreadRepository threads; @Autowired TurnRepository turns; @Autowired RunRepository runs;
    @Autowired PlanRepository plans; @Autowired TaskRepository tasks; @Autowired TaskAttemptRepository attempts;
    @Autowired InputSnapshotRepository inputs; @Autowired ArtifactRepository artifacts; @Autowired TaskResultRepository results;
    @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager transactions; @Autowired ObjectMapper mapper;
    static final String SHA="a".repeat(64), NEXT="b".repeat(64);
    record Chain(String project,String thread,String turn,String root,String child,String plan,String attempt,
                 String upstreamChild,String upstreamAttempt,String artifact,String snapshot) { }
    String id(String prefix){return prefix+UUID.randomUUID().toString().replace("-","");}
    RunEntity run(String id,String turn,String parent,String role,String state,Instant now){
        return new RunEntity(id,turn,parent,role,state,"PENDING",1,this.id("ws_"),"digest",24,80,200000,now.plusSeconds(600),now,now);
    }
    TaskEntity task(Chain c,String taskId,String state,String deps,String refs){
        var now=Instant.now();return new TaskEntity(id("task_"),c.plan,taskId,1,"目标",deps,refs,"交付","[\"正确行为\"]",null,"DELEGATED",true,state,now,now);
    }
    Chain chain(boolean dependency)throws Exception{
        var now=Instant.now();var c=new Chain(id("proj_"),id("thr_"),id("turn_"),id("run_"),id("run_"),id("plan_"),id("att_"),id("run_"),id("att_"),id("art_"),id("snap_"));
        projects.save(new ProjectEntity(c.project,"authority","/unread-project",true,now,now));
        String profile=id("prof_");profiles.save(new ModelProfileEntity(profile,"fake","fake","fake","http://127.0.0.1:9",null,"disabled",null,4096,false,"ACTIVE",now,now));
        threads.save(new ThreadEntity(c.thread,c.project,"authority",profile,now,now));
        turns.save(new TurnEntity(c.turn,c.thread,c.root,"用户原文","RUNNING","PENDING",c.thread,now,now));
        runs.save(run(c.root,c.turn,null,"MAIN","RUNNING",now));runs.save(run(c.child,c.turn,c.root,"CODER","COMPLETED",now));
        plans.save(new PlanEntity(c.plan,c.root,c.project,1,"DRAFT","规划",now,now));
        tasks.save(task(c,"A","VERIFYING",dependency?"[\"U\"]":"[]",dependency?"[{\"taskId\":\"U\",\"output\":\"patch\"}]":"[]"));
        attempts.save(new TaskAttemptEntity(c.attempt,c.plan,"A",1,c.child,1,"worker-pool",now.plusSeconds(300),2L,null,"RUNNING",now,now));
        if(dependency){
            tasks.save(task(c,"U","SUCCEEDED","[]","[]"));runs.save(run(c.upstreamChild,c.turn,c.root,"CODER","COMPLETED",now));
            attempts.save(new TaskAttemptEntity(c.upstreamAttempt,c.plan,"U",1,c.upstreamChild,1,null,null,1L,null,"SUCCEEDED",now,now));
            artifacts.save(new ArtifactEntity(c.artifact,c.upstreamChild,c.project,"patch","text/plain",11,SHA,now,true));
            results.save(new TaskResultEntity(c.upstreamAttempt,c.plan,"U",1,c.upstreamChild,"SUCCEEDED",null,null,"[\""+c.artifact+"\"]",c.artifact,SHA,null,null,0,0,null,1,now));
        }
        String json=dependency?mapper.writeValueAsString(List.of(Map.of("artifactId",c.artifact,"mediaType","text/plain","sha256",SHA))):"[]";
        inputs.save(new InputSnapshotEntity(c.snapshot,c.child,c.root,c.project,"","",json,null,now));return c;
    }
    TaskCheckAttemptAuthority.Observation observe(Chain c){return authority.observe(c.attempt,c.child,2);}
    void refuses(String reason,Runnable call){var e=assertThrows(TaskCheckAttemptAuthority.Unavailable.class,call::run);assertEquals(reason,e.reason().name());assertEquals("Task attempt observation refused: "+reason,e.getMessage());}
    void change(String table,String column,Object value,String idColumn,String id){jdbc.update("UPDATE "+table+" SET "+column+"=? WHERE "+idColumn+"=?",value,id);}

    @Test void noDependenciesUsesActualChildAndWritesNothing()throws Exception{
        var c=chain(false);int resultCount=jdbc.queryForObject("SELECT count(*) FROM agent_task_results",Integer.class);
        int artifactCount=jdbc.queryForObject("SELECT count(*) FROM artifacts",Integer.class);
        var value=observe(c);assertEquals(c.project,value.projectId());assertEquals(c.root,value.rootRunId());assertEquals(c.child,value.child().id());
        assertEquals(2,value.attempt().fencingToken());assertTrue(value.upstream().isEmpty());assertTrue(value.input().artifacts().isEmpty());
        authority.requireCurrent(value);assertEquals(resultCount,jdbc.queryForObject("SELECT count(*) FROM agent_task_results",Integer.class));
        assertEquals(artifactCount,jdbc.queryForObject("SELECT count(*) FROM artifacts",Integer.class));
        assertEquals("VERIFYING",tasks.findByPlanIdAndTaskId(c.plan,"A").orElseThrow().getState());
    }
    @Test void dependencyBindsFrozenMetadataAndImmutableLists()throws Exception{
        var c=chain(true);var value=observe(c);assertEquals(c.upstreamAttempt,value.upstream().getFirst().attemptId());
        assertEquals(c.artifact,value.input().artifacts().getFirst().id());assertEquals(11,value.input().artifacts().getFirst().bytes());
        assertEquals(SHA,value.upstream().getFirst().outputDigest());
        assertThrows(UnsupportedOperationException.class,()->value.upstream().clear());
        assertThrows(UnsupportedOperationException.class,()->value.input().artifacts().clear());
        assertThrows(UnsupportedOperationException.class,()->value.upstream().getFirst().artifacts().clear());
    }
    @Test void terminalPredecessorAndMatchingSeedAndScopeAreAllowed()throws Exception{
        var c=chain(false);var now=Instant.now();
        change("agent_task_attempts","attempt_no",2,"attempt_id",c.attempt);
        attempts.save(new TaskAttemptEntity(id("att_"),c.plan,"A",1,null,1,null,null,1L,null,"FAILED",now,now));
        jdbc.update("UPDATE agent_tasks SET resource_scope=? WHERE plan_id=? AND task_id='A'","{\"read\":[],\"write\":[\"./src/\"]}",c.plan);
        change("agent_input_snapshots","seed_workspace_ref",runs.findById(c.root).orElseThrow().getWorkspaceRef(),"snapshot_id",c.snapshot);
        change("agent_input_snapshots","base_digest",SHA,"snapshot_id",c.snapshot);
        change("agent_input_snapshots","write_scope","{\"write\":[\"src/\"]}","snapshot_id",c.snapshot);
        assertEquals(2,observe(c).attempt().attemptNo());
    }
    @Test void dependencyWithoutInputReferenceStillNeedsCurrentSuccess()throws Exception{
        var c=chain(true);jdbc.update("UPDATE agent_tasks SET input_refs='[]' WHERE plan_id=? AND task_id='A'",c.plan);
        change("agent_input_snapshots","input_artifacts","[]","snapshot_id",c.snapshot);
        assertEquals(1,observe(c).upstream().size());assertTrue(observe(c).input().artifacts().isEmpty());
        jdbc.update("UPDATE agent_tasks SET state='FAILED' WHERE plan_id=? AND task_id='U'",c.plan);refuses("STALE_UPSTREAM",()->observe(c));
    }
    @Test void twoArtifactOrderAndBytesAreBound()throws Exception{
        var c=chain(true);String second=id("art_");var now=Instant.now();
        artifacts.save(new ArtifactEntity(second,c.upstreamChild,c.project,"second","text/plain",8,NEXT,now,true));
        change("agent_task_results","artifact_refs_json",mapper.writeValueAsString(List.of(c.artifact,second)),"attempt_id",c.upstreamAttempt);
        var first=Map.of("artifactId",c.artifact,"mediaType","text/plain","sha256",SHA);
        var next=Map.of("artifactId",second,"mediaType","text/plain","sha256",NEXT);
        change("agent_input_snapshots","input_artifacts",mapper.writeValueAsString(List.of(first,next)),"snapshot_id",c.snapshot);
        assertEquals(List.of(c.artifact,second),observe(c).input().artifacts().stream().map(TaskCheckAttemptAuthority.Artifact::id).toList());
        change("agent_input_snapshots","input_artifacts",mapper.writeValueAsString(List.of(next,first)),"snapshot_id",c.snapshot);
        refuses("STALE_UPSTREAM",()->observe(c));
    }
    @ParameterizedTest @ValueSource(strings={"missing","wrong-child","wrong-token","zero-token","bad-id","null-id"})
    void coordinatorIdentityMustMatch(String mode)throws Exception{
        var c=chain(false);switch(mode){
            case "missing"->refuses("MISSING_ATTEMPT",()->authority.observe("att_missing",c.child,2));
            case "wrong-child"->refuses("IDENTITY_MISMATCH",()->authority.observe(c.attempt,"run_other",2));
            case "wrong-token"->refuses("IDENTITY_MISMATCH",()->authority.observe(c.attempt,c.child,3));
            case "zero-token"->refuses("INVALID_INPUT",()->authority.observe(c.attempt,c.child,0));
            case "bad-id"->refuses("INVALID_INPUT",()->authority.observe("../att",c.child,2));
            case "null-id"->refuses("INVALID_INPUT",()->authority.observe(null,c.child,2));
        }
    }
    @ParameterizedTest @ValueSource(strings={"task-version","task-running","task-cancelling","task-inline","attempt-cancelled","lease-expired","lease-null","owner-null","child-running","child-failed","child-cancel","root-cancel","root-failed","child-role","child-parent","child-turn","same-workspace","bad-workspace","project","turn-root","turn-inactive","turn-state"})
    void stateLeaseAndOwnershipRefused(String mode)throws Exception{
        var c=chain(false);String reason="INVALID_STATE";
        switch(mode){
            case "task-version"->jdbc.update("UPDATE agent_tasks SET task_version=2 WHERE plan_id=? AND task_id='A'",c.plan);
            case "task-running"->jdbc.update("UPDATE agent_tasks SET state='RUNNING' WHERE plan_id=? AND task_id='A'",c.plan);
            case "task-cancelling"->jdbc.update("UPDATE agent_tasks SET state='CANCELLING' WHERE plan_id=? AND task_id='A'",c.plan);
            case "task-inline"->jdbc.update("UPDATE agent_tasks SET execution_mode='INLINE' WHERE plan_id=? AND task_id='A'",c.plan);
            case "attempt-cancelled"->change("agent_task_attempts","state","CANCELLED","attempt_id",c.attempt);
            case "lease-expired"->{change("agent_task_attempts","lease_until",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),"attempt_id",c.attempt);reason="LEASE_EXPIRED";}
            case "lease-null"->{change("agent_task_attempts","lease_until",null,"attempt_id",c.attempt);reason="LEASE_EXPIRED";}
            case "owner-null"->{change("agent_task_attempts","lease_owner",null,"attempt_id",c.attempt);reason="LEASE_EXPIRED";}
            case "child-running"->change("runs","state","RUNNING","id",c.child);
            case "child-failed"->change("runs","state","FAILED","id",c.child);
            case "child-cancel"->change("runs","cancel_requested_at",java.sql.Timestamp.from(Instant.now()),"id",c.child);
            case "root-cancel"->change("runs","cancel_requested_at",java.sql.Timestamp.from(Instant.now()),"id",c.root);
            case "root-failed"->change("runs","state","FAILED","id",c.root);
            case "child-role"->{change("runs","role","REVIEWER","id",c.child);reason="IDENTITY_MISMATCH";}
            case "child-parent"->{change("runs","parent_run_id","run_other","id",c.child);reason="IDENTITY_MISMATCH";}
            case "child-turn"->{change("runs","turn_id","turn_other","id",c.child);reason="IDENTITY_MISMATCH";}
            case "same-workspace"->{change("runs","workspace_ref",runs.findById(c.root).orElseThrow().getWorkspaceRef(),"id",c.child);reason="IDENTITY_MISMATCH";}
            case "bad-workspace"->{change("runs","workspace_ref","../../work","id",c.child);reason="IDENTITY_MISMATCH";}
            case "project"->{change("agent_plans","project_id","proj_other","id",c.plan);reason="IDENTITY_MISMATCH";}
            case "turn-root"->{change("turns","root_run_id","run_other","id",c.turn);reason="IDENTITY_MISMATCH";}
            case "turn-inactive"->{change("turns","active_key",null,"id",c.turn);reason="IDENTITY_MISMATCH";}
            case "turn-state"->{change("turns","state","CANCELLED","id",c.turn);reason="IDENTITY_MISMATCH";}
        }String expected=reason;refuses(expected,()->observe(c));
    }
    @ParameterizedTest @ValueSource(strings={"newer","same-number","older-active","child-reused","already-result"})
    void replacementAndDuplicateAttemptsRefused(String mode)throws Exception{
        var c=chain(false);var now=Instant.now();String reason="STALE_ATTEMPT";
        if(mode.equals("already-result"))results.save(new TaskResultEntity(c.attempt,c.plan,"A",1,c.child,"FAILED",null,null,"[]",null,SHA,null,null,0,0,null,1,now));
        else{
            int no=mode.equals("newer")?2:mode.equals("older-active")?0:1;
            attempts.save(new TaskAttemptEntity(id("att_"),c.plan,mode.equals("child-reused")?"X":"A",1,mode.equals("child-reused")?c.child:null,no,null,null,3L,null,"QUEUED",now,now));
            if(mode.equals("child-reused"))reason="IDENTITY_MISMATCH";
        }String expected=reason;refuses(expected,()->observe(c));
    }
    @ParameterizedTest @ValueSource(strings={"missing","root","project","seed","base","scope","invalid-json","extra-field","wrong-sha","missing-artifact","duplicate-artifact"})
    void frozenInputRefused(String mode)throws Exception{
        var c=chain(true);String reason="INVALID_SNAPSHOT";
        switch(mode){
            case "missing"->jdbc.update("DELETE FROM agent_input_snapshots WHERE snapshot_id=?",c.snapshot);
            case "root"->change("agent_input_snapshots","root_run_id","run_other","snapshot_id",c.snapshot);
            case "project"->change("agent_input_snapshots","project_id","proj_other","snapshot_id",c.snapshot);
            case "seed"->change("agent_input_snapshots","seed_workspace_ref","../ws_other","snapshot_id",c.snapshot);
            case "base"->change("agent_input_snapshots","base_digest","not-sha","snapshot_id",c.snapshot);
            case "scope"->change("agent_input_snapshots","write_scope","{\"write\":[\"src/\"]}","snapshot_id",c.snapshot);
            case "invalid-json"->change("agent_input_snapshots","input_artifacts","[] trailing","snapshot_id",c.snapshot);
            case "extra-field"->change("agent_input_snapshots","input_artifacts",mapper.writeValueAsString(List.of(Map.of("artifactId",c.artifact,"mediaType","text/plain","sha256",SHA,"trusted",true))),"snapshot_id",c.snapshot);
            case "wrong-sha"->{change("agent_input_snapshots","input_artifacts",mapper.writeValueAsString(List.of(Map.of("artifactId",c.artifact,"mediaType","text/plain","sha256",NEXT))),"snapshot_id",c.snapshot);reason="STALE_UPSTREAM";}
            case "missing-artifact"->{change("agent_input_snapshots","input_artifacts","[]","snapshot_id",c.snapshot);reason="STALE_UPSTREAM";}
            case "duplicate-artifact"->{var json=jdbc.queryForObject("SELECT input_artifacts FROM agent_input_snapshots WHERE snapshot_id=?",String.class,c.snapshot);var list=mapper.readTree(json);change("agent_input_snapshots","input_artifacts",mapper.writeValueAsString(List.of(list.get(0),list.get(0))),"snapshot_id",c.snapshot);reason="STALE_UPSTREAM";}
        }String expected=reason;refuses(expected,()->observe(c));
    }
    @ParameterizedTest @ValueSource(strings={"task-failed","task-version","newer-attempt","result-failed","result-version","result-child","output-digest","artifact-missing","artifact-project","artifact-owner","artifact-sha","artifact-size","child-failed","bad-refs","verification-missing"})
    void upstreamCurrentResultAndMetadataRefused(String mode)throws Exception{
        var c=chain(true);switch(mode){
            case "task-failed"->jdbc.update("UPDATE agent_tasks SET state='FAILED' WHERE plan_id=? AND task_id='U'",c.plan);
            case "task-version"->jdbc.update("UPDATE agent_tasks SET task_version=2 WHERE plan_id=? AND task_id='U'",c.plan);
            case "newer-attempt"->{var now=Instant.now();attempts.save(new TaskAttemptEntity(id("att_"),c.plan,"U",1,null,2,null,null,3L,null,"FAILED",now,now));}
            case "result-failed"->change("agent_task_results","state","FAILED","attempt_id",c.upstreamAttempt);
            case "result-version"->change("agent_task_results","task_version",2,"attempt_id",c.upstreamAttempt);
            case "result-child"->change("agent_task_results","child_run_id",c.child,"attempt_id",c.upstreamAttempt);
            case "output-digest"->change("agent_task_results","output_digest","not-sha","attempt_id",c.upstreamAttempt);
            case "artifact-missing"->jdbc.update("DELETE FROM artifacts WHERE id=?",c.artifact);
            case "artifact-project"->change("artifacts","project_id","proj_other","id",c.artifact);
            case "artifact-owner"->change("artifacts","run_id",c.child,"id",c.artifact);
            case "artifact-sha"->change("artifacts","sha256",NEXT,"id",c.artifact);
            case "artifact-size"->change("artifacts","size_bytes",32L*1048576+1,"id",c.artifact);
            case "child-failed"->change("runs","state","FAILED","id",c.upstreamChild);
            case "bad-refs"->change("agent_task_results","artifact_refs_json","[123]","attempt_id",c.upstreamAttempt);
            case "verification-missing"->change("agent_task_results","verification_ref","verify_missing","attempt_id",c.upstreamAttempt);
        }refuses("STALE_UPSTREAM",()->observe(c));
    }
    @ParameterizedTest @ValueSource(strings={"goal","revision","output","metadata","input-base","workspace"})
    void repeatedObservationSeesCommittedChanges(String mode)throws Exception{
        var c=chain(true);var before=observe(c);
        switch(mode){
            case "goal"->jdbc.update("UPDATE agent_tasks SET goal='新目标' WHERE plan_id=? AND task_id='A'",c.plan);
            case "revision"->change("agent_plans","revision",2,"id",c.plan);
            case "output"->change("agent_task_results","output_digest",NEXT,"attempt_id",c.upstreamAttempt);
            case "metadata"->change("artifacts","size_bytes",12,"id",c.artifact);
            case "input-base"->change("agent_input_snapshots","base_digest",NEXT,"snapshot_id",c.snapshot);
            case "workspace"->change("runs","workspace_ref",id("ws_"),"id",c.child);
        }refuses("STALE_INPUTS",()->authority.requireCurrent(before));
    }
    @Test void callerTransactionAndNullObservationRefused()throws Exception{
        var c=chain(false);new TransactionTemplate(transactions).executeWithoutResult(s->refuses("CALLER_TRANSACTION",()->observe(c)));
        refuses("INVALID_INPUT",()->authority.requireCurrent(null));
    }
    @Test void usesActualFreshReadOnlyRepeatableReadTransaction()throws Exception{
        var c=chain(false);int[] commits={0};
        var checked=new PlatformTransactionManager(){
            public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition){
                assertTrue(definition.isReadOnly());assertEquals(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ,definition.getIsolationLevel());
                assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW,definition.getPropagationBehavior());
                var status=transactions.getTransaction(definition);
                assertEquals("repeatable read",jdbc.queryForObject("SHOW transaction_isolation",String.class));
                assertEquals("on",jdbc.queryForObject("SHOW transaction_read_only",String.class));return status;
            }
            public void commit(org.springframework.transaction.TransactionStatus status){transactions.commit(status);commits[0]++;}
            public void rollback(org.springframework.transaction.TransactionStatus status){transactions.rollback(status);}
        };
        var local=new TaskCheckAttemptAuthority(jdbc,checked,new com.dsh.orchestration.WriteScopePolicy());
        var before=local.observe(c.attempt,c.child,2);local.requireCurrent(before);assertEquals(2,commits[0]);
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
    }
    @Test void concurrentCommitCannotMixRowsInsideAnObservation()throws Exception{
        var c=chain(false);boolean[] injected={false};
        var intercepted=new JdbcTemplate(jdbc.getDataSource()){
            @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args){
                var rows=super.query(sql,mapper,args);
                if(!injected[0] && sql.contains("FROM agent_task_attempts WHERE attempt_id=?")){
                    injected[0]=true;var write=new TransactionTemplate(transactions);
                    write.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    write.executeWithoutResult(s->change("runs","state","CANCELLED","id",c.root));
                }return rows;
            }
        };
        var local=new TaskCheckAttemptAuthority(intercepted,transactions,new com.dsh.orchestration.WriteScopePolicy());
        var before=local.observe(c.attempt,c.child,2);assertTrue(injected[0]);assertEquals("RUNNING",before.root().state());
        refuses("INVALID_STATE",()->local.requireCurrent(before));
    }
}
