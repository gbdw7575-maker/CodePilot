package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ApprovalRepository extends JpaRepository<ApprovalEntity, String> {

    @Modifying
    @Query("delete from ApprovalEntity a where a.runId = :runId")
    void deleteByRunId(@Param("runId") String runId);

    @Query("select a from ApprovalEntity a where a.turnId = :turnId order by a.createdAt asc, a.id asc")
    List<ApprovalEntity> listByTurn(@Param("turnId") String turnId);

    @Query("select a from ApprovalEntity a where a.runId = :runId and a.status = :status order by a.createdAt desc")
    List<ApprovalEntity> findByRunIdAndStatus(@Param("runId") String runId, @Param("status") String status);
}
