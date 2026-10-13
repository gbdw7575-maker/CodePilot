package com.dsh.agent.model;

import com.dsh.agent.tool.ToolDefinition;

import java.util.List;

/**
 * 一次模型调用请求（DSH-006 起支持工具定义挂载与凭据引用）。
 * tools 为当前挂载集合的定义，供 Gateway 发送给供应商；credentialRef 由 CredentialStore 解析为 Key。
 * baseUrl 由 ModelProfile 提供，网关据此拼接 /chat/completions。
 * estimatedInputTokens 为 D-07 单次上下文预算的预估输入 token（仅用于与真实 usage 对比误差，不发往供应商）。
 * prefixHash 为 D-09 前缀缓存布局的稳定前缀哈希（system+工具定义），仅用于缓存命中率校验，不发往供应商。
 */
public record ModelRequest(
        String provider,
        String model,
        List<ModelMessage> messages,
        int maxOutputTokens,
        String thinking,
        List<ToolDefinition> tools,
        String credentialRef,
        String baseUrl,
        boolean streaming,
        int estimatedInputTokens,
        String prefixHash
) {

    /** 兼容构造（无工具、无凭据、无 baseUrl，fake 网关使用）。 */
    public ModelRequest(String provider, String model, List<ModelMessage> messages,
                        int maxOutputTokens, String thinking) {
        this(provider, model, messages, maxOutputTokens, thinking, List.of(), null, null, false, 0, null);
    }

    /** 兼容构造（无 baseUrl/streaming）。 */
    public ModelRequest(String provider, String model, List<ModelMessage> messages,
                        int maxOutputTokens, String thinking,
                        List<ToolDefinition> tools, String credentialRef) {
        this(provider, model, messages, maxOutputTokens, thinking, tools, credentialRef, null, false, 0, null);
    }

    /** 兼容构造（含 baseUrl/streaming，预估 token 与前缀哈希缺省，供非 Loop 路径使用）。 */
    public ModelRequest(String provider, String model, List<ModelMessage> messages,
                        int maxOutputTokens, String thinking,
                        List<ToolDefinition> tools, String credentialRef,
                        String baseUrl, boolean streaming) {
        this(provider, model, messages, maxOutputTokens, thinking, tools, credentialRef, baseUrl, streaming, 0, null);
    }
}
