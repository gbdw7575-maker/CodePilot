package com.dsh.api.dto;

/** 对应 openapi.json 的 components/schemas/CreateThread；title 可选，缺省由服务端命名。 */
public record CreateThread(String title, String modelProfileId) {
}
