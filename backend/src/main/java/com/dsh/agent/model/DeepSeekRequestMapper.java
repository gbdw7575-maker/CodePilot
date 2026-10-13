package com.dsh.agent.model;

import com.dsh.agent.tool.ToolDefinition;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * ModelRequest → DeepSeek /chat/completions 请求体。
 * 映射 messages、tools、thinking、max_tokens、model；不写入凭据（由 HTTP 头携带）。
 */
public final class DeepSeekRequestMapper {

    private DeepSeekRequestMapper() {
    }

    public static ObjectNode buildRequestBody(ModelRequest request) {
        ObjectNode body = JsonSupport.mapper().createObjectNode();
        body.put("model", request.model());

        ArrayNode messages = JsonSupport.mapper().createArrayNode();
        for (ModelMessage msg : request.messages()) {
            messages.add(mapMessage(msg));
        }
        body.set("messages", messages);

        List<ToolDefinition> tools = request.tools();
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArray = JsonSupport.mapper().createArrayNode();
            for (ObjectNode def : ToolDefinitionMapper.toFunctionSchemas(tools)) {
                toolArray.add(def);
            }
            body.set("tools", toolArray);
        }

        body.put("max_tokens", request.maxOutputTokens());

        // thinking 配置：enabled 时发送 thinking 字段，disabled 不发
        String thinking = request.thinking();
        if ("enabled".equalsIgnoreCase(thinking)) {
            body.putObject("thinking").put("type", "enabled");
        }

        return body;
    }

    private static ObjectNode mapMessage(ModelMessage msg) {
        ObjectNode node = JsonSupport.mapper().createObjectNode();
        switch (msg.role()) {
            case "system" -> {
                node.put("role", "system");
                node.put("content", msg.content());
            }
            case "user" -> {
                node.put("role", "user");
                node.put("content", msg.content());
            }
            case "assistant" -> {
                node.put("role", "assistant");
                if (msg.content() != null && !msg.content().isEmpty()) {
                    node.put("content", msg.content());
                } else {
                    node.putNull("content");
                }
                if (msg.reasoningContent() != null) {
                    node.put("reasoning_content", msg.reasoningContent());
                }
                if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                    ArrayNode calls = JsonSupport.mapper().createArrayNode();
                    for (ModelToolCall call : msg.toolCalls()) {
                        ObjectNode tc = calls.addObject();
                        tc.put("id", call.callId());
                        tc.put("type", "function");
                        ObjectNode fn = tc.putObject("function");
                        fn.put("name", call.name());
                        fn.put("arguments", call.argumentsJson());
                    }
                    node.set("tool_calls", calls);
                }
            }
            case "tool" -> {
                node.put("role", "tool");
                node.put("tool_call_id", msg.toolCallId());
                node.put("content", msg.content());
            }
            default -> throw new IllegalArgumentException("未知消息角色: " + msg.role());
        }
        return node;
    }
}
