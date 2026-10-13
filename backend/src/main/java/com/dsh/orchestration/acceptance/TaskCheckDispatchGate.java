package com.dsh.orchestration.acceptance;

import com.dsh.api.ApiException;
import com.dsh.api.dto.TaskCheckSelectionPreview;
import com.dsh.storage.TaskEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.List;

/** Validate each real frozen registration. M1D execution is deliberately unavailable, never downgraded. */
@Service
public class TaskCheckDispatchGate {
    @org.springframework.beans.factory.annotation.Value("${dsh.orchestration.strong-runtime-enabled:false}") private boolean runtimeEnabled;
    private final JdbcTemplate jdbc; private final TaskCheckPreviewSource sources;
    private final HostTaskCheckCatalog catalogs; private final ObjectMapper mapper;
    public TaskCheckDispatchGate(JdbcTemplate jdbc,TaskCheckPreviewSource sources,HostTaskCheckCatalog catalogs,ObjectMapper mapper){
        this.jdbc=jdbc;this.sources=sources;this.catalogs=catalogs;this.mapper=mapper;
    }
    public record Refusal(String diagnostic,String detail) { }
    @Transactional(propagation=Propagation.MANDATORY)
    public Refusal inspect(String rootId,String planId,List<TaskEntity> tasks){
        for(var task:tasks){
            var rows=jdbc.query("""
                SELECT id,project_id,root_run_id,turn_id,plan_id,task_id,task_version,observed_plan_revision,
                binding_digest,binding_json,snapshot_digest,snapshot_json,recorded_at
                FROM task_check_registrations WHERE root_run_id=? AND plan_id=? AND task_id=? AND task_version=?
                """,(rs,n)->new Row(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                    rs.getString(6),rs.getInt(7),rs.getInt(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getTimestamp(13)),
                rootId,planId,task.getTaskId(),task.getTaskVersion());
            if(rows.isEmpty())return refuse("MISSING_REGISTRATION",task);
            if(rows.size()!=1)return refuse("INVALID_EVIDENCE",task);
            var row=rows.getFirst();TaskCheckRegistrationBinding frozen;TaskCheckSelectionPreview selected;
            try{
                frozen=TaskCheckRegistrationBinding.decode(row.snapshot());
                if(!row.id().matches("checkreg_[a-f0-9]{32}")||row.time()==null
                        ||!frozen.projectId.equals(row.project())||!frozen.rootRunId.equals(row.root())||!frozen.turnId.equals(row.turn())
                        ||!frozen.planId.equals(row.plan())||!frozen.taskId.equals(row.task())||frozen.taskVersion!=row.version()
                        ||frozen.observedPlanRevision!=row.revision()||!frozen.bindingDigest.equals(row.bindingDigest())
                        ||!frozen.canonicalBinding.equals(row.binding())||!frozen.snapshotDigest.equals(row.snapshotDigest()))throw new IllegalArgumentException();
                selected=mapper.readValue(row.snapshot(),TaskCheckSelectionPreview.class);
                if(runtimeEnabled&&selected.execution()==null)return refuse("EXECUTION_NOT_AVAILABLE",task);
            }catch(Exception damaged){return refuse("INVALID_EVIDENCE",task);}
            try{
                var actual=sources.readJoined(rootId,planId,task.getTaskId());
                var catalog=catalogs.load(actual.projectId());
                var currentSelection=TaskCheckSelectionPreviewService.select(TaskCheckPreviewService.compose(actual,catalog),selected.checkRefs());
                var execution=selected.execution()==null?null:catalogs.loadExecution(actual.projectId());
                if(execution!=null)currentSelection=TaskCheckSelectionPreviewService.withExecution(currentSelection,execution);
                var current=TaskCheckRegistrationBinding.from(currentSelection);
                if(!frozen.canonicalBinding.equals(current.canonicalBinding))return refuse("STALE_BINDING",task);
                catalogs.requireCurrent(catalog);
                if(execution!=null)catalogs.requireCurrentExecution(execution);
            }catch(HostTaskCheckCatalog.Unavailable|ApiException|IllegalArgumentException unavailable){
                return refuse("STALE_BINDING",task);
            }
        }
        return runtimeEnabled?null:new Refusal("EXECUTION_NOT_AVAILABLE","强运行未启用；拒绝派发，不回退关键词或补丁兜底");
    }
    private static Refusal refuse(String diagnostic,TaskEntity task){return new Refusal(diagnostic,"任务 "+task.getTaskId()+" 验收绑定拒绝；请核对需求、任务版本和可信目录，重新预览确认");}
    private record Row(String id,String project,String root,String turn,String plan,String task,int version,int revision,
            String bindingDigest,String binding,String snapshotDigest,String snapshot,java.sql.Timestamp time) { }
}
