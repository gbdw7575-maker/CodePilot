package com.dsh.rag;

import com.dsh.agent.tool.discovery.CharNgramEmbeddingClient;
import com.dsh.agent.tool.discovery.EmbeddingClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 索引服务（R3 新增）：文档加载→切分→嵌入→入库，带文件 sha256 变更检测。
 * <p>
 * 幂等：重新索引前 {@link RagVectorStore#clear} 再 save；manifest 存文件 sha256，
 * 全部未变则跳过（避免重复嵌入）。失败仅返回 false，不抛异常（@Async 场景不阻断主 Loop）。
 */
public class RagIndexer {

    private final RagDocumentLoader loader;
    private final EmbeddingClient embeddingClient;
    private final RagVectorStore vectorStore;
    private final Path manifestBaseDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public RagIndexer(RagDocumentLoader loader, EmbeddingClient embeddingClient,
                      RagVectorStore vectorStore, Path manifestBaseDir) {
        this.loader = loader;
        this.embeddingClient = embeddingClient != null ? embeddingClient : new CharNgramEmbeddingClient();
        this.vectorStore = vectorStore;
        this.manifestBaseDir = manifestBaseDir;
    }

    /** 索引指定项目的文档。返回 true 表示索引了/更新了，false 表示无变更或失败。 */
    public boolean indexProject(String projectId, Path sourcesDir) {
        if (projectId == null || projectId.isBlank() || sourcesDir == null
                || !Files.isDirectory(sourcesDir)) {
            return false;
        }
        try {
            Map<String, String> oldManifest = loadManifest(projectId);
            Map<String, String> newManifest = new HashMap<>();
            List<RagDocument> docs = loader.load(sourcesDir);
            if (docs.isEmpty()) {
                return false;
            }
            // 计算每个源文件 sha256，判断是否变更
            for (RagDocument d : docs) {
                String sha = sha256(sourcesDir.resolve(d.sourcePath()));
                newManifest.put(d.sourcePath(), sha);
            }
            if (oldManifest.equals(newManifest)) {
                return false; // 无变更跳过
            }
            // 嵌入
            List<float[]> vectors = new ArrayList<>(docs.size());
            for (RagDocument d : docs) {
                vectors.add(embeddingClient.embed(d.text()));
            }
            // 先清旧索引再写入
            vectorStore.clear(projectId);
            vectorStore.save(projectId, docs, vectors);
            saveManifest(projectId, newManifest);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, String> loadManifest(String projectId) {
        Path file = manifestPath(projectId);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            return mapper.readValue(file.toFile(), new TypeReference<Map<String, String>>() {
            });
        } catch (IOException e) {
            return Map.of();
        }
    }

    private void saveManifest(String projectId, Map<String, String> manifest) throws IOException {
        Path file = manifestPath(projectId);
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), "manifest", ".tmp");
        mapper.writeValue(tmp.toFile(), manifest);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path manifestPath(String projectId) {
        return manifestBaseDir.resolve(projectId).resolve("manifest.json");
    }

    private String sha256(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
