package com.dsh.orchestration.acceptance;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.TaskCheckPreview.*;
import com.dsh.application.VerificationEvidenceContext;
import com.dsh.storage.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;

/** Copy scalars in a fresh short transaction; no host catalog IO or writes here. */
@Service
public class TaskCheckPreviewSource {
    private final RunRepository runs; private final TurnRepository turns; private final ThreadRepository threads;
    private final PlanRepository plans; private final TaskRepository tasks; private final VerificationEvidenceContext origins;
    public TaskCheckPreviewSource(RunRepository runs, TurnRepository turns, ThreadRepository threads,
            PlanRepository plans, TaskRepository tasks, VerificationEvidenceContext origins) {
        this.runs=runs; this.turns=turns; this.threads=threads; this.plans=plans; this.tasks=tasks; this.origins=origins;
    }
    public record Snapshot(String projectId, String rootRunId, String turnId, String planId,
            int planRevision, String rootState, String taskRowId, String taskState,
            Definition task, List<Requirement> requirements) {
        public Snapshot { requirements=List.copyOf(requirements); }
    }
    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW)
    public Snapshot read(String runId, String planId, String taskId) {
        return readActual(runId,planId,taskId);
    }
    /** Confirmation caller owns source locks; never suspend its writable transaction. */
    @Transactional(propagation=Propagation.MANDATORY, noRollbackFor=ApiException.class)
    public Snapshot readJoined(String runId, String planId, String taskId) {
        return readActual(runId,planId,taskId);
    }
    private Snapshot readActual(String runId, String planId, String taskId) {
        var run=runs.findById(runId).orElseThrow(TaskCheckPreviewSource::missing);
        if (run.getParentRunId()!=null || !"MAIN".equals(run.getRole())) throw missing();
        var turn=turns.findById(run.getTurnId()).orElseThrow(TaskCheckPreviewSource::missing);
        if (!runId.equals(turn.getRootRunId())) throw missing();
        var thread=threads.findById(turn.getThreadId()).orElseThrow(TaskCheckPreviewSource::missing);
        var plan=plans.findByIdAndRootRunId(planId,runId).orElseThrow(TaskCheckPreviewSource::missing);
        if (!runId.equals(plan.getRootRunId()) || plan.getProjectId()==null || !plan.getProjectId().equals(thread.getProjectId())) throw missing();
        var task=tasks.findByPlanIdAndTaskId(planId,taskId).orElseThrow(TaskCheckPreviewSource::missing);
        if (!planId.equals(task.getPlanId()) || !taskId.equals(task.getTaskId())) throw missing();
        try {
            valid(plan.getRevision()>0 && task.getTaskVersion()>0 && taskId.matches("[A-Za-z0-9_-]{1,64}"));
            text(task.getGoal(),2000); text(task.getDeliverable(),500);
            valid(task.getExecutionMode()!=null && Set.of("INLINE","DELEGATED").contains(task.getExecutionMode()));
            valid(task.getState()!=null && Set.of("DRAFT","PENDING","READY","RUNNING","VERIFYING","WAITING_APPROVAL","BLOCKED","FAILED","CANCELLING","CANCELLED","SUCCEEDED").contains(task.getState()));
            var acceptance=strings(task.getAcceptance(),1,8,200);
            // Match the existing frozen acceptance limit in Java characters as well as strict JSON.
            valid(acceptance.stream().allMatch(s->s.length()<=200));
            var depends=strings(task.getDependsOn(),0,6,64);
            valid(new HashSet<>(depends).size()==depends.size() && depends.stream().allMatch(s->s.matches("[A-Za-z0-9_-]+")&&!s.equals(taskId)));
            JsonNode input=parse(task.getInputRefs()); valid(input.isArray()&&input.size()<=8);
            var refs=new ArrayList<InputRef>();
            for(var node:input){fields(node,"taskId","output");String dependency=string(node.get("taskId"),64);
                valid(depends.contains(dependency)); refs.add(new InputRef(dependency,string(node.get("output"),200)));}
            Scope scope=null;
            if(task.getResourceScope()!=null){var node=parse(task.getResourceScope());fields(node,"read","write");
                scope=new Scope(strings(node.get("read"),0,32,512),strings(node.get("write"),0,32,512));}
            var definition=new Definition(taskId,task.getTaskVersion(),task.getGoal(),acceptance,task.getDeliverable(),
                    depends,refs,scope,task.getExecutionMode(),task.isRequired());
            var requirements=new ArrayList<Requirement>();long chars=0;long previous=-1;var ids=new HashSet<String>();
            for(var message:origins.humanInputs(run)){
                valid(turn.getId().equals(message.getTurnId())&&thread.getId().equals(message.getThreadId())&&"user".equals(message.getRole()));
                text(message.getContent(),100000);valid(message.getSequence()>previous && ids.add(message.getId()));
                valid(message.getId()!=null && message.getId().matches("[A-Za-z0-9_-]{1,128}"));
                previous=message.getSequence(); chars+=message.getContent().length();
                if(requirements.size()>=32 || chars>262144)throw new ApiException(ErrorCode.INPUT_TOO_LARGE,"需求来源超过预览上限，请保留原文另行处理");
                requirements.add(new Requirement(message.getId(),Long.toString(message.getSequence()),message.getContent()));
            }
            valid(!requirements.isEmpty() && requirements.getFirst().content().equals(turn.getInput()));
            return new Snapshot(thread.getProjectId(),runId,turn.getId(),planId,plan.getRevision(),run.getState(),
                    task.getId(),task.getState(),definition,requirements);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,"任务或原始需求数据不完整，无法生成验收预览");
        }
    }
    private static JsonNode parse(String raw){valid(raw!=null);return CanonicalTaskJson.parse(raw.getBytes(StandardCharsets.UTF_8));}
    private static void fields(JsonNode node,String... allowed){valid(node!=null&&node.isObject());Set<String> keys=new HashSet<>();node.fieldNames().forEachRemaining(keys::add);valid(keys.equals(Set.of(allowed)));}
    private static List<String> strings(String raw,int min,int max,int length){return strings(parse(raw),min,max,length);}
    private static List<String> strings(JsonNode node,int min,int max,int length){valid(node!=null&&node.isArray()&&node.size()>=min&&node.size()<=max);var result=new ArrayList<String>();for(var value:node)result.add(string(value,length));return List.copyOf(result);}
    private static String string(JsonNode node,int max){valid(node!=null&&node.isTextual());String value=node.textValue();text(value,max);return value;}
    private static void text(String value,int max){valid(value!=null&&!value.isBlank()&&value.length()<=max);}
    private static void valid(boolean value){if(!value)throw new IllegalArgumentException("Invalid preview source");}
    private static ApiException missing(){return new ApiException(ErrorCode.RESOURCE_NOT_FOUND,"根运行、计划或任务不存在或归属不符");}
}
