package com.dsh.orchestration.acceptance;

import com.dsh.application.ArtifactStore;
import com.dsh.application.TaskCheckReports;
import com.dsh.api.dto.TaskCheckSelectionPreview;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.TaskResultEntity;
import com.dsh.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Revalidates committed evidence. Terminal DB observations cannot authorize a new execution. */
@Service
public class TaskCheckReceiptReader {
    private final JdbcTemplate jdbc; private final ArtifactStore artifacts; private final ObjectMapper mapper;
    private final TaskCheckAttemptAuthority authority; private final TaskCheckPreviewSource sources;
    private final HostTaskCheckCatalog catalogs; private final WorkspaceManager workspaces;
    public TaskCheckReceiptReader(JdbcTemplate jdbc,ArtifactStore artifacts,ObjectMapper mapper,
            TaskCheckAttemptAuthority authority,TaskCheckPreviewSource sources,HostTaskCheckCatalog catalogs,WorkspaceManager workspaces){
        this.jdbc=jdbc;this.artifacts=artifacts;this.mapper=mapper;this.authority=authority;
        this.sources=sources;this.catalogs=catalogs;this.workspaces=workspaces;
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public boolean isValid(TaskEntity task,TaskResultEntity result){
        if(task==null||result==null||!"SUCCEEDED".equals(task.getState())||!"SUCCEEDED".equals(result.getState())
                ||!task.getPlanId().equals(result.getPlanId())||!task.getTaskId().equals(result.getTaskId())
                ||task.getTaskVersion()!=result.getTaskVersion())return false;
        try{validate(result.getAttemptId(),new HashSet<>(),0);return true;}catch(Exception unavailable){return false;}
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void requireAttempt(String attempt){try{validate(attempt,new HashSet<>(),0);}catch(Exception invalid){throw new IllegalStateException("EVIDENCE_MISSING",invalid);}}
    /** All required tasks must have current receipts. Optional succeeded writers are checked too. */
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public boolean rootReady(String root){
        try{
            var plans=jdbc.queryForList("SELECT id FROM agent_plans WHERE root_run_id=? ORDER BY created_at DESC LIMIT 1",String.class,root);
            if(plans.size()!=1)return false;
            var tasks=jdbc.queryForList("SELECT task_id,required,state FROM agent_tasks WHERE plan_id=? ORDER BY task_id",plans.getFirst());
            if(tasks.isEmpty())return false;var checked=new HashSet<String>();int required=0;
            for(var task:tasks){boolean must=Boolean.TRUE.equals(task.get("required"));if(must)required++;
                if(must&&!"SUCCEEDED".equals(task.get("state")))return false;
                if("SUCCEEDED".equals(task.get("state"))){var ids=jdbc.queryForList("SELECT attempt_id FROM agent_task_attempts WHERE plan_id=? AND task_id=? ORDER BY attempt_no DESC LIMIT 1",String.class,plans.getFirst(),task.get("task_id"));
                    if(ids.size()!=1)return false;validate(ids.getFirst(),checked,0);
                }else if(Set.of("RUNNING","VERIFYING","READY","PENDING","CANCELLING").contains(task.get("state")))return false;
            }return required>0;
        }catch(Exception invalid){return false;}
    }
    private void validate(String attempt,Set<String> visited,int depth)throws Exception{
        check(depth<=6&&visited.size()<32&&visited.add(attempt));
        var rows=jdbc.queryForList("SELECT * FROM task_check_completions WHERE attempt_id=?",attempt);check(rows.size()==1);var row=rows.getFirst();
        String before=canonical(row);byte[] bytes=artifacts.readVerifiedContent((String)row.get("artifact_id"),2*1048576);
        check(CanonicalTaskJson.sha256(bytes).equals(row.get("receipt_digest"))&&new String(bytes,StandardCharsets.UTF_8).equals(row.get("receipt_json")));
        var receipt=TaskCheckEvidenceService.parseManifest(bytes);var b=receipt.path("binding");var expected=b.path("attemptObservation");
        check("dsh-task-check-completion-v1".equals(receipt.path("version").asText())&&receipt.path("executionId").asText().equals(row.get("execution_id")));
        var current=authority.observeCommitted(attempt,expected.path("child").path("id").asText(),expected.path("attempt").path("fencingToken").asLong());
        check(current.rootRunId().equals(row.get("root_run_id"))&&current.attempt().fencingToken()==((Number)row.get("fencing_token")).longValue());
        check(same(normalize(expected),normalize(mapper.valueToTree(current))));
        for(var u:current.upstream())validate(u.attemptId(),visited,depth+1);
        var source=sources.read(current.rootRunId(),current.planId(),current.task().id());
        var reg=jdbc.queryForMap("SELECT * FROM task_check_registrations WHERE id=?",b.path("registrationId").asText());
        var frozen=TaskCheckRegistrationBinding.decode((String)reg.get("snapshot_json"));
        check(frozen.bindingDigest.equals(reg.get("binding_digest"))&&frozen.canonicalBinding.equals(reg.get("binding_json"))
                &&frozen.snapshotDigest.equals(reg.get("snapshot_digest"))&&frozen.bindingDigest.equals(b.path("bindingDigest").asText())
                &&frozen.rootRunId.equals(current.rootRunId())&&frozen.planId.equals(current.planId())&&frozen.taskId.equals(current.task().id())
                &&frozen.taskVersion==current.task().version()&&frozen.observedPlanRevision==current.planRevision());
        var selected=mapper.readValue((String)reg.get("snapshot_json"),TaskCheckSelectionPreview.class);check(selected.execution()!=null);
        var catalog=catalogs.load(current.projectId());var execution=catalogs.loadExecution(current.projectId());
        var actual=TaskCheckSelectionPreviewService.withExecution(TaskCheckSelectionPreviewService.select(TaskCheckPreviewService.compose(source,catalog),selected.checkRefs()),execution);
        check(frozen.canonicalBinding.equals(TaskCheckRegistrationBinding.from(actual).canonicalBinding));
        var oldSource=b.path("source").deepCopy();((ObjectNode)oldSource).put("taskState",source.taskState()).put("rootState",source.rootState());
        check(same(oldSource,source)&&b.path("executionSpecDigest").asText().equals(execution.executionSpecDigest())
                &&b.path("executionSourceDigest").asText().equals(execution.sourceDigest())&&b.path("registryDigest").asText().equals(execution.registryDigest())
                &&b.path("imageDigest").asText().equals(execution.imageDigest())&&same(b.path("cacheFiles"),execution.cacheFiles())
                &&b.path("executionDefinition").equals(CanonicalTaskJson.parse(execution.canonicalDefinition().getBytes(StandardCharsets.UTF_8))));
        var resolved=workspaces.resolveByRef(current.child().workspaceRef());
        check(workspaces.find(current.child().id())==null&&current.child().workspaceRef().equals(b.path("workspaceRef").asText())
                &&jdbc.queryForObject("SELECT count(*) FROM runs WHERE workspace_ref=?",Integer.class,current.child().workspaceRef())==1);
        var files=TaskCheckExecutionRequestFactory.sourceFiles(resolved.dir());
        check(same(b.path("candidateFiles"),files)&&b.path("candidateDigest").asText().equals(CanonicalTaskJson.digest(mapper.valueToTree(files))));
        var delivery=TaskCheckDelivery.capture(resolved.dir(),resolved.metaDir(),files);
        check(receipt.path("deliveryDigest").asText().equals(delivery.digest()));
        var refs=new TreeMap<String,TaskCheckEvidenceService.ArtifactRef>();var contents=new HashMap<String,byte[]>();long total=0;
        check(receipt.path("artifacts").isArray()&&receipt.path("artifacts").size()<=160);
        for(var n:receipt.path("artifacts")){var ref=mapper.treeToValue(n,TaskCheckEvidenceService.ArtifactRef.class);
            check(ref.runId().equals(current.child().id())&&ref.projectId().equals(current.projectId())&&refs.putIfAbsent(ref.id(),ref)==null);
            var content=read(ref);check((total+=content.length)<=64L*1048576);contents.put(ref.id(),content);
        }
        var used=new HashSet<String>();var checks=receipt.path("checks");check(checks.isArray()&&checks.size()==selected.checkRefs().size());
        var launchIds=new TreeSet<String>();var operations=new HashSet<String>();var containers=new HashSet<String>();int index=0;
        for(var c:checks){String checkId=selected.checkRefs().get(index++);check(c.path("checkId").asText().equals(checkId));
            var profile=execution.select(List.of(checkId)).checks().getFirst();var manifestRef=member(c.path("manifest"),refs,used,"check-evidence.json","application/json");
            var manifest=TaskCheckEvidenceService.parseManifest(contents.get(manifestRef.id()));var mb=manifest.path("binding");
            var compare=b.deepCopy();((ObjectNode)compare).set("check",mapper.valueToTree(profile));check(same(compare,mb));
            check(bool(manifest,"successAuthority",false)&&bool(manifest,"currentAfterExecution",true)&&bool(manifest,"currentAtSeal",true)&&bool(manifest,"cancellationObserved",false));
            var fact=manifest.path("execution");String op=fact.path("operationId").asText();String container=fact.path("containerId").asText();
            check(op.matches("[a-f0-9]{32}")&&operations.add(op)&&container.matches("[a-f0-9]{64}")&&containers.add(container)
                    &&op.equals(c.path("operationId").asText())&&"COMPLETED".equals(fact.path("termination").asText())
                    &&fact.path("exitCode").isIntegralNumber()&&fact.path("exitCode").asInt()==0&&"ABSENT".equals(fact.path("cleanup").asText()));
            var launches=jdbc.queryForList("SELECT * FROM task_check_launches WHERE attempt_id=? AND check_id=?",attempt,checkId);check(launches.size()==1);var launch=launches.getFirst();
            check("SEALED".equals(launch.get("state"))&&Boolean.FALSE.equals(launch.get("cancel_requested"))&&op.equals(launch.get("operation_id"))
                    &&row.get("execution_id").equals(launch.get("claim_owner"))
                    &&current.rootRunId().equals(launch.get("root_run_id"))&&current.attempt().fencingToken()==((Number)launch.get("fencing_token")).longValue()
                    &&CanonicalTaskJson.sha256(((String)launch.get("intent_json")).getBytes(StandardCharsets.UTF_8)).equals(launch.get("intent_digest")));
            var intent=TaskCheckEvidenceService.parseManifest(((String)launch.get("intent_json")).getBytes(StandardCharsets.UTF_8));check(intent.path("binding").equals(mb)&&intent.path("operationId").asText().equals(op));
            check(mapper.readTree((String)launch.get("manifest_json")).equals(c.path("manifest")));launchIds.add((String)launch.get("id"));
            var d=manifest.path("delivery");var zip=member(d.path("artifact"),refs,used,"check-delivery.zip","application/zip");
            check(d.path("digest").asText().equals(delivery.digest())&&d.path("baselineDigest").asText().equals(delivery.baselineDigest())
                    &&same(d.path("files"),delivery.files())&&Arrays.equals(contents.get(zip.id()),delivery.zip()));
            var raw=new ArrayList<byte[]>();var paths=new HashSet<String>();int reportIndex=0;long reportBytes=0;
            check(manifest.path("reports").isArray()&&manifest.path("reports").size()>0&&manifest.path("reports").size()<=execution.limits().reportFiles());
            for(var report:manifest.path("reports")){check(report.path("path").asText().matches("target/surefire-reports/TEST-[A-Za-z0-9_.$-]{1,240}\\.xml")&&paths.add(report.path("path").asText().toLowerCase(Locale.ROOT)));
                var ref=member(report.path("artifact"),refs,used,"check-report-"+(reportIndex++)+".xml","application/xml");var xml=contents.get(ref.id());
                check(xml.length<=execution.limits().reportFileBytes()&&(reportBytes+=xml.length)<=execution.limits().reportTotalBytes());raw.add(xml);
            }
            var parsed=TaskCheckReports.parseAll(raw);check(parsed.error()==null&&parsed.counts().failures()==0&&parsed.counts().passed()>=profile.minPassed()
                    &&same(manifest.path("parsedReports"),parsed)&&same(c.path("counts"),parsed.counts()));
            member(manifest.path("logs").path("stdout"),refs,used,"check-stdout.log","text/plain");member(manifest.path("logs").path("stderr"),refs,used,"check-stderr.log","text/plain");
        }
        check(used.equals(refs.keySet())&&same(receipt.path("launchIds"),launchIds));
        var slot=jdbc.queryForMap("SELECT * FROM task_check_executions WHERE id=?",row.get("execution_id"));
        check("COMMITTED".equals(slot.get("state"))&&attempt.equals(slot.get("attempt_id"))&&current.rootRunId().equals(slot.get("root_run_id"))
                &&b.path("registrationId").asText().equals(slot.get("registration_id"))&&current.attempt().fencingToken()==((Number)slot.get("fencing_token")).longValue()
                &&same(mapper.readTree((String)slot.get("check_refs_json")),selected.checkRefs())&&ids(mapper.readTree((String)slot.get("launch_ids_json"))).equals(launchIds)
                &&jdbc.queryForObject("SELECT count(*) FROM task_check_executions WHERE attempt_id=?",Integer.class,attempt)==1
                &&jdbc.queryForObject("SELECT count(*) FROM task_check_launches WHERE attempt_id=?",Integer.class,attempt)==checks.size());
        String input=CanonicalTaskJson.digest(mapper.valueToTree(Map.of("input",current.input(),"upstream",current.upstream())));
        var result=jdbc.queryForMap("SELECT * FROM agent_task_results WHERE attempt_id=?",attempt);
        check("SUCCEEDED".equals(result.get("state"))&&current.child().id().equals(result.get("child_run_id"))&&current.planId().equals(result.get("plan_id"))
                &&current.task().id().equals(result.get("task_id"))&&current.task().version()==((Number)result.get("task_version")).intValue()
                &&delivery.digest().equals(result.get("output_digest"))&&input.equals(result.get("input_digest"))&&input.equals(receipt.path("inputDigest").asText())
                &&result.get("verification_ref")==null&&result.get("patch_artifact_id")==null);
        var publicIds=new TreeSet<String>();publicIds.add((String)row.get("artifact_id"));for(var ref:refs.values())if(ref.name().equals("check-evidence.json")||ref.mediaType().equals("application/zip"))publicIds.add(ref.id());
        check(same(mapper.readTree((String)result.get("artifact_refs_json")),publicIds));
        var summary=Map.of("version","dsh-selected-check-observation-v1","bindingDigest",b.path("bindingDigest").asText(),"attempt",expected,
                "candidateDigest",b.path("candidateDigest").asText(),"deliveryDigest",delivery.digest(),"checks",checks,"successAuthority",false);
        check(CanonicalTaskJson.digest(mapper.valueToTree(summary)).equals(receipt.path("assessmentDigest").asText()));
        var projects=jdbc.query("SELECT id,root_path,trusted,version FROM projects ORDER BY id LIMIT 4097",(r,n)->new Project(r.getString(1),r.getString(2),r.getBoolean(3),r.getInt(4)));
        check(!projects.isEmpty()&&projects.size()<=4096&&CanonicalTaskJson.digest(mapper.valueToTree(projects)).equals(receipt.path("projectInputsDigest").asText()));
        catalogs.requireCurrent(catalog);catalogs.requireCurrentExecution(execution);
        check(current.equals(authority.observeCommitted(attempt,current.child().id(),current.attempt().fencingToken()))&&source.equals(sources.read(current.rootRunId(),current.planId(),current.task().id()))
                &&before.equals(canonical(jdbc.queryForMap("SELECT * FROM task_check_completions WHERE attempt_id=?",attempt))));
        check(delivery.digest().equals(TaskCheckDelivery.capture(resolved.dir(),resolved.metaDir(),TaskCheckExecutionRequestFactory.sourceFiles(resolved.dir())).digest()));
        for(var ref:refs.values())check(Arrays.equals(contents.get(ref.id()),read(ref)));
        visited.remove(attempt);
    }
    private record Project(String id,String root,boolean trusted,int version){}
    private ObjectNode normalize(JsonNode supplied){ObjectNode n=supplied.deepCopy();((ObjectNode)n.path("task")).remove("state");
        ((ObjectNode)n.path("attempt")).remove(List.of("leaseOwner","leaseUntil"));((ObjectNode)n.path("root")).remove("state");return n;}
    private byte[] read(TaskCheckEvidenceService.ArtifactRef ref){var e=artifacts.get(ref.id());
        check(e.isDownloadable()&&ref.equals(new TaskCheckEvidenceService.ArtifactRef(e.getId(),e.getRunId(),e.getProjectId(),e.getName(),e.getMediaType(),e.getSizeBytes(),e.getSha256())));
        var bytes=artifacts.readVerifiedContent(ref.id(),32L*1048576);check(bytes.length==ref.bytes()&&CanonicalTaskJson.sha256(bytes).equals(ref.sha256()));return bytes;}
    private TaskCheckEvidenceService.ArtifactRef member(JsonNode n,Map<String,TaskCheckEvidenceService.ArtifactRef> refs,Set<String> used,String name,String media){var ref=refs.get(n.path("id").asText());
        check(ref!=null&&same(n,ref)&&name.equals(ref.name())&&media.equals(ref.mediaType())&&used.add(ref.id()));return ref;}
    private static boolean bool(JsonNode n,String field,boolean value){return n.path(field).isBoolean()&&n.path(field).booleanValue()==value;}
    private static SortedSet<String> ids(JsonNode node){check(node.isArray()&&node.size()>0&&node.size()<=8);var values=new TreeSet<String>();
        for(var n:node)check(n.isTextual()&&values.add(n.asText()));return values;}
    private boolean same(JsonNode actual,Object expected){return Arrays.equals(CanonicalTaskJson.encode(actual),CanonicalTaskJson.encode(mapper.valueToTree(expected)));}
    private String canonical(Object value){return new String(CanonicalTaskJson.encode(mapper.valueToTree(value)),StandardCharsets.UTF_8);}
    private static void check(boolean valid){if(!valid)throw new IllegalStateException("EVIDENCE_MISSING");}
}
