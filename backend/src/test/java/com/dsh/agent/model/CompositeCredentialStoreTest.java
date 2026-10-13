package com.dsh.agent.model;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组合凭据存储：本地文件保存/解析/覆盖/列举，环境变量兜底与缺失报错。
 * 测试期间快照并恢复真实凭据文件，避免污染开发者本地数据。
 */
class CompositeCredentialStoreTest {

    private static final Path STORE = Path.of("./data/credentials.properties").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    private static byte[] snapshot;
    private static boolean existed;

    private final CompositeCredentialStore store = new CompositeCredentialStore(new EnvCredentialStore());

    @BeforeAll
    static void snapshotStore() throws Exception {
        existed = Files.isRegularFile(STORE);
        snapshot = existed ? Files.readAllBytes(STORE) : null;
    }

    @org.junit.jupiter.api.BeforeEach
    void ensureParent() throws Exception {
        Files.createDirectories(STORE.getParent());
    }

    @AfterAll
    static void restoreStore() throws Exception {
        if (existed) {
            Files.write(STORE, snapshot);
        } else {
            Files.deleteIfExists(STORE);
        }
    }

    @Test
    void saveResolveOverwriteAndList() {
        String ref = "test-cred-" + System.nanoTime();
        assertFalse(store.hasLocal(ref));

        store.save(ref, "sk-test-1");
        assertTrue(store.hasLocal(ref));
        assertEquals("sk-test-1", store.resolve(ref));
        assertTrue(store.localRefs().contains(ref));

        // 覆盖保存生效
        store.save(ref, "sk-test-2");
        assertEquals("sk-test-2", store.resolve(ref));

        // 文件确实落盘（重启可复用）
        Properties onDisk = new Properties();
        try (InputStream in = Files.newInputStream(STORE)) {
            onDisk.load(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertEquals("sk-test-2", onDisk.getProperty(ref));
    }

    @Test
    void missingRefFallsBackToEnvAndThrowsWhenAbsent() {
        String ref = "no-such-cred-" + System.nanoTime();
        // 环境变量与系统属性都未配置 → 明确报错
        assertThrows(ModelGatewayException.class, () -> store.resolve(ref));
    }

    @Test
    void systemPropertyFallbackStillWorks() {
        String ref = "sysprop-cred";
        String key = "DSH_CREDENTIAL_" + ref.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        try {
            System.setProperty(key, "sk-from-sysprop");
            assertEquals("sk-from-sysprop", store.resolve(ref));
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    void saveRejectsBlankArguments() {
        assertThrows(ModelGatewayException.class, () -> store.save(" ", "k"));
        assertThrows(ModelGatewayException.class, () -> store.save("ref", " "));
    }

    @Test
    void atomicReplaceLeavesNoTempFiles() throws Exception {
        List<Path> before;
        try (var stream = Files.list(STORE.getParent())) {
            before = stream.filter(p -> p.getFileName().toString().startsWith("credentials-")).toList();
        }
        store.save("temp-check-" + System.nanoTime(), "k1");
        store.save("temp-check-" + System.nanoTime(), "k2");
        List<Path> after;
        try (var stream = Files.list(STORE.getParent())) {
            after = stream.filter(p -> p.getFileName().toString().startsWith("credentials-")).toList();
        }
        assertEquals(before.size(), after.size(), "原子替换不得遗留临时文件");
    }
}
