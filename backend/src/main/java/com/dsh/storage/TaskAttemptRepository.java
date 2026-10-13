package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface TaskAttemptRepository extends JpaRepository<TaskAttemptEntity, String> {

    List<TaskAttemptEntity> findByPlanIdAndTaskIdOrderByAttemptNoDesc(String planId, String taskId);

    /** Includes removed tasks when initializing the generation upper bound on legacy plans. */
    @Query("select coalesce(max(a.taskVersion), 0) from TaskAttemptEntity a where a.planId = :planId")
    int maximumTaskVersion(@Param("planId") String planId);

    // ---------- MA-05 调度领取 ----------

    /** 待执行记录：QUEUED（含被满额/预约暂缺重新排队的尝试），按登记顺序领取。 */
    List<TaskAttemptEntity> findByStateOrderByCreatedAtAsc(String state);

    /** 同一 taskVersion 是否已有有效尝试（READY 条件 5：不能并发两个执行者）。 */
    long countByPlanIdAndTaskIdAndTaskVersionAndStateIn(
            String planId, String taskId, int taskVersion, List<String> activeStates);

    /**
     * 领取 CAS：仅 QUEUED 能转 RUNNING。返回更新行数；0 表示已被其他执行者领取
     * 或已被取消（MA-08 将在此叠加 fencingToken 校验）。
     */
    @Modifying
    @Query("update TaskAttemptEntity a set a.state = 'RUNNING', a.leaseOwner = :leaseOwner, "
            + "a.leaseUntil = :leaseUntil, a.fencingToken = :fencingToken, a.updatedAt = :now "
            + "where a.attemptId = :attemptId and a.state = 'QUEUED'")
    int claim(@Param("attemptId") String attemptId, @Param("leaseOwner") String leaseOwner,
              @Param("leaseUntil") Instant leaseUntil, @Param("fencingToken") Long fencingToken,
              @Param("now") Instant now);

    /** 取消尚未领取的尝试（QUEUED→CANCELLED）；已领取返回 0，走 CANCELLING 通知路径。 */
    @Modifying
    @Query("update TaskAttemptEntity a set a.state = 'CANCELLED', a.updatedAt = :now "
            + "where a.attemptId = :attemptId and a.state = 'QUEUED'")
    int cancelQueued(@Param("attemptId") String attemptId, @Param("now") Instant now);

    /** 根 Run（经其全部 planId）内 fencingToken 单调最大值；无尝试时返回 0。 */
    @Query("select coalesce(max(a.fencingToken), 0) from TaskAttemptEntity a "
            + "where a.planId in :planIds")
    long maxFencingToken(@Param("planIds") java.util.Collection<String> planIds);

    /** A later claim replaces an earlier writer only in the same logical task, not another task in its root. */
    @Query("select coalesce(max(a.fencingToken), 0) from TaskAttemptEntity a "
            + "where a.planId = :planId and a.taskId = :taskId")
    long maxTaskFencingToken(@Param("planId") String planId, @Param("taskId") String taskId);

    // ---------- MA-08 租约过期回收（AT-15 窗口2） ----------

    /**
     * 租约过期且仍在 RUNNING 的 attempt（LeaseReaper 回收目标）。
     * leaseUntil 非空且早于 now；未过期者不返回（避免双执行者）。
     */
    @Query("select a from TaskAttemptEntity a where a.state = 'RUNNING' "
            + "and a.leaseUntil is not null and a.leaseUntil < :now "
            + "order by a.leaseUntil asc")
    List<TaskAttemptEntity> findExpiredRunning(@Param("now") Instant now);
}
