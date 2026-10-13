package com.dsh.orchestration.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrustedTaskExecutionSpecTest {
    final ObjectMapper json = new ObjectMapper();
    @TempDir Path temp;
    Path control, project, workspace, privateProject, descriptor, catalogFile, cache, dependency;
    ObjectNode catalog, spec;
    List<Path> excluded;
    @BeforeEach void prepare() throws Exception {
        control=Files.createDirectory(temp.resolve("control")); project=Files.createDirectory(temp.resolve("project"));
        workspace=Files.createDirectory(temp.resolve("workspace")); excluded=List.of(project,workspace);
        privateProject=Files.createDirectory(control.resolve("project_fixture"));
        catalogFile=privateProject.resolve("task-checks.json"); descriptor=privateProject.resolve("task-execution.json");
        cache=Files.createDirectory(privateProject.resolve("runtime-cache")); dependency=cache.resolve("dep.jar");
        Files.writeString(dependency,"fake dependency bytes: input validation only");
        catalog=(ObjectNode)json.readTree(getClass().getResourceAsStream("/m1c1/catalog.json"));
        var check=(ObjectNode)catalog.get("checks").get(0);
        check.putArray("argv").add("sh").add("/verifier/run-check.sh").add("normal");
        check.put("timeoutSeconds",35).put("minPassed",3);
        var verifiers=check.putArray("verifiers");
        Files.createDirectory(privateProject.resolve("tests"));
        for(String name:List.of("pom.xml","run-check.sh","tests/TrustedTest.java")) {
            Path file=privateProject.resolve(name);Files.writeString(file,"trusted fixture "+name);
            verifiers.addObject().put("path",name).put("sha256",CanonicalTaskJson.sha256(Files.readAllBytes(file)));
        }
        saveCatalog();
        spec=json.createObjectNode().put("version",TrustedTaskExecutionSpec.VERSION).put("projectId","project_fixture")
                .put("registryDigest",CanonicalTaskJson.sha256(Files.readAllBytes(catalogFile)))
                .put("imageDigest",catalog.get("imageDigest").asText()).put("adapterId",TrustedTaskExecutionSpec.ADAPTER);
        spec.set("executionPolicy",catalog.get("executionPolicy").deepCopy());
        spec.putObject("limits").put("cpuMillis",1000).put("memoryBytes",805306368).put("pids",128)
                .put("tmpfsBytes",268435456).put("logBytes",1048576).put("reportFiles",64)
                .put("reportFileBytes",1048576).put("reportTotalBytes",4194304);
        var report=spec.putObject("report").put("format","MAVEN_SUREFIRE").put("policy","surefire-reports-v1")
                .put("transport","BOUNDED_DOCKER_STDOUT_BYTES_V1").put("lifecycle","MAVEN_EXIT_CAPTURE_RELEASE_V1");
        report.putArray("directories").add("target/surefire-reports");
        spec.putArray("cacheManifest").addObject().put("path","dep.jar").put("bytes",Files.size(dependency))
                .put("sha256",CanonicalTaskJson.sha256(Files.readAllBytes(dependency)));
        spec.putArray("checkRefs").add("slugify-semantic");save();
    }
    void save() throws Exception { Files.write(descriptor,json.writeValueAsBytes(spec)); }
    void saveCatalog() throws Exception { Files.write(catalogFile,json.writeValueAsBytes(catalog)); }
    void bindCatalog() throws Exception { saveCatalog();spec.put("registryDigest",CanonicalTaskJson.sha256(Files.readAllBytes(catalogFile)));save(); }
    ObjectNode dep() { return (ObjectNode)spec.get("cacheManifest").get(0); }
    TrustedTaskExecutionSpec.Snapshot load() { return TrustedTaskExecutionSpec.load(control,"project_fixture",excluded); }
    void refused() { assertThrows(IllegalArgumentException.class,this::load); }

    @Test void actualIdentityAndFrozenExports() throws Exception {
        var frozen=load(); assertEquals("project_fixture",frozen.projectId());assertEquals(cache,frozen.cacheRoot());
        assertEquals(CanonicalTaskJson.sha256(Files.readAllBytes(catalogFile)),frozen.registryDigest());
        assertEquals(CanonicalTaskJson.sha256(Files.readAllBytes(descriptor)),frozen.sourceDigest());
        assertEquals(new String(CanonicalTaskJson.encode(spec),java.nio.charset.StandardCharsets.UTF_8),frozen.canonicalDefinition());
        assertEquals(3,frozen.checks().getFirst().minPassed());assertEquals(35,frozen.checks().getFirst().timeoutSeconds());
        assertEquals(805306368,frozen.limits().memoryBytes());
        assertEquals(List.of("target/surefire-reports"),frozen.report().directories());
        assertThrows(UnsupportedOperationException.class,()->frozen.cacheFiles().clear());
        assertThrows(UnsupportedOperationException.class,()->frozen.checks().clear());
        assertThrows(UnsupportedOperationException.class,()->frozen.checks().getFirst().argv().clear());
        assertThrows(UnsupportedOperationException.class,()->frozen.report().directories().clear());
        assertThrows(UnsupportedOperationException.class,()->frozen.select(List.of("slugify-semantic")).checks().clear());
        TrustedTaskExecutionSpec.requireCurrent(control,excluded,frozen);
    }
    @Test void selectionIsExactSortedAndSupportsWholeCatalogBeyondEight() throws Exception {
        var template=catalog.get("checks").get(0).deepCopy();var checks=catalog.putArray("checks");var refs=spec.putArray("checkRefs");
        for(int i=0;i<9;i++){var c=(ObjectNode)template.deepCopy();c.put("id","check_"+i);checks.add(c);refs.add("check_"+i);}bindCatalog();
        var frozen=load();assertEquals(9,frozen.checks().size());
        assertEquals(frozen.select(List.of("check_2","check_0")).digest(),frozen.select(List.of("check_0","check_2")).digest());
        assertNotEquals(frozen.select(List.of("check_0")).digest(),frozen.select(List.of("check_0","check_2")).digest());
        assertEquals(8,frozen.select(java.util.stream.IntStream.range(0,8).mapToObj(i->"check_"+i).toList()).checks().size());
        for(List<String> bad:List.of(List.<String>of(),List.of("missing"),List.of("check_0","check_0"),java.util.stream.IntStream.range(0,9).mapToObj(i->"check_"+i).toList()))
            assertThrows(IllegalArgumentException.class,()->frozen.select(bad));
        assertThrows(IllegalArgumentException.class,()->frozen.select(null));
        TrustedTaskExecutionSpec.requireCurrent(control,excluded,frozen);
    }
    @Test void descriptorWhitespaceNeedsNewConfirmationEvenWithSameCanonicalDefinition() throws Exception {
        var frozen=load();Files.writeString(descriptor,Files.readString(descriptor)+"\n");var changed=load();
        assertEquals(frozen.canonicalDefinition(),changed.canonicalDefinition());assertNotEquals(frozen.executionSpecDigest(),changed.executionSpecDigest());
        assertThrows(IllegalArgumentException.class,()->TrustedTaskExecutionSpec.requireCurrent(control,excluded,frozen));
    }
    @Test void registryDriftCannotBeAutomaticallyRebound() throws Exception {
        var frozen=load();Files.writeString(catalogFile,Files.readString(catalogFile)+"\n");refused();
        spec.put("registryDigest",CanonicalTaskJson.sha256(Files.readAllBytes(catalogFile)));save();
        assertNotEquals(frozen.executionSpecDigest(),load().executionSpecDigest());
        assertThrows(IllegalArgumentException.class,()->TrustedTaskExecutionSpec.requireCurrent(control,excluded,frozen));
    }
    @Test void verifierActualBytesStillRequired() throws Exception {
        var frozen=load();Files.writeString(privateProject.resolve("pom.xml"),"changed trusted verifier");refused();
        assertThrows(IllegalArgumentException.class,()->TrustedTaskExecutionSpec.requireCurrent(control,excluded,frozen));
    }
    @Test void changedDependencyRequiresExplicitNewManifestNotCachedSuccess() throws Exception {
        var frozen=load();byte[] changed=Files.readAllBytes(dependency);changed[0]^=1;Files.write(dependency,changed);refused();
        dep().put("sha256",CanonicalTaskJson.sha256(changed));save();assertNotEquals(frozen.executionSpecDigest(),load().executionSpecDigest());
        assertThrows(IllegalArgumentException.class,()->TrustedTaskExecutionSpec.requireCurrent(control,excluded,frozen));
        assertNotEquals(frozen.cacheFiles().getFirst().sha256(),load().cacheFiles().getFirst().sha256());
    }
    @Test void completeMultiFileManifestMustBeSortedAndFrozen() throws Exception {
        var frozen=load();var nested=Files.createDirectory(cache.resolve("plugins"));Path file=nested.resolve("check.jar");
        Files.writeString(file,"second synthetic dependency");
        var other=json.createObjectNode().put("path","plugins/check.jar").put("bytes",Files.size(file))
                .put("sha256",CanonicalTaskJson.sha256(Files.readAllBytes(file)));
        var first=dep().deepCopy();spec.putArray("cacheManifest").add(other).add(first);save();refused();
        spec.putArray("cacheManifest").add(first).add(other);save();var changed=load();assertEquals(2,changed.cacheFiles().size());
        assertNotEquals(frozen.executionSpecDigest(),changed.executionSpecDigest());
        assertThrows(IllegalArgumentException.class,()->TrustedTaskExecutionSpec.requireCurrent(control,excluded,frozen));
    }
    @Test void cacheSizeLimitsApplyBeforeLoadingPayloads() throws Exception {
        dep().put("bytes",64L*1024*1024+1);save();refused();
        var entries=spec.putArray("cacheManifest");
        for(int i=0;i<5;i++)entries.addObject().put("path","dep-"+i+".jar").put("bytes",64L*1024*1024).put("sha256","0".repeat(64));
        save();refused();
    }
    @Test void relocatedControlledOriginDoesNotReuseExistingSnapshot() throws Exception {
        var frozen=load();Path relocated=temp.resolve("relocated-control");Files.move(control,relocated);
        var current=TrustedTaskExecutionSpec.load(relocated,"project_fixture",excluded);
        assertEquals(frozen.executionSpecDigest(),current.executionSpecDigest());
        assertThrows(IllegalArgumentException.class,()->TrustedTaskExecutionSpec.requireCurrent(relocated,excluded,frozen));
    }
    @ParameterizedTest @ValueSource(strings={"missing","extra","wrong-size","wrong-sha","directory","duplicate","case-alias","empty"})
    void completeActualCacheCannotHideOrInventFiles(String kind) throws Exception {
        switch(kind){
            case "missing"->Files.delete(dependency);
            case "extra"->Files.writeString(cache.resolve("unlisted.jar"),"not frozen");
            case "wrong-size"->dep().put("bytes",Files.size(dependency)+1);
            case "wrong-sha"->dep().put("sha256","0".repeat(64));
            case "directory"->{Files.delete(dependency);Files.createDirectory(dependency);}
            case "duplicate"->((com.fasterxml.jackson.databind.node.ArrayNode)spec.get("cacheManifest")).add(dep().deepCopy());
            case "case-alias"->{var other=dep().deepCopy();other.put("path","DEP.jar");Files.copy(dependency,cache.resolve("DEP.jar"));var a=spec.putArray("cacheManifest");a.add(other);a.add(depCopy("dep.jar"));}
            case "empty"->spec.putArray("cacheManifest");
        }
        save();refused();
    }
    ObjectNode depCopy(String name) throws Exception { return json.createObjectNode().put("path",name).put("bytes",Files.size(dependency)).put("sha256",CanonicalTaskJson.sha256(Files.readAllBytes(dependency))); }
    @ParameterizedTest @ValueSource(strings={"../outside.jar","/tmp/outside.jar","C:/secret","a\\b.jar","a//b.jar","./dep.jar","a/../dep.jar","x:"})
    void dependencyPathsCannotEscape(String path) throws Exception { dep().put("path",path);save();refused(); }
    @ParameterizedTest @ValueSource(strings={"version","projectId","registryDigest","imageDigest","executionPolicy","adapterId","limits","report","cacheManifest","checkRefs"})
    void missingDescriptorFieldsNeverDefault(String field) throws Exception { spec.remove(field);save();refused(); }
    @ParameterizedTest @ValueSource(strings={"command","producer","trusted","executionAvailable"})
    void claimsDoNotAddAuthority(String field) throws Exception { spec.put(field,true);save();refused(); }
    @ParameterizedTest @ValueSource(strings={"version","projectId","imageDigest","registryDigest","adapterId","format","policy","transport","lifecycle","directories","checkRefs"})
    void unsupportedIdentityAndReportPolicyRejected(String field) throws Exception {
        if(List.of("format","policy","transport","lifecycle").contains(field))((ObjectNode)spec.get("report")).put(field,"unsupported");
        else if(field.equals("directories"))((ObjectNode)spec.get("report")).putArray(field).add("../candidate-report");
        else if(field.equals("checkRefs"))spec.putArray(field).add("other");
        else spec.put(field,"unsupported");save();refused();
    }
    @ParameterizedTest @ValueSource(strings={"cpuMillis","memoryBytes","pids","tmpfsBytes","logBytes","reportFiles","reportFileBytes","reportTotalBytes"})
    void resourceAndCollectionBoundsCannotBeDisabled(String field) throws Exception {
        var limits=(ObjectNode)spec.get("limits");var original=limits.get(field).deepCopy();
        for(var bad:List.of(json.readTree("0"),json.readTree("-1"),json.readTree("true"),json.readTree("1.5"),json.readTree("\"1000\""),json.readTree("9007199254740991"))){limits.set(field,bad);save();refused();}
        limits.set(field,original);save();assertDoesNotThrow(this::load);
    }
    @Test void policyAndNestedFieldsRemainStrict() throws Exception {
        var original=spec.deepCopy();
        ((ObjectNode)spec.get("executionPolicy")).put("network","host");save();refused();
        for(String section:List.of("limits","report")){spec=original.deepCopy();((ObjectNode)spec.get(section)).put("unknown",true);save();refused();}
        spec=original.deepCopy();dep().put("producer","model");save();refused();
        spec=original.deepCopy();((ObjectNode)spec.get("limits")).put("reportTotalBytes",1024);save();refused();
    }
    @Test void unsupportedArgvAndMissingTrustedBuildFilesRefused() throws Exception {
        var check=(ObjectNode)catalog.get("checks").get(0);check.putArray("argv").add("mvn").add("-f").add("/candidate/pom.xml");bindCatalog();refused();
        check.putArray("argv").add("sh").add("/verifier/run-check.sh").add("normal");
        ((com.fasterxml.jackson.databind.node.ArrayNode)check.get("verifiers")).remove(0);bindCatalog();refused();
    }
    @Test void strictJsonUtf8AndDescriptorBounds() throws Exception {
        String valid=Files.readString(descriptor);
        for(String raw:List.of(valid+" {}",valid.replaceFirst("\\{","{\"version\":\"duplicate\","),valid.replace("MAVEN_JUNIT_SOURCE_V1","MAVEN_JUNIT_SOURCE_V1\\u0000"))){Files.writeString(descriptor,raw);refused();}
        Files.write(descriptor,new byte[]{(byte)0xc3,(byte)0x28});refused();
        Files.writeString(descriptor," ".repeat(512*1024+1));refused();
    }
    @Test void excludeEntireProjectsAndWorkspaceAndRejectUnspecifiedBoundary() throws Exception {
        var frozen=load();
        for(List<Path> roots:List.of(List.<Path>of(),List.of(temp),List.of(control),List.of(privateProject)))
            assertThrows(IllegalArgumentException.class,()->TrustedTaskExecutionSpec.load(control,"project_fixture",roots));
        assertThrows(IllegalArgumentException.class,()->TrustedTaskExecutionSpec.load(control.resolve(".."),"project_fixture",excluded));
        assertThrows(IllegalArgumentException.class,()->TrustedTaskExecutionSpec.load(control,"../project_fixture",excluded));
        assertThrows(IllegalArgumentException.class,()->TrustedTaskExecutionSpec.requireCurrent(project,excluded,frozen));
    }
    @ParameterizedTest @ValueSource(strings={"descriptor","dependency","cache","control","nested-directory"})
    void realSymlinksNotTrustedInputs(String target) throws Exception {
        Path outside=Files.createDirectory(temp.resolve("outside"));
        switch(target){
            case "descriptor"->{Path f=outside.resolve("spec.json");Files.copy(descriptor,f);Files.delete(descriptor);Files.createSymbolicLink(descriptor,f);}
            case "dependency"->{Path f=outside.resolve("dep.jar");Files.copy(dependency,f);Files.delete(dependency);Files.createSymbolicLink(dependency,f);}
            case "cache"->{Files.move(cache,outside.resolve("cache"));Files.createSymbolicLink(cache,outside.resolve("cache"));}
            case "control"->{Files.move(control,outside.resolve("control"));Files.createSymbolicLink(control,outside.resolve("control"));}
            case "nested-directory"->Files.createSymbolicLink(cache.resolve("plugin-redirect"),outside);
        }
        refused();
    }
}
