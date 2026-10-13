package com.dsh.agent.tool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * write_file 对"瞬时写入被拒"的重试。
 *
 * <p>动机（2026-10-06 实测）：Windows 宿主机上 {@code Files.write} 会偶发
 * {@code AccessDeniedException}，同一个文件手工写入却完全正常 ⇒ 属瞬时占用。
 * `ext-sphinx-doc__sphinx-10466` 的 write_file 正被它挡下，连同 3 次 apply_patch 失败，
 * 整轮 65 个请求 / 1,558,857 token 之后源码改动为 0（官方判定 empty_patch）。</p>
 */
class WriteFileRetryTest {

    private static final byte[] PAYLOAD = "new".getBytes(StandardCharsets.UTF_8);

    @Test
    void writesOnceWhenNoDenial(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("a.txt");
        Files.write(target, "old".getBytes(StandardCharsets.UTF_8));
        WriteFileTool.writeWithRetry(target, PAYLOAD, 3);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(target));
    }

    @Test
    void retriesTransientDenialThenSucceeds(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("b.txt");
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger written = new AtomicInteger();
        WriteFileTool.FileWriter flaky = (p, b) -> {
            calls.incrementAndGet();
            if (calls.get() < 3) throw new AccessDeniedException(p.toString());
            written.set(b.length);
        };
        WriteFileTool.writeWithRetry(target, PAYLOAD, 3, flaky);
        assertEquals(3, calls.get(), "应在第 3 次成功");
        assertEquals(PAYLOAD.length, written.get());
    }

    @Test
    void givesUpAfterAttemptsAndNamesTheFailure(@TempDir Path dir) {
        Path target = dir.resolve("c.txt");
        AtomicInteger calls = new AtomicInteger();
        WriteFileTool.FileWriter alwaysDenied = (p, b) -> {
            calls.incrementAndGet();
            throw new AccessDeniedException(p.toString());
        };
        IOException io = assertThrows(IOException.class,
                () -> WriteFileTool.writeWithRetry(target, PAYLOAD, 3, alwaysDenied));
        assertEquals(3, calls.get(), "尝试次数应等于配置上限");
        assertTrue(io.getMessage().contains("3"), "失败信息要说明尝试次数: " + io.getMessage());
        assertTrue(io.getMessage().contains("AccessDenied"), "失败信息要带异常类型: " + io.getMessage());
    }

    @Test
    void doesNotRetryNonTransientIoFailures(@TempDir Path dir) {
        Path target = dir.resolve("d.txt");
        AtomicInteger calls = new AtomicInteger();
        WriteFileTool.FileWriter missing = (p, b) -> {
            calls.incrementAndGet();
            throw new NoSuchFileException(p.toString());
        };
        assertThrows(NoSuchFileException.class,
                () -> WriteFileTool.writeWithRetry(target, PAYLOAD, 3, missing));
        assertEquals(1, calls.get(), "非瞬时异常不应重试");
    }

    @Test
    void attemptsBelowOneStillWritesOnce(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("e.txt");
        AtomicInteger calls = new AtomicInteger();
        WriteFileTool.FileWriter counting = (p, b) -> calls.incrementAndGet();
        WriteFileTool.writeWithRetry(target, PAYLOAD, 0, counting);
        assertEquals(1, calls.get(), "attempts=0 也要至少写一次，不能静默不写");
    }

    @Test
    void defaultAttemptsIsThree() {
        assertEquals(3, WriteFileTool.DEFAULT_WRITE_ATTEMPTS);
        assertTrue(WriteFileTool.BACKOFF_MS > 0);
    }

    @Test
    void noArgConstructorKeepsOldSignature() {
        // 历史构造签名必须仍然可用（Spring 之外还有测试/回放链路直接 new）
        WriteFileTool tool = new WriteFileTool();
        assertEquals("write_file", tool.name());
    }
}
