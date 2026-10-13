package com.dsh.orchestration;

import com.dsh.application.DshProperties;
import com.dsh.common.IdGen;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-16 有界并行单元测试（不启动 Spring）：
 * 用带延迟的 fake 子执行证明并行真实发生（在途峰值=并行度、墙钟显著低于串行合计），
 * 以及部分子失败时其他子仍执行且报告 PARTIAL、全部失败报告 FAILED。
 * 文件级汇合正确性由 FanoutDsh016Test 与 WorkspaceMergeTest 覆盖。
 */
class FanoutServiceParallelTest {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxFlight = new AtomicInteger();

    private RunEntity parent() {
        return new RunEntity("run-parent", "turn-parent", null, "MAIN",
                "RUNNING", "PENDING", 1, "ws_seed", "digest",
                24, 80, 200_000, Instant.now().plusSeconds(600), Instant.now(), Instant.now());
    }

    private WorkspaceManager workspaceManagerStub() {
        WorkspaceLease lease = new WorkspaceLease("ws_seed", "run-parent", "proj-x",
                Path.of("."), Path.of("."), Path.of("."), "digest", Instant.now());
        WorkspaceManager wm = mock(WorkspaceManager.class);
        when(wm.find(anyString())).thenReturn(lease);
        return wm;
    }

    private DshProperties properties(int maxFanout) {
        return new DshProperties(null, null, null, null, null, null, null, null, null,
                new DshProperties.Orchestration(true, 2, 8, 30, 60_000, 600, false, maxFanout,
                        DshProperties.Orchestration.Plan.defaults(),
                        DshProperties.Orchestration.Quota.defaults(), 3, 500, null),
                null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private RunRepository runRepositoryStub(RunEntity parent) {
        RunRepository runRepository = mock(RunRepository.class);
        when(runRepository.findById(anyString())).thenReturn(java.util.Optional.of(parent));
        return runRepository;
    }

    @Test
    void boundedParallelismCutsWallClockAndCapsInFlight() {
        int maxFanout = 3;
        long sleepMs = 300;

        SubagentService subagentService = mock(SubagentService.class);
        when(subagentService.run(any())).thenAnswer(inv -> {
            int now = inFlight.incrementAndGet();
            maxFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(sleepMs);
            } finally {
                inFlight.decrementAndGet();
            }
            // 无产物写者 → 不触发汇合，只验证并行执行
            return new SubagentResult(IdGen.next("sub_run_"), "CODER", "COMPLETED", null,
                    "ok", List.of(), null, "ws_child", "", 1, 1, 100, sleepMs);
        });

        FanoutService service = new FanoutService(subagentService, null,
                workspaceManagerStub(), runRepositoryStub(parent()), null, null, null,
                properties(maxFanout));

        long start = System.nanoTime();
        FanoutService.FanoutReport report = service.fanout("run-parent", List.of("t1", "t2", "t3"));
        long elapsed = (System.nanoTime() - start) / 1_000_000L;

        assertEquals("COMPLETED", report.state(),
                () -> "reason=" + report.reason() + " children=" + report.children().stream()
                        .map(c -> c.state() + "/" + c.stopReason() + "/" + c.summary()).toList()
                        + " maxFlight=" + maxFlight.get());
        assertEquals(3, report.parallelism());
        assertEquals(3, maxFlight.get(), "3 个任务必须真实同时在途（并行度 3）");
        // 3 × 300ms 串行需 900ms；并行应在 ~300ms 完成（宽限断言，不依赖极端调度）
        assertTrue(elapsed < 600, "并行墙钟应接近单任务耗时，远低于串行 900ms，实际 " + elapsed + "ms");
        assertTrue(report.wallClockMs() < report.totals().sumDurationMs(),
                "墙钟应低于子任务耗时合计");
    }

    @Test
    void oneChildFailureDoesNotBlockOthersAndReportsPartial() {
        AtomicInteger seq = new AtomicInteger();
        SubagentService subagentService = mock(SubagentService.class);
        when(subagentService.run(any())).thenAnswer(inv -> {
            int i = seq.incrementAndGet();
            Thread.sleep(20);
            if (i == 1) {
                return new SubagentResult(IdGen.next("sub_run_"), "CODER", "FAILED", "BUDGET_LIMIT",
                        "模拟子失败", List.of(), null, "", "", 0, 0, null, 20);
            }
            return new SubagentResult(IdGen.next("sub_run_"), "CODER", "COMPLETED", null,
                    "成功", List.of(), null, "", "", 1, 1, 100, 20);
        });

        FanoutService service = new FanoutService(subagentService, null,
                workspaceManagerStub(), runRepositoryStub(parent()), null, null, null,
                properties(4));

        FanoutService.FanoutReport report = service.fanout("run-parent", List.of("bad", "good"));

        assertEquals("PARTIAL", report.state());
        assertEquals(2, report.children().size());
        assertEquals(1, report.children().stream().filter(c -> "COMPLETED".equals(c.state())).count());
        assertEquals(1, report.children().stream().filter(c -> "FAILED".equals(c.state())).count());
    }

    @Test
    void allChildrenFailedReportsFailed() {
        SubagentService subagentService = mock(SubagentService.class);
        when(subagentService.run(any())).thenReturn(
                new SubagentResult(IdGen.next("sub_run_"), "CODER", "FAILED", "MODEL_ERROR",
                        "x", List.of(), null, "", "", 0, 0, null, 1));

        FanoutService service = new FanoutService(subagentService, null,
                workspaceManagerStub(), runRepositoryStub(parent()), null, null, null,
                properties(4));

        FanoutService.FanoutReport report = service.fanout("run-parent", List.of("a", "b"));
        assertEquals("FAILED", report.state());
        assertEquals(2, report.children().size());
    }
}
