package com.dsh.storage;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EventCursorRepository extends JpaRepository<EventCursorEntity, String> {

    @Modifying
    @Query("delete from EventCursorEntity c where c.threadId = :threadId")
    void deleteByThreadId(@Param("threadId") String threadId);

    /** 行锁串行化同事务内的序号分配。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from EventCursorEntity c where c.threadId = :threadId")
    Optional<EventCursorEntity> findByIdForUpdate(@Param("threadId") String threadId);
}
