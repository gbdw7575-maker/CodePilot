package com.dsh.agent;

import com.dsh.agent.model.ModelMessage;
import com.dsh.agent.model.ModelToolCall;
import com.dsh.storage.MessageEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A1/A2 压缩口径修复。
 *
 * <p>背景（源码核实）：压缩原本只由「单请求上下文超窗」触发，而窗口是 12 万 token 级、
 * 评测侧累计预算是百万级，每次请求只重发数万 token ⇒ 在「累计预算先耗尽」的场景里
 * 窗口检查**永不触发**；且保护区锚定「最后一条 user 消息」，DSH/SWE-bench 这类
 * 「一条 user + 几十步执行」的单轮长任务下该锚点等于全部历史 ⇒ 两阶段压缩都是空操作。
 *
 * <p>本测试同时锁住两件事：新口径确实解开死锁，旧口径的默认行为逐字不变。
 */
class CompactionWatermarkTest {

    // ---------- A1：累计预算水位 ----------

    private LoopSteps.RoundSnapshot snapshot(int settled, int maxTokens, int reqUsed, int reqMax) {
        return new LoopSteps.RoundSnapshot("run-1", "RUNNING", "deepseek", "deepseek-chat",
                4096, null, reqUsed, reqMax, settled, maxTokens, Instant.now().plusSeconds(600));
    }

    @Test
    void watermarkTriggersOnCumulativeTokensBeforeWindowIsEverExceeded() {
        // 单次请求上下文只有 3 万级（远低于 12 万窗口），但累计已用 70% 预算
        assertTrue(LoopSteps.budgetWatermarkReached(snapshot(1_120_000, 1_600_000, 50, 128), 70));
        assertFalse(LoopSteps.budgetWatermarkReached(snapshot(1_119_000, 1_600_000, 50, 128), 70));
    }

    @Test
    void watermarkTakesMaxOfTokenAndRequestDimensions() {
        // token 维度只有 10%，但请求次数 100/128 ≈ 78% 已达 70
        assertTrue(LoopSteps.budgetWatermarkReached(snapshot(160_000, 1_600_000, 100, 128), 70));
    }

    @Test
    void watermarkDisabledWhenPercentNonPositive() {
        assertFalse(LoopSteps.budgetWatermarkReached(snapshot(1_600_000, 1_600_000, 128, 128), 0));
        assertFalse(LoopSteps.budgetWatermarkReached(snapshot(1_600_000, 1_600_000, 128, 128), -1));
    }

    @Test
    void watermarkUnknownDimensionIsIgnoredNotTreatedAsExhausted() {
        // 两个维度都无上限 ⇒ 无法判断消耗率 ⇒ 不得触发（也不得误判为 100%）
        assertFalse(LoopSteps.budgetWatermarkReached(snapshot(9_999, 0, 9, 0), 70));
        // 仅 requests 有上限时只看 requests
        assertTrue(LoopSteps.budgetWatermarkReached(snapshot(9_999, 0, 90, 100), 70));
        assertFalse(LoopSteps.budgetWatermarkReached(snapshot(9_999, 0, 69, 100), 70));
    }

    // ---------- 开关解析：未知值保守回退 ----------

    @Test
    void compactionModeParsesLenientlyAndFallsBack() {
        assertEquals(LoopSteps.CompactionMode.BUDGET_WATERMARK,
                LoopSteps.CompactionMode.parse("BUDGET_WATERMARK"));
        assertEquals(LoopSteps.CompactionMode.BUDGET_WATERMARK,
                LoopSteps.CompactionMode.parse("  budget_watermark  "));
        // 未知/空值必须落到旧口径，避免拼写错误悄悄改变运行时行为
        assertEquals(LoopSteps.CompactionMode.WINDOW_LASTUSER, LoopSteps.CompactionMode.parse(" nope "));
        assertEquals(LoopSteps.CompactionMode.WINDOW_LASTUSER, LoopSteps.CompactionMode.parse(""));
        assertEquals(LoopSteps.CompactionMode.WINDOW_LASTUSER, LoopSteps.CompactionMode.parse(null));
    }

    // ---------- A2：单轮长任务的可折叠上界 ----------

    /** 复刻单轮长任务：一条 user 消息 + N 个「assistant_tool + tool」执行单元。 */
    private List<MessageEntity> singleUserLongTask(int units) {
        List<MessageEntity> stored = new ArrayList<>();
        stored.add(entity("user", "把仓库里的这个 bug 修掉"));
        long seq = 1;
        for (int i = 1; i <= units; i++) {
            stored.add(entity("assistant_tool", "{\"calls\":[{\"callId\":\"call_" + i + "\"}]}"));
            stored.add(entity("tool", "{\"callId\":\"call_" + i + "\"}"));
            seq += 2;
        }
        return stored;
    }

    private MessageEntity entity(String role, String content) {
        return new MessageEntity("msg_" + role + "_" + content.hashCode(), "th_1", "tu_1",
                role, content, 0L, Instant.now());
    }

    @Test
    void oldGateMakesSingleUserTaskUnfodable() {
        List<MessageEntity> stored = singleUserLongTask(6);
        // 旧口径（protectedTailUnits=0）＝最后一条 user 的下标，此处 user 就在 0
        assertEquals(0, LoopSteps.compactableUpperBound(stored, 0),
                "单轮长任务下旧保护区等于全部历史，可折叠区间为空");
    }

    @Test
    void newGateUnblocksSingleUserTaskAndProtectsTailUnits() {
        List<MessageEntity> stored = singleUserLongTask(6);
        int bound = LoopSteps.compactableUpperBound(stored, 5);

        assertTrue(bound > 0, "新口径必须让单轮长任务存在可折叠区间");
        // 折叠区间不得以孤立 tool 结果结尾（否则工具调用/结果配对被破坏）
        assertNotEquals("tool", stored.get(bound).getRole(),
                "折叠上界必须落在执行单元的头部位置，不能把工具配对切开");
        // 保护最近 5 个单元 ⇒ 只应放开第 1 个单元（外加开头的 user）
        assertEquals(3, bound);
    }

    @Test
    void newGatePreservesExactlyProtectedTailUnits() {
        List<MessageEntity> stored = singleUserLongTask(6);
        for (int protectedUnits = 1; protectedUnits <= 6; protectedUnits++) {
            int bound = LoopSteps.compactableUpperBound(stored, protectedUnits);
            // 保留最近 k 个单元：每个单元 2 条消息，故上界应为 size - 2k（下限 0）
            assertEquals(Math.max(0, stored.size() - 2 * protectedUnits), bound,
                    "protectedTailUnits=" + protectedUnits + " 时保护范围不符");
        }
    }

    // ---------- A2：摘要式压缩在单轮长任务上真正生效 ----------

    private List<ModelMessage> modelHistoryOf(int units) {
        List<ModelMessage> msgs = new ArrayList<>();
        msgs.add(ModelMessage.system("sys"));
        msgs.add(ModelMessage.user("把仓库里的这个 bug 修掉"));
        for (int i = 1; i <= units; i++) {
            msgs.add(ModelMessage.assistantToolCalls(
                    List.of(new ModelToolCall("call_" + i, "read_file", "{}"))));
            // 单个体量接近真实读文件结果：折叠必须真的换来净减少，
            // 否则「摘要本身比被折叠内容还大」会让压缩失去意义。
            msgs.add(ModelMessage.toolResult("call_" + i, "read_file",
                    "FILE BODY " + i + " " + "x".repeat(12000)));
        }
        return msgs;
    }

    @Test
    void legacyCompressionIsInertOnSingleUserTask() {
        List<ModelMessage> msgs = modelHistoryOf(6);
        int sys = TokenEstimator.estimateMessage(msgs.get(0));
        int historyTokens = TokenEstimator.estimateMessages(msgs.subList(1, msgs.size()));
        int window = sys + historyTokens * 3 / 4; // 可用空间只有历史一半

        assertNull(LoopSteps.compressHistoryToWindow(msgs, sys, 0, 0, window),
                "旧口径在单轮长任务上取不到任何可移除单元，压缩恒不生效（这是缺陷本身）");
    }

    @Test
    void protectedTailCompressionFoldsEarlyUnitsAndKeepsTaskStatement() {
        List<ModelMessage> msgs = modelHistoryOf(6);
        int sys = TokenEstimator.estimateMessage(msgs.get(0));
        int historyTokens = TokenEstimator.estimateMessages(msgs.subList(1, msgs.size()));
        int window = sys + historyTokens * 3 / 4;

        List<ModelMessage> result =
                LoopSteps.compressHistoryToWindow(msgs, sys, 0, 0, window, 3);

        assertNotNull(result, "新口径应能折叠早期执行单元");
        assertEquals("system", result.get(0).role());
        assertEquals("assistant", result.get(1).role());
        assertTrue(result.get(1).content().contains("历史摘要"), "应插入历史摘要消息");

        // 任务陈述必须逐字保留，绝不能被缩写成摘要
        assertTrue(result.stream().anyMatch(m -> "user".equals(m.role())
                        && m.content().contains("把仓库里的这个 bug 修掉")),
                "唯一那条 user 任务陈述必须逐字保留");
        assertFalse(result.get(1).content().contains("把仓库里的这个 bug 修掉"),
                "任务陈述不应只存在于摘要里");

        // 压缩后确实变小
        assertTrue(TokenEstimator.estimateMessages(result.subList(1, result.size())) < historyTokens);

        // 工具调用/结果必须同生共死
        long calls = result.stream()
                .filter(m -> "assistant".equals(m.role()) && m.toolCalls() != null && !m.toolCalls().isEmpty())
                .count();
        long results = result.stream().filter(m -> "tool".equals(m.role())).count();
        assertEquals(calls, results, "工具调用与结果必须成对，不得孤立: calls=" + calls + " results=" + results);
        assertTrue(calls > 0, "最近 3 个执行单元应逐字保留");
    }

    @Test
    void protectedTailCompressionKeepsLastUnitsVerbatim() {
        List<ModelMessage> msgs = modelHistoryOf(6);
        int sys = TokenEstimator.estimateMessage(msgs.get(0));
        int historyTokens = TokenEstimator.estimateMessages(msgs.subList(1, msgs.size()));
        int window = sys + historyTokens * 3 / 4;

        List<ModelMessage> result =
                LoopSteps.compressHistoryToWindow(msgs, sys, 0, 0, window, 3);

        assertNotNull(result);
        // 最近 3 个单元（call_4/5/6）逐字保留；更早的单元被折叠
        for (int i = 4; i <= 6; i++) {
            int expect = i;
            assertTrue(result.stream().anyMatch(m -> "tool".equals(m.role())
                            && m.content().contains("FILE BODY " + expect)),
                    "最近 3 个执行单元应逐字保留 call_" + i);
        }
        assertTrue(result.stream().noneMatch(m -> "tool".equals(m.role())
                        && m.content().contains("FILE BODY 1")),
                "最早的执行单元应被折叠掉");
    }

    @Test
    void protectedTailNullWhenThereIsNothingToFold() {
        List<ModelMessage> msgs = modelHistoryOf(6);
        int sys = TokenEstimator.estimateMessage(msgs.get(0));
        // 保护全部单元 ⇒ 没有可折叠单元 ⇒ 返回 null（调用方保持原样，绝不变差）
        assertNull(LoopSteps.compressHistoryToWindow(msgs, sys, 0, 0, 1_000_000, 100));
    }

    /**
     * 契约：要么不压缩，要么必须交出一份**严格更小**的历史。
     *
     * <p>被折叠单元体量很小时，摘要自身的开销可能不亚于被折叠的内容；此时若照旧返回，
     * 调用方会得到一份并没有变小的历史，压缩成了纯粹的净损失。
     */
    @Test
    void protectedTailNeverReturnsNonReducingHistory() {
        List<ModelMessage> msgs = new ArrayList<>();
        msgs.add(ModelMessage.system("sys"));
        msgs.add(ModelMessage.user("q"));
        for (int i = 1; i <= 6; i++) {
            msgs.add(ModelMessage.assistantToolCalls(List.of(new ModelToolCall("c" + i, "t", "{}"))));
            msgs.add(ModelMessage.toolResult("c" + i, "t", "r")); // 极小正文
        }
        int sys = TokenEstimator.estimateMessage(msgs.get(0));
        int historyTokens = TokenEstimator.estimateMessages(msgs.subList(1, msgs.size()));

        // available 只有 1 token，会尽量折叠所有可折叠单元
        List<ModelMessage> result = LoopSteps.compressHistoryToWindow(msgs, sys, 0, 0, sys + 1, 3);

        assertTrue(result == null
                        || TokenEstimator.estimateMessages(result.subList(1, result.size())) < historyTokens,
                "压缩结果必须严格更小，否则应返回 null 让调用方保持原样");
    }

    // ---------- 默认口径逐字不变 ----------

    /** 多轮历史：旧口径在这里确实会压缩，用于验证「零保护尾＝旧口径」是逐字等价而非同为 null。 */
    private List<ModelMessage> multiUserHistory() {
        List<ModelMessage> msgs = new ArrayList<>();
        msgs.add(ModelMessage.system("sys"));
        msgs.add(ModelMessage.user("第一个用户问题"));
        msgs.add(ModelMessage.assistant("第一轮回答"));
        msgs.add(ModelMessage.user("第二个用户问题"));
        msgs.add(ModelMessage.assistantToolCalls(
                List.of(new ModelToolCall("c1", "read_file", "{}"))));
        msgs.add(ModelMessage.toolResult("c1", "read_file", "FILE BODY 1"));
        msgs.add(ModelMessage.user("第三个用户问题"));
        return msgs;
    }

    private static String render(List<ModelMessage> messages) {
        if (messages == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ModelMessage m : messages) {
            sb.append(m.role()).append('|').append(m.content()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void zeroProtectedTailIsByteIdenticalToLegacyOverload() {
        List<ModelMessage> msgs = multiUserHistory();
        int sys = TokenEstimator.estimateMessage(msgs.get(0));
        int historyTokens = TokenEstimator.estimateMessages(msgs.subList(1, msgs.size()));
        int step = Math.max(1, historyTokens / 8);

        boolean sawCompression = false;
        for (int window = sys + 1; window <= sys + historyTokens * 2; window += step) {
            List<ModelMessage> legacy = LoopSteps.compressHistoryToWindow(msgs, sys, 0, 0, window);
            List<ModelMessage> withZero =
                    LoopSteps.compressHistoryToWindow(msgs, sys, 0, 0, window, 0);

            assertEquals(render(legacy), render(withZero),
                    "protectedTailUnits=0 必须与旧口径逐字等价（window=" + window + "）");
            sawCompression |= legacy != null;
        }
        assertTrue(sawCompression, "扫描区间应至少覆盖到一次真实压缩，否则等价性未被验证");
    }
}
