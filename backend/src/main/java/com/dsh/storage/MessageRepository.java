package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<MessageEntity, String> {

    @Modifying
    @Query("delete from MessageEntity m where m.threadId = :threadId")
    void deleteByThreadId(@Param("threadId") String threadId);

    @Query("select m from MessageEntity m where m.threadId = :threadId order by m.sequence asc")
    List<MessageEntity> listByThread(@Param("threadId") String threadId);

    /** 公开消息流只暴露 user/assistant；工具调用与结果只在事件轨迹中体现。 */
    @Query("select m from MessageEntity m where m.threadId = :threadId "
            + "and m.role in ('user', 'assistant') order by m.sequence asc")
    List<MessageEntity> listPublicByThread(@Param("threadId") String threadId);

    Optional<MessageEntity> findFirstByThreadIdOrderBySequenceDesc(String threadId);
}
