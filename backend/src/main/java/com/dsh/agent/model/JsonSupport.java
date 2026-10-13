package com.dsh.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 模型层共享的 ObjectMapper（注册 JavaTimeModule 处理 Instant）。 */
final class JsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private JsonSupport() {
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }
}
