package com.dsh.api.dto;

/** 对应 openapi.json 的 components/schemas/ThreadPatch（minProperties=1）；null 表示未提供。 */
public record ThreadPatch(String title, String modelProfileId) {
}
