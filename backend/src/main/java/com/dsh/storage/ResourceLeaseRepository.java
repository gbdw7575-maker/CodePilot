package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** 资源租约仓储（MA-04）。 */
public interface ResourceLeaseRepository extends JpaRepository<ResourceLeaseEntity, String> {

    Optional<ResourceLeaseEntity> findFirstByHolderRunIdAndStateOrderByAcquiredAtDesc(
            String holderRunId, String state);

    int countByRootRunIdAndState(String rootRunId, String state);

    /** 幂等释放：仅 HELD 行可被置 RELEASED；返回更新行数（0=已释放/不存在）。 */
    @Transactional
    @Modifying
    @Query("update ResourceLeaseEntity l set l.state = 'RELEASED', l.releasedAt = :now "
            + "where l.leaseId = :leaseId and l.state = 'HELD'")
    int releaseIfHeld(@Param("leaseId") String leaseId, @Param("now") Instant now);
}
