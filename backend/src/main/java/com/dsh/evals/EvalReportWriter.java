package com.dsh.evals;

import com.dsh.evals.model.EvalReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * 评测报告写入（可追溯）：evals/reports/<configDigest>-<timestamp>.json。
 * 报告记录通过率、失败分型、延迟、请求/token/成本、证据与原始 trial 引用。
 */
@Component
public class EvalReportWriter {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObjectMapper mapper = new ObjectMapper();

    /** Pre-run private evidence survives failed evaluation; never enters the Agent workspace. */
    public Path writeExecutionManifest(com.dsh.evals.model.EvalExecutionManifest manifest, Path reportsDir) {
        try {
            Path directory=reportsDir.resolve(".execution-manifests");Files.createDirectories(directory);
            Path file=directory.resolve(manifest.executionDigest()+"-"+java.util.UUID.randomUUID()+".json");
            try(var out=Files.newOutputStream(file,java.nio.file.StandardOpenOption.CREATE_NEW)) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(out,manifest);
            }
            return file;
        }catch(IOException e){throw new UncheckedIOException("封存执行清单失败",e);}
    }

    public Path write(EvalReport report, Path reportsDir) {
        try {
            Files.createDirectories(reportsDir);
            String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(TS);
            String suffix = report.executionManifest() == null ? "" : "-" + report.executionManifest().executionDigest().substring(0,12);
            Path file = reportsDir.resolve(report.configDigest() + "-" + stamp + suffix + "-" + java.util.UUID.randomUUID() + ".json");
            try(var out=Files.newOutputStream(file,java.nio.file.StandardOpenOption.CREATE_NEW)) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(out, report);
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("写入评测报告失败", e);
        }
    }
}
