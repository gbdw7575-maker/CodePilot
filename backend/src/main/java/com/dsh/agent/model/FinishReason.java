package com.dsh.agent.model;

/** 归一化结束原因（model.completed.finishReason 直接使用这些字符串）。 */
public final class FinishReason {

    public static final String STOP = "stop";
    public static final String TOOL_CALLS = "tool_calls";
    public static final String WAIT_INPUT = "wait_input";
    public static final String LENGTH = "length";
    public static final String PROTOCOL_ERROR = "protocol_error";

    private FinishReason() {
    }
}
