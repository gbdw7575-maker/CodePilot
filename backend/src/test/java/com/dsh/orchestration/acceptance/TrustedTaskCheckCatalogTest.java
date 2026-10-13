package com.dsh.orchestration.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TrustedTaskCheckCatalogTest {
    private static final byte[] VERIFIER = "assert 2 + 2 == 4\n".getBytes(StandardCharsets.UTF_8);
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path temp;
    private Path control, project, workspace, catalog, verifier;
    private List<Path> excluded;
    private ObjectNode fixture;

    @BeforeEach void setup() throws Exception {
        control = Files.createDirectory(temp.resolve("control"));
        project = Files.createDirectory(temp.resolve("project"));
        workspace = Files.createDirectory(temp.resolve("workspace"));
        Path privateProject = Files.createDirectory(control.resolve("project_fixture"));
        catalog = privateProject.resolve("task-checks.json"); verifier = privateProject.resolve("check_slugify.py");
        fixture = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/m1c1/catalog.json"));
        Files.write(catalog, mapper.writeValueAsBytes(fixture)); Files.write(verifier, VERIFIER);
        excluded = List.of(project, workspace);
    }
    private TrustedTaskCheckCatalog.Snapshot load() { return TrustedTaskCheckCatalog.load(control, "project_fixture", excluded); }
    private void save() throws Exception { Files.write(catalog, mapper.writeValueAsBytes(fixture)); }
    private ObjectNode check() { return (ObjectNode) fixture.get("checks").get(0); }
    private ObjectNode dependency() { return (ObjectNode) check().get("verifiers").get(0); }

    @Test void actualFileSnapshotAndOriginalVectors() throws Exception {
        var frozen = load(); assertEquals("project_fixture", frozen.projectId());
        assertEquals(CanonicalTaskJson.sha256(Files.readAllBytes(catalog)), frozen.registryDigest());
        assertEquals(CanonicalTaskJson.digest(fixture), frozen.canonicalDigest());
        assertEquals(fixture.get("imageDigest").textValue(), frozen.imageDigest());
        assertEquals(CanonicalTaskJson.digest(fixture.get("executionPolicy")), frozen.policyDigest());
        assertArrayEquals(VERIFIER, frozen.verifierBytes("slugify-semantic", "check_slugify.py"));
        assertEquals(4, frozen.checks().getFirst().minPassed());
        TrustedTaskCheckCatalog.requireCurrent(control, excluded, frozen);
        JsonNode vectors = mapper.readTree(getClass().getResourceAsStream("/m1c1/canonical-vectors.json"));
        assertEquals(2, vectors.size());
        for (JsonNode vector : vectors) {
            byte[] actual = CanonicalTaskJson.encode(vector.get("input"));
            assertArrayEquals(vector.get("expectedUtf8").textValue().getBytes(StandardCharsets.UTF_8), actual);
            assertEquals(vector.get("sha256").textValue(), CanonicalTaskJson.sha256(actual));
        }
    }

    @Test void immutableExportsAndExactCheckSelection() {
        var frozen = load();
        assertThrows(UnsupportedOperationException.class, () -> frozen.checks().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.checks().getFirst().argv().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.checks().getFirst().verifiers().clear());
        byte[] leaked = frozen.verifierBytes("slugify-semantic", "check_slugify.py"); leaked[0] = 0;
        assertArrayEquals(VERIFIER, frozen.verifierBytes("slugify-semantic", "check_slugify.py"));
        assertThrows(IllegalArgumentException.class, () -> frozen.select(null));
        assertThrows(IllegalArgumentException.class, () -> frozen.select(List.of()));
        assertThrows(IllegalArgumentException.class, () -> frozen.select(List.of("unknown")));
        assertThrows(IllegalArgumentException.class, () -> frozen.select(List.of("slugify-semantic", "slugify-semantic")));
        assertThrows(IllegalArgumentException.class, () -> frozen.select(java.util.Collections.nCopies(9, "slugify-semantic")));
        assertThrows(IllegalArgumentException.class, () -> frozen.verifierBytes("other", "check_slugify.py"));
        assertThrows(IllegalArgumentException.class, () -> frozen.verifierBytes("slugify-semantic", "task-checks.json"));
    }

    @Test void selectedSetOrderIsStableButArgOrderAndCatalogBytesAreNot() throws Exception {
        ObjectNode other = check().deepCopy(); other.put("id", "a-first"); ((com.fasterxml.jackson.databind.node.ArrayNode) fixture.get("checks")).add(other); save();
        var first = load(); var refs = List.of("slugify-semantic", "a-first");
        assertEquals(first.checksDigest(refs), first.checksDigest(List.of("a-first", "slugify-semantic")));
        assertEquals("a-first", first.select(refs).getFirst().id());
        Files.writeString(catalog, Files.readString(catalog) + "\n");
        var whitespace = load(); assertEquals(first.canonicalDigest(), whitespace.canonicalDigest());
        assertNotEquals(first.registryDigest(), whitespace.registryDigest());
        assertThrows(IllegalArgumentException.class, () -> TrustedTaskCheckCatalog.requireCurrent(control, excluded, first));
        ((com.fasterxml.jackson.databind.node.ArrayNode) check().get("argv")).add("--new-argument"); save();
        assertNotEquals(first.checksDigest(refs), load().checksDigest(refs));
    }

    @Test void actualVerifierDriftNeverGetsReSignedOrSilentlyReused() throws Exception {
        var frozen = load(); Files.writeString(verifier, "assert False\n");
        assertThrows(IllegalArgumentException.class, this::load);
        assertThrows(IllegalArgumentException.class, () -> TrustedTaskCheckCatalog.requireCurrent(control, excluded, frozen));
        dependency().put("sha256", CanonicalTaskJson.sha256(Files.readAllBytes(verifier))); save();
        assertNotEquals(frozen.registryDigest(), load().registryDigest());
        assertThrows(IllegalArgumentException.class, () -> TrustedTaskCheckCatalog.requireCurrent(control, excluded, frozen));
        assertArrayEquals(VERIFIER, frozen.verifierBytes("slugify-semantic", "check_slugify.py"));
    }

    @Test void catalogCanContainMoreChecksThanOneTaskSelects() throws Exception {
        ObjectNode base = check().deepCopy();
        var entries = (com.fasterxml.jackson.databind.node.ArrayNode) fixture.get("checks"); entries.removeAll();
        for (int i = 0; i < 9; i++) { ObjectNode next = base.deepCopy(); next.put("id", "check_" + i); entries.add(next); }
        save(); var frozen = load(); assertEquals(9, frozen.checks().size());
        assertDoesNotThrow(() -> TrustedTaskCheckCatalog.requireCurrent(control, excluded, frozen));
        assertEquals(8, frozen.select(java.util.stream.IntStream.range(0, 8).mapToObj(i -> "check_" + i).toList()).size());
        assertThrows(IllegalArgumentException.class, () -> frozen.select(java.util.stream.IntStream.range(0, 9).mapToObj(i -> "check_" + i).toList()));
    }

    @ParameterizedTest @ValueSource(strings={"version", "projectId", "imageDigest", "executionPolicy", "checks"})
    void missingRequiredTopLevelField(String key) throws Exception { fixture.remove(key); save(); assertThrows(IllegalArgumentException.class, this::load); }

    @ParameterizedTest @ValueSource(strings={"unknown", "command", "producer", "trusted"})
    void modelClaimsCannotAddFields(String key) throws Exception { fixture.put(key, "model supplied"); save(); assertThrows(IllegalArgumentException.class, this::load); }

    @ParameterizedTest @ValueSource(strings={"id", "argv", "timeoutSeconds", "expectTests", "minPassed", "verifiers"})
    void missingRequiredCheckField(String key) throws Exception { check().remove(key); save(); assertThrows(IllegalArgumentException.class, this::load); }

    @ParameterizedTest @ValueSource(strings={"../outside.py", "/tmp/outside.py", "C:/outside.py", "a\\b.py", "a//b.py", "./b.py", "a/../b.py", "task-checks.json"})
    void dependencyPathsCannotEscapeOrReadCatalog(String value) throws Exception { dependency().put("path", value); save(); assertThrows(IllegalArgumentException.class, this::load); }

    @ParameterizedTest @ValueSource(strings={"../../project_fixture", "/project_fixture", "project fixture", "", "项目"})
    void projectIdentityIsNotAPath(String id) { assertThrows(IllegalArgumentException.class, () -> TrustedTaskCheckCatalog.load(control, id, excluded)); }

    @ParameterizedTest @ValueSource(strings={"network", "rootFilesystem", "user", "candidateMount", "verifierMount"})
    void executionPolicyCannotRelaxIsolation(String key) throws Exception { ((ObjectNode) fixture.get("executionPolicy")).put(key, "unsafe"); save(); assertThrows(IllegalArgumentException.class, this::load); }

    @Test void typesCountsAndFixedPoliciesFailClosed() throws Exception {
        ObjectNode original = fixture.deepCopy();
        for (String field : List.of("timeoutSeconds", "minPassed")) {
            for (JsonNode value : List.of(mapper.readTree("true"), mapper.readTree("1.5"), mapper.readTree("0"), mapper.readTree("\"4\""), mapper.readTree("1000001"))) {
                fixture = original.deepCopy(); check().set(field, value); save(); assertThrows(IllegalArgumentException.class, this::load, field);
            }
        }
        for (int variant = 0; variant < 12; variant++) {
            fixture = original.deepCopy();
            switch (variant) {
                case 0 -> fixture.put("version", "dsh-task-checks-v1-design");
                case 1 -> fixture.put("projectId", "other");
                case 2 -> fixture.put("imageDigest", "latest");
                case 3 -> ((ObjectNode) fixture.get("executionPolicy")).put("noNewPrivileges", false);
                case 4 -> ((com.fasterxml.jackson.databind.node.ArrayNode) fixture.get("executionPolicy").get("capabilities")).add("SYS_ADMIN");
                case 5 -> check().put("expectTests", false);
                case 6 -> ((com.fasterxml.jackson.databind.node.ArrayNode) check().get("argv")).removeAll();
                case 7 -> ((com.fasterxml.jackson.databind.node.ArrayNode) check().get("argv")).add("bad\u0000arg");
                case 8 -> ((com.fasterxml.jackson.databind.node.ArrayNode) check().get("verifiers")).removeAll();
                case 9 -> ((com.fasterxml.jackson.databind.node.ArrayNode) check().get("verifiers")).add(dependency().deepCopy());
                case 10 -> ((com.fasterxml.jackson.databind.node.ArrayNode) fixture.get("checks")).add(check().deepCopy());
                case 11 -> ((com.fasterxml.jackson.databind.node.ArrayNode) fixture.get("checks")).removeAll();
            }
            save(); assertThrows(IllegalArgumentException.class, this::load, "variant " + variant);
        }
    }

    @Test void hostControlDirectoryCannotOverlapProjectsOrWorkspaces() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> TrustedTaskCheckCatalog.load(control, "project_fixture", List.of()));
        for (Path root : List.of(project, workspace, temp)) assertThrows(IllegalArgumentException.class, () -> TrustedTaskCheckCatalog.load(root, "project_fixture", excluded));
        Path insideProject = Files.createDirectory(project.resolve("catalogs"));
        Path insideWorkspace = Files.createDirectory(workspace.resolve("catalogs"));
        for (Path root : List.of(insideProject, insideWorkspace)) assertThrows(IllegalArgumentException.class, () -> TrustedTaskCheckCatalog.load(root, "project_fixture", excluded));
        assertThrows(IllegalArgumentException.class, () -> TrustedTaskCheckCatalog.load(temp.resolve("missing"), "project_fixture", excluded));
    }

    @Test void realSymlinksAreRejectedAtEveryRelevantLevel() throws Exception {
        Path outside = temp.resolve("outside.py"); Files.write(outside, VERIFIER);
        Files.delete(verifier); Files.createSymbolicLink(verifier, outside); assertThrows(IllegalArgumentException.class, this::load);
        Files.delete(verifier); Files.write(verifier, VERIFIER);
        Path alias = temp.resolve("control-alias"); Files.createSymbolicLink(alias, control);
        assertThrows(IllegalArgumentException.class, () -> TrustedTaskCheckCatalog.load(alias, "project_fixture", excluded));
        Path projectAlias = control.resolve("project_alias"); Files.createSymbolicLink(projectAlias, catalog.getParent());
        assertThrows(IllegalArgumentException.class, () -> TrustedTaskCheckCatalog.load(control, "project_alias", excluded));
        Path originalCatalog = catalog.getParent().resolve("original.json"); Files.move(catalog, originalCatalog); Files.createSymbolicLink(catalog, originalCatalog);
        assertThrows(IllegalArgumentException.class, this::load);
    }

    @Test void absentNonRegularAndOversizeInputsFailClosed() throws Exception {
        Files.delete(verifier); assertThrows(IllegalArgumentException.class, this::load);
        Files.createDirectory(verifier); assertThrows(IllegalArgumentException.class, this::load);
        Files.delete(verifier); Files.write(verifier, new byte[1048577]); assertThrows(IllegalArgumentException.class, this::load);
        Files.write(verifier, VERIFIER); Files.write(catalog, new byte[65537]); assertThrows(IllegalArgumentException.class, this::load);
    }

    static Stream<String> invalidJson() { return Stream.of("", "{} {}", "{\"a\":1,\"a\":2}", "NaN", "{\"x\":Infinity}", "1.0", "9007199254740992", "-9007199254740992", "\"\\ud800\"", "\"\\udc00\"", "{\"中文字段\":1}", "[".repeat(18)+"0"+"]".repeat(18)); }
    @ParameterizedTest @MethodSource("invalidJson")
    void strictCanonicalParsingRejectsUnsupportedValues(String raw) { assertThrows(IllegalArgumentException.class, () -> CanonicalTaskJson.parse(raw.getBytes(StandardCharsets.UTF_8))); }

    @Test void actualCatalogDuplicateTrailingAndInvalidUtf8AreRejected() throws Exception {
        byte[] original = Files.readAllBytes(catalog);
        Files.writeString(catalog, new String(original, StandardCharsets.UTF_8).replaceFirst("\\{", "{\"version\":\"dsh-task-checks-v1\","));
        assertThrows(IllegalArgumentException.class, this::load);
        Files.writeString(catalog, new String(original, StandardCharsets.UTF_8) + " {}"); assertThrows(IllegalArgumentException.class, this::load);
        Files.write(catalog, new byte[]{(byte) 0xC3, (byte) 0x28}); assertThrows(IllegalArgumentException.class, this::load);
    }

    @Test void canonicalEscapesMatchPythonAndKeepUnicode() throws Exception {
        String input = "{\"z\":\"😀验收\\u000b\\u001f\\n\\t\\r\\b\\f\\\"\\\\/\",\"a\":9007199254740991}";
        String expected = "{\"a\":9007199254740991,\"z\":\"😀验收\\u000b\\u001f\\n\\t\\r\\b\\f\\\"\\\\/\"}";
        assertEquals(expected, new String(CanonicalTaskJson.encode(CanonicalTaskJson.parse(input.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8));
    }
}
