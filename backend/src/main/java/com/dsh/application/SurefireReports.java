package com.dsh.application;

import com.dsh.common.Digest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Element;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

/** Reads only declared directories. Raw bounded bytes are sealed, never added to model context. */
final class SurefireReports {
    static final int MAX_FILES = 64, MAX_FILE_BYTES = 1024 * 1024, MAX_TOTAL_BYTES = 4 * 1024 * 1024;
    record FileData(String path, byte[] bytes, String sha256, Instant modifiedAt) { }
    record Snapshot(Map<String, FileData> files, String error) { }
    record Result(TestCounts counts, String error, List<FileData> files, Instant startedAt, Instant finishedAt, TestReportSpec spec) {
        boolean valid() { return error == null; }
        byte[] evidence() {
            var json = new ObjectMapper();
            var reports = json.createArrayNode();
            for (var file : files) reports.addObject().put("path", file.path())
                    .put("sizeBytes", file.bytes().length).put("sha256", file.sha256())
                    .put("modifiedAt", file.modifiedAt().toString())
                    .put("base64", Base64.getEncoder().encodeToString(file.bytes()));
            var root = json.createObjectNode().put("version", spec.policy()).put("format", spec.format())
                    .put("startedAt", startedAt.toString()).put("finishedAt", finishedAt.toString())
                    .put("valid", valid()).put("reason", error == null ? "VALID" : error)
                    .put("discovered", counts.discovered()).put("passed", counts.passed())
                    .put("failuresAndErrors", counts.failures()).put("skipped", counts.skipped());
            root.set("reports", reports);
            return root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    static Snapshot before(Path root, TestReportSpec spec) {
        try { return new Snapshot(readFiles(root, spec, false), null); }
        catch (ReportException error) { return new Snapshot(Map.of(), error.code); }
    }
    static Result after(Path root, TestReportSpec spec, Snapshot before, Instant start, Instant finish) {
        var captured = new ArrayList<FileData>();
        var counts = new TestCounts(0, 0, 0, 0);
        String error = before.error();
        try {
            // Capture all readable bounded files before parsing; malformed XML is still reviewable.
            captured.addAll(readFiles(root, spec, true).values());
            if (error != null) throw new ReportException(error);
            for (var file : captured) {
                var old = before.files().get(file.path());
                if (old != null && old.sha256().equals(file.sha256())) throw new ReportException("REPORT_UNCHANGED");
                if (file.modifiedAt().isBefore(start) || file.modifiedAt().isAfter(finish))
                    throw new ReportException("REPORT_STALE_TIME");
                var suite = parse(file.bytes(), "GRADLE".equals(spec.format()) ? start : null, finish);
                counts = new TestCounts(Math.addExact(counts.discovered(), suite.discovered()),
                        Math.addExact(counts.passed(), suite.passed()), Math.addExact(counts.failures(), suite.failures()),
                        Math.addExact(counts.skipped(), suite.skipped()));
            }
            if (counts.discovered() == 0) throw new ReportException("REPORT_ZERO_TESTS");
            if (counts.passed() == 0 && counts.failures() == 0) throw new ReportException("REPORT_ALL_SKIPPED");
        } catch (ReportException rejected) { error = rejected.code; }
        catch (ArithmeticException overflow) { error = "REPORT_COUNT_OVERFLOW"; }
        return new Result(counts, error, List.copyOf(captured), start, finish, spec);
    }
    private static LinkedHashMap<String, FileData> readFiles(Path root, TestReportSpec spec, boolean require) {
        var result = new LinkedHashMap<String, FileData>();
        int total = 0;
        try {
            root = root.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
                throw new ReportException("REPORT_PATH_UNSAFE");
            for (String directory : spec.directories()) {
                Path dir = checked(root, directory);
                if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                    if (require) throw new ReportException("REPORT_DIRECTORY_MISSING");
                    continue;
                }
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new ReportException("REPORT_PATH_UNSAFE");
                var entries = new ArrayList<Path>();
                try (var listing = Files.newDirectoryStream(dir)) {
                    int inspected = 0;
                    for (Path file : listing) {
                        if (++inspected > 4096) throw new ReportException("REPORT_DIRECTORY_LIMIT");
                        if (file.getFileName().toString().matches("TEST-.+\\.xml")) entries.add(file);
                        if (entries.size() + result.size() > MAX_FILES) throw new ReportException("REPORT_FILE_LIMIT");
                    }
                }
                entries.sort(Comparator.comparing(Path::toString));
                if (require && entries.isEmpty()) throw new ReportException("REPORT_FILES_MISSING");
                for (Path file : entries) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (result.containsKey(relative)) throw new ReportException("REPORT_DUPLICATE_PATH");
                    checked(root, relative);
                    var pre = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!pre.isRegularFile()) throw new ReportException("REPORT_PATH_UNSAFE");
                    if (pre.size() > MAX_FILE_BYTES || total + pre.size() > MAX_TOTAL_BYTES)
                        throw new ReportException("REPORT_BYTE_LIMIT");
                    byte[] bytes;
                    try (SeekableByteChannel channel = Files.newByteChannel(file,
                            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                        ByteBuffer buffer = ByteBuffer.allocate((int)pre.size());
                        while (buffer.hasRemaining() && channel.read(buffer) != -1) { }
                        if (buffer.hasRemaining() || channel.size() != pre.size()) throw new ReportException("REPORT_CHANGED_DURING_READ");
                        bytes = buffer.array();
                    }
                    checked(root, relative);
                    var post = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!post.isRegularFile() || pre.size() != post.size() || !pre.lastModifiedTime().equals(post.lastModifiedTime())
                            || !Objects.equals(pre.fileKey(), post.fileKey())) throw new ReportException("REPORT_CHANGED_DURING_READ");
                    total += bytes.length;
                    result.put(relative, new FileData(relative, bytes, Digest.sha256Hex(bytes), post.lastModifiedTime().toInstant()));
                }
            }
            return result;
        } catch (IOException unavailable) { throw new ReportException("REPORT_UNREADABLE"); }
    }
    private static Path checked(Path root, String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw new ReportException("REPORT_PATH_UNSAFE");
        Path cursor = root;
        for (Path part : root.relativize(path)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new ReportException("REPORT_PATH_UNSAFE");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !path.toRealPath().startsWith(root.toRealPath()))
            throw new ReportException("REPORT_PATH_UNSAFE");
        return path;
    }
    static TestCounts parse(byte[] bytes) {
        return parse(bytes, null, null);
    }
    static TestCounts parse(byte[] bytes, java.util.Set<String> identities) {
        return parse(bytes, null, null, identities);
    }
    private static TestCounts parse(byte[] bytes, Instant executionStart, Instant executionFinish) {
        return parse(bytes, executionStart, executionFinish, new HashSet<>());
    }
    private static TestCounts parse(byte[] bytes, Instant executionStart, Instant executionFinish, java.util.Set<String> identities) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException e) throws SAXParseException { throw e; }
                public void error(SAXParseException e) throws SAXParseException { throw e; }
                public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
            });
            Element suite = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            if (!"testsuite".equals(suite.getTagName())) throw new ReportException("REPORT_UNSUPPORTED_XML");
            if (executionStart != null) {
                // Gradle cache restoration can rewrite mtime. The standard XML timestamp is the
                // original class execution time (ISO_INSTANT), so cached bytes remain stale even
                // when no report existed before this command. Never infer success from task logs.
                Instant executedAt;
                try { executedAt = Instant.parse(suite.getAttribute("timestamp")); }
                catch (java.time.format.DateTimeParseException invalid) {
                    throw new ReportException("REPORT_EXECUTION_TIME_INVALID");
                }
                if (executedAt.isBefore(executionStart) || executedAt.isAfter(executionFinish))
                    throw new ReportException("REPORT_EXECUTION_TIME_STALE");
            }
            int tests = count(suite, "tests"), failures = count(suite, "failures"), errors = count(suite, "errors"), skipped = count(suite, "skipped");
            int seen = 0, seenFailures = 0, seenErrors = 0, seenSkipped = 0;
            for (var node = suite.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element test)) continue;
                if (Set.of("properties", "system-out", "system-err").contains(test.getTagName())) continue;
                if (!"testcase".equals(test.getTagName())) throw new ReportException("REPORT_UNSUPPORTED_XML");
                if (test.getAttribute("name").isBlank() || test.getAttribute("classname").isBlank()
                        || !identities.add(test.getAttribute("classname") + "\0" + test.getAttribute("name")))
                    throw new ReportException("REPORT_TEST_IDENTITY");
                seen++;
                int states = 0;
                for (var child = test.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (!(child instanceof Element element)) continue;
                    switch (element.getTagName()) {
                        case "failure" -> { seenFailures++; states++; }
                        case "error" -> { seenErrors++; states++; }
                        case "skipped" -> { seenSkipped++; states++; }
                        case "system-out", "system-err" -> { }
                        default -> throw new ReportException("REPORT_UNSUPPORTED_XML");
                    }
                }
                if (states > 1) throw new ReportException("REPORT_COUNT_MISMATCH");
            }
            if (seen != tests || seenFailures != failures || seenErrors != errors || seenSkipped != skipped)
                throw new ReportException("REPORT_COUNT_MISMATCH");
            return new TestCounts(tests, tests - failures - errors - skipped, failures + errors, skipped);
        } catch (ReportException rejected) { throw rejected; }
        catch (Exception invalid) { throw new ReportException("REPORT_INVALID_XML"); }
    }
    private static int count(Element suite, String attribute) {
        String value = suite.getAttribute(attribute);
        if (!value.matches("[0-9]{1,7}")) throw new ReportException("REPORT_INVALID_COUNTS");
        int count = Integer.parseInt(value);
        if (count > 1_000_000) throw new ReportException("REPORT_INVALID_COUNTS");
        return count;
    }
    private static final class ReportException extends RuntimeException {
        final String code;
        ReportException(String code) { super(code); this.code = code; }
    }
}
