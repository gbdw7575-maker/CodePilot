package com.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 父工作区集成记录仓库（MA-07 §8.3）。
 *
 * <p>根 Run 的集成记录按时间倒序；中断恢复扫描 IN_PROGRESS 行决定重做或阻塞。</p>
 */
public interface IntegrationRecordRepository extends JpaRepository<IntegrationRecordEntity, String> {

    /** 根 Run 全部集成记录（最新在前）。 */
    List<IntegrationRecordEntity> findByRootRunIdOrderByCreatedAtDesc(String rootRunId);

    /** 根 Run 最新一条集成记录。 */
    Optional<IntegrationRecordEntity> findFirstByRootRunIdOrderByCreatedAtDesc(String rootRunId);

    /** 根 Run 指定状态的记录（中断恢复查 IN_PROGRESS）。 */
    List<IntegrationRecordEntity> findByRootRunIdAndStateOrderByCreatedAtDesc(
            String rootRunId, String state);
}
