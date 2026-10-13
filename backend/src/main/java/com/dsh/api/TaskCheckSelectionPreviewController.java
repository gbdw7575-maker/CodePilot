package com.dsh.api;

import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.TaskCheckSelectionPreview;
import com.dsh.orchestration.acceptance.TaskCheckSelectionPreviewService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/runs/{runId}/plans/{planId}/tasks/{taskId}")
public class TaskCheckSelectionPreviewController {
    private final TaskCheckSelectionPreviewService selections;
    public TaskCheckSelectionPreviewController(TaskCheckSelectionPreviewService selections){this.selections=selections;}
    @GetMapping("/acceptance-selection-preview")
    public ResponseEntity<TaskCheckSelectionPreview> preview(@PathVariable String runId,@PathVariable String planId,
            @PathVariable String taskId,@RequestHeader(value="If-Match",required=false) String ifMatch,
            @RequestParam MultiValueMap<String,String> query){
        // Keep each raw query value; don't let Spring split a comma string into two IDs.
        if(!Set.of("checkRef").containsAll(query.keySet()))throw new ApiException(ErrorCode.VALIDATION_FAILED,"仅接受重复checkRef查询参数");
        var selected=selections.preview(runId,planId,taskId,ifMatch,query.get("checkRef"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag("\"task-check-selection-v1-"+selected.selectionDigest()+"\"").body(selected);
    }
    @GetMapping("/acceptance-execution-preview")
    public ResponseEntity<TaskCheckSelectionPreview> execution(@PathVariable String runId,@PathVariable String planId,
            @PathVariable String taskId,@RequestHeader(value="If-Match",required=false) String ifMatch,
            @RequestParam MultiValueMap<String,String> query){
        if(!Set.of("checkRef").containsAll(query.keySet()))throw new ApiException(ErrorCode.VALIDATION_FAILED,"仅接受重复checkRef查询参数");
        var selected=selections.previewExecution(runId,planId,taskId,ifMatch,query.get("checkRef"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag("\"task-check-selection-v2-"+selected.selectionDigest()+"\"").body(selected);
    }
}
