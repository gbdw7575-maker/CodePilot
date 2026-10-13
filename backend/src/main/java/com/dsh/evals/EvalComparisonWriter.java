package com.dsh.evals;

import com.dsh.evals.model.EvalComparison;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 单/多 Agent 对照报告写入（MA-11，离线、追加不覆盖）：
 * evals/reports/&lt;singleDigest&gt;-vs-&lt;multiDigest&gt;-&lt;UTC时间戳&gt;.json。
 */
@Component
public class EvalComparisonWriter {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObjectMapper mapper = new ObjectMapper();

    public Path write(EvalComparison comparison, Path reportsDir) {
        try {
            Files.createDirectories(reportsDir);
            String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(TS);
            String name = comparison.overallSingle().configDigest().substring(0, 12)
                    + "-vs-" + comparison.overallMulti().configDigest().substring(0, 12)
                    + "-" + stamp + ".json";
            Path file = reportsDir.resolve(name);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), comparison);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("写入对照报告失败", e);
        }
    }
}
