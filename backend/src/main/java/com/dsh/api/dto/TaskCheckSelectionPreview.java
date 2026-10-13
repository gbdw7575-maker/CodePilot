package com.dsh.api.dto;

import com.dsh.orchestration.acceptance.TrustedTaskCheckCatalog;
import java.util.List;

/** A selected set for review. It is not a confirmation, registration or execution record. */
public record TaskCheckSelectionPreview(String status, TaskCheckPreview taskPreview,
        List<String> checkRefs, List<TrustedTaskCheckCatalog.Check> selectedChecks,
        String checksDigest, String selectionDigest,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        TaskCheckExecutionView execution) {
    public TaskCheckSelectionPreview(String status,TaskCheckPreview taskPreview,List<String> checkRefs,
            List<TrustedTaskCheckCatalog.Check> selectedChecks,String checksDigest,String selectionDigest) {
        this(status,taskPreview,checkRefs,selectedChecks,checksDigest,selectionDigest,null);
    }
    public TaskCheckSelectionPreview { checkRefs=List.copyOf(checkRefs); selectedChecks=List.copyOf(selectedChecks); }
}
