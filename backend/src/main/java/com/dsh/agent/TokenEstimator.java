package com.dsh.agent;

import com.dsh.agent.model.ModelMessage;
import com.dsh.agent.model.ModelToolCall;
import com.dsh.agent.tool.ToolDefinition;

import java.util.List;

/**
 * D-07 单次上下文预算：模型请求前的 token 粗估。
 *
 * <p>不引入真实分词器；采用保守启发式：CJK 字符按 1 token、其余按 1/4 token 估算，
 * 宁高勿低以避免实际超窗。误差在每次模型响应后与真实 usage 对比并记录。</p>
 */
public final class TokenEstimator {

    // 2026-09-26 DeepSeek s01 诊断：原始粗估比真实输入少 18%～32%；8/5 提供保守余量。
    private static final int INPUT_CALIBRATION_NUMERATOR = 8;
    private static final int INPUT_CALIBRATION_DENOMINATOR = 5;

    private TokenEstimator() {
    }

    /** 估算单段文本 token 数（CJK 字符 ~1 token，其余 ~0.25 token，向上取整）。 */
    public static int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            }
        }
        int other = text.length() - cjk;
        return (int) Math.ceil(cjk * 1.0 + other / 4.0);
    }

    /** 估算一条模型消息的 token（content + 工具调用 JSON）。 */
    public static int estimateMessage(ModelMessage message) {
        if (message == null) {
            return 0;
        }
        int tokens = estimateText(message.content());
        if (message.toolCalls() != null) {
            for (ModelToolCall call : message.toolCalls()) {
                tokens += estimateText(call.name());
                tokens += estimateText(call.argumentsJson());
            }
        }
        return tokens;
    }

    /** 估算消息列表总 token。 */
    public static int estimateMessages(List<ModelMessage> messages) {
        int total = 0;
        for (ModelMessage m : messages) {
            total += estimateMessage(m);
        }
        return total;
    }

    /** 估算工具定义列表的 token（按 JSON 序列化长度估算）。 */
    public static int estimateTools(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        for (ToolDefinition t : tools) {
            sb.append(t.name()).append('\n');
            sb.append(t.description() == null ? "" : t.description()).append('\n');
            sb.append(t.inputSchema() == null ? "" : t.inputSchema().toString()).append('\n');
        }
        return estimateText(sb.toString());
    }

    /** 对消息/工具原始粗估做输入预算校准；不包含预留输出 token。 */
    public static int calibrateInput(int rawInputTokens) {
        return (int) Math.min(Integer.MAX_VALUE,
                ((long) rawInputTokens * INPUT_CALIBRATION_NUMERATOR
                        + INPUT_CALIBRATION_DENOMINATOR - 1) / INPUT_CALIBRATION_DENOMINATOR);
    }

    /** 将校准后的上下文窗口折算为旧历史缩减算法使用的原始粗估窗口。 */
    public static int rawWindow(int calibratedWindow, int reservedOutput) {
        int available = Math.max(0, calibratedWindow - reservedOutput);
        return (int) ((long) available * INPUT_CALIBRATION_DENOMINATOR
                / INPUT_CALIBRATION_NUMERATOR) + reservedOutput;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)   // CJK 统一表意
                || (c >= 0x3000 && c <= 0x303F) // CJK 符号标点
                || (c >= 0xFF00 && c <= 0xFFEF) // 全角字符
                || (c >= 0x3040 && c <= 0x309F) // 平假名
                || (c >= 0x30A0 && c <= 0x30FF); // 片假名
    }
}
