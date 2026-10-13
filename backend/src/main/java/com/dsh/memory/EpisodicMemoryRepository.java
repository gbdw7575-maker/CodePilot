package com.dsh.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * L5 情景记忆仓储（S2 新增）：按 projectId + 关键词召回，按 decayWeight desc 取 top-N。
 * <p>
 * 关键词召回采用 LIKE 拼接：对每个用户意图关键词构造 OR 条件，命中 minKeywords 个才记分。
 * minKeywords 与 top-N 由调用层（EpisodicMemoryService）控制，仓储仅返回候选集。
 */
public interface EpisodicMemoryRepository extends JpaRepository<EpisodicMemoryEntry, String> {

    /** 单关键词召回：keywords 列 LIKE %word%，按 decayWeight desc 取所有候选（top-N 由调用方截断）。 */
    @Query("select e from EpisodicMemoryEntry e where e.projectId = :projectId "
            + "and lower(e.keywords) like lower(concat('%', :kw, '%')) "
            + "order by e.decayWeight desc, e.createdAt desc")
    List<EpisodicMemoryEntry> findByProjectAndKeyword(@Param("projectId") String projectId,
                                                      @Param("kw") String keyword);

    /** 取项目下最近的 N 条情景（用于 ingest 阶段去重判断与 fallback 召回）。 */
    @Query("select e from EpisodicMemoryEntry e where e.projectId = :projectId "
            + "order by e.createdAt desc")
    List<EpisodicMemoryEntry> findRecentByProject(@Param("projectId") String projectId,
                                                  org.springframework.data.domain.Pageable pageable);

    /** ingest 幂等去重：同一 sourceRunId 只入库一次（终态钩子可能被多入口 finally 重复触发）。 */
    boolean existsBySourceRunId(String sourceRunId);
}
