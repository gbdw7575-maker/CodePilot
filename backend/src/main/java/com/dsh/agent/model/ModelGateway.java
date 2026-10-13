package com.dsh.agent.model;

import com.dsh.agent.control.CancellationToken;

/**
 * 模型网关：把一次完整上下文换成完整响应（同步语义，网关内部可走流式拼接）。
 * generate 在调用前与每次重试前检查取消令牌；流式供应商片段在内部收齐后返回。
 * 真实供应商/流式续接属于后续阶段；失败统一抛 ModelGatewayException。
 */
public interface ModelGateway {

    String provider();

    ModelResponse generate(ModelRequest request, CancellationToken token);
}
