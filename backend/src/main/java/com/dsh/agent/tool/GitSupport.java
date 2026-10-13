package com.dsh.agent.tool;

import com.dsh.sandbox.CommandOutcome;
import com.dsh.sandbox.CommandSpec;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** git 只读命令经同一沙箱执行的公共封装（git_status/git_diff）。 */
final class GitSupport {

    private GitSupport() {
    }

    static CommandOutcome git(ToolExecutionContext ctx, String arguments, int timeoutSeconds) {
        Instant deadline = ctx.deadline() == null ? Instant.now().plusSeconds(30) : ctx.deadline();
        int timeout = (int) Math.min(timeoutSeconds,
                Math.max(1, Duration.between(Instant.now(), deadline).getSeconds()));
        return ctx.sandbox().execute(new CommandSpec(
                com.dsh.common.IdGen.next("op_"), "git " + arguments, null, timeout), ctx.lease());
    }

    /** 解析 git status --porcelain=v1 输出为 [状态(2字符), 路径]。 */
    static List<String[]> parsePorcelain(String output) {
        List<String[]> entries = new ArrayList<>();
        for (String line : output.split("\n", -1)) {
            if (line.length() < 4) {
                continue;
            }
            String xy = line.substring(0, 2);
            String path = line.substring(3).trim();
            if (!path.isEmpty()) {
                entries.add(new String[]{xy, path});
            }
        }
        return entries;
    }

    /** pathspec 最小引用：简单路径直出，其余双引号包裹并转义反斜杠/引号。 */
    static String quotePath(String relativeUnix) {
        if (relativeUnix.matches("[A-Za-z0-9_./-]+")) {
            return relativeUnix;
        }
        return "\"" + relativeUnix.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static ToolOutcome gitFailure(String source, String stage, CommandOutcome result) {
        String stderr = result.stderr() == null ? "" : result.stderr().trim();
        String message = stage + "失败";
        if (!stderr.isEmpty()) {
            message += ": " + stderr.substring(0, Math.min(300, stderr.length()));
        }
        return ToolOutcome.failed("GIT_ERROR", message, source);
    }
}
