package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskWaitRepository extends JpaRepository<TaskWaitEntity, String> {

    /** 停车后的主循环线程据此找到自己的等待行（同根同时至多一行 WAITING）。 */
    Optional<TaskWaitEntity> findFirstByRootRunIdAndStateOrderByCreatedAtDesc(
            String rootRunId, String state);

    List<TaskWaitEntity> findByRootRunIdAndState(String rootRunId, String state);

    Optional<TaskWaitEntity> findByToolCallId(String toolCallId);

    /** 启动补偿扫描。 */
    List<TaskWaitEntity> findByState(String state);

    /**
     * 等待归属 CAS：只有 WAITING 行能被唯一完成者翻成 DONE/TIMED_OUT，
     * 并发完成信号（多个子任务同时终态）只允许一个赢家回填 tool result。
     */
    @Modifying
    @Query("update TaskWaitEntity w set w.state = :toState, w.completedAt = :now "
            + "where w.id = :id and w.state = 'WAITING'")
    int casComplete(@Param("id") String id, @Param("toState") String toState,
                    @Param("now") Instant now);
}
