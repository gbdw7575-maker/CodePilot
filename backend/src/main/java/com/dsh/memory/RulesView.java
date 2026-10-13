package com.dsh.memory;

/**
 * 规则层视图（开发文档 §10.2）：读取适用规则文件并记录路径、哈希和版本。
 * version 取内容 sha256 前 12 位（内容即版本）；文件缺失时 exists=false 且内容为空。
 */
public record RulesView(String projectId, String path, String sha256, String version,
                        boolean exists, boolean truncated, int byteSize, String content) {
}
