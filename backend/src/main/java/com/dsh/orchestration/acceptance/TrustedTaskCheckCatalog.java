package com.dsh.orchestration.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * M1C1 internal control-side reader, deliberately absent from tools/controllers.
 * Caller must supply a server-controlled root and ALL project/workspace exclusion roots.
 * This parser proves content identity; it cannot authorize a caller or confirm user intent.
 */
public final class TrustedTaskCheckCatalog {
    private static final int MAX_CATALOG_BYTES = 65536, MAX_VERIFIER_BYTES = 1048576, MAX_TOTAL_BYTES = 4194304;
    private static final String VERSION = "dsh-task-checks-v1";
    private TrustedTaskCheckCatalog() { }

    public record Verifier(String path, String sha256) { }
    public record Check(String id, List<String> argv, int timeoutSeconds, boolean expectTests,
                        int minPassed, List<Verifier> verifiers) {
        public Check { argv = List.copyOf(argv); verifiers = List.copyOf(verifiers); }
    }

    /** Lists and exported bytes cannot mutate the frozen definition. No host path is exported. */
    public static final class Snapshot {
        private final String projectId, registryDigest, canonicalDigest, imageDigest, policyDigest;
        private final Map<String, Check> checks;
        private final Map<String, JsonNode> definitions;
        private final Map<String, byte[]> verifierBytes;
        private Snapshot(String projectId, byte[] source, JsonNode catalog, Map<String, Check> checks,
                         Map<String, JsonNode> definitions, Map<String, byte[]> bytes) {
            this.projectId = projectId; registryDigest = CanonicalTaskJson.sha256(source);
            canonicalDigest = CanonicalTaskJson.digest(catalog); imageDigest = catalog.get("imageDigest").textValue();
            policyDigest = CanonicalTaskJson.digest(catalog.get("executionPolicy"));
            this.checks = Collections.unmodifiableMap(new TreeMap<>(checks));
            this.definitions = Map.copyOf(definitions); this.verifierBytes = Map.copyOf(bytes);
        }
        public String projectId() { return projectId; }
        public String registryDigest() { return registryDigest; }
        public String canonicalDigest() { return canonicalDigest; }
        public String imageDigest() { return imageDigest; }
        public String policyDigest() { return policyDigest; }
        public List<Check> checks() { return List.copyOf(checks.values()); }
        public List<Check> select(List<String> refs) {
            require(refs != null && !refs.isEmpty() && refs.size() <= 8);
            TreeSet<String> selected = new TreeSet<>();
            for (String ref : refs) require(ref != null && checks.containsKey(ref) && selected.add(ref));
            return selected.stream().map(checks::get).toList();
        }
        public String checksDigest(List<String> refs) {
            ArrayNode selected = JsonNodeFactory.instance.arrayNode();
            select(refs).forEach(check -> selected.add(definitions.get(check.id())));
            return CanonicalTaskJson.digest(selected);
        }
        public byte[] verifierBytes(String checkId, String relativePath) {
            require(checks.containsKey(checkId) && checks.get(checkId).verifiers().stream().anyMatch(v -> v.path().equals(relativePath)));
            return verifierBytes.get(relativePath).clone();
        }
    }

    public static Snapshot load(Path controlRoot, String projectId, List<Path> excludedRoots) {
        try {
            require(projectId != null && projectId.matches("[A-Za-z0-9_-]{1,128}"));
            Path root = directory(controlRoot);
            require(excludedRoots != null && !excludedRoots.isEmpty());
            for (Path excluded : excludedRoots) {
                Path resolved = directory(excluded);
                require(!root.startsWith(resolved) && !resolved.startsWith(root));
            }
            Path project = directory(root.resolve(projectId));
            Path catalogFile = project.resolve("task-checks.json");
            byte[] source = readFile(project, catalogFile, MAX_CATALOG_BYTES);
            JsonNode catalog = CanonicalTaskJson.parse(source);
            fields(catalog, "version", "projectId", "imageDigest", "executionPolicy", "checks");
            require(VERSION.equals(text(catalog.get("version"), 64)) && projectId.equals(text(catalog.get("projectId"), 128)));
            hash(catalog.get("imageDigest"));
            validatePolicy(catalog.get("executionPolicy"));
            JsonNode entries = catalog.get("checks"); array(entries, 1, 32);
            Map<String, Check> checks = new TreeMap<>(); Map<String, JsonNode> definitions = new HashMap<>();
            Map<String, byte[]> bytes = new HashMap<>(); int totalBytes = 0, dependencies = 0;
            for (JsonNode entry : entries) {
                fields(entry, "id", "argv", "timeoutSeconds", "expectTests", "minPassed", "verifiers");
                String id = text(entry.get("id"), 64); require(id.matches("[A-Za-z0-9_-]+") && !checks.containsKey(id));
                JsonNode argv = entry.get("argv"); array(argv, 1, 32); List<String> args = new ArrayList<>();
                for (JsonNode argument : argv) { String arg = text(argument, 512); require(arg.codePoints().noneMatch(c -> c < 32 || c == 127)); args.add(arg); }
                int timeout = integer(entry.get("timeoutSeconds"), 1, 600);
                require(entry.get("expectTests").isBoolean() && entry.get("expectTests").booleanValue());
                int minPassed = integer(entry.get("minPassed"), 1, 1000000);
                JsonNode dependenciesNode = entry.get("verifiers"); array(dependenciesNode, 1, 16);
                List<Verifier> verifiers = new ArrayList<>(); Set<String> uniquePaths = new HashSet<>();
                for (JsonNode dependency : dependenciesNode) {
                    fields(dependency, "path", "sha256"); String name = text(dependency.get("path"), 240);
                    require(validRelativePath(name) && !name.equals("task-checks.json") && uniquePaths.add(name));
                    String digest = hash(dependency.get("sha256")); byte[] content = readFile(project, project.resolve(name), MAX_VERIFIER_BYTES);
                    require(CanonicalTaskJson.sha256(content).equals(digest));
                    totalBytes += content.length; require(totalBytes <= MAX_TOTAL_BYTES && ++dependencies <= 128);
                    byte[] previous = bytes.putIfAbsent(name, content); require(previous == null || Arrays.equals(previous, content));
                    verifiers.add(new Verifier(name, digest));
                }
                Check check = new Check(id, args, timeout, true, minPassed, verifiers);
                checks.put(id, check); definitions.put(id, entry.deepCopy());
            }
            // Detect mutation during collection; future consumers MUST also reload before use/confirmation.
            require(Arrays.equals(source, readFile(project, catalogFile, MAX_CATALOG_BYTES)));
            for (var entry : bytes.entrySet()) require(Arrays.equals(entry.getValue(), readFile(project, project.resolve(entry.getKey()), MAX_VERIFIER_BYTES)));
            return new Snapshot(projectId, source, catalog, checks, definitions, bytes);
        } catch (IOException | SecurityException invalid) {
            throw new IllegalArgumentException("Invalid task check catalog");
        }
    }

    public static void requireCurrent(Path controlRoot, List<Path> excludedRoots, Snapshot frozen) {
        require(frozen != null);
        Snapshot current = load(controlRoot, frozen.projectId(), excludedRoots);
        // Whole source identity covers every check, and load already validates every dependency's bytes.
        // Do not apply the per-task selection limit to a complete (up to 32-check) registry.
        require(current.registryDigest().equals(frozen.registryDigest()));
    }

    private static void validatePolicy(JsonNode policy) {
        fields(policy, "network", "rootFilesystem", "user", "capabilities", "candidateMount", "verifierMount", "noNewPrivileges");
        require("none".equals(text(policy.get("network"), 32)) && "read-only".equals(text(policy.get("rootFilesystem"), 32))
                && "1000:1000".equals(text(policy.get("user"), 32)) && "read-only".equals(text(policy.get("candidateMount"), 32))
                && "read-only".equals(text(policy.get("verifierMount"), 32)));
        array(policy.get("capabilities"), 0, 0);
        require(policy.get("noNewPrivileges").isBoolean() && policy.get("noNewPrivileges").booleanValue());
    }
    private static boolean validRelativePath(String value) {
        if (value.startsWith("/") || value.contains("\\") || value.contains(":")) return false;
        for (String segment : value.split("/", -1)) if (!segment.matches("[A-Za-z0-9_.-]+") || segment.equals(".") || segment.equals("..")) return false;
        return true;
    }
    private static Path directory(Path value) throws IOException {
        require(value != null);
        Path absolute = value.toAbsolutePath().normalize(); noLinks(absolute);
        require(Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) && absolute.equals(absolute.toRealPath()));
        return absolute;
    }
    private static void noLinks(Path file) throws IOException {
        Path current = file.getRoot();
        for (Path part : file) { current = current.resolve(part); require(!Files.isSymbolicLink(current)); }
    }
    private static byte[] readFile(Path root, Path path, int max) throws IOException {
        Path normalized = path.toAbsolutePath().normalize(); require(normalized.startsWith(root)); noLinks(normalized);
        BasicFileAttributes attrs = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(attrs.isRegularFile() && attrs.size() <= max && normalized.equals(normalized.toRealPath()));
        // Bounded read even if the file grows after the size check; no follow on the final component.
        try (var channel = Files.newByteChannel(normalized, Set.of(java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var stream = java.nio.channels.Channels.newInputStream(channel)) {
            byte[] content = stream.readNBytes(max + 1); require(content.length <= max); return content;
        }
    }
    private static void fields(JsonNode value, String... names) {
        require(value != null && value.isObject()); Set<String> keys = new HashSet<>(); value.fieldNames().forEachRemaining(keys::add);
        require(keys.equals(Set.of(names)));
    }
    private static void array(JsonNode value, int min, int max) { require(value != null && value.isArray() && value.size() >= min && value.size() <= max); }
    private static String text(JsonNode value, int max) {
        require(value != null && value.isTextual()); String text = value.textValue();
        require(!text.isBlank() && text.codePointCount(0, text.length()) <= max); return text;
    }
    private static String hash(JsonNode value) { String digest = text(value, 64); require(digest.matches("[0-9a-f]{64}")); return digest; }
    private static int integer(JsonNode value, int min, int max) { require(value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= min && value.intValue() <= max); return value.intValue(); }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid task check catalog"); }
}
