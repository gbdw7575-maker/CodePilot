package com.dsh.agent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 重复命令防护单测：同命令 + 源码摘要不变时，第 3 次起拦截；
 * 源码变化或命令不同一律放行。对应真实负载中同一构建命令空转 5 次的问题。
 */
class RunCommandRegistryTest {

    private final RunCommandRegistry registry = new RunCommandRegistry();

    @Test
    void allowsFirstTwoIdenticalRunsThenBlocksThird() {
        String key = "python -m pytest -q\u0000/ws";
        assertNull(registry.check("run-1", key, "digest-a"));
        registry.record("run-1", key, "digest-a", 1);
        assertNull(registry.check("run-1", key, "digest-a"));
        registry.record("run-1", key, "digest-a", 1);
        String blocked = registry.check("run-1", key, "digest-a");
        assertNotNull(blocked);
        // 引导信息要包含退出码和逃生参数
        assertContains(blocked, "allowRepeated");
    }

    @Test
    void sourceChangeResetsRepeatCount() {
        String key = "make\u0000/ws";
        registry.record("run-1", key, "d1", 0);
        registry.record("run-1", key, "d1", 0);
        assertNotNull(registry.check("run-1", key, "d1"));
        // 源码改了 → 放行并从 1 重新计数
        assertNull(registry.check("run-1", key, "d2"));
        registry.record("run-1", key, "d2", 0);
        assertNull(registry.check("run-1", key, "d2"));
    }

    @Test
    void runsAreIsolatedById() {
        String key = "mvn test\u0000/ws";
        registry.record("run-a", key, "d", 0);
        registry.record("run-a", key, "d", 0);
        assertNotNull(registry.check("run-a", key, "d"));
        // 另一个 Run 执行同样命令不受影响
        assertNull(registry.check("run-b", key, "d"));
    }

    @Test
    void differentCommandsAreTrackedSeparately() {
        registry.record("run-1", "pytest a\u0000/ws", "d", 0);
        registry.record("run-1", "pytest a\u0000/ws", "d", 0);
        assertNotNull(registry.check("run-1", "pytest a\u0000/ws", "d"));
        assertNull(registry.check("run-1", "pytest b\u0000/ws", "d"));
    }

    @Test
    void releaseClearsRunState() {
        String key = "tox\u0000/ws";
        registry.record("run-1", key, "d", 0);
        registry.record("run-1", key, "d", 0);
        registry.release("run-1");
        assertNull(registry.check("run-1", key, "d"));
    }

    private static void assertContains(String text, String token) {
        assertNotNull(text);
        assertEquals(true, text.contains(token));
    }
}
