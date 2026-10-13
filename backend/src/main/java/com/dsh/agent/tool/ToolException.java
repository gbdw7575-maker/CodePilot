package com.dsh.agent.tool;

/** 工具执行期受控异常：错误码沿用契约错误码/工具错误码，由 Dispatcher 映射为 FAILED/DENIED 结果。 */
public class ToolException extends Exception {

    private final String errorCode;

    public ToolException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ToolException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
