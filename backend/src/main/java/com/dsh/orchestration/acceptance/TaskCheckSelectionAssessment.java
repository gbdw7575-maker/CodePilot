package com.dsh.orchestration.acceptance;

import com.dsh.application.ArtifactStore;
import com.dsh.application.TaskCheckReports;
import com.dsh.application.TestCounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;

/** Complete selected-check observation only. No launch capability, state write or final success CAS. */
@Service
public class TaskCheckSelectionAssessment {
    private final JdbcTemplate jdbc;
    private final TaskCheckAttemptPreparation preparation;
    private final TaskCheckEvidenceService evidence;
    private final ArtifactStore artifacts;
    private final ObjectMapper mapper;
    private final com.dsh.agent.control.CancellationTokenRegistry cancellations;
    public TaskCheckSelectionAssessment(JdbcTemplate jdbc,TaskCheckAttemptPreparation preparation,
            TaskCheckEvidenceService evidence,ArtifactStore artifacts,ObjectMapper mapper,
            com.dsh.agent.control.CancellationTokenRegistry cancellations) {
        this.jdbc=jdbc;this.preparation=preparation;this.evidence=evidence;this.artifacts=artifacts;this.mapper=mapper;
        this.cancellations=cancellations;
    }
    public enum Reason { INVALID_INPUT, CALLER_TRANSACTION, SELECTION_MISMATCH, INPUTS_MISMATCH,
        EVIDENCE_INVALID, CHECK_FAILED, INPUTS_STALE }
    public static final class Unavailable extends RuntimeException {
        private final Reason reason;
        private Unavailable(Reason reason){super("Selected check observation refused: "+reason);this.reason=reason;}
        public Reason reason(){return reason;}
    }
    public record CheckEvidence(TaskCheckAttemptPreparation.Prepared prepared,TaskCheckEvidenceService.Sealed sealed) { }
    public record CheckObservation(String checkId,String operationId,TaskCheckEvidenceService.ArtifactRef manifest,TestCounts counts) { }
    public static final class Observation {
        private final List<CheckEvidence> inputs;
        private final List<CheckObservation> checks;
        private final String digest;
        private final Instant observedAt;
        private Observation(List<CheckEvidence> inputs,List<CheckObservation> checks,String digest) {
            this.inputs=List.copyOf(inputs);this.checks=List.copyOf(checks);this.digest=digest;observedAt=Instant.now();
        }
        public List<CheckObservation> checks(){return checks;}
        public String digest(){return digest;}
        public Instant observedAt(){return observedAt;}
        public boolean successAuthority(){return false;}
    }
    public Observation assess(List<CheckEvidence> supplied) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),Reason.CALLER_TRANSACTION);
        require(supplied!=null && !supplied.isEmpty() && supplied.size()<=8,Reason.INVALID_INPUT);
        var inputs=new ArrayList<CheckEvidence>();var byCheck=new TreeMap<String,CheckEvidence>();
        for(var pair:supplied) {
            require(pair!=null && pair.prepared()!=null && pair.sealed()!=null,Reason.INVALID_INPUT);
            require(byCheck.putIfAbsent(pair.prepared().request().check().checkId(),pair)==null,Reason.SELECTION_MISMATCH);
        }
        inputs.addAll(byCheck.values());var first=inputs.getFirst().prepared();
        for(var pair:inputs)sameDelivery(first,pair.prepared());
        var selected=selected(first);
        require(new ArrayList<>(byCheck.keySet()).equals(selected),Reason.SELECTION_MISMATCH);
        var operations=new HashSet<String>();var containers=new HashSet<String>();var refs=new HashSet<String>();
        var checks=new ArrayList<CheckObservation>();var manifests=new ArrayList<byte[]>();
        for(var pair:inputs) {
            current(pair.prepared());
            byte[] bytes=verified(pair.sealed());manifests.add(bytes);
            checks.add(inspect(pair,bytes,operations,containers,refs));
        }
        // Long IO must end with fresh full input observations and another read of every sealed byte.
        for(int i=0;i<inputs.size();i++) {
            require(Arrays.equals(manifests.get(i),verified(inputs.get(i).sealed())),Reason.EVIDENCE_INVALID);
            current(inputs.get(i).prepared());
        }
        require(selected.equals(selected(first)),Reason.SELECTION_MISMATCH);
        var summary=new LinkedHashMap<String,Object>();summary.put("version","dsh-selected-check-observation-v1");
        summary.put("bindingDigest",first.request().bindingDigest());summary.put("attempt",first.observed());
        summary.put("candidateDigest",first.request().candidateDigest());summary.put("deliveryDigest",first.delivery().digest());
        summary.put("checks",checks);summary.put("successAuthority",false);
        return new Observation(inputs,checks,CanonicalTaskJson.digest(mapper.valueToTree(summary)));
    }
    /** Re-observation is still not a transactionally consumable success permission. */
    public void requireCurrent(Observation observed) {
        require(observed!=null,Reason.INVALID_INPUT);
        require(observed.digest().equals(assess(observed.inputs).digest()),Reason.INPUTS_STALE);
    }
    private List<String> selected(TaskCheckAttemptPreparation.Prepared first) {
        var r=first.request();
        var rows=jdbc.query("SELECT binding_json,binding_digest,snapshot_json,snapshot_digest FROM task_check_registrations WHERE id=?",
                (rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)},r.registrationId());
        require(rows.size()==1,Reason.SELECTION_MISMATCH);
        try {
            var row=rows.getFirst();var bound=TaskCheckRegistrationBinding.decode(row[2]);
            require(bound.canonicalBinding.equals(row[0]) && bound.bindingDigest.equals(row[1])
                    && bound.snapshotDigest.equals(row[3]) && r.bindingDigest().equals(bound.bindingDigest),Reason.SELECTION_MISMATCH);
            var preview=mapper.readValue(row[2],com.dsh.api.dto.TaskCheckSelectionPreview.class);
            require(preview.execution()!=null,Reason.SELECTION_MISMATCH);return preview.checkRefs();
        }catch(Unavailable refused){throw refused;}catch(Exception invalid){throw new Unavailable(Reason.SELECTION_MISMATCH);}
    }
    private void sameDelivery(TaskCheckAttemptPreparation.Prepared a,TaskCheckAttemptPreparation.Prepared b) {
        var x=a.request();var y=b.request();
        require(a.observed().equals(b.observed()) && x.registrationId().equals(y.registrationId())
                && x.bindingDigest().equals(y.bindingDigest()) && x.sourceSnapshot().equals(y.sourceSnapshot())
                && x.workspaceId().equals(y.workspaceId()) && x.candidateRoot().equals(y.candidateRoot())
                && x.candidateFiles().equals(y.candidateFiles()) && x.candidateDigest().equals(y.candidateDigest())
                && x.execution().executionSpecDigest().equals(y.execution().executionSpecDigest())
                && x.execution().sourceDigest().equals(y.execution().sourceDigest())
                && x.execution().cacheRoot().equals(y.execution().cacheRoot())
                && a.delivery().digest().equals(b.delivery().digest()),Reason.INPUTS_MISMATCH);
    }
    private CheckObservation inspect(CheckEvidence pair,byte[] bytes,Set<String> operations,Set<String> containers,Set<String> refs) {
        try {
            var p=pair.prepared();var r=p.request();var sealed=pair.sealed();var tree=TaskCheckEvidenceService.parseManifest(bytes);
            require(sealed.currentAtReturn() && bool(tree,"currentAfterExecution",true) && bool(tree,"currentAtSeal",true)
                    && bool(tree,"cancellationObserved",false) && bool(tree,"successAuthority",false),Reason.CHECK_FAILED);
            require(sameJson(tree.path("binding"),TaskCheckEvidenceService.binding(p)),Reason.INPUTS_MISMATCH);
            require(sealed.manifest().runId().equals(p.observed().child().id())
                    && sealed.manifest().projectId().equals(p.observed().projectId()),Reason.INPUTS_MISMATCH);
            require(refs.add(sealed.manifest().id()),Reason.EVIDENCE_INVALID);
            var execution=tree.path("execution");
            require(r.operationId().equals(execution.path("operationId").asText()) && operations.add(r.operationId()),Reason.INPUTS_MISMATCH);
            var container=execution.path("containerId");
            require(container.isTextual() && container.textValue().matches("[a-f0-9]{64}") && containers.add(container.textValue()),Reason.EVIDENCE_INVALID);
            require("COMPLETED".equals(execution.path("termination").asText()) && execution.path("exitCode").isIntegralNumber()
                    && execution.path("exitCode").canConvertToInt() && execution.path("exitCode").intValue()==0
                    && "ABSENT".equals(execution.path("cleanup").asText()),Reason.CHECK_FAILED);
            var members=new HashMap<String,TaskCheckEvidenceService.ArtifactRef>();
            for(var ref:sealed.members()){require(refs.add(ref.id()) && members.putIfAbsent(ref.id(),ref)==null,Reason.EVIDENCE_INVALID);}
            var used=new HashSet<String>();var delivery=tree.path("delivery");
            var zip=member(delivery.path("artifact"),members,used,"check-delivery.zip","application/zip");
            require(delivery.path("digest").asText().equals(p.delivery().digest())
                    && delivery.path("baselineDigest").asText().equals(p.delivery().baselineDigest())
                    && sameJson(delivery.path("files"),p.delivery().files())
                    && Arrays.equals(artifacts.readVerifiedContent(zip.id(),64L*1048576),p.delivery().zip()),Reason.INPUTS_MISMATCH);
            var reportNodes=tree.path("reports");var limits=r.execution().limits();
            require(reportNodes.isArray() && reportNodes.size()>0 && reportNodes.size()<=limits.reportFiles(),Reason.CHECK_FAILED);
            var raw=new ArrayList<byte[]>();var paths=new HashSet<String>();long total=0;int i=0;
            for(var report:reportNodes) {
                var path=report.path("path");require(path.isTextual() && path.textValue().matches("target/surefire-reports/TEST-[A-Za-z0-9_.$-]{1,240}\\.xml")
                        && paths.add(path.textValue().toLowerCase(Locale.ROOT)),Reason.EVIDENCE_INVALID);
                var ref=member(report.path("artifact"),members,used,"check-report-"+(i++)+".xml","application/xml");
                byte[] xml=artifacts.readVerifiedContent(ref.id(),limits.reportFileBytes());total+=xml.length;
                require(total<=limits.reportTotalBytes(),Reason.EVIDENCE_INVALID);raw.add(xml);
            }
            member(tree.path("logs").path("stdout"),members,used,"check-stdout.log","text/plain");
            member(tree.path("logs").path("stderr"),members,used,"check-stderr.log","text/plain");
            require(used.equals(members.keySet()),Reason.EVIDENCE_INVALID);
            var parsed=TaskCheckReports.parseAll(raw);var c=parsed.counts();
            require(sameJson(tree.path("parsedReports"),parsed),Reason.EVIDENCE_INVALID);
            require(parsed.error()==null && c.discovered()>0 && c.failures()==0 && c.passed()>=r.check().minPassed(),Reason.CHECK_FAILED);
            return new CheckObservation(r.check().checkId(),r.operationId(),sealed.manifest(),c);
        }catch(Unavailable refused){throw refused;}catch(RuntimeException invalid){throw new Unavailable(Reason.EVIDENCE_INVALID);}
    }
    private TaskCheckEvidenceService.ArtifactRef member(JsonNode node,Map<String,TaskCheckEvidenceService.ArtifactRef> members,
            Set<String> used,String name,String mediaType) {
        var ref=members.get(node.path("id").asText());
        require(ref!=null && sameJson(node,ref) && name.equals(ref.name()) && mediaType.equals(ref.mediaType())
                && used.add(ref.id()),Reason.EVIDENCE_INVALID);return ref;
    }
    private static boolean bool(JsonNode node,String field,boolean expected){return node.path(field).isBoolean() && node.path(field).booleanValue()==expected;}
    private boolean sameJson(JsonNode actual,Object expected) {
        return Arrays.equals(CanonicalTaskJson.encode(actual),CanonicalTaskJson.encode(mapper.valueToTree(expected)));
    }
    private byte[] verified(TaskCheckEvidenceService.Sealed sealed) {
        try{return evidence.verify(sealed);}catch(RuntimeException invalid){throw new Unavailable(Reason.EVIDENCE_INVALID);}
    }
    private void current(TaskCheckAttemptPreparation.Prepared prepared) {
        try{
            requireNotCancelled(prepared);preparation.requireCurrent(prepared);requireNotCancelled(prepared);
        }catch(RuntimeException invalid){throw new Unavailable(Reason.INPUTS_STALE);}
    }
    private void requireNotCancelled(TaskCheckAttemptPreparation.Prepared prepared) {
        var token=cancellations.get(prepared.observed().rootRunId());
        require(!Thread.currentThread().isInterrupted() && (token==null || !token.isCancelled()),Reason.INPUTS_STALE);
    }
    private static void require(boolean valid,Reason reason){if(!valid)throw new Unavailable(reason);}
}
