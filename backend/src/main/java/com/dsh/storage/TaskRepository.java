package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {

    List<TaskEntity> findByPlanIdOrderByTaskIdAsc(String planId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TaskEntity t where t.planId = :planId order by t.taskId asc")
    List<TaskEntity> findForPlanUpdate(@Param("planId") String planId);

    /** MA-05 调度扫描：一个根 Run 的全部计划任务（计划数预期很少）。 */
    List<TaskEntity> findByPlanIdInOrderByPlanIdAscTaskIdAsc(Collection<String> planIds);

    /** MA-05 周期补偿：按状态扫描全部任务（PENDING 兜底推进）。 */
    List<TaskEntity> findByStateIn(Collection<String> states);

    Optional<TaskEntity> findByPlanIdAndTaskId(String planId, String taskId);

    /**
     * 计划修订前的活跃任务检查：命中即意味着存在不可静默修改的任务
     * （READY/RUNNING/VERIFYING/WAITING_APPROVAL/CANCELLING/SUCCEEDED）。
     */
    long countByPlanIdAndStateIn(String planId, List<String> states);

    void deleteByPlanId(String planId);
}
