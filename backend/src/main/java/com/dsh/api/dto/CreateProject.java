package com.dsh.api.dto;

/** 对应 openapi.json 的 components/schemas/CreateProject。 */
public record CreateProject(String name, String rootPath) {
}
