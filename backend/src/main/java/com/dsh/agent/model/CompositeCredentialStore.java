package com.dsh.agent.model;

import com.dsh.common.ApplicationDataPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 组合凭据存储：本地文件优先（运行时经 API 写入，免去重启/环境变量），
 * 环境变量 DSH_CREDENTIAL_* 兜底（保留既有部署方式）。
 *
 * <p>文件位置 ./data/credentials.properties（与 H2 ./data/dsh 同目录，相对进程工作目录），
 * 明文存储——仅适用于本地单机开发部署；生产环境应替换为 Vault/KMS 实现并禁用写接口。
 * 写入采用临时文件 + 原子替换，避免并发写损坏。</p>
 */
@Primary
@Component
public class CompositeCredentialStore implements CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(CompositeCredentialStore.class);
    private final EnvCredentialStore envFallback;

    public CompositeCredentialStore(EnvCredentialStore envFallback) {
        this.envFallback = envFallback;
    }

    @Override
    public String resolve(String credentialRef) {
        String local = readLocal(credentialRef);
        if (local != null && !local.isBlank()) {
            return local;
        }
        return envFallback.resolve(credentialRef);
    }

    /** 本地是否已有该条目（供 API 幂等提示用）。 */
    public synchronized boolean hasLocal(String credentialRef) {
        return readLocal(credentialRef) != null;
    }

    /** 本地条目名列表（不返回明文 Key）。 */
    public synchronized List<String> localRefs() {
        Properties props = loadStore();
        List<String> refs = new ArrayList<>();
        props.stringPropertyNames().stream().sorted().forEach(refs::add);
        return refs;
    }

    /** 保存/覆盖本地条目；原子替换。 */
    public synchronized void save(String credentialRef, String apiKey) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new ModelGatewayException("credentialRef 不能为空");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModelGatewayException("apiKey 不能为空");
        }
        try {
            Path file = storeFile();
            Properties props = loadStore();
            props.setProperty(credentialRef, apiKey);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "credentials-", ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                props.store(out, "DSH local credential store (plaintext; local dev only)");
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("凭据已保存到本地文件: {}", credentialRef);
        } catch (IOException e) {
            throw new ModelGatewayException("凭据写入失败: " + e.getMessage());
        }
    }

    private String readLocal(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return null;
        }
        Properties props = loadStore();
        return props.getProperty(credentialRef);
    }

    private Properties loadStore() {
        Properties props = new Properties();
        Path file = storeFile();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                log.warn("凭据文件读取失败（忽略本地条目）: {}", e.getMessage());
            }
        }
        return props;
    }

    private Path storeFile() {
        return ApplicationDataPaths.credentialFile();
    }
}
