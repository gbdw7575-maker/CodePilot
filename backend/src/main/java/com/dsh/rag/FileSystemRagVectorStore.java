package com.dsh.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文件系统向量存储（R1 新增）：JSON 文件存 {@code <baseDir>/<projectId>/vectors.json}，
 * 内存加载 + 原子写入，按 projectId 隔离。线性扫描余弦相似度，语料量小够用。
 * <p>
 * 线程安全：save/clear 用 synchronized 保证写入原子性；search 读内存快照无锁。
 */
public class FileSystemRagVectorStore implements RagVectorStore {

    private final Path baseDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileSystemRagVectorStore(Path baseDir) throws IOException {
        this.baseDir = baseDir;
        if (baseDir != null && !Files.isDirectory(baseDir)) {
            Files.createDirectories(baseDir);
        }
    }

    @Override
    public synchronized void save(String projectId, List<RagDocument> docs, List<float[]> vectors) {
        if (baseDir == null || projectId == null || projectId.isBlank() || docs == null
                || vectors == null || docs.size() != vectors.size()) {
            return;
        }
        Path file = indexPath(projectId);
        try {
            Files.createDirectories(file.getParent());
            List<Entry> entries = new ArrayList<>(docs.size());
            for (int i = 0; i < docs.size(); i++) {
                entries.add(new Entry(docs.get(i), vectors.get(i)));
            }
            Path tmp = Files.createTempFile(file.getParent(), "vectors", ".tmp");
            mapper.writeValue(tmp.toFile(), entries);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // fail-open：写入失败不阻断，下次 index 时重试
        }
    }

    @Override
    public List<ScoredDoc> search(String projectId, float[] queryVector, int topK) {
        if (projectId == null || projectId.isBlank() || queryVector == null || topK <= 0) {
            return List.of();
        }
        List<Entry> entries = load(projectId);
        if (entries.isEmpty()) {
            return List.of();
        }
        List<ScoredDoc> scored = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            scored.add(new ScoredDoc(e.doc, cosine(queryVector, e.vector)));
        }
        scored.sort(Comparator.comparingDouble(ScoredDoc::score).reversed());
        return scored.size() <= topK ? scored : new ArrayList<>(scored.subList(0, topK));
    }

    @Override
    public synchronized void clear(String projectId) {
        if (baseDir == null || projectId == null || projectId.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(indexPath(projectId));
        } catch (IOException ignored) {
        }
    }

    @Override
    public boolean exists(String projectId) {
        Path file = indexPath(projectId);
        return projectId != null && !projectId.isBlank() && file != null && Files.isRegularFile(file);
    }

    @Override
    public List<RagDocument> documents(String projectId) {
        List<Entry> entries = load(projectId);
        if (entries.isEmpty()) {
            return List.of();
        }
        List<RagDocument> docs = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            docs.add(e.doc);
        }
        return docs;
    }

    private Path indexPath(String projectId) {
        return baseDir == null ? null : baseDir.resolve(projectId).resolve("vectors.json");
    }

    private List<Entry> load(String projectId) {
        Path file = indexPath(projectId);
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return mapper.readValue(file.toFile(), new TypeReference<List<Entry>>() {
            });
        } catch (IOException e) {
            return List.of();
        }
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na <= 0 || nb <= 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** JSON 序列化容器：文档 + 向量。 */
    public static class Entry {
        public RagDocument doc;
        public float[] vector;

        public Entry() {
        }

        Entry(RagDocument doc, float[] vector) {
            this.doc = doc;
            this.vector = vector;
        }
    }
}
