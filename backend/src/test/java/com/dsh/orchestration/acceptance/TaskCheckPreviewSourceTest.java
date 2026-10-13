package com.dsh.orchestration.acceptance;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.application.VerificationEvidenceContext;
import com.dsh.storage.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskCheckPreviewSourceTest {
    private RunRepository runs;private TurnRepository turns;private ThreadRepository threads;
    private PlanRepository plans;private TaskRepository tasks;private VerificationEvidenceContext origins;
    private TaskCheckPreviewSource source;private RunEntity run;private TurnEntity turn;
    private PlanEntity plan;private TaskEntity task;private MessageEntity input;
    private final Instant now=Instant.EPOCH;
    @BeforeEach void setup(){
        runs=mock(RunRepository.class);turns=mock(TurnRepository.class);threads=mock(ThreadRepository.class);
        plans=mock(PlanRepository.class);tasks=mock(TaskRepository.class);origins=mock(VerificationEvidenceContext.class);
        run=new RunEntity("run_one","turn_one",null,"MAIN","RUNNING","PENDING",1,"ws_one","digest",24,80,200000,now,now,now);
        turn=new TurnEntity("turn_one","thread_one","run_one","  原文\n要求  ","RUNNING","PENDING","thread_one",now,now);
        plan=new PlanEntity("plan_one","run_one","project_one",1,"DRAFT","计划",now,now);
        task=task("[\"断言\"]","[]","[]",null,"修复");
        input=message("msg_one","thread_one","turn_one","user",turn.getInput(),9007199254740993L);
        when(runs.findById("run_one")).thenReturn(Optional.of(run));when(turns.findById("turn_one")).thenReturn(Optional.of(turn));
        when(threads.findById("thread_one")).thenReturn(Optional.of(new ThreadEntity("thread_one","project_one","会话","model_one",now,now)));
        when(plans.findByIdAndRootRunId("plan_one","run_one")).thenReturn(Optional.of(plan));
        when(tasks.findByPlanIdAndTaskId("plan_one","A")).thenAnswer(i->Optional.of(task));
        when(origins.humanInputs(run)).thenReturn(List.of(input));
        source=new TaskCheckPreviewSource(runs,turns,threads,plans,tasks,origins);
    }
    private TaskEntity task(String acceptance,String deps,String refs,String scope,String goal){return new TaskEntity("row_one","plan_one","A",1,goal,deps,refs,"交付",acceptance,scope,"DELEGATED",true,"DRAFT",now,now);}
    private MessageEntity message(String id,String thread,String turn,String role,String content,long sequence){return new MessageEntity(id,thread,turn,role,content,sequence,now);}
    private TaskCheckPreviewSource.Snapshot read(){return source.read("run_one","plan_one","A");}
    private void rejected(ErrorCode code){assertEquals(code,assertThrows(ApiException.class,this::read).code());}
    @Test void exactSourceFullDefinitionAndImmutableValues(){
        var snapshot=read();assertEquals(turn.getInput(),snapshot.requirements().getFirst().content());
        assertEquals("9007199254740993",snapshot.requirements().getFirst().sequence());
        assertEquals("修复",snapshot.task().goal());assertEquals(List.of("断言"),snapshot.task().acceptance());
        assertThrows(UnsupportedOperationException.class,()->snapshot.task().acceptance().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.requirements().clear());
        task.setState("RUNNING");plan.setRevision(2);assertEquals("DRAFT",snapshot.taskState());assertEquals(1,snapshot.planRevision());
        verify(origins).humanInputs(run);verifyNoMoreInteractions(origins);verify(tasks,never()).save(any());
    }
    @Test void dependencyAndScopePreserveDeclaredOrder(){
        task=task("[\"断言一\",\"断言二\"]","[\"B\",\"C\"]","[{\"taskId\":\"B\",\"output\":\"deliverable\"}]","{\"read\":[\"b\",\"a\"],\"write\":[\"x\"]}"," 原目标 ");
        var snapshot=read();assertEquals(List.of("B","C"),snapshot.task().dependsOn());assertEquals(List.of("b","a"),snapshot.task().resourceScope().read());
        assertEquals(" 原目标 ",snapshot.task().goal());assertEquals("deliverable",snapshot.task().inputRefs().getFirst().output());
    }
    @ParameterizedTest @ValueSource(strings={"", "[]","null","[3]","[\" \"]","[\"a\"][]","{\"acceptance\":[\"a\"]}"})
    void damagedAcceptanceIsNotRenderedAsMissingOrEmpty(String raw){task=task(raw,"[]","[]",null,"修复");rejected(ErrorCode.VALIDATION_FAILED);}
    @ParameterizedTest @ValueSource(strings={"[\"B\",\"B\"]","[\"A\"]","[3]","[\"B\"]{}","{\"deps\":[]}"})
    void damagedDependenciesAreRejected(String raw){task=task("[\"断言\"]",raw,"[]",null,"修复");rejected(ErrorCode.VALIDATION_FAILED);}
    @ParameterizedTest @ValueSource(strings={"[{\"taskId\":\"B\",\"output\":\"x\"}]","[{\"taskId\":\"B\",\"output\":3}]","[{}]","[] true"})
    void unknownInputDependenciesOrWrongTypesAreRejected(String raw){task=task("[\"断言\"]","[]",raw,null,"修复");rejected(ErrorCode.VALIDATION_FAILED);}
    @ParameterizedTest @ValueSource(strings={"{}","{\"read\":[],\"write\":[],\"trusted\":true}","{\"read\":[],\"read\":[\"x\"],\"write\":[]}","{\"read\":[3],\"write\":[]}"})
    void unknownDuplicateOrCoercedScopeFieldsAreRejected(String raw){task=task("[\"断言\"]","[]","[]",raw,"修复");rejected(ErrorCode.VALIDATION_FAILED);}
    @Test void callerCannotSelectOtherRootsOrMissingPlans(){when(plans.findByIdAndRootRunId("plan_one","run_one")).thenReturn(Optional.empty());rejected(ErrorCode.RESOURCE_NOT_FOUND);}
    @Test void callerCannotUseAChildAsRoot(){run=new RunEntity("run_one","turn_one","parent","CODER","RUNNING","PENDING",1,"ws","digest",24,80,200000,now,now,now);when(runs.findById("run_one")).thenReturn(Optional.of(run));rejected(ErrorCode.RESOURCE_NOT_FOUND);}
    @Test void planProjectMustMatchRunResolvedProject(){when(plans.findByIdAndRootRunId("plan_one","run_one")).thenReturn(Optional.of(new PlanEntity("plan_one","run_one","project_other",1,"DRAFT",null,now,now)));rejected(ErrorCode.RESOURCE_NOT_FOUND);}
    @Test void turnMustNameTheActualRoot(){when(turns.findById("turn_one")).thenReturn(Optional.of(new TurnEntity("turn_one","thread_one","other_root",turn.getInput(),"RUNNING","PENDING",null,now,now)));rejected(ErrorCode.RESOURCE_NOT_FOUND);}
    @Test void initialOriginalCannotBeReplacedByAnAgentSummary(){when(origins.humanInputs(run)).thenReturn(List.of(message("msg_one","thread_one","turn_one","user","摘要",1)));rejected(ErrorCode.VALIDATION_FAILED);}
    @Test void missingRequirementsDoNotBecomeAnEmptyDigest(){when(origins.humanInputs(run)).thenReturn(List.of());rejected(ErrorCode.VALIDATION_FAILED);}
    @Test void wrongThreadTurnRoleDuplicateOrReorderedSourcesAreRejected(){
        for(var invalid:List.of(message("other","wrong","turn_one","user","补充",9007199254740994L),message("other","thread_one","wrong","user","补充",9007199254740994L),message("other","thread_one","turn_one","assistant","摘要",9007199254740994L),message("msg_one","thread_one","turn_one","user","补充",9007199254740994L),message("other","thread_one","turn_one","user","补充",1))){when(origins.humanInputs(run)).thenReturn(List.of(input,invalid));rejected(ErrorCode.VALIDATION_FAILED);}
    }
    @Test void legitimateHumanPrefixIsPreservedByTrustedOriginResolver(){
        when(origins.humanInputs(run)).thenReturn(List.of(input,message("msg_two","thread_one","turn_one","user","[verification-fix:这是用户要求]",9007199254740994L)));
        assertEquals(2,read().requirements().size());verify(origins).humanInputs(run);
    }
    @Test void overlongOrTooManySourcesAreNotTruncated(){
        var many=new ArrayList<MessageEntity>();many.add(input);for(int i=1;i<=32;i++)many.add(message("msg_"+i,"thread_one","turn_one","user","补充",9007199254740993L+i));
        when(origins.humanInputs(run)).thenReturn(many);rejected(ErrorCode.INPUT_TOO_LARGE);
        when(origins.humanInputs(run)).thenReturn(List.of(input,message("m2","thread_one","turn_one","user","a".repeat(100000),9007199254740994L),message("m3","thread_one","turn_one","user","b".repeat(100000),9007199254740995L),message("m4","thread_one","turn_one","user","c".repeat(100000),9007199254740996L)));rejected(ErrorCode.INPUT_TOO_LARGE);
    }
}
