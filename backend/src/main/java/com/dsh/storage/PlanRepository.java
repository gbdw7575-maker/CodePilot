package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface PlanRepository extends JpaRepository<PlanEntity, String> {

    List<PlanEntity> findByRootRunIdOrderByCreatedAtDesc(String rootRunId);

    /** 归属复核：计划必须属于该根 Run（模型传入的 planId 不能跨 Run 引用）。 */
    Optional<PlanEntity> findByIdAndRootRunId(String id, String rootRunId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PlanEntity p where p.id = :id and p.rootRunId = :rootRunId")
    Optional<PlanEntity> findForUpdate(@Param("id") String id, @Param("rootRunId") String rootRunId);
}
