package com.dsh.orchestration.task;

import com.dsh.common.IdGen;
import com.dsh.orchestration.event.OrchestrationEventBridge;
import com.dsh.storage.TaskEventCursorEntity;
import com.dsh.storage.TaskEventCursorRepository;
import com.dsh.storage.TaskEventEntity;
import com.dsh.storage.TaskEventRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 任务状态变更流水（MA-05，规范 §6.3/§9）。
 *
 * <p>append 必须与任务/attempt 状态推进在同一短事务：先对根游标行做条件加一
 * （并发推进在该行串行化），再以返回 seq 落流水；事务回滚则 seq 与状态一同回滚，
 * 不会出现“有状态无流水/有流水无状态”。await_tasks 的 afterCursor 是该 seq 的
 * 十进制字符串；游标只增不减。</p>
 */
@Service
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class TaskEventJournal {

    private final TaskEventCursorRepository cursorRepository;
    private final TaskEventRepository eventRepository;
    private final ObjectProvider<OrchestrationEventBridge> bridge;

    public TaskEventJournal(TaskEventCursorRepository cursorRepository,
                            TaskEventRepository eventRepository,
                            ObjectProvider<OrchestrationEventBridge> bridge) {
        this.cursorRepository = cursorRepository;
        this.eventRepository = eventRepository;
        this.bridge = bridge;
    }

    /** 追加一条状态变更，返回根内单调新游标。调用方必须已持有写事务。 */
    @Transactional
    public long append(String rootRunId, String planId, String taskId, int taskVersion,
                       String attemptId, String childRunId, String from, String to,
                       String reasonCode) {
        Instant now = Instant.now();
        // MA-10：桥接统一事件必须在任务游标推进之前。两处使用不同的游标行：统一事件流锁
        // thread 的 event_cursor（B），任务流水锁 rootRun 的 task_event_cursor（A）。
        // 根循环事务可能在派发前已持有 B（如同事务先写 tool.started），worker 完成事务持 A；
        // 统一锁序为 B→A，避免“循环持 B 等 A / worker 持 A 等 B”的交叉死锁。
        OrchestrationEventBridge b = bridge.getIfAvailable();
        if (b != null) {
            if ("DISPATCH_REQUESTED".equals(reasonCode)) {
                // dispatch 登记单独发 task.dispatched，其余迁移发 task.state.changed。
                b.taskDispatched(rootRunId, planId, taskId, taskVersion, attemptId, to);
            } else {
                b.taskStateChanged(rootRunId, planId, taskId, taskVersion, attemptId,
                        childRunId, from, to, reasonCode);
            }
        }
        if (cursorRepository.findById(rootRunId).isEmpty()) {
            cursorRepository.saveAndFlush(new TaskEventCursorEntity(rootRunId, 0L, now));
        }
        cursorRepository.increment(rootRunId, now);
        cursorRepository.flush();
        Long seqValue = cursorRepository.currentSeq(rootRunId);
        if (seqValue == null) {
            throw new IllegalStateException("任务事件游标行缺失: " + rootRunId);
        }
        long seq = seqValue;
        eventRepository.save(new TaskEventEntity(IdGen.next("tev_"), rootRunId, seq, planId,
                taskId, taskVersion, attemptId, childRunId, to, reasonCode, now));
        return seq;
    }

    /** await 增量拉取：只取等待集合内、seq 严格大于游标的流水。 */
    @Transactional(readOnly = true)
    public List<TaskEventEntity> eventsAfter(String rootRunId, List<String> taskIds, long afterSeq) {
        return eventRepository
                .findByRootRunIdAndTaskIdInAndSeqGreaterThanOrderBySeqAsc(rootRunId, taskIds, afterSeq);
    }

    @Transactional(readOnly = true)
    public long latestSeq(String rootRunId) {
        return eventRepository.findTopByRootRunIdOrderBySeqDesc(rootRunId)
                .map(TaskEventEntity::getSeq).orElse(0L);
    }
}
