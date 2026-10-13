package com.dsh.agent.model;

/** 模型侧错误（端点不可达、协议错误、供应商报错）；Loop 映射为 Run FAILED/PROVIDER_ERROR。 */
public class ModelGatewayException extends RuntimeException {

    public ModelGatewayException(String message) {
        super(message);
    }

    public ModelGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
