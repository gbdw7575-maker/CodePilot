package com.dsh.storage;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RunRepository extends JpaRepository<RunEntity, String> {

    /** 行锁读取，串行化取消/恢复与预算预约的竞争。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RunEntity r where r.id = :id")
    Optional<RunEntity> findByIdForUpdate(@Param("id") String id);

    /** 列出根 Run 的直属子运行。 */
    @Query("select r from RunEntity r where r.parentRunId = :parentId order by r.createdAt asc, r.id asc")
    List<RunEntity> listChildren(@Param("parentId") String parentId);

    /**
     * MA-03：按 workspaceRef 反查属主 Run（种子工作区授权）。workspaceRef 由 IdGen 生成、
     * 每次 prepare 唯一；保留 Optional 以对“模型伪造不存在的 ref” fail-closed。
     */
    Optional<RunEntity> findFirstByWorkspaceRefOrderByCreatedAtDesc(String workspaceRef);

    /** 列出一批 Turn 下的全部 Run（删除会话时枚举运行及其工作区）。 */
    List<RunEntity> findByTurnIdIn(java.util.Collection<String> turnIds);

    /** D-14：按状态枚举 Run（启动恢复扫描进程所有的活动态孤儿）。 */
    List<RunEntity> findByStateIn(java.util.Collection<String> states);

    /**
     * 原子预约模型请求预算：仅当 used + :n <= max 时累加并返回 1，否则返回 0。
     * 不使用 @Version 重试，避免并发下的乐观锁噪声失败。
     */
    @Modifying
    @Query("update RunEntity r set r.budgetModelRequestsUsed = r.budgetModelRequestsUsed + :n, "
            + "r.updatedAt = :now where r.id = :id "
            + "and r.budgetModelRequestsUsed + :n <= r.budgetMaxModelRequests")
    int reserveModelRequests(@Param("id") String id, @Param("n") int n, @Param("now") java.time.Instant now);

    /** B03: 请求计数与输入/输出 Token 同一条件 UPDATE；并发派发不能复用余额。 */
    @Modifying
    @Query("update RunEntity r set r.budgetModelRequestsUsed = r.budgetModelRequestsUsed + 1, "
            + "r.budgetReservedTokens = r.budgetReservedTokens + :tokens, r.updatedAt = :now "
            + "where r.id = :id and r.state = 'RUNNING' and r.budgetDeadlineAt >= :now "
            + "and r.budgetModelRequestsUsed < r.budgetMaxModelRequests "
            + "and cast(coalesce(r.budgetSettledTokens, 0) as long) + r.budgetReservedTokens + :tokens "
            + "<= r.budgetMaxCumulativeTokens")
    int reserveModelCall(@Param("id") String id, @Param("tokens") int tokens,
                         @Param("now") java.time.Instant now);

    /** 原子预约工具调用预算。 */
    @Modifying
    @Query("update RunEntity r set r.budgetToolCallsUsed = r.budgetToolCallsUsed + :n, "
            + "r.updatedAt = :now where r.id = :id "
            + "and r.budgetToolCallsUsed + :n <= r.budgetMaxToolCalls")
    int reserveToolCalls(@Param("id") String id, @Param("n") int n, @Param("now") java.time.Instant now);

    /** 释放已结算前预占的保留 Token（settle 后 reserved 归零）。 */
    @Modifying
    @Query("update RunEntity r set r.budgetReservedTokens = 0, r.updatedAt = :now where r.id = :id")
    int releaseReservedTokens(@Param("id") String id, @Param("now") java.time.Instant now);

    /**
     * DSH-010：父 Run 为子 Run 原子预约预算切片（模型请求 + 工具调用 + token 一次条件更新）。
     * 三类额度任一不足则整笔失败返回 0，兄弟子 Run 不能同时花同一份余额。
     * 模型/工具计数沿用现有“计数即占用”语义；token 计入 reservedTokens（settledTokens 之外）。
     */
    @Modifying
    @Query("update RunEntity r set r.budgetModelRequestsUsed = r.budgetModelRequestsUsed + :mr, "
            + "r.budgetToolCallsUsed = r.budgetToolCallsUsed + :tc, "
            + "r.budgetReservedTokens = r.budgetReservedTokens + :tok, r.updatedAt = :now "
            + "where r.id = :id "
            + "and r.budgetModelRequestsUsed + :mr <= r.budgetMaxModelRequests "
            + "and r.budgetToolCallsUsed + :tc <= r.budgetMaxToolCalls "
            + "and coalesce(r.budgetSettledTokens, 0) + r.budgetReservedTokens + :tok <= r.budgetMaxCumulativeTokens")
    int reserveChildBudget(@Param("id") String id, @Param("mr") int modelRequests,
                           @Param("tc") int toolCalls, @Param("tok") int tokens,
                           @Param("now") java.time.Instant now);

    /**
     * MA-04：在 {@link #reserveChildBudget} 三维原子预约之上加入主任务保留额（规范 §10.1）。
     * 可分配额度 = 根上限 − 已结算 − 进行中预约 − 主任务保留；
     * 任一维度（含保留）不满足整笔失败返回 0。保留额允许 0（旧调用零保留委托）。
     */
    @Modifying
    @Query("update RunEntity r set r.budgetModelRequestsUsed = r.budgetModelRequestsUsed + :mr, "
            + "r.budgetToolCallsUsed = r.budgetToolCallsUsed + :tc, "
            + "r.budgetReservedTokens = r.budgetReservedTokens + :tok, r.updatedAt = :now "
            + "where r.id = :id "
            + "and r.budgetModelRequestsUsed + :mr + :resMr <= r.budgetMaxModelRequests "
            + "and r.budgetToolCallsUsed + :tc + :resTc <= r.budgetMaxToolCalls "
            + "and coalesce(r.budgetSettledTokens, 0) + r.budgetReservedTokens + :tok + :resTok "
            + "<= r.budgetMaxCumulativeTokens")
    int reserveChildBudgetWithReserve(@Param("id") String id,
                                      @Param("mr") int modelRequests,
                                      @Param("tc") int toolCalls,
                                      @Param("tok") int tokens,
                                      @Param("resMr") int reserveModelRequests,
                                      @Param("resTc") int reserveToolCalls,
                                      @Param("resTok") int reserveTokens,
                                      @Param("now") java.time.Instant now);

    /**
     * DSH-010：子 Run 结束后向父 Run 实结：占用退订、实际用量入账（差值可为负或零，下界 0）。
     * token 用量未知时调用方按预约全额保守结算。
     */
    @Modifying
    @Query("update RunEntity r set "
            + "r.budgetModelRequestsUsed = greatest(r.budgetModelRequestsUsed - :reservedMr + :usedMr, 0), "
            + "r.budgetToolCallsUsed = greatest(r.budgetToolCallsUsed - :reservedTc + :usedTc, 0), "
            + "r.budgetSettledTokens = coalesce(r.budgetSettledTokens, 0) + :usedTok, "
            + "r.budgetReservedTokens = greatest(r.budgetReservedTokens - :reservedTok, 0), "
            + "r.updatedAt = :now where r.id = :id")
    int settleChildBudget(@Param("id") String id,
                          @Param("reservedMr") int reservedModelRequests,
                          @Param("usedMr") int usedModelRequests,
                          @Param("reservedTc") int reservedToolCalls,
                          @Param("usedTc") int usedToolCalls,
                          @Param("reservedTok") int reservedTokens,
                          @Param("usedTok") int usedTokens,
                          @Param("now") java.time.Instant now);

    /**
     * DSH-010：标量直读预算计数（绕过一级缓存中的托管实体）。
     * 返回 [modelRequestsUsed, toolCallsUsed, settledTokens, reservedTokens]，
     * 供子预算分账（同事务 bulk UPDATE）后合并回父 Loop 的内存实体，避免脏写覆盖。
     */
    @Query("select r.budgetModelRequestsUsed, r.budgetToolCallsUsed, "
            + "r.budgetSettledTokens, r.budgetReservedTokens from RunEntity r where r.id = :id")
    java.util.List<Object[]> readBudgetCounters(@Param("id") String id);
}
