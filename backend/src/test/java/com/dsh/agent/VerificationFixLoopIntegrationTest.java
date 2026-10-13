package com.dsh.agent;

import com.dsh.agent.model.*;
import com.dsh.api.dto.Usage;
import com.dsh.common.IdGen;
import com.dsh.storage.*;
import com.dsh.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** 真正驱动 Loop、文件工具、验证命令与数据库，不以反馈纯函数代替修正闭环。 */
@SpringBootTest(properties = {"dsh.loop.verification-retry-limit=1",
        "dsh.policy.require-for-side-effects=NONE"})
class VerificationFixLoopIntegrationTest {
    @Autowired AgentLoop loop;
    @Autowired RunRepository runs;
    @Autowired TurnRepository turns;
    @Autowired ThreadRepository threads;
    @Autowired ProjectRepository projects;
    @Autowired ModelProfileRepository profiles;
    @Autowired MessageRepository messages;
    @Autowired VerificationRepository verifications;
    @Autowired WorkspaceManager workspaces;
    @Autowired com.dsh.application.VerificationService verificationService;
    @MockitoSpyBean FakeModelGateway gateway;
    @TempDir Path root;

    record Chain(String runId, String turnId, String threadId) {}
    private Chain create() throws Exception {
        return create(12);
    }
    private Chain create(int maxRequests) throws Exception {
        Files.createDirectories(root.resolve(".dsh"));
        Files.writeString(root.resolve(".dsh/verify.yaml"), "command: bash verify.sh\nexpectTests: true\n");
        Files.writeString(root.resolve("verify.sh"), "#!/bin/bash\nif grep -qx good result.txt; then\n"
                + "echo '1 passed in 0.01s'\nelse\n"
                + "echo 'FAILED tests/test_result.py::test_value - AssertionError: expected good, got bad'\n"
                + "echo '1 failed in 0.01s'\nexit 1\nfi\n");
        Instant now = Instant.now();
        String project = IdGen.next("proj_"); String profile = IdGen.next("prof_");
        projects.save(new ProjectEntity(project, "A4", root.toString(), true, now, now));
        profiles.save(new ModelProfileEntity(profile, "fake-a4", "fake", "fake-a4",
                "http://127.0.0.1:9", null, "disabled", null, 4096, false, "ACTIVE", now, now));
        String thread = IdGen.next("thr_"); String turn = IdGen.next("turn_"); String run = IdGen.next("run_");
        threads.save(new ThreadEntity(thread, project, "A4", profile, now, now));
        turns.save(new TurnEntity(turn, thread, run, "修复 result", "QUEUED", "PENDING", thread, now, now));
        runs.save(new RunEntity(run, turn, null, "MAIN", "QUEUED", "PENDING", 1,
                IdGen.next("ws_"), "a4", maxRequests, 20, 200000, now.plusSeconds(60), now, now));
        messages.save(new MessageEntity(IdGen.next("msg_"), thread, turn, "user", "修复 result", 1L, now));
        return new Chain(run, turn, thread);
    }
    private ModelResponse response(String value) {
        return response(value, value == null || "bad".equals(value) ? null : "bad");
    }
    private ModelResponse response(String value, String previous) {
        String expectedHash = previous == null ? ""
                : ",\"expectedFileHash\":\"" + com.dsh.common.Digest.sha256Hex(
                        previous.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\"";
        return new ModelResponse(value == null ? "完成" : "", value == null ? List.of() : List.of(
                new ModelToolCall(IdGen.next("call_"), "write_file",
                        "{\"path\":\"result.txt\",\"content\":\"" + value + "\"" + expectedHash + "}")),
                value == null ? "stop" : "tool_calls", new Usage("KNOWN", 10, 2, null, null, null, null, null), null);
    }
    private void script(AtomicInteger calls, String repair) {
        doAnswer(invocation -> {
            int step = calls.incrementAndGet();
            ModelRequest request = invocation.getArgument(0);
            if (step == 1) return response("bad");
            if (step == 3) {
                String feedback = request.messages().stream().filter(m -> "user".equals(m.role()))
                        .reduce((a, b) -> b).orElseThrow().content();
                assertTrue(feedback.contains("tests/test_result.py::test_value"), feedback);
                assertTrue(feedback.contains("expected good, got bad"), feedback);
                assertTrue(feedback.contains("art_"), "反馈须可追溯至原日志");
                return response(repair);
            }
            return response(null);
        }).when(gateway).generate(any(), any());
    }
    private List<VerificationEntity> records(Chain c) {
        return verifications.findByRunIdOrderByStartedAtDesc(c.runId(),
                org.springframework.data.domain.PageRequest.of(0, 20)).getContent();
    }
    private long feedbackCount(Chain c) {
        return messages.listByThread(c.threadId()).stream()
                .filter(m -> "user".equals(m.getRole()) && m.getContent().contains("修正机会")).count();
    }
    @Test void failedVerificationReturnsDiagnosticsThenRechecksRepairedWorkspace() throws Exception {
        Chain c = create(); AtomicInteger calls = new AtomicInteger(); script(calls, "good");
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> loop.startRootRun(c.runId()));
        assertEquals("COMPLETED", runs.findById(c.runId()).orElseThrow().getState(),
                () -> messages.listByThread(c.threadId()).toString());
        assertEquals(4, calls.get()); assertEquals(2, records(c).size());
        assertEquals("PASSED", records(c).getFirst().getStatus());
        assertEquals("FAILED", records(c).getLast().getStatus());
        assertNotEquals(records(c).getFirst().getWorkspaceDigest(), records(c).getLast().getWorkspaceDigest());
        assertEquals(1, feedbackCount(c));
        assertEquals(4, runs.findById(c.runId()).orElseThrow().getBudgetModelRequestsUsed());
        assertEquals(48, runs.findById(c.runId()).orElseThrow().getBudgetSettledTokens());
        assertNull(workspaces.find(c.runId()));
        String evidence = records(c).getFirst().getId();
        assertEquals(evidence, verificationService.runForCurrentWorkspace(c.runId()));
        assertEquals(evidence, verificationService.run(c.runId()));
        assertEquals(2, records(c).size(), "恢复相同工作区不得重复执行验证");
    }
    @Test void unchangedWorkspaceStopsWithoutReplayingFailureOrSpendingAllBudget() throws Exception {
        Chain c = create(); AtomicInteger calls = new AtomicInteger(); script(calls, null);
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> loop.startRootRun(c.runId()));
        assertEquals("VERIFICATION_FAILED", runs.findById(c.runId()).orElseThrow().getStopReason());
        assertEquals("FAILED", runs.findById(c.runId()).orElseThrow().getVerificationStatus(),
                "复用失败证据时不得把 PENDING 错判为 ENV_ERROR");
        assertEquals(3, calls.get()); assertEquals(1, records(c).size());
        assertEquals(1, feedbackCount(c)); assertNull(workspaces.find(c.runId()));
    }
    @Test void changedButStillWrongWorkspaceUsesOneRepairOpportunityOnly() throws Exception {
        Chain c = create(); AtomicInteger calls = new AtomicInteger(); script(calls, "still-bad");
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> loop.startRootRun(c.runId()));
        assertEquals("VERIFICATION_FAILED", runs.findById(c.runId()).orElseThrow().getStopReason());
        assertEquals(4, calls.get()); assertEquals(2, records(c).size());
        assertEquals(1, feedbackCount(c)); assertNull(workspaces.find(c.runId()));
    }
    @Test void restoringBaselineDuringRepairStillRequiresFreshVerification() throws Exception {
        Chain c = create(); Files.writeString(root.resolve("result.txt"), "good");
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> switch (calls.incrementAndGet()) {
            case 1 -> response("bad", "good");
            case 3 -> response("good", "bad");
            default -> response(null);
        }).when(gateway).generate(any(), any());
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> loop.startRootRun(c.runId()));
        assertEquals("COMPLETED", runs.findById(c.runId()).orElseThrow().getState());
        assertEquals("VERIFICATION_PASSED", runs.findById(c.runId()).orElseThrow().getStopReason());
        assertEquals(4, calls.get()); assertEquals(2, records(c).size());
        assertEquals("PASSED", records(c).getFirst().getStatus());
        assertNull(workspaces.find(c.runId()));
    }
    @Test void repairCannotBypassOriginalRequestBudget() throws Exception {
        Chain c = create(2); AtomicInteger calls = new AtomicInteger(); script(calls, "good");
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> loop.startRootRun(c.runId()));
        var run = runs.findById(c.runId()).orElseThrow();
        assertEquals("FAILED", run.getState()); assertEquals("BUDGET_LIMIT", run.getStopReason());
        assertEquals(2, calls.get()); assertEquals(2, run.getBudgetMaxModelRequests());
        assertEquals(2, run.getBudgetModelRequestsUsed()); assertEquals(24, run.getBudgetSettledTokens());
        assertEquals(1, records(c).size()); assertEquals(1, feedbackCount(c));
        assertNull(workspaces.find(c.runId()));
    }
}
