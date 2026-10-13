package com.dsh.orchestration.acceptance;

import com.dsh.application.ArtifactStore;
import com.dsh.workspace.WorkspaceManager;
import org.springframework.stereotype.Service;

/** Joins current DB/confirmation/Host inputs to actual closed child delivery bytes; no execution/CAS. */
@Service
public class TaskCheckAttemptPreparation {
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private TaskCheckReceiptReader receipts;
    @org.springframework.beans.factory.annotation.Value("${dsh.orchestration.strong-runtime-enabled:false}") private boolean strongRuntime;
    private final TaskCheckAttemptAuthority attempts;
    private final TaskCheckExecutionRequestFactory requests;
    private final WorkspaceManager workspaces;
    private final ArtifactStore artifacts;
    public TaskCheckAttemptPreparation(TaskCheckAttemptAuthority attempts,TaskCheckExecutionRequestFactory requests,
                                        WorkspaceManager workspaces,ArtifactStore artifacts){
        this.attempts=attempts;this.requests=requests;this.workspaces=workspaces;this.artifacts=artifacts;
    }
    public static final class Prepared {
        private final TaskCheckAttemptAuthority.Observation observed;
        private final TaskCheckExecutionRequest request;
        private final TaskCheckDelivery delivery;
        private Prepared(TaskCheckAttemptAuthority.Observation observed,TaskCheckExecutionRequest request,TaskCheckDelivery delivery){
            this.observed=observed;this.request=request;this.delivery=delivery;
        }
        public TaskCheckAttemptAuthority.Observation observed(){return observed;}
        public TaskCheckExecutionRequest request(){return request;}
        TaskCheckDelivery delivery(){return delivery;}
    }
    public Prepared prepare(String registrationId,String checkId,String attemptId,String childRunId,long token){
        var observed=attempts.observe(attemptId,childRunId,token);
        var request=requests.prepareForAttempt(registrationId,checkId,observed);
        requireArtifacts(observed);
        var delivery=TaskCheckDelivery.capture(request.candidateRoot(),workspaces.resolveByRef(request.workspaceId()).metaDir(),request.candidateFiles());
        // A second complete capture detects changes outside the checked Java subset as well.
        var second=TaskCheckDelivery.capture(request.candidateRoot(),workspaces.resolveByRef(request.workspaceId()).metaDir(),request.candidateFiles());
        if(!delivery.digest().equals(second.digest()))throw new IllegalStateException("INPUTS_STALE");
        attempts.requireCurrent(observed);
        var current=requests.prepareForAttempt(registrationId,checkId,observed);
        if(!request.sameInputs(current))throw new IllegalStateException("INPUTS_STALE");
        return new Prepared(observed,request,delivery);
    }
    public void requireCurrent(Prepared prepared){
        if(prepared==null)throw new IllegalArgumentException("INVALID_INPUT");var a=prepared.observed().attempt();
        var current=prepare(prepared.request().registrationId(),prepared.request().check().checkId(),a.id(),a.childRunId(),a.fencingToken());
        if(!prepared.observed().equals(current.observed()) || !prepared.request().sameInputs(current.request())
                || !prepared.delivery().digest().equals(current.delivery().digest()))throw new IllegalStateException("INPUTS_STALE");
    }
    /** Cheap DB observation for the running check's cancellation predicate; no full cache/file IO. */
    public boolean invalidated(Prepared prepared){
        try{attempts.requireCurrent(prepared.observed());return false;}catch(RuntimeException unavailable){return true;}
    }
    private void requireArtifacts(TaskCheckAttemptAuthority.Observation observed){
        if(strongRuntime)for(var upstream:observed.upstream())receipts.requireAttempt(upstream.attemptId());
        // All declared dependency output refs are bound, even if not materialized by inputRefs.
        long total=0;
        for(var upstream:observed.upstream())for(var expected:upstream.artifacts()){
            if((total+=expected.bytes())>64L*1048576)throw new IllegalStateException("UPSTREAM_LIMIT");
            var actual=artifacts.get(expected.id());var bytes=artifacts.readVerifiedContent(expected.id(),32L*1048576);
            if(!observed.projectId().equals(actual.getProjectId()) || !expected.runId().equals(actual.getRunId())
                    || expected.bytes()!=bytes.length || !expected.sha256().equals(CanonicalTaskJson.sha256(bytes))
                    || !expected.mediaType().equals(actual.getMediaType()))throw new IllegalStateException("UPSTREAM_UNAVAILABLE");
        }
    }
}
