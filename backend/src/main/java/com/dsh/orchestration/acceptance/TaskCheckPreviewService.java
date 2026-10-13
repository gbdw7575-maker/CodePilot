package com.dsh.orchestration.acceptance;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.TaskCheckPreview;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** Read-only preview, never registration. File IO is outside source transactions. */
@Service
public class TaskCheckPreviewService {
    private final TaskCheckPreviewSource sources;
    private final HostTaskCheckCatalog catalogs;
    private final ObjectMapper canonicalMapper=new ObjectMapper();
    public TaskCheckPreviewService(TaskCheckPreviewSource sources,HostTaskCheckCatalog catalogs){this.sources=sources;this.catalogs=catalogs;}
    public TaskCheckPreview preview(String runId,String planId,String taskId){
        var before=sources.read(runId,planId,taskId);
        try {
            var catalog=catalogs.load(before.projectId());
            var after=sources.read(runId,planId,taskId);
            if(!before.equals(after))throw new ApiException(ErrorCode.VERSION_CONFLICT,"任务、修订或需求已变化，请重新读取预览");
            catalogs.requireCurrent(catalog);
            return compose(before,catalog);
        } catch(HostTaskCheckCatalog.Unavailable unavailable){
            throw unavailable(unavailable);
        } catch(IllegalArgumentException invalid){
            throw new ApiException(ErrorCode.VALIDATION_FAILED,"任务或需求包含不支持的规范化数据");
        }
    }
    static TaskCheckPreview compose(TaskCheckPreviewSource.Snapshot before,TrustedTaskCheckCatalog.Snapshot catalog){
            var canonicalMapper=new ObjectMapper();
            String taskDigest=CanonicalTaskJson.digest(canonicalMapper.valueToTree(before.task()));
            String requirementsDigest=CanonicalTaskJson.digest(canonicalMapper.valueToTree(before.requirements()));
            var binding=canonicalMapper.createObjectNode().put("version","task-check-preview-v1")
                    .put("projectId",before.projectId()).put("rootRunId",before.rootRunId()).put("turnId",before.turnId())
                    .put("planId",before.planId()).put("planRevision",before.planRevision()).put("taskState",before.taskState())
                    .put("taskDefinitionDigest",taskDigest).put("requirementsDigest",requirementsDigest)
                    .put("registryDigest",catalog.registryDigest());
            return new TaskCheckPreview("PREVIEW_ONLY",before.projectId(),before.rootRunId(),before.turnId(),
                    before.planId(),before.planRevision(),before.taskState(),before.task(),before.requirements(),taskDigest,
                    requirementsDigest,catalog.registryDigest(),catalog.imageDigest(),catalog.policyDigest(),
                    catalog.checks(),CanonicalTaskJson.digest(binding));
    }
    static ApiException unavailable(HostTaskCheckCatalog.Unavailable unavailable){
            ErrorCode code=switch(unavailable.reason()){
                case DISABLED->ErrorCode.SERVICE_UNAVAILABLE;
                case PROJECT_NOT_FOUND->ErrorCode.RESOURCE_NOT_FOUND;
                case PROJECT_NOT_TRUSTED->ErrorCode.PROJECT_NOT_TRUSTED;
                case PROJECT_SET_CHANGED,STALE_CATALOG->ErrorCode.VERSION_CONFLICT;
                case CATALOG_INVALID,INVALID_CONTROL_LAYOUT->ErrorCode.VALIDATION_FAILED;
            };
            return new ApiException(code,"可信检查目录不可用："+unavailable.reason());
    }
}
