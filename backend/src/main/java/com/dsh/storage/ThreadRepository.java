package com.dsh.storage;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ThreadRepository extends JpaRepository<ThreadEntity, String> {

    @Query("select t from ThreadEntity t where t.projectId = :projectId order by t.createdAt asc, t.id asc")
    List<ThreadEntity> listByProject(@Param("projectId") String projectId);

    @Modifying
    @Query("delete from ThreadEntity t where t.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);

    /** 行锁串行化同会话的 Turn 提交与消息写入，保证活动轮次判断和消息序号分配的正确性。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ThreadEntity t where t.id = :id")
    Optional<ThreadEntity> findByIdForUpdate(@Param("id") String id);
}
