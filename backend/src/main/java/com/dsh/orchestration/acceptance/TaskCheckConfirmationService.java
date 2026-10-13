package com.dsh.orchestration.acceptance;

import com.dsh.api.ApiException;
import com.dsh.api.dto.*;
import com.dsh.storage.*;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Set;

/** Local HTTP coordinator only: one writable transaction, source locks and exact human selection. */
@Service
public class TaskCheckConfirmationService {
    private final TaskCheckPreviewSource sources; private final HostTaskCheckCatalog catalogs;
    private final TaskCheckRegistrationStore store; private final RunRepository runs; private final TurnRepository turns;
    private final ThreadRepository threads; private final PlanRepository plans; private final TaskRepository tasks;
    private final TaskAttemptRepository attempts; private final MessageRepository messages; private final ProjectRepository projects;
    @PersistenceContext private EntityManager em;
    public TaskCheckConfirmationService(TaskCheckPreviewSource sources,HostTaskCheckCatalog catalogs,
            TaskCheckRegistrationStore store,RunRepository runs,TurnRepository turns,ThreadRepository threads,
            PlanRepository plans,TaskRepository tasks,TaskAttemptRepository attempts,MessageRepository messages,ProjectRepository projects){
        this.sources=sources;this.catalogs=catalogs;this.store=store;this.runs=runs;this.turns=turns;this.threads=threads;
        this.plans=plans;this.tasks=tasks;this.attempts=attempts;this.messages=messages;this.projects=projects;
    }
    @Transactional
    public TaskCheckRegistration confirm(String runId,String planId,String taskId,String ifMatch,ConfirmTaskChecks request){
        if(ifMatch==null||ifMatch.isBlank())throw new ApiException(ErrorCode.PRECONDITION_REQUIRED,"请先读取选定检查预览并提供If-Match");
        if(!ifMatch.matches("\"task-check-selection-v[12]-[a-f0-9]{64}\""))throw invalid("If-Match必须是实际选定检查预览ETag");
        boolean complete=ifMatch.startsWith("\"task-check-selection-v2-");
        if(request==null||!Boolean.TRUE.equals(request.confirmed())||request.taskVersion()==null||request.taskVersion()<1)throw invalid("必须明确确认并提供正整数任务版本");
        TaskCheckSelectionPreviewService.validateRefs(request.checkRefs());
        var initial=runs.findById(runId).orElseThrow(TaskCheckConfirmationService::missing);
        var initialTurn=turns.findById(initial.getTurnId()).orElseThrow(TaskCheckConfirmationService::missing);
        var thread=threads.findByIdForUpdate(initialTurn.getThreadId()).orElseThrow(TaskCheckConfirmationService::missing);
        em.refresh(thread,LockModeType.PESSIMISTIC_WRITE);
        var root=runs.findByIdForUpdate(runId).orElseThrow(TaskCheckConfirmationService::missing);
        em.refresh(root,LockModeType.PESSIMISTIC_WRITE);
        var turn=turns.findById(root.getTurnId()).orElseThrow(TaskCheckConfirmationService::missing);
        em.refresh(turn,LockModeType.PESSIMISTIC_WRITE);
        if(!thread.getId().equals(turn.getThreadId())||!runId.equals(turn.getRootRunId())||root.getParentRunId()!=null||!"MAIN".equals(root.getRole()))throw missing();
        var plan=plans.findForUpdate(planId,runId).orElseThrow(TaskCheckConfirmationService::missing);
        em.refresh(plan,LockModeType.PESSIMISTIC_WRITE);
        var rows=tasks.findForPlanUpdate(planId);rows.forEach(row->em.refresh(row,LockModeType.PESSIMISTIC_WRITE));
        var task=rows.stream().filter(row->taskId.equals(row.getTaskId())).findFirst().orElseThrow(TaskCheckConfirmationService::missing);
        if(thread.getProjectId()==null)throw missing();
        var project=projects.findById(thread.getProjectId()).orElseThrow(TaskCheckConfirmationService::missing);
        em.refresh(project,LockModeType.PESSIMISTIC_WRITE);
        // Thread lock serializes actual TurnService.appendInput; refresh any caller-cached source scalars.
        messages.listByThread(thread.getId()).forEach(em::refresh);
        if(!Set.of("RUNNING","WAITING_INPUT","WAITING_APPROVAL").contains(root.getState())
                ||Set.of("COMPLETED","FAILED","CANCELLED").contains(turn.getState())
                ||!"DRAFT".equals(task.getState())||!attempts.findByPlanIdAndTaskIdOrderByAttemptNoDesc(planId,taskId).isEmpty())
            throw new ApiException(ErrorCode.INVALID_STATE,"仅活动根Run中尚未派发的DRAFT任务可确认");
        if(task.getTaskVersion()!=request.taskVersion())throw stale();
        var before=sources.readJoined(runId,planId,taskId);
        try {
            var catalog=catalogs.load(before.projectId());
            var selected=TaskCheckSelectionPreviewService.select(TaskCheckPreviewService.compose(before,catalog),request.checkRefs());
            var execution=complete?catalogs.loadExecution(before.projectId()):null;
            if(complete)selected=TaskCheckSelectionPreviewService.withExecution(selected,execution);
            if(!ifMatch.equals("\"task-check-selection-v"+(complete?"2":"1")+"-"+selected.selectionDigest()+"\""))throw stale();
            if(!before.equals(sources.readJoined(runId,planId,taskId)))throw stale();
            catalogs.requireCurrent(catalog);
            if(complete)catalogs.requireCurrentExecution(execution);
            var binding=TaskCheckRegistrationBinding.from(selected);
            var stored=store.append(binding);
            // Last file observation before commit. DB locks cannot freeze arbitrary external Host writes.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void beforeCommit(boolean readOnly){try{catalogs.requireCurrent(catalog);if(complete)catalogs.requireCurrentExecution(execution);}catch(HostTaskCheckCatalog.Unavailable changed){throw TaskCheckPreviewService.unavailable(changed);}}
            });
            var original=TaskCheckRegistrationBinding.decode(stored.snapshotJson());
            var frozen=new com.fasterxml.jackson.databind.ObjectMapper().readTree(original.canonicalSnapshot);
            var originalSelection=new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(frozen,TaskCheckSelectionPreview.class);
            return new TaskCheckRegistration("REGISTERED",stored.id(),binding.projectId,binding.rootRunId,binding.turnId,
                    binding.planId,binding.taskId,binding.taskVersion,originalSelection.checkRefs(),originalSelection.checksDigest(),
                    stored.bindingDigest(),stored.snapshotDigest(),stored.observedPlanRevision(),stored.recordedAt(),
                    originalSelection.execution()==null?null:originalSelection.execution().executionSpecDigest());
        }catch(HostTaskCheckCatalog.Unavailable unavailable){throw TaskCheckPreviewService.unavailable(unavailable);}
        catch(IllegalArgumentException invalid){throw stale();}
        catch(TaskCheckRegistrationStore.BindingConflict conflict){throw new ApiException(ErrorCode.WORKSPACE_CONFLICT,"本任务版本已有不同的不可变验收登记，需要新任务版本");}
        catch(com.fasterxml.jackson.core.JsonProcessingException damaged){throw new IllegalStateException("已登记快照无法解码",damaged);}
    }
    private static ApiException stale(){return new ApiException(ErrorCode.VERSION_CONFLICT,"需求、任务或目录已变化，请重新预览并确认");}
    private static ApiException invalid(String message){return new ApiException(ErrorCode.VALIDATION_FAILED,message);}
    private static ApiException missing(){return new ApiException(ErrorCode.RESOURCE_NOT_FOUND,"根运行、计划或任务不存在或归属不符");}
}
