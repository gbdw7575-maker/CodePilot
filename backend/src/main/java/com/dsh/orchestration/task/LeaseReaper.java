package com.dsh.orchestration.task;

import com.dsh.storage.TaskAttemptEntity;
import com.dsh.storage.TaskAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 租约过期回收（MA-08，规范 §10.3 五窗口之窗口2"运行中断"）。
 *
 * <p>定时扫描 state='RUNNING' and leaseUntil &lt; now 的 attempt：
 * <ul>
 *   <li>任务 CANCELLING → attempt CANCELLED, task CANCELLED（取消已登记，
 *       租约过期直接收敛，不再等子 Run 安全点）；</li>
 *   <li>否则 → requeue（attempt QUEUED 清 lease，task READY），
 *       等补偿扫描重投（claim CAS 保证不会双执行者）。</li>
 * </ul>
 * fencingToken 不清除：保留单调递增，旧 token 的迟到产物由 MA-08 fencing 写校验拒绝（§8.3）。</p>
 *
 * <p>租约未过期的 RUNNING 孤儿不回收（避免双执行者）；进程启动时的孤儿扫描在
 * {@link TaskRecoveryService#recover()} 调用 {@link TaskSchedulingService#reapExpiredLease}。</p>
 *
 * <p>固定延迟而非固定速率，避免回收雪崩连打；回收后 attempt 回 QUEUED，
 * 由 {@link TaskWorkerExecutor#scheduledScan} 重投。</p>
 */
@Component
@ConditionalOnProperty(name = "dsh.orchestration.enabled", havingValue = "true")
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final TaskAttemptRepository attemptRepository;
    private final TaskSchedulingService schedulingService;

    public LeaseReaper(TaskAttemptRepository attemptRepository,
                       TaskSchedulingService schedulingService) {
        this.attemptRepository = attemptRepository;
        this.schedulingService = schedulingService;
    }

    /**
     * 定时回收扫描：查过期 RUNNING attempt，逐个经事务回收。
     * 回收失败的单条不中断整批扫描（记录告警继续）。
     */
    @Scheduled(fixedDelayString = "${dsh.orchestration.lease-reap-ms:5000}",
               initialDelayString = "${dsh.orchestration.recovery-scan-ms:1000}")
    public void reapScan() {
        try {
            Instant now = Instant.now();
            List<TaskAttemptEntity> expired = attemptRepository.findExpiredRunning(now);
            if (expired.isEmpty()) {
                return;
            }
            int reaped = 0;
            for (TaskAttemptEntity attempt : expired) {
                try {
                    if (schedulingService.reapExpiredLease(attempt.getAttemptId(), now)) {
                        reaped++;
                    }
                } catch (RuntimeException e) {
                    log.warn("租约回收失败 attempt={}: {}", attempt.getAttemptId(), e.getMessage());
                }
            }
            if (reaped > 0) {
                log.info("租约过期回收扫描完成：回收 {} 个 attempt（共扫描 {}）", reaped, expired.size());
            }
        } catch (RuntimeException e) {
            log.warn("租约回收扫描失败: {}", e.getMessage());
        }
    }
}
