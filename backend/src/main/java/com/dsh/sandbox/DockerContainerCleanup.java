package com.dsh.sandbox;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Docker 控制面的失败不是“不存在”的证据。所有确认均要求完整、成功的响应。 */
final class DockerContainerCleanup {
    enum Presence { PRESENT, ABSENT, UNKNOWN }

    @FunctionalInterface
    interface Launcher {
        Process start(List<String> command) throws IOException;
    }

    private record Reply(boolean complete, int exit, String output) {}
    private final Launcher launcher;

    DockerContainerCleanup() {
        this(command -> new ProcessBuilder(command).redirectErrorStream(true).start());
    }

    DockerContainerCleanup(Launcher launcher) {
        this.launcher = launcher;
    }

    Presence presence(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) return Presence.UNKNOWN;
        // ls 成功返回空列表才能证明不存在；inspect 的 exit=1 也可能是 daemon 故障。
        Reply reply = run(List.of("docker", "container", "ls", "--all", "--filter",
                "name=^/" + name.replace(".", "\\.") + "$", "--format", "{{.Names}}"), 5);
        if (!reply.complete() || reply.exit() != 0) return Presence.UNKNOWN;
        List<String> names = reply.output().lines().toList();
        if (names.stream().anyMatch(value -> !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]*"))) {
            return Presence.UNKNOWN;
        }
        return names.contains(name) ? Presence.PRESENT : Presence.ABSENT;
    }

    boolean removeAndConfirm(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) return false;
        Reply reply = run(List.of("docker", "rm", "-f", name), 10);
        if (reply.complete() && reply.exit() == 0 && reply.output().strip().equals(name)) return true;
        // --rm 已抢先删除也允许成功，但必须重新取得可信的不存在证据。
        return presence(name) == Presence.ABSENT;
    }

    boolean afterTimeout(String name, boolean processTreeReaped) {
        // 不允许 && 短路跳过容器回收；CLI 进程树与 Docker 容器是两个回收对象。
        boolean containerRemoved = removeAndConfirm(name);
        return processTreeReaped && containerRemoved;
    }

    private Reply run(List<String> command, int timeoutSeconds) {
        Process process = null;
        Thread reader = null;
        boolean interrupted = false;
        try {
            process = launcher.start(command);
            BoundedStreamCollector output = new BoundedStreamCollector(process.getInputStream(), 16_384);
            reader = Thread.ofPlatform().daemon(true).name("docker-cleanup-output").start(output);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) return unknown();
            reader.join(1000);
            if (reader.isAlive() || output.truncated()) return unknown();
            return new Reply(true, process.exitValue(), output.text());
        } catch (InterruptedException e) {
            interrupted = true;
            return unknown();
        } catch (IOException | RuntimeException e) {
            return unknown();
        } finally {
            if (process != null) {
                // 超时/中断时也终止控制进程，关闭流让采集线程退出，避免 inspect 泄漏。
                if (process.isAlive()) process.destroyForcibly();
                try { process.getInputStream().close(); } catch (IOException ignored) {}
                try { process.getErrorStream().close(); } catch (IOException ignored) {}
                try { process.getOutputStream().close(); } catch (IOException ignored) {}
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static Reply unknown() { return new Reply(false, -1, ""); }
}
