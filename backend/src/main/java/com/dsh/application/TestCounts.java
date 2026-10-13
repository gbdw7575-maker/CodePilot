package com.dsh.application;

/**
 * 验证/评分命令输出的测试计数（发现总数/通过/失败含 error 与 xpassed/跳过）。
 */
public record TestCounts(int discovered, int passed, int failures, int skipped) {
}
