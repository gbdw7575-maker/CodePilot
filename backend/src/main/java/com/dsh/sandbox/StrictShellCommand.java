package com.dsh.sandbox;

/** 仅用于受控验证/评分/诊断；普通 run_command 保留调用者的 shell 语义。 */
public final class StrictShellCommand {
    /** C20/C21 的成功证据必须来自此执行/验证策略，旧记录不按新规则自动背书。 */
    public static final String POLICY_VERSION = "bash-errexit-pipefail-cleanup-executed-tests-v1";

    private StrictShellCommand() {}

    public static String wrap(String command) {
        if (command == null || command.isBlank() || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("严格执行命令必须是非空文本且不含空字节");
        }
        // 一个字面参数传给 Bash；管道与条件表达式由 shell 执行，不能靠字符串扫描解释。
        return "bash -e -o pipefail -c '" + command.replace("'", "'\"'\"'") + "'";
    }
}
