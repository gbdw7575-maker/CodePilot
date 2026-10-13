package com.dsh.orchestration.acceptance;

import com.dsh.agent.control.CancellationTokenRegistry;
import com.dsh.application.ArtifactStore;
import com.dsh.application.TaskCheckReports;
import com.dsh.sandbox.SandboxRunner;
import com.dsh.sandbox.TaskCheckExecutionOutcome;
import com.dsh.storage.ArtifactEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Internal evidence producer only. Never grants launch permission or writes a task success state. */
@Service
public class TaskCheckEvidenceService {
    private final TaskCheckAttemptPreparation preparation;
    private final SandboxRunner runner;
    private final ArtifactStore artifacts;
    private final ObjectMapper mapper;
    private final CancellationTokenRegistry cancellations;
    private static final ObjectMapper EVIDENCE_JSON=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(524288).maxNumberLength(32).build()).build());
    public TaskCheckEvidenceService(TaskCheckAttemptPreparation preparation,SandboxRunner runner,
            ArtifactStore artifacts,ObjectMapper mapper,CancellationTokenRegistry cancellations) {
        this.preparation=preparation;this.runner=runner;this.artifacts=artifacts;this.mapper=mapper;this.cancellations=cancellations;
    }
    public record ArtifactRef(String id,String runId,String projectId,String name,String mediaType,long bytes,String sha256) { }
    public static final class Sealed {
        private final ArtifactRef manifest;
        private final List<ArtifactRef> members;
        private final boolean currentAtReturn;
        private Sealed(ArtifactRef manifest,List<ArtifactRef> members,boolean currentAtReturn) {
            this.manifest=manifest;this.members=List.copyOf(members);this.currentAtReturn=currentAtReturn;
        }
        public ArtifactRef manifest(){return manifest;}
        public List<ArtifactRef> members(){return members;}
        public boolean currentAtReturn(){return currentAtReturn;}
    }

    public Sealed executeAndSeal(TaskCheckAttemptPreparation.Prepared prepared,BooleanSupplier cancelled) {
        if(prepared==null || cancelled==null || TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("CHECK_EVIDENCE_UNAVAILABLE");
        preparation.requireCurrent(prepared);
        var request=prepared.request();var cancellation=new CheckCancellation(prepared,cancelled);
        Instant started=Instant.now();var outcome=runner.executeCheck(request,cancellation);Instant finished=Instant.now();
        validateOutcome(request,outcome);
        boolean currentAfterExecution=current(prepared);
        var members=new ArrayList<ArtifactRef>();
        var delivery=save(prepared,"check-delivery.zip","application/zip",prepared.delivery().zip());members.add(delivery);
        var reports=new ArrayList<Map<String,Object>>();var raw=new ArrayList<byte[]>();int index=0;
        for(String path:outcome.reportPaths()) {
            byte[] bytes=outcome.reportBytes(path);raw.add(bytes);
            var ref=save(prepared,"check-report-"+(index++)+".xml","application/xml",bytes);members.add(ref);
            reports.add(Map.of("path",path,"artifact",ref));
        }
        var stdout=save(prepared,"check-stdout.log","text/plain",outcome.logs().stdout().getBytes(StandardCharsets.UTF_8));members.add(stdout);
        var stderr=save(prepared,"check-stderr.log","text/plain",outcome.logs().stderr().getBytes(StandardCharsets.UTF_8));members.add(stderr);
        boolean currentAtSeal=currentAfterExecution && current(prepared);
        var binding=binding(prepared);
        var fact=new LinkedHashMap<String,Object>();fact.put("operationId",outcome.operationId());fact.put("containerId",outcome.containerId());
        fact.put("termination",outcome.termination());fact.put("exitCode",outcome.exitCode());fact.put("diagnostic",outcome.diagnostic());
        fact.put("cleanup",outcome.cleanup());fact.put("durationMs",outcome.durationMs());fact.put("startedAt",started.toString());fact.put("finishedAt",finished.toString());
        var document=new LinkedHashMap<String,Object>();document.put("version","dsh-task-check-evidence-v1");document.put("binding",binding);
        document.put("delivery",Map.of("artifact",delivery,"files",prepared.delivery().files(),"digest",prepared.delivery().digest(),"baselineDigest",prepared.delivery().baselineDigest()));
        document.put("execution",fact);document.put("reports",reports);document.put("parsedReports",TaskCheckReports.parseAll(raw));
        document.put("logs",Map.of("stdout",stdout,"stderr",stderr,"totalBytes",outcome.logs().totalBytes(),
                "droppedBytes",outcome.logs().droppedBytes(),"truncated",outcome.logs().truncated(),"encoding","UTF-8_DECODED_RUNNER_TEXT"));
        document.put("currentAfterExecution",currentAfterExecution);document.put("currentAtSeal",currentAtSeal);
        document.put("cancellationObserved",cancellation.observed);document.put("successAuthority",false);
        byte[] bytes=CanonicalTaskJson.encode(mapper.valueToTree(document));
        if(bytes.length>2*1048576)throw new IllegalStateException("CHECK_EVIDENCE_LIMIT");
        var manifest=save(prepared,"check-evidence.json","application/json",bytes);
        var sealed=new Sealed(manifest,members,currentAtSeal && current(prepared));verify(sealed);return sealed;
    }

    /** One serialization contract for producer and internal selection observer. */
    static Map<String,Object> binding(TaskCheckAttemptPreparation.Prepared prepared) {
        var request=prepared.request();var execution=request.execution();var binding=new LinkedHashMap<String,Object>();
        binding.put("registrationId",request.registrationId());binding.put("bindingDigest",request.bindingDigest());
        binding.put("source",request.sourceSnapshot());binding.put("attemptObservation",prepared.observed());
        binding.put("workspaceRef",request.workspaceId());binding.put("candidateDigest",request.candidateDigest());binding.put("candidateFiles",request.candidateFiles());
        binding.put("executionSpecDigest",execution.executionSpecDigest());binding.put("executionSourceDigest",execution.sourceDigest());
        binding.put("registryDigest",execution.registryDigest());binding.put("imageDigest",execution.imageDigest());
        binding.put("executionDefinition",CanonicalTaskJson.parse(execution.canonicalDefinition().getBytes(StandardCharsets.UTF_8)));
        binding.put("cacheFiles",execution.cacheFiles());binding.put("check",request.check());
        return binding;
    }

    /** Verify actual DB ownership/metadata and every referenced byte, including the final manifest. */
    public byte[] verify(Sealed sealed) {
        if(sealed==null)throw new IllegalArgumentException("INVALID_INPUT");
        byte[] manifest=read(sealed.manifest(),2*1048576);
        var tree=parseManifest(manifest);
        if(!"dsh-task-check-evidence-v1".equals(tree.path("version").asText()) || tree.path("successAuthority").asBoolean(true))
            throw new IllegalStateException("CHECK_EVIDENCE_UNAVAILABLE");
        for(var member:sealed.members()) {
            if(!sealed.manifest().runId().equals(member.runId()) || !sealed.manifest().projectId().equals(member.projectId()))
                throw new IllegalStateException("CHECK_EVIDENCE_UNAVAILABLE");
            read(member,64L*1048576);
        }
        return manifest;
    }
    static com.fasterxml.jackson.databind.JsonNode parseManifest(byte[] bytes) {
        try(var parser=EVIDENCE_JSON.createParser(bytes)) {
            com.fasterxml.jackson.databind.JsonNode tree=EVIDENCE_JSON.readTree(parser);
            if(tree==null || parser.nextToken()!=null || !Arrays.equals(bytes,CanonicalTaskJson.encode(tree)))throw protocol();
            return tree;
        }catch(java.io.IOException invalid){throw new IllegalStateException("CHECK_EVIDENCE_UNAVAILABLE");}
    }
    private byte[] read(ArtifactRef ref,long limit) {
        var entity=artifacts.get(ref.id());
        if(!ref.equals(reference(entity)))throw new IllegalStateException("CHECK_EVIDENCE_UNAVAILABLE");
        var bytes=artifacts.readVerifiedContent(ref.id(),limit);
        if(bytes.length!=ref.bytes() || !CanonicalTaskJson.sha256(bytes).equals(ref.sha256())
                || !ref.equals(reference(artifacts.get(ref.id()))))throw new IllegalStateException("CHECK_EVIDENCE_UNAVAILABLE");
        return bytes;
    }
    private ArtifactRef save(TaskCheckAttemptPreparation.Prepared p,String name,String mediaType,byte[] bytes) {
        return reference(artifacts.saveCheckEvidence(p.observed().child().id(),p.observed().projectId(),p.request().workspaceId(),name,mediaType,bytes));
    }
    private static ArtifactRef reference(ArtifactEntity e) {
        if(!e.isDownloadable())throw new IllegalStateException("CHECK_EVIDENCE_UNAVAILABLE");
        return new ArtifactRef(e.getId(),e.getRunId(),e.getProjectId(),e.getName(),e.getMediaType(),e.getSizeBytes(),e.getSha256());
    }
    private boolean current(TaskCheckAttemptPreparation.Prepared p) {
        try{preparation.requireCurrent(p);return true;}catch(RuntimeException stale){return false;}
    }
    private static void validateOutcome(TaskCheckExecutionRequest request,TaskCheckExecutionOutcome outcome) {
        if(outcome==null || !request.operationId().equals(outcome.operationId()) || outcome.termination()==null || outcome.cleanup()==null
                || (outcome.containerId()!=null && !outcome.containerId().matches("[a-f0-9]{64}")) || outcome.durationMs()<0
                || outcome.diagnostic()==null || !outcome.diagnostic().matches("[A-Z_]{1,100}")
                || (outcome.termination()==TaskCheckExecutionOutcome.Termination.COMPLETED
                    && (outcome.exitCode()==null || outcome.exitCode()<0 || outcome.exitCode()>255))
                || (outcome.termination()!=TaskCheckExecutionOutcome.Termination.COMPLETED && outcome.exitCode()!=null))throw protocol();
        var logs=outcome.logs();var limits=request.execution().limits();
        if(logs==null || logs.stdout()==null || logs.stderr()==null || logs.totalBytes()<0 || logs.droppedBytes()<0
                || logs.droppedBytes()>logs.totalBytes() || (logs.droppedBytes()>0 && !logs.truncated())
                || (long)logs.stdout().getBytes(StandardCharsets.UTF_8).length+logs.stderr().getBytes(StandardCharsets.UTF_8).length>3L*limits.logBytes())throw protocol();
        if(outcome.reportPaths().size()>limits.reportFiles())throw protocol();
        long total=0;var aliases=new HashSet<String>();
        for(String path:outcome.reportPaths()) {
            if(!path.startsWith("target/surefire-reports/TEST-")
                    || !path.substring("target/surefire-reports/".length()).matches("[A-Za-z0-9_.$-]{1,240}\\.xml")
                    || !aliases.add(path.toLowerCase(Locale.ROOT)))throw protocol();
            byte[] bytes=outcome.reportBytes(path);
            if(bytes.length==0 || bytes.length>limits.reportFileBytes() || (total+=bytes.length)>limits.reportTotalBytes())throw protocol();
        }
    }
    private static IllegalStateException protocol(){return new IllegalStateException("CHECK_OUTCOME_INVALID");}
    private final class CheckCancellation implements BooleanSupplier {
        private final TaskCheckAttemptPreparation.Prepared prepared;private final BooleanSupplier external;
        private long nextRead;private volatile boolean observed;
        CheckCancellation(TaskCheckAttemptPreparation.Prepared p,BooleanSupplier external){this.prepared=p;this.external=external;}
        @Override public synchronized boolean getAsBoolean() {
            if(observed)return true;
            var token=cancellations.get(prepared.observed().rootRunId());
            if(Thread.currentThread().isInterrupted() || external.getAsBoolean() || (token!=null && token.isCancelled()))return observed=true;
            long now=System.nanoTime();if(now-nextRead>=0){nextRead=now+500_000_000L;if(preparation.invalidated(prepared))observed=true;}
            return observed;
        }
    }
}
