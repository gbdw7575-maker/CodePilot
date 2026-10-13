package com.dsh.api.dto;

/** 契约 RuleFile：适用规则文件的项目相对路径、内容 sha256 与截断标记。 */
public record RuleFile(String relativePath, String sha256, String content, boolean truncated) {
}
