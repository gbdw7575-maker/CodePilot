package com.dsh.api.dto;
import java.time.Instant;
import java.util.List;
/** Immutable historical registration, never executable evidence or a current dispatch grant. */
public record TaskCheckRegistration(String status,String registrationId,String projectId,String rootRunId,
        String turnId,String planId,String taskId,int taskVersion,List<String> checkRefs,String checksDigest,
        String bindingDigest,String snapshotDigest,int observedPlanRevision,Instant recordedAt,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String executionSpecDigest) {
    public TaskCheckRegistration(String status,String registrationId,String projectId,String rootRunId,
            String turnId,String planId,String taskId,int taskVersion,List<String> checkRefs,String checksDigest,
            String bindingDigest,String snapshotDigest,int observedPlanRevision,Instant recordedAt) {
        this(status,registrationId,projectId,rootRunId,turnId,planId,taskId,taskVersion,checkRefs,checksDigest,
                bindingDigest,snapshotDigest,observedPlanRevision,recordedAt,null);
    }
    public TaskCheckRegistration { checkRefs=List.copyOf(checkRefs); }
}
