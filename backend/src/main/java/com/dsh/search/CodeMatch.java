package com.dsh.search;

/**
 * 单条代码搜索命中（D-05）：path 为相对工作区根的 POSIX 风格路径；
 * line 从 1 开始；lineTruncated 表示原文行超过单行展示上限被截断。
 */
public record CodeMatch(String path, int line, String text, boolean lineTruncated) {
}
