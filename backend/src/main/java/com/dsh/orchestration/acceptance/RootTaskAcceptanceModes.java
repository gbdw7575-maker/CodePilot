package com.dsh.orchestration.acceptance;

import com.dsh.api.ApiException;
import com.dsh.api.dto.*;
import com.dsh.storage.*;
import jakarta.persistence.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Set;

/** Source lock order shared by explicit human opt-in and dispatch. Policy survives config changes. */
@Service
public class RootTaskAcceptanceModes {
    @Value("${dsh.orchestration.strong-runtime-enabled:false}") private boolean strongRuntime;
    public static final String LEGACY="LEGACY_COVERAGE_ONLY", STRONG="EXECUTABLE_CHECKS";
    private final JdbcTemplate jdbc; private final RunRepository runs; private final TurnRepository turns;
    private final ThreadRepository threads; private final MessageRepository messages; private final boolean enabled;
    @PersistenceContext private EntityManager em;
    public RootTaskAcceptanceModes(JdbcTemplate jdbc,RunRepository runs,TurnRepository turns,
            ThreadRepository threads,MessageRepository messages,
            @Value("${dsh.orchestration.executable-checks-enabled:false}") boolean enabled){
        this.jdbc=jdbc;this.runs=runs;this.turns=turns;this.threads=threads;this.messages=messages;this.enabled=enabled;
    }
    public record RootSource(RunEntity root,TurnEntity turn,ThreadEntity thread) { }
    @Transactional(propagation=Propagation.MANDATORY)
    public RootSource lockSource(String rootId){
        var initial=runs.findById(rootId).orElseThrow(RootTaskAcceptanceModes::missing);
        var origin=turns.findById(initial.getTurnId()).orElseThrow(RootTaskAcceptanceModes::missing);
        var thread=threads.findByIdForUpdate(origin.getThreadId()).orElseThrow(RootTaskAcceptanceModes::missing);
        em.refresh(thread,LockModeType.PESSIMISTIC_WRITE);
        var root=runs.findByIdForUpdate(rootId).orElseThrow(RootTaskAcceptanceModes::missing);
        em.refresh(root,LockModeType.PESSIMISTIC_WRITE);
        var turn=turns.findById(root.getTurnId()).orElseThrow(RootTaskAcceptanceModes::missing);
        em.refresh(turn,LockModeType.PESSIMISTIC_WRITE);
        requireRoot(root,turn,thread);
        return new RootSource(root,turn,thread);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void refreshRequirements(String threadId){messages.listByThread(threadId).forEach(em::refresh);}
    @Transactional(readOnly=true)
    public TaskAcceptanceModeView view(String rootId){
        var root=runs.findById(rootId).orElseThrow(RootTaskAcceptanceModes::missing);
        var turn=turns.findById(root.getTurnId()).orElseThrow(RootTaskAcceptanceModes::missing);
        var thread=threads.findById(turn.getThreadId()).orElseThrow(RootTaskAcceptanceModes::missing);
        requireRoot(root,turn,thread);return read(rootId);
    }
    private TaskAcceptanceModeView read(String rootId){
        var rows=jdbc.query("SELECT mode,frozen_at FROM root_task_acceptance_modes WHERE root_run_id=?",
                (rs,n)->new TaskAcceptanceModeView(rootId,rs.getString(1),true,enabled,strongRuntime&&STRONG.equals(rs.getString(1)),rs.getTimestamp(2).toInstant()),rootId);
        if(rows.isEmpty())return new TaskAcceptanceModeView(rootId,"UNFROZEN",false,enabled,false,null);
        var mode=rows.getFirst();if(!Set.of(LEGACY,STRONG).contains(mode.mode()))throw new ApiException(ErrorCode.INVALID_STATE,"根任务验收模式损坏，拒绝执行");
        return mode;
    }
    public boolean isStrong(String rootId){return STRONG.equals(read(rootId).mode());}
    /** Used by old verification/query/claim: never infer the mode from current global config. */
    public boolean legacyAllowed(String planId){
        var roots=jdbc.queryForList("SELECT root_run_id FROM agent_plans WHERE id=?",String.class,planId);
        return roots.size()==1 && !isStrong(roots.getFirst());
    }
    @Transactional
    public TaskAcceptanceModeView enable(String rootId){
        var source=lockSource(rootId);var current=read(rootId);
        if(current.frozen()){
            if(STRONG.equals(current.mode()))return current;
            throw new ApiException(ErrorCode.INVALID_STATE,"已冻结旧模式，不能追溯升级或降级；请创建新的根任务");
        }
        if(!enabled)throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,"强验收用户选择入口未启用");
        if(!Set.of("RUNNING","WAITING_INPUT","WAITING_APPROVAL").contains(source.root().getState())
                ||Set.of("COMPLETED","FAILED","CANCELLED").contains(source.turn().getState()))
            throw new ApiException(ErrorCode.INVALID_STATE,"仅活动根任务可选择验收模式");
        // Root lock serializes dispatch, including its after-commit scheduling registration.
        int count=jdbc.queryForObject("""
            SELECT (SELECT count(*) FROM agent_task_dispatches WHERE root_run_id=?)
            + (SELECT count(*) FROM agent_task_attempts a JOIN agent_plans p ON p.id=a.plan_id WHERE p.root_run_id=?)
            + (SELECT count(*) FROM agent_tasks t JOIN agent_plans p ON p.id=t.plan_id WHERE p.root_run_id=? AND t.state<>'DRAFT')
            """,Integer.class,rootId,rootId,rootId);
        if(count!=0)throw new ApiException(ErrorCode.INVALID_STATE,"已有派发、尝试或非草案任务，不能升级验收模式");
        insert(rootId,STRONG);return read(rootId);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void freezeLegacy(String rootId){
        if(isStrong(rootId))throw new ApiException(ErrorCode.INVALID_STATE,"强任务禁止降级");
        insert(rootId,LEGACY);
    }
    private void insert(String rootId,String mode){
        jdbc.update("INSERT INTO root_task_acceptance_modes(root_run_id,mode,frozen_at) VALUES (?,?,?) ON CONFLICT(root_run_id) DO NOTHING",
                rootId,mode,Timestamp.from(Instant.now()));
        if(!mode.equals(read(rootId).mode()))throw new ApiException(ErrorCode.INVALID_STATE,"任务验收模式已经冻结，不能改写");
    }
    private static void requireRoot(RunEntity root,TurnEntity turn,ThreadEntity thread){
        if(root.getParentRunId()!=null||!"MAIN".equals(root.getRole())||!root.getId().equals(turn.getRootRunId())
                ||!thread.getId().equals(turn.getThreadId()))throw missing();
    }
    private static ApiException missing(){return new ApiException(ErrorCode.RESOURCE_NOT_FOUND,"真实根任务及所属轮次不存在");}
}
