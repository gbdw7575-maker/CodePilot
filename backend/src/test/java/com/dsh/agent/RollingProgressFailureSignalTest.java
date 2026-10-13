package com.dsh.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dsh.storage.MessageEntity;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 收敛提示的"失败主导"态。
 *
 * <p>动机（2026-10-06 实测）：`psf__requests-1142` 连跑 36 条失败命令排查环境
 * （pip download / 翻 conda 包元数据 / git reflog），每条都按原口径算作"新证据"，
 * 于是检测器一直给出"近期有探索证据"这一较软的提示，咬不住；
 * 整轮 51 请求 / 1,562,775 token、**0 次写操作**，官方判定 empty_patch。</p>
 *
 * <p>本改动是**纯增量**：不取消失败命令的证据计数（那会推翻既有语义保证），
 * 只在窗口内失败命令占多数时改走更强的提示态。</p>
 */
class RollingProgressFailureSignalTest {

    private static final String TURN = "turn-1";
    private final ObjectMapper mapper = new ObjectMapper();

    private static String commandOutcome(int exitCode, String text, String commandDigest) {
        return "{\"name\":\"run_command\",\"status\":\"" + (exitCode == 0 ? "SUCCEEDED" : "FAILED")
                + "\",\"exitCode\":" + exitCode
                + ",\"structuredContent\":{\"termination\":\"COMPLETED\",\"commandDigest\":\""
                + commandDigest + "\"},\"content\":[{\"type\":\"text\",\"text\":\""
                + text.replace("\"", "\\\"") + "\"}]}";
    }

    private RollingProgressDetector.Snapshot analyze(int count, int exitCode) {
        List<MessageEntity> stored = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            stored.add(new MessageEntity("m" + i, "thread-1", TURN, "tool",
                    commandOutcome(exitCode, "output " + i, "cmd" + i), i, Instant.now()));
        }
        return RollingProgressDetector.analyze(stored, TURN, mapper);
    }

    @Test
    void failedCommandsAreCounted() {
        assertTrue(analyze(10, 1).failedCommands() >= RollingProgressDetector.FAILURE_DOMINANT);
    }

    @Test
    void successfulCommandsProduceNoFailureCount() {
        assertEquals(0, analyze(10, 0).failedCommands());
    }

    @Test
    void failureDominantStateStopsEncouragingRetries() {
        var snap = new RollingProgressDetector.Snapshot(
                8, 0, 5, 0, 0, RollingProgressDetector.FAILURE_DOMINANT, false);
        String hint = snap.hint();
        assertTrue(hint.contains("反复失败"), "命中失败主导态: " + hint);
        assertTrue(hint.contains("停止"), "要明确要求停止继续排查环境: " + hint);
        assertFalse(hint.contains("确有偶发故障"), "失败主导态下不应再鼓励重跑: " + hint);
    }

    @Test
    void belowThresholdKeepsOldBehaviour() {
        var snap = new RollingProgressDetector.Snapshot(
                8, 0, 5, 0, 0, RollingProgressDetector.FAILURE_DOMINANT - 1, false);
        String hint = snap.hint();
        assertFalse(hint.contains("反复失败"), "未达阈值不应切到失败主导态: " + hint);
        assertTrue(hint.contains("探索证据"), "应回到原口径: " + hint);
    }

    @Test
    void modificationCandidateStillSuppressesAnyHint() {
        var snap = new RollingProgressDetector.Snapshot(
                8, 1, 0, 0, 0, RollingProgressDetector.FAILURE_DOMINANT, false);
        assertNull(snap.hint(), "已有修改候选时仍不提示");
    }

    @Test
    void statesAreDistinguishableAndNumericFree() {
        String failed = new RollingProgressDetector.Snapshot(8, 0, 0, 0, 0, 3, false).hint();
        String idle = new RollingProgressDetector.Snapshot(8, 0, 0, 0, 0, 0, false).hint();
        String exploring = new RollingProgressDetector.Snapshot(8, 0, 3, 0, 0, 0, false).hint();
        assertNotEquals(failed, idle);
        assertNotEquals(idle, exploring);
        // 本段拼进 system，而 prefixHash 覆盖 system 内容 ⇒ 数字会让前缀缓存每轮失效
        for (String h : List.of(failed, idle, exploring)) {
            assertFalse(h.matches(".*\\d.*"), "提示文本里不能出现数字: " + h);
        }
    }
}
