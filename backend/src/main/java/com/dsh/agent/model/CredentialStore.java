package com.dsh.agent.model;

/**
 * 凭据存储：把 ModelProfile.credentialRef 解析为实际 API Key。
 * 不保存明文 Key，不写入日志或事件；缺失时抛 ModelGatewayException。
 */
public interface CredentialStore {

    /** 解析凭据引用为 API Key；缺失或引用无效抛 ModelGatewayException。 */
    String resolve(String credentialRef);
}
