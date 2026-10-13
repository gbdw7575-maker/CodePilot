package com.dsh.agent.mcp;

/** MCP 协议/传输错误（D-17b）：code 供工具结果映射，消息不含凭据。 */
public class McpClientException extends RuntimeException {

    private final String code;

    public McpClientException(String code, String message) {
        super(message);
        this.code = code;
    }

    public McpClientException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
