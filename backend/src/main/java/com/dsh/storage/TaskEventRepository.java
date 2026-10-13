package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskEventRepository extends JpaRepository<TaskEventEntity, String> {

    /** 游标增量：root 内 seq 大于给定值的全部流水（恢复/对账用）。 */
    List<TaskEventEntity> findByRootRunIdAndSeqGreaterThanOrderBySeqAsc(String rootRunId, long seq);

    /** await 拉取：只取本次等待任务集合、seq 大于游标的流水。 */
    List<TaskEventEntity> findByRootRunIdAndTaskIdInAndSeqGreaterThanOrderBySeqAsc(
            String rootRunId, Collection<String> taskIds, long seq);

    /** root 内最新流水（决定初始/全局游标）。 */
    Optional<TaskEventEntity> findTopByRootRunIdOrderBySeqDesc(String rootRunId);
}
