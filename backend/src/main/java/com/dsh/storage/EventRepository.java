package com.dsh.storage;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EventRepository extends JpaRepository<EventEntity, String> {

    @Modifying
    @Query("delete from EventEntity e where e.threadId = :threadId")
    void deleteByThreadId(@Param("threadId") String threadId);

    /** after < sequence <= through，按提交顺序返回（间隙保留）。 */
    @Query("select e from EventEntity e where e.threadId = :threadId "
            + "and e.sequence > :after and e.sequence <= :through order by e.sequence asc")
    List<EventEntity> findRange(@Param("threadId") String threadId,
                                @Param("after") long after,
                                @Param("through") long through,
                                Pageable pageable);

    @Query("select min(e.sequence) from EventEntity e where e.threadId = :threadId")
    Long findEarliestSequence(@Param("threadId") String threadId);

    /** 按 traceId 查询整条链路的事件（跨父子 Run），按提交顺序返回。 */
    @Query("select e from EventEntity e where e.traceId = :traceId order by e.sequence asc")
    List<EventEntity> findByTraceId(@Param("traceId") String traceId);

    /**
     * S1 L1 账本增强：按 runId 查询单个 Run 内的事件，按 sequence 升序返回，支持任务级回放。
     * runId 在 DSH 中由父/子 Run 各自独立分配，同一 runId 的事件天然属于同一任务。
     */
    @Query("select e from EventEntity e where e.runId = :runId order by e.sequence asc")
    List<EventEntity> findByRunId(@Param("runId") String runId);

    /**
     * S1 L1 账本增强：按 projectId 查询最近 N 条事件，按 sequence desc 取 top-N，
     * 作为 L5 情景记忆召回的数据源。limit 由调用方控制。
     */
    @Query("select e from EventEntity e where e.projectId = :projectId order by e.sequence desc")
    List<EventEntity> findByProjectId(@Param("projectId") String projectId, Pageable pageable);
}
