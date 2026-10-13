package com.dsh.agent.model;

/**
 * 模型返回的一次工具调用（参数为原始 JSON 字符串，完整收齐后才执行）。
 *
 * @param callId       供应商调用标识
 * @param name         工具名
 * @param argumentsJson 原始参数 JSON 文本
 */
public record ModelToolCall(String callId, String name, String argumentsJson) {
}
