package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskEventCursorRepository extends JpaRepository<TaskEventCursorEntity, String> {

    /**
     * 根内 seq 原子加一并返回新值（与流水插入同事务）。
     * 行不存在时调用方先 insert 0 再调用本方法。
     */
    @Modifying
    @Query("update TaskEventCursorEntity c set c.lastSeq = c.lastSeq + 1, "
            + "c.updatedAt = :now where c.rootRunId = :rootRunId")
    int increment(@Param("rootRunId") String rootRunId, @Param("now") java.time.Instant now);

    /** 增量后的新游标（标量查询，绕开批量更新后一级缓存的陈旧实体）。 */
    @Query("select c.lastSeq from TaskEventCursorEntity c where c.rootRunId = :rootRunId")
    Long currentSeq(@Param("rootRunId") String rootRunId);
}
