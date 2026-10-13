package com.dsh.agent.tool;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每 Run 的命令执行指纹册（通用能力，非任何基准/语言特化）。
 *
 * <p>解决的问题：真实负载观测到 agent 在未修改源码时反复执行同一条重命令
 * （同一构建/测试命令连跑数次），每次都消耗数十秒与大量输出 token，
 * 得到完全相同的结果，50 次模型请求都停在「反复验证」阶段没有产出修复。
 *
 * <p>按 (runId, 命令, workdir) 记录最近一次执行前的源码摘要与退出码。
 * 同一条命令在源码摘要未变化时重复执行：允许一次重跑（保留确认偶发问题的空间），
 * 第二次重复起直接拦截并引导模型换策略；命令不同、或两次之间源码有变化，总是放行。
 *
 * <p>仅内存态、按 Run 隔离、容量有界；进程重启后只是不再拦截，不会出错。
 */
@Component
public class RunCommandRegistry {

    /** 同摘要下允许的前两次执行，第 3 次起拦截（即 1 次重跑余量）。 */
    static final int MAX_IDENTICAL_RUNS = 2;
    static final int MAX_RUNS = 256;

    private record CommandRecord(int count, int exitCode, String sourceDigest) {
    }

    private final Map<String, Map<String, CommandRecord>> runs = new ConcurrentHashMap<>();

    /**
     * 预检一次命令执行。
     *
     * @return 非空表示应拦截，内容为给模型的引导信息；null 表示放行
     */
    public synchronized String check(String runId, String commandKey, String sourceDigest) {
        Map<String, CommandRecord> commands = commandsOf(runId);
        CommandRecord prev = commands.get(commandKey);
        if (prev != null && prev.sourceDigest().equals(sourceDigest)
                && prev.count() >= MAX_IDENTICAL_RUNS) {
            return "该命令在源码未发生任何修改的情况下已执行 " + prev.count()
                    + " 次（上次退出码 " + prev.exitCode() + "），当前未观察到新的源码输入。"
                    + "请先修改代码再重新运行验证，或改用不同命令/参数继续排查；"
                    + "若确实需要原样重跑（例如排查偶发故障），可在参数中传 \"allowRepeated\": true。";
        }
        return null;
    }

    /** 记录一次已放行的执行结果。 */
    public synchronized void record(String runId, String commandKey, String sourceDigest, int exitCode) {
        Map<String, CommandRecord> commands = commandsOf(runId);
        CommandRecord prev = commands.get(commandKey);
        int next = (prev != null && prev.sourceDigest().equals(sourceDigest)) ? prev.count() + 1 : 1;
        commands.put(commandKey, new CommandRecord(next, exitCode, sourceDigest));
    }

    private Map<String, CommandRecord> commandsOf(String runId) {
        String key = runId == null ? "" : runId;
        Map<String, CommandRecord> commands = runs.get(key);
        if (commands == null) {
            if (runs.size() >= MAX_RUNS) {
                var it = runs.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            commands = new HashMap<>();
            runs.put(key, commands);
        }
        return commands;
    }

    /** Run 终态后清理（幂等）。 */
    public void release(String runId) {
        if (runId != null) {
            runs.remove(runId);
        }
    }
}
