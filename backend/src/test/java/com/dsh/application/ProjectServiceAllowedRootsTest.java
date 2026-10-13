package com.dsh.application;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-01 授权根核验回归：{@link ProjectService#isWithinAllowedRoots} 必须同时挡住
 * 字面 {@code ..} 穿越与"授权根内符号链接指向授权根外"的绕过。
 * 不依赖 Spring，仅验证静态核验逻辑。
 */
class ProjectServiceAllowedRootsTest {

    @TempDir
    Path base;

    private List<String> roots(Path... roots) {
        return List.of(roots).stream().map(Path::toString).toList();
    }

    @Test
    void emptyAllowedRootsMeansNoRestriction() {
        assertTrue(ProjectService.isWithinAllowedRoots(base.toString(), List.of()));
        assertTrue(ProjectService.isWithinAllowedRoots(base.toString(), null));
    }

    @Test
    void rootItselfAndChildrenPass() throws Exception {
        Path allowed = Files.createDirectories(base.resolve("allowed"));
        Path child = Files.createDirectories(allowed.resolve("team-a/project1"));
        List<String> roots = roots(allowed);

        assertTrue(ProjectService.isWithinAllowedRoots(allowed.toString(), roots));
        assertTrue(ProjectService.isWithinAllowedRoots(child.toString(), roots));
    }

    @Test
    void notYetExistingChildOfAllowedRootPasses() throws Exception {
        Path allowed = Files.createDirectories(base.resolve("allowed"));
        // 允许预登记尚不存在的项目目录（最近存在祖先在授权根内）
        Path future = allowed.resolve("new-team/new-project");
        assertTrue(ProjectService.isWithinAllowedRoots(future.toString(), roots(allowed)));
    }

    @Test
    void siblingOutsideAllowedRootIsRejected() throws Exception {
        Path allowed = Files.createDirectories(base.resolve("allowed"));
        Path outside = Files.createDirectories(base.resolve("outside"));
        List<String> roots = roots(allowed);

        assertFalse(ProjectService.isWithinAllowedRoots(outside.toString(), roots));
        // 前缀巧合也不能放行：allowed-other 不是 allowed 的子目录
        Path lookalike = Files.createDirectories(base.resolve("allowed-other"));
        assertFalse(ProjectService.isWithinAllowedRoots(lookalike.toString(), roots));
    }

    @Test
    void literalTraversalIsRejected() throws Exception {
        Path allowed = Files.createDirectories(base.resolve("allowed"));
        Files.createDirectories(base.resolve("outside"));
        String traversal = allowed.resolve("sub/../../outside").toString();
        assertFalse(ProjectService.isWithinAllowedRoots(traversal, roots(allowed)));
    }

    @Test
    void symlinkInsideAllowedRootPointingOutsideIsRejected() throws Exception {
        Path allowed = Files.createDirectories(base.resolve("allowed"));
        Path outside = Files.createDirectories(base.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "host secret\n");
        Path link = allowed.resolve("link-out");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception e) {
            Assumptions.abort("当前环境不允许创建符号链接: " + e.getMessage());
        }

        // 字面路径在授权根内，但真实目标在授权根外——必须拒绝
        assertFalse(ProjectService.isWithinAllowedRoots(link.toString(), roots(allowed)));
        assertFalse(ProjectService.isWithinAllowedRoots(
                link.resolve("deep/sub").toString(), roots(allowed)));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void junctionInsideAllowedRootPointingOutsideIsRejectedOnWindows() throws Exception {
        // Windows junction：Files.isSymbolicLink 不识别，但 toRealPath 解析后必须暴露越界
        Path allowed = Files.createDirectories(base.resolve("allowed-j"));
        Path outside = Files.createDirectories(base.resolve("outside-j"));
        Files.writeString(outside.resolve("secret.txt"), "host secret via junction\n");
        Path link = allowed.resolve("junc-out");
        try {
            Process process = new ProcessBuilder("cmd", "/c",
                    "mklink /J \"" + link + "\" \"" + outside + "\"")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            Assumptions.assumeTrue(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0,
                    "mklink /J 失败: " + output);

            assertFalse(ProjectService.isWithinAllowedRoots(link.toString(), roots(allowed)));
            assertFalse(ProjectService.isWithinAllowedRoots(
                    link.resolve("proj").toString(), roots(allowed)));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void symlinkAllowedRootResolvesToRealTarget() throws Exception {
        // 授权根本身配置为符号链接时，其下真实子目录仍应放行
        Path realRoot = Files.createDirectories(base.resolve("real-root"));
        Path child = Files.createDirectories(realRoot.resolve("proj"));
        Path rootLink = base.resolve("root-link");
        try {
            Files.createSymbolicLink(rootLink, realRoot);
        } catch (Exception e) {
            Assumptions.abort("当前环境不允许创建符号链接: " + e.getMessage());
        }

        assertTrue(ProjectService.isWithinAllowedRoots(
                rootLink.resolve("proj").toString(), roots(rootLink)));
        assertTrue(ProjectService.isWithinAllowedRoots(child.toString(), roots(rootLink)));
    }
}
