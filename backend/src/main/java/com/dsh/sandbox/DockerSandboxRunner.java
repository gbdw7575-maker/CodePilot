package com.dsh.sandbox;

import com.dsh.application.DshProperties;
import com.dsh.workspace.SafePaths;
import com.dsh.workspace.WorkspaceLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Docker 命令沙箱（生产默认；开发文档 §15.1 硬边界）：
 * 容器非 root、{@code --network none}、内存/CPU/PID 上限、{@code --cap-drop ALL} 与
 * {@code no-new-privileges} 禁提权、只挂任务副本到 /workspace、
 * 不挂 Docker socket 与主机凭据、不注入宿主环境变量（docker run 无 -e/--env）。
 * 超时先 docker rm -f 确认容器回收，超时/丢失不返回伪造退出码。镜像由 DSH/sandbox/Dockerfile 构建。
 */
@Component
@ConditionalOnProperty(name = "dsh.sandbox.mode", havingValue = "docker", matchIfMissing = true)
public class DockerSandboxRunner implements SandboxRunner {
    @Override public TaskCheckQuarantine quarantineCheck(String operationId,String imageDigest,String ownerId){return new DockerTaskCheckQuarantine().fence(operationId,imageDigest,ownerId);}
    @Override public boolean verifyCheckQuarantine(String operationId,String containerId,String ownerId,String imageDigest){return new DockerTaskCheckQuarantine().verify(operationId,containerId,ownerId,imageDigest);}

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxRunner.class);

    /** 容器内 PID 上限（D-01）：与内存/CPU 同类的 fork 型滥用资源边界。 */
    private static final int PIDS_LIMIT = 256;

    private final DshProperties.Sandbox config;
    private final int outputLimitBytes;
    private final DockerContainerCleanup cleanup = new DockerContainerCleanup();

    public DockerSandboxRunner(DshProperties properties) {
        this.config = properties.sandbox();
        this.outputLimitBytes = config.outputLimitBytes();
    }

    @Override
    public String mode() {
        return "docker";
    }

    /** No process is launched; this immutable plan cannot be used as a complete CLI command. */
    @Override
    public DockerCheckIsolationPlan planCheck(com.dsh.orchestration.acceptance.TaskCheckExecutionRequest request) {
        return DockerCheckIsolationPlan.from(request);
    }

    @Override
    public TaskCheckExecutionOutcome executeCheck(com.dsh.orchestration.acceptance.TaskCheckExecutionRequest request,
                                                  java.util.function.BooleanSupplier cancelled) {
        return new DockerTaskCheckExecutor().execute(request, cancelled);
    }

    @Override
    public TaskCheckCleanupObservation cleanupCheck(String operationId, String expectedContainerId) {
        return new DockerTaskCheckRecovery().cleanup(operationId, expectedContainerId);
    }

    @Override
    public CommandOutcome execute(CommandSpec spec, WorkspaceLease lease) {
        long started = System.nanoTime();
        String containerName = "dsh-" + spec.operationId().replace('_', '-');
        List<String> commandLine = dockerRunCommand(spec, lease, containerName);

        Process process;
        try {
            process = new ProcessBuilder(commandLine).start();
        } catch (Exception e) {
            log.warn("Docker 启动失败 op={}: {}", spec.operationId(), e.getMessage());
            return new CommandOutcome(spec.operationId(), null, CommandSpec.Termination.LAUNCH_FAILED,
                    "", "无法调用 docker: " + e.getMessage(), false, elapsed(started), true);
        }

        BoundedStreamCollector out = new BoundedStreamCollector(process.getInputStream(), outputLimitBytes);
        BoundedStreamCollector err = new BoundedStreamCollector(process.getErrorStream(), outputLimitBytes);
        Thread outThread = Thread.ofPlatform().name("docker-stdout-" + containerName).start(out);
        Thread errThread = Thread.ofPlatform().name("docker-stderr-" + containerName).start(err);
        // 使用 stdin 传输，避免 Windows argv 长度上限；并行写入让启动/写阻塞也受原截止时间约束。
        AtomicReference<Exception> inputFailure = new AtomicReference<>();
        Thread inputThread = Thread.ofPlatform().name("docker-stdin-" + containerName).start(() -> {
            try (var input = process.getOutputStream()) {
                input.write(encodedCommandBytes(spec.command()));
            } catch (Exception e) {
                inputFailure.set(e);
            }
        });

        Integer exitCode = null;
        CommandSpec.Termination termination = CommandSpec.Termination.COMPLETED;
        boolean cleanupConfirmed = true;
        boolean interrupted = false;
        try {
            Boolean timedOut = ProcessSupport.awaitWithTimeout(process, spec.timeoutSeconds());
            if (timedOut != null) {
                termination = CommandSpec.Termination.TIMEOUT;
                cleanupConfirmed = cleanup.afterTimeout(containerName, timedOut);
                log.warn("容器命令超时 op={} container={} cleanupConfirmed={}",
                        spec.operationId(), containerName, cleanupConfirmed);
            } else {
                int code = process.exitValue();
                if (code == 125) {
                    // docker run 自身失败（镜像/守护进程/参数），不是容器内命令退出码
                    termination = CommandSpec.Termination.LAUNCH_FAILED;
                    cleanupConfirmed = cleanup.presence(containerName) == DockerContainerCleanup.Presence.ABSENT;
                } else {
                    exitCode = code;
                }
            }
        } catch (InterruptedException e) {
            termination = CommandSpec.Termination.TIMEOUT;
            interrupted = true;
            // waitFor 中断后先在有界回收期间清除标志，否则后续 waitFor 会立即再次抛异常。
            Thread.interrupted();
            boolean processReaped = false;
            try {
                Boolean timedOut = ProcessSupport.awaitWithTimeout(process, 0);
                processReaped = timedOut == null || timedOut;
            } catch (InterruptedException interruptedAgain) {
                Thread.interrupted();
            }
            cleanupConfirmed = cleanup.afterTimeout(containerName, processReaped);
        } finally {
            boolean interruptedDuringCleanup = Thread.interrupted();
            joinQuietly(inputThread);
            joinQuietly(outThread);
            joinQuietly(errThread);
            if (interrupted || interruptedDuringCleanup) Thread.currentThread().interrupt();
        }

        if (termination == CommandSpec.Termination.COMPLETED && inputFailure.get() != null) {
            // 不把传输失败后可能执行的残缺命令当作一次完整命令成功。
            termination = CommandSpec.Termination.LAUNCH_FAILED;
            exitCode = null;
            cleanupConfirmed = cleanup.removeAndConfirm(containerName);
        }

        return new CommandOutcome(spec.operationId(), exitCode, termination,
                out.text(), err.text(), out.truncated() || err.truncated(),
                elapsed(started), cleanupConfirmed,
                out.totalBytes() + err.totalBytes(), out.droppedBytes() + err.droppedBytes());
    }

    /** MA-04：包级可见以便 G-BACKEND 纯命令构造断言（无 Docker 守护进程依赖）。 */
    List<String> dockerRunCommand(CommandSpec spec, WorkspaceLease lease, String containerName) {
        List<String> cmd = new ArrayList<>(List.of(
                "docker", "run",
                "--rm",
                "-i",
                "--name", containerName,
                "--network", "none",
                "--memory", config.memory(),
                "--cpus", config.cpus(),
                "--pids-limit", String.valueOf(PIDS_LIMIT),
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--user", "1000:1000",
                "-v", dockerMountPath(lease.dir()) + ":/workspace",
                "-w", containerWorkdir(lease, spec.workdir())));
        cmd.add(config.image());
        cmd.add("bash");
        cmd.add("-c");
        cmd.add(commandBootstrap());
        return cmd;
    }

    /**
     * Windows ProcessBuilder 到 docker.exe 的 argv 边界不能可靠保留内嵌双引号。
     * stdin 只传 Base64 数据；容器内还原 UTF-8 字节到私有临时脚本后执行。
     * 固定 mktemp 路径没有空白，启动片段不含双引号；不改写用户命令或工作目录。
     * 传输关闭后用户脚本的 stdin 为 EOF（现有命令 API 无交互输入），不会把后续源码当输入。
     * 脚本位于容器 /tmp，退出时删除，不污染工作区；最后一条 bash 保留真实退出码。
     */
    static String commandBootstrap() {
        return "script=$(mktemp /tmp/dsh-command.XXXXXXXX) || exit; "
                + "trap 'rm -f -- $script' EXIT; "
                + "base64 -d > $script || exit; bash $script";
    }

    static byte[] encodedCommandBytes(String command) {
        return Base64.getEncoder().encode(command.getBytes(StandardCharsets.UTF_8));
    }

    private static String containerWorkdir(WorkspaceLease lease, java.nio.file.Path workdir) {
        if (workdir == null) {
            return "/workspace";
        }
        String rel = SafePaths.relativeUnix(lease.dir(), workdir);
        return ".".equals(rel) ? "/workspace" : "/workspace/" + rel;
    }

    /** Windows 主机路径转 Docker Desktop 可挂载的 /c/... 形式；POSIX 原样。 */
    static String dockerMountPath(java.nio.file.Path path) {
        String value = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':') {
            value = "/" + Character.toLowerCase(value.charAt(0)) + value.substring(2);
        }
        return value;
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsed(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
