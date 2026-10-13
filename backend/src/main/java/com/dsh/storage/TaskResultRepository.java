package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TaskResultRepository extends JpaRepository<TaskResultEntity, String> {

    /** 任务最新一次 attempt 的终态结果（attemptNo 越大越新；同创建时间取 attemptId 兜底顺序）。 */
    Optional<TaskResultEntity>
            findTopByPlanIdAndTaskIdOrderByCreatedAtDesc(String planId, String taskId);
}
