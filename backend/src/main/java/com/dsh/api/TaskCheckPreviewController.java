package com.dsh.api;

import com.dsh.api.dto.TaskCheckPreview;
import com.dsh.orchestration.acceptance.TaskCheckPreviewService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/runs/{runId}/plans/{planId}/tasks/{taskId}")
public class TaskCheckPreviewController {
    private final TaskCheckPreviewService previews;
    public TaskCheckPreviewController(TaskCheckPreviewService previews){this.previews=previews;}
    @GetMapping("/acceptance-preview")
    public ResponseEntity<TaskCheckPreview> preview(@PathVariable String runId,@PathVariable String planId,@PathVariable String taskId){
        var preview=previews.preview(runId,planId,taskId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag("\"task-check-preview-v1-"+preview.previewDigest()+"\"").body(preview);
    }
}
