package com.dsh.orchestration.acceptance;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.TaskCheckSelectionPreview;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/** Only derives a selected view from the actual authorized, immutable full preview. No writes. */
@Service
public class TaskCheckSelectionPreviewService {
    private final TaskCheckPreviewService previews;
    @org.springframework.beans.factory.annotation.Autowired private HostTaskCheckCatalog catalogs;
    private final ObjectMapper canonicalMapper=new ObjectMapper();
    public TaskCheckSelectionPreviewService(TaskCheckPreviewService previews){this.previews=previews;}
    public TaskCheckSelectionPreview preview(String runId,String planId,String taskId,String ifMatch,List<String> refs){
        if(ifMatch==null||ifMatch.isBlank())throw new ApiException(ErrorCode.PRECONDITION_REQUIRED,"请先读取任务需求预览并提供If-Match");
        String expected=ifMatch.trim();
        if(!expected.matches("\"task-check-preview-v1-[a-f0-9]{64}\""))throw invalid("If-Match必须是实际任务需求预览的完整ETag");
        validateRefs(refs);
        var taskPreview=previews.preview(runId,planId,taskId);
        if(!expected.equals("\"task-check-preview-v1-"+taskPreview.previewDigest()+"\""))
            throw new ApiException(ErrorCode.VERSION_CONFLICT,"需求、任务或目录已变化，请重新读取预览并选择检查");
        return select(taskPreview,refs);
    }
    public TaskCheckSelectionPreview previewExecution(String runId,String planId,String taskId,String ifMatch,List<String> refs){
        var selected=preview(runId,planId,taskId,ifMatch,refs);
        try {
            var execution=catalogs.loadExecution(selected.taskPreview().projectId());
            var result=withExecution(selected,execution);
            var after=preview(runId,planId,taskId,ifMatch,refs);
            if(!selected.equals(after))throw new ApiException(ErrorCode.VERSION_CONFLICT,"需求或目录已变化，请重新预览");
            catalogs.requireCurrentExecution(execution); return result;
        }catch(HostTaskCheckCatalog.Unavailable invalid){throw TaskCheckPreviewService.unavailable(invalid);}
        catch(IllegalArgumentException invalid){throw new ApiException(ErrorCode.VERSION_CONFLICT,"完整执行描述与当前目录不一致，请重新预览");}
    }
    static TaskCheckSelectionPreview withExecution(TaskCheckSelectionPreview selected,TrustedTaskExecutionSpec.Snapshot snapshot){
        var execution=com.dsh.api.dto.TaskCheckExecutionView.from(snapshot);
        execution.validateAgainst(selected.taskPreview());
        return new TaskCheckSelectionPreview(selected.status(),selected.taskPreview(),selected.checkRefs(),selected.selectedChecks(),
                selected.checksDigest(),selectionDigest(selected.taskPreview().previewDigest(),selected.checksDigest(),selected.checkRefs(),execution),execution);
    }
    static String selectionDigest(String previewDigest,String checksDigest,List<String> refs,com.dsh.api.dto.TaskCheckExecutionView execution){
        var mapper=new ObjectMapper();
        var bound=mapper.createObjectNode().put("version",execution==null?"task-check-selection-v1":"task-check-selection-v2")
                .put("previewDigest",previewDigest).put("checksDigest",checksDigest);
        bound.set("checkRefs",mapper.valueToTree(refs));
        if(execution!=null)bound.set("execution",mapper.valueToTree(execution));
        return CanonicalTaskJson.digest(bound);
    }
    static void validateRefs(List<String> refs){
        if(refs==null||refs.isEmpty()||refs.size()>8)throw invalid("请选择1～8个检查");
        var ids=new TreeSet<String>();
        for(String ref:refs)if(ref==null||!ref.matches("[A-Za-z0-9_-]{1,64}")||!ids.add(ref))throw invalid("检查引用无效或重复");
    }
    static TaskCheckSelectionPreview select(com.dsh.api.dto.TaskCheckPreview taskPreview,List<String> refs){
        validateRefs(refs);
        var canonicalMapper=new ObjectMapper();
        var ids=new TreeSet<String>();
        for(String ref:refs)if(ref==null||!ref.matches("[A-Za-z0-9_-]{1,64}")||!ids.add(ref))throw invalid("检查引用无效或重复");
        var available=new TreeMap<String,TrustedTaskCheckCatalog.Check>();
        taskPreview.availableChecks().forEach(check->available.put(check.id(),check));
        if(!available.keySet().containsAll(ids))throw invalid("所选检查不在当前可信目录中");
        var checks=ids.stream().map(available::get).toList();
        // The Check record has exactly the frozen catalog definition fields; canonical
        // serialization therefore matches Snapshot.checksDigest, including ordered argv/verifiers.
        String checksDigest=CanonicalTaskJson.digest(canonicalMapper.valueToTree(checks));
        var binding=canonicalMapper.createObjectNode().put("version","task-check-selection-v1")
                .put("previewDigest",taskPreview.previewDigest()).put("checksDigest",checksDigest);
        binding.set("checkRefs",canonicalMapper.valueToTree(ids));
        return new TaskCheckSelectionPreview("SELECTED_PREVIEW_ONLY",taskPreview,List.copyOf(ids),checks,
                checksDigest,CanonicalTaskJson.digest(binding));
    }
    private static ApiException invalid(String message){return new ApiException(ErrorCode.VALIDATION_FAILED,message);}
}
