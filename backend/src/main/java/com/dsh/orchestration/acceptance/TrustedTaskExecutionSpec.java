package com.dsh.orchestration.acceptance;

import com.dsh.workspace.SafePaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Internal trusted-file input only: not Host authorization, user confirmation or a Runner request. */
public final class TrustedTaskExecutionSpec {
    public static final String VERSION = "dsh-task-execution-v1";
    public static final String ADAPTER = "MAVEN_JUNIT_SOURCE_V1";
    private static final int MAX_SPEC_BYTES = 512 * 1024, MAX_CACHE_FILES = 4096;
    private static final long MAX_CACHE_FILE_BYTES = 64L * 1024 * 1024, MAX_CACHE_BYTES = 256L * 1024 * 1024;
    private static final List<String> ARGV = List.of("sh", "/verifier/run-check.sh", "normal");

    public record Limits(int cpuMillis, long memoryBytes, int pids, long tmpfsBytes, int logBytes,
                         int reportFiles, int reportFileBytes, int reportTotalBytes) { }
    public record Report(String format, String policy, List<String> directories, String transport, String lifecycle) {
        public Report { directories = List.copyOf(directories); }
    }
    public record CacheFile(String path, long bytes, String sha256) { }
    public record CheckProfile(String checkId, List<String> argv, int timeoutSeconds, int minPassed,
                               List<TrustedTaskCheckCatalog.Verifier> verifiers, String checksDigest) {
        public CheckProfile { argv = List.copyOf(argv); verifiers = List.copyOf(verifiers); }
    }
    public record Selection(String executionSpecDigest, List<CheckProfile> checks, String digest) {
        public Selection { checks = List.copyOf(checks); }
    }
    public static final class Snapshot {
        private final Path controlRoot, cacheRoot;
        private final String projectId, registryDigest, imageDigest, sourceDigest, executionSpecDigest, canonicalDefinition;
        private final Limits limits;
        private final Report report;
        private final List<CacheFile> cacheFiles;
        private final SortedMap<String,CheckProfile> checks;
        private Snapshot(Path root, Path cache, TrustedTaskCheckCatalog.Snapshot catalog, byte[] source,
                         JsonNode definition, Limits limits, Report report, List<CacheFile> files, SortedMap<String,CheckProfile> checks) {
            controlRoot = root; cacheRoot = cache; projectId = catalog.projectId(); registryDigest = catalog.registryDigest();
            imageDigest = catalog.imageDigest(); sourceDigest = CanonicalTaskJson.sha256(source);
            this.limits = limits; this.report = report; cacheFiles = List.copyOf(files);
            this.checks = Collections.unmodifiableSortedMap(new TreeMap<>(checks));
            canonicalDefinition = new String(CanonicalTaskJson.encode(definition), java.nio.charset.StandardCharsets.UTF_8);
            var bound = JsonNodeFactory.instance.objectNode().put("version", VERSION).put("sourceDigest", sourceDigest);
            bound.set("definition", definition.deepCopy());
            var profiles = bound.putArray("checks"); checks.values().forEach(c -> profiles.add(profile(c)));
            executionSpecDigest = CanonicalTaskJson.digest(bound);
        }
        public String projectId() { return projectId; }
        public String registryDigest() { return registryDigest; }
        public String imageDigest() { return imageDigest; }
        public String sourceDigest() { return sourceDigest; }
        public String executionSpecDigest() { return executionSpecDigest; }
        public String canonicalDefinition() { return canonicalDefinition; }
        public Path cacheRoot() { return cacheRoot; }
        public Limits limits() { return limits; }
        public Report report() { return report; }
        public List<CacheFile> cacheFiles() { return cacheFiles; }
        public List<CheckProfile> checks() { return List.copyOf(checks.values()); }
        public Selection select(List<String> refs) {
            require(refs != null && !refs.isEmpty() && refs.size() <= 8);
            var ids = new TreeSet<String>();
            for (String id : refs) require(id != null && checks.containsKey(id) && ids.add(id));
            var chosen = ids.stream().map(checks::get).toList();
            var value = JsonNodeFactory.instance.objectNode().put("version", "task-execution-selection-v1")
                    .put("executionSpecDigest", executionSpecDigest);
            var entries = value.putArray("checks"); chosen.forEach(c -> entries.add(profile(c)));
            return new Selection(executionSpecDigest, chosen, CanonicalTaskJson.digest(value));
        }
    }

    public static Snapshot load(Path controlRoot, String projectId, List<Path> excludedRoots) {
        try {
            require(projectId != null && projectId.matches("[A-Za-z0-9_-]{1,128}"));
            Path root = directory(controlRoot), project = directory(root.resolve(projectId));
            require(excludedRoots != null && !excludedRoots.isEmpty());
            for (Path excluded : excludedRoots) {
                Path resolved = directory(excluded);
                require(!root.startsWith(resolved) && !resolved.startsWith(root));
            }
            // Existing catalog loader checks identity, complete exclusion roots and actual verifier bytes.
            var catalog = TrustedTaskCheckCatalog.load(controlRoot, projectId, excludedRoots);
            Path descriptor = project.resolve("task-execution.json"), cache = directory(project.resolve("runtime-cache"));
            byte[] source = readSmall(project, descriptor);
            JsonNode spec = CanonicalTaskJson.parse(source);
            fields(spec, "version", "projectId", "registryDigest", "imageDigest", "executionPolicy", "adapterId", "limits", "report", "cacheManifest", "checkRefs");
            require(VERSION.equals(text(spec.get("version"))) && projectId.equals(text(spec.get("projectId")))
                    && catalog.registryDigest().equals(hash(spec.get("registryDigest")))
                    && catalog.imageDigest().equals(hash(spec.get("imageDigest"))) && ADAPTER.equals(text(spec.get("adapterId"))));
            require(CanonicalTaskJson.digest(spec.get("executionPolicy")).equals(catalog.policyDigest()));
            Limits limits = limits(spec.get("limits")); Report report = report(spec.get("report"));
            JsonNode references = spec.get("checkRefs"); array(references, 1, 32);
            var ids = new ArrayList<String>(); for (var ref : references) ids.add(text(ref));
            require(ids.equals(catalog.checks().stream().map(TrustedTaskCheckCatalog.Check::id).toList()));
            var profiles = new TreeMap<String,CheckProfile>();
            for (var check : catalog.checks()) {
                require(check.argv().equals(ARGV));
                var paths = new HashSet<String>(); check.verifiers().forEach(v -> paths.add(v.path()));
                require(paths.containsAll(List.of("pom.xml", "run-check.sh"))
                        && paths.stream().anyMatch(p -> p.startsWith("tests/") && p.endsWith(".java")));
                require(paths.stream().noneMatch(p -> p.equals("task-execution.json") || p.startsWith("runtime-cache/")));
                profiles.put(check.id(), new CheckProfile(check.id(), check.argv(), check.timeoutSeconds(), check.minPassed(),
                        check.verifiers(), catalog.checksDigest(List.of(check.id()))));
            }
            JsonNode manifest = spec.get("cacheManifest"); array(manifest, 1, MAX_CACHE_FILES);
            var files = new ArrayList<CacheFile>(); var unique = new HashSet<String>(); long total = 0;
            String previous = null;
            for (var row : manifest) {
                fields(row, "path", "bytes", "sha256"); String name = text(row.get("path")); relative(name);
                require(unique.add(name.toLowerCase(Locale.ROOT)) && (previous == null || previous.compareTo(name) < 0));
                previous = name; long size = number(row.get("bytes"), 0, MAX_CACHE_FILE_BYTES);
                total = Math.addExact(total, size); require(total <= MAX_CACHE_BYTES);
                files.add(new CacheFile(name, size, hash(row.get("sha256"))));
            }
            verifyCache(cache, files); verifyCache(cache, files);
            require(Arrays.equals(source, readSmall(project, descriptor)));
            TrustedTaskCheckCatalog.requireCurrent(root, excludedRoots, catalog);
            return new Snapshot(root, cache, catalog, source, spec, limits, report, files, profiles);
        } catch (IOException | SecurityException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid task execution specification");
        }
    }

    public static void requireCurrent(Path controlRoot, List<Path> excludedRoots, Snapshot frozen) {
        require(frozen != null);
        Snapshot current = load(controlRoot, frozen.projectId(), excludedRoots);
        require(current.controlRoot.equals(frozen.controlRoot) && current.cacheRoot.equals(frozen.cacheRoot)
                && current.executionSpecDigest.equals(frozen.executionSpecDigest));
    }

    /** Validate a stored/public canonical definition, without claiming filesystem or Host authority. */
    public static JsonNode validateExport(String canonical) {
        require(canonical != null && canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_SPEC_BYTES);
        JsonNode spec=CanonicalTaskJson.parse(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        require(canonical.equals(new String(CanonicalTaskJson.encode(spec),java.nio.charset.StandardCharsets.UTF_8)));
        fields(spec,"version","projectId","registryDigest","imageDigest","executionPolicy","adapterId","limits","report","cacheManifest","checkRefs");
        require(VERSION.equals(text(spec.get("version"))) && ADAPTER.equals(text(spec.get("adapterId"))));
        require(text(spec.get("projectId")).matches("[A-Za-z0-9_-]{1,128}")); hash(spec.get("registryDigest"));hash(spec.get("imageDigest"));
        var policy=spec.get("executionPolicy");
        fields(policy,"network","rootFilesystem","user","capabilities","candidateMount","verifierMount","noNewPrivileges");
        require("none".equals(text(policy.get("network")))&&"read-only".equals(text(policy.get("rootFilesystem")))
                &&"1000:1000".equals(text(policy.get("user")))&&"read-only".equals(text(policy.get("candidateMount")))
                &&"read-only".equals(text(policy.get("verifierMount")))&&policy.path("capabilities").isArray()
                &&policy.path("capabilities").isEmpty()&&policy.path("noNewPrivileges").isBoolean()&&policy.path("noNewPrivileges").booleanValue());
        limits(spec.get("limits"));report(spec.get("report"));
        array(spec.get("checkRefs"),1,32);String priorRef=null;
        for(var ref:spec.get("checkRefs")){String id=text(ref);require(id.matches("[A-Za-z0-9_-]{1,64}")&&(priorRef==null||priorRef.compareTo(id)<0));priorRef=id;}
        array(spec.get("cacheManifest"),1,MAX_CACHE_FILES);String prior=null;long total=0;var aliases=new HashSet<String>();
        for(var row:spec.get("cacheManifest")){
            fields(row,"path","bytes","sha256");String name=text(row.get("path"));relative(name);hash(row.get("sha256"));
            require(aliases.add(name.toLowerCase(Locale.ROOT))&&(prior==null||prior.compareTo(name)<0));prior=name;
            total+=number(row.get("bytes"),0,MAX_CACHE_FILE_BYTES);require(total<=MAX_CACHE_BYTES);
        }
        return spec;
    }

    private static JsonNode profile(CheckProfile check) {
        var node = JsonNodeFactory.instance.objectNode().put("checkId", check.checkId())
                .put("timeoutSeconds", check.timeoutSeconds()).put("minPassed", check.minPassed()).put("checksDigest", check.checksDigest());
        var args = node.putArray("argv"); check.argv().forEach(args::add);
        var deps = node.putArray("verifiers"); check.verifiers().forEach(v -> deps.addObject().put("path", v.path()).put("sha256", v.sha256()));
        return node;
    }
    private static Limits limits(JsonNode node) {
        fields(node, "cpuMillis", "memoryBytes", "pids", "tmpfsBytes", "logBytes", "reportFiles", "reportFileBytes", "reportTotalBytes");
        int cpu = (int)number(node.get("cpuMillis"), 1000, 2000), pids = (int)number(node.get("pids"), 64, 256);
        long memory = number(node.get("memoryBytes"), 512L*1024*1024, 2L*1024*1024*1024);
        long tmpfs = number(node.get("tmpfsBytes"), 64L*1024*1024, 512L*1024*1024);
        int logs = (int)number(node.get("logBytes"), 1024, 1024*1024), files = (int)number(node.get("reportFiles"), 1, 64);
        int each = (int)number(node.get("reportFileBytes"), 1024, 1024*1024), total = (int)number(node.get("reportTotalBytes"), each, 4*1024*1024);
        require(tmpfs <= memory); return new Limits(cpu, memory, pids, tmpfs, logs, files, each, total);
    }
    private static Report report(JsonNode node) {
        fields(node, "format", "policy", "directories", "transport", "lifecycle");
        require("MAVEN_SUREFIRE".equals(text(node.get("format"))) && "surefire-reports-v1".equals(text(node.get("policy")))
                && "BOUNDED_DOCKER_STDOUT_BYTES_V1".equals(text(node.get("transport")))
                && "MAVEN_EXIT_CAPTURE_RELEASE_V1".equals(text(node.get("lifecycle"))));
        array(node.get("directories"), 1, 1); require("target/surefire-reports".equals(text(node.get("directories").get(0))));
        return new Report("MAVEN_SUREFIRE", "surefire-reports-v1", List.of("target/surefire-reports"),
                "BOUNDED_DOCKER_STDOUT_BYTES_V1", "MAVEN_EXIT_CAPTURE_RELEASE_V1");
    }
    private static void verifyCache(Path cache, List<CacheFile> expected) throws IOException {
        boolean windows=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");
        var actual = new TreeSet<String>(); int[] directories = {0};
        Files.walkFileTree(cache, Set.of(), 33, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                require(++directories[0] <= 8192);
                // The walk starts at a canonical authorized root and uses actual directory names.
                // Preserve fresh checks of every prefix without re-expanding each Windows child path.
                if(windows){require(attrs.isDirectory());noLinks(dir);}else directory(dir);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                require(attrs.isRegularFile()); noLinks(file);
                String name = cache.relativize(file).toString().replace('\\', '/'); relative(name);
                require(actual.add(name) && actual.size() <= MAX_CACHE_FILES); return FileVisitResult.CONTINUE;
            }
        });
        require(actual.equals(new TreeSet<>(expected.stream().map(CacheFile::path).toList())));
        for (var entry : expected) {
            Path file = cache.resolve(entry.path()); noLinks(file);
            var before = attributes(file); require(before.isRegularFile() && before.size() == entry.bytes());
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            long count = 0;
            try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 var stream = java.nio.channels.Channels.newInputStream(channel)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = stream.read(buffer)) != -1) { count += n; require(count <= entry.bytes()); digest.update(buffer, 0, n); }
            }
            noLinks(file); var after = attributes(file);
            require(count == entry.bytes() && before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime())
                    && Objects.equals(before.fileKey(), after.fileKey()) && after.isRegularFile()
                    && HexFormat.of().formatHex(digest.digest()).equals(entry.sha256()));
        }
    }
    private static BasicFileAttributes attributes(Path path) throws IOException { return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
    private static byte[] readSmall(Path project, Path file) throws IOException {
        require(file.toAbsolutePath().normalize().startsWith(project)); noLinks(file);
        var before = attributes(file); require(before.isRegularFile() && before.size() <= MAX_SPEC_BYTES);
        byte[] bytes;
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var stream = java.nio.channels.Channels.newInputStream(channel)) { bytes = stream.readNBytes(MAX_SPEC_BYTES + 1); }
        noLinks(file); var after = attributes(file);
        require(bytes.length <= MAX_SPEC_BYTES && bytes.length == before.size() && after.isRegularFile()
                && before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime())
                && Objects.equals(before.fileKey(), after.fileKey())); return bytes;
    }
    private static Path directory(Path path) throws IOException {
        require(path != null && path.isAbsolute() && path.equals(path.normalize())); noLinks(path);
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && path.equals(path.toRealPath())); return path;
    }
    private static void noLinks(Path path) throws IOException {
        boolean windows=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if(windows) {
                // OpenJDK 21 Windows NOFOLLOW attributes mark junctions/reparse points as isOther.
                // Inspect EVERY prefix before/after each read; avoid O(depth^2) real-path expansion.
                var attrs=attributes(current);require(!attrs.isSymbolicLink() && !attrs.isOther());
            } else require(!Files.isSymbolicLink(current) && !SafePaths.isReparseRedirect(current));
        }
    }
    private static void relative(String name) {
        require(name != null && name.length() <= 256 && !name.startsWith("/") && !name.contains(":"));
        String[] parts = name.split("/", -1); require(parts.length <= 32);
        for (String part : parts) require(part.matches("[A-Za-z0-9_.-]+") && !part.equals(".") && !part.equals(".."));
    }
    private static void fields(JsonNode node, String... names) {
        require(node != null && node.isObject()); var keys = new HashSet<String>(); node.fieldNames().forEachRemaining(keys::add);
        require(keys.equals(Set.of(names)));
    }
    private static void array(JsonNode node, int min, int max) { require(node != null && node.isArray() && node.size() >= min && node.size() <= max); }
    private static String text(JsonNode node) { require(node != null && node.isTextual() && !node.textValue().isBlank()); return node.textValue(); }
    private static String hash(JsonNode node) { String value = text(node); require(value.matches("[a-f0-9]{64}")); return value; }
    private static long number(JsonNode node, long min, long max) { require(node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= min && node.longValue() <= max); return node.longValue(); }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid task execution specification"); }
}
