package com.dsh.sandbox;

import com.dsh.application.DshProperties;
import com.dsh.workspace.WorkspaceLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-04 / G-BACKEND：docker run 命令纯构造断言（不依赖 Docker 守护进程）。
 * 真实容器隔离（假凭据不可读、无网落文件）属 G-DOCKER 用例，本机无环境时记 BLOCKED。
 */
class Ma04DockerCommandShapeTest {

    @TempDir
    Path workspaceDir;
    @TempDir
    Path sourceRoot;
    @TempDir
    Path metaDir;

    private DockerSandboxRunner runner() {
        DshProperties properties = new DshProperties(null, null, null, null,
                new DshProperties.Sandbox("docker", "dsh-sandbox:latest", "512m", "2.0",
                        60, 600, 262_144),
                null, null, null, null, null, null, null, null, null, null);
        return new DockerSandboxRunner(properties);
    }

    @Test
    void dockerRunHasNoEnvInjectionAndMountsOnlyWorkspace() {
        WorkspaceLease lease = new WorkspaceLease("ws-ma04-shape", "run-ma04", "proj-ma04",
                sourceRoot, workspaceDir, metaDir, "digest-ma04", Instant.now());
        CommandSpec spec = new CommandSpec("op_ma04_shape", "echo ok", null, 30);

        List<String> cmd = runner().dockerRunCommand(spec, lease, "dsh-op-ma04-shape");

        // 不注入宿主环境变量（凭据只能经受控凭证通道，MA-11 真实链路另验）
        assertFalse(cmd.contains("-e"), "docker run 不得携带 -e 环境变量参数");
        assertFalse(cmd.contains("--env"), "docker run 不得携带 --env 环境变量参数");
        // 不挂 Docker socket / 主机目录：恰好一个 -v 且只挂任务副本到 /workspace
        long mounts = cmd.stream().filter("-v"::equals).count();
        assertEquals(1, mounts, "docker run 必须恰好一个挂载");
        int vIndex = cmd.indexOf("-v");
        String mount = cmd.get(vIndex + 1);
        assertTrue(mount.endsWith(":/workspace"), "挂载目标只能是 /workspace: " + mount);
        assertFalse(mount.contains("docker.sock"), "严禁挂载 Docker socket");

        // 隔离基线参数齐备
        assertTrue(cmd.contains("--rm"));
        assertTrue(cmd.contains("-i"));
        assertTrue(cmd.contains("--network"));
        assertEquals("none", cmd.get(cmd.indexOf("--network") + 1));
        assertEquals("ALL", cmd.get(cmd.indexOf("--cap-drop") + 1));
        assertTrue(cmd.contains("no-new-privileges"));
        assertEquals("1000:1000", cmd.get(cmd.indexOf("--user") + 1));
        // 镜像之后是容器内命令，工作目录固定在挂载点内
        int imageIndex = cmd.indexOf("dsh-sandbox:latest");
        assertEquals("/workspace", cmd.get(cmd.indexOf("-w") + 1));
        assertTrue(imageIndex > 0);
        assertEquals(List.of("bash", "-c", DockerSandboxRunner.commandBootstrap()),
                cmd.subList(imageIndex + 1, cmd.size()));
        assertFalse(cmd.contains(spec.command()), "原始脚本不得经过 Windows argv 引号边界");
    }
}
