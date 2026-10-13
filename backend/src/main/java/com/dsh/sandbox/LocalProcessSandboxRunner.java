package com.dsh.sandbox;

import com.dsh.agent.model.EnvCredentialStore;
import com.dsh.application.DshProperties;
import com.dsh.workspace.WorkspaceLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地真实进程沙箱：仅供开发与测试（dsh.sandbox.mode=local）。
 * 仍强制执行语义——任务副本内 workdir、超时杀进程、有界日志、清理确认；
 * 但不具备容器隔离，生产必须使用 docker 模式（开发文档 §15.1）。
 *
 * <p>D-01：即使 local 模式也不把 DSH_CREDENTIAL_* 凭据变量传给命令子进程，
 * 模型生成的命令无法经环境变量读取宿主 API Key。</p>
 */
@Component
@ConditionalOnProperty(name = "dsh.sandbox.mode", havingValue = "local")
public class LocalProcessSandboxRunner implements SandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessSandboxRunner.class);

    private final int outputLimitBytes;
    private final AtomicLong sequence = new AtomicLong();

    public LocalProcessSandboxRunner(DshProperties properties) {
        this.outputLimitBytes = properties.sandbox().outputLimitBytes();
    }

    @Override
    public String mode() {
        return "local";
    }

    @Override
    public CommandOutcome execute(CommandSpec spec, WorkspaceLease lease) {
        long started = System.nanoTime();
        List<String> commandLine = commandLine(spec.command());
        File workdir = spec.workdir() != null ? spec.workdir().toFile() : lease.dir().toFile();

        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(commandLine).directory(workdir);
            sanitizeEnvironment(builder.environment());
            process = builder.start();
        } catch (Exception e) {
            log.warn("本地沙箱启动失败 op={}: {}", spec.operationId(), e.getMessage());
            return new CommandOutcome(spec.operationId(), null, CommandSpec.Termination.LAUNCH_FAILED,
                    "", e.getMessage(), false, elapsed(started), true);
        }

        BoundedStreamCollector out = new BoundedStreamCollector(process.getInputStream(), outputLimitBytes);
        BoundedStreamCollector err = new BoundedStreamCollector(process.getErrorStream(), outputLimitBytes);
        Thread outThread = Thread.ofPlatform().name("sandbox-stdout-" + sequence.incrementAndGet()).start(out);
        Thread errThread = Thread.ofPlatform().name("sandbox-stderr-" + sequence.incrementAndGet()).start(err);

        Integer exitCode = null;
        CommandSpec.Termination termination = CommandSpec.Termination.COMPLETED;
        boolean cleanupConfirmed = true;
        try {
            Boolean timedOut = ProcessSupport.awaitWithTimeout(process, spec.timeoutSeconds());
            if (timedOut != null) {
                termination = CommandSpec.Termination.TIMEOUT;
                cleanupConfirmed = timedOut;
                log.warn("命令超时 op={} timeout={}s cleanupConfirmed={}",
                        spec.operationId(), spec.timeoutSeconds(), cleanupConfirmed);
            } else {
                exitCode = process.exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            termination = CommandSpec.Termination.TIMEOUT;
            cleanupConfirmed = !process.isAlive();
        } finally {
            joinQuietly(outThread);
            joinQuietly(errThread);
        }

        return new CommandOutcome(spec.operationId(), exitCode, termination,
                out.text(), err.text(), out.truncated() || err.truncated(),
                elapsed(started), cleanupConfirmed,
                out.totalBytes() + err.totalBytes(), out.droppedBytes() + err.droppedBytes());
    }

    private static long elapsed(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static List<String> commandLine(String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows
                ? List.of("cmd", "/c", command)
                : List.of("sh", "-c", command);
    }

    /**
     * 剥离命令子进程环境中的凭据变量（D-01）：
     * 移除所有 {@link EnvCredentialStore#ENV_PREFIX} 前缀项，其余环境（PATH 等）保留以保证命令可用。
     */
    static void sanitizeEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(key -> key != null && key.startsWith(EnvCredentialStore.ENV_PREFIX));
    }
}
