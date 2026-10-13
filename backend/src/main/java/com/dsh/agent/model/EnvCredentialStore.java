package com.dsh.agent.model;

import org.springframework.stereotype.Component;

/**
 * 环境变量凭据存储：DSH_CREDENTIAL_{ref.toUpperCase()} → API Key。
 * 本地单机部署不引入外部密钥管理；生产环境可替换为 Vault/KMS 实现。
 */
@Component
public class EnvCredentialStore implements CredentialStore {

    /** 凭据环境变量前缀；沙箱启动命令子进程前按此前缀剥离（D-01：Key 不进入命令环境）。 */
    public static final String ENV_PREFIX = "DSH_CREDENTIAL_";

    @Override
    public String resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new ModelGatewayException("模型配置缺少 credentialRef，无法获取 API Key");
        }
        String key = ENV_PREFIX + credentialRef.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            // 回退到系统属性（测试注入用）
            value = System.getProperty(key);
        }
        if (value == null || value.isBlank()) {
            throw new ModelGatewayException("凭据未配置: 环境变量 " + key + " 缺失");
        }
        return value;
    }
}
