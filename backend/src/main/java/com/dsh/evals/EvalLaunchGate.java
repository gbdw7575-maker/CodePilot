package com.dsh.evals;

import com.dsh.evals.model.EvalConfig;
import com.dsh.evals.model.EvalExecutionManifest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;

/** C15: private CLI launch evidence; no credentials, model calls or workspace tools. */
@Component
public class EvalLaunchGate {
    private static final int MAX_BYTES = 1024 * 1024;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public EvalExecutionManifest load(Path file, EvalConfig config) {
        if (file == null) throw new IllegalArgumentException("执行评测必须提供 expected-manifest");
        Path path = file.toAbsolutePath().normalize();
        for (Path cursor = path; cursor != null; cursor = cursor.getParent())
            if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("expected-manifest 不允许链接路径");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("expected-manifest 必须是可读普通文件");
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("expected-manifest 超过1MiB限制");
            var expected = mapper.readValue(bytes, EvalExecutionManifest.class);
            if (expected == null) throw new IllegalArgumentException("expected-manifest 为空");
            expected.validateFor(config);
            return expected;
        } catch (IOException e) {
            throw new UncheckedIOException("expected-manifest 无法读取或格式/摘要不合法", e);
        }
    }

    public void assertMatches(EvalExecutionManifest expected, EvalExecutionManifest actual) {
        if (!expected.executionDigest().equals(actual.executionDigest()))
            throw new IllegalStateException("启动条件偏离预检清单；尚未提交试验，拒绝执行");
    }
}
