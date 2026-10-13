package com.dsh.api.dto;

/** 对应 openapi.json 的 ModelValidation.checks 项。 */
public record ModelValidationCheck(String name, boolean passed, String message) {
}
