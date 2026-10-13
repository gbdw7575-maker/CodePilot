package com.dsh.orchestration.acceptance;

import com.dsh.api.dto.TaskCheckPreview.*;
import com.dsh.api.dto.TaskCheckSelectionPreview;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskCheckRegistrationBindingTest {
    @TempDir Path temp;
    static final ObjectMapper mapper = new ObjectMapper();
    static TaskCheckSelectionPreview fixture(Path temp, String planId) throws Exception {
        Path control=Files.createDirectory(temp.resolve("control")), project=Files.createDirectory(temp.resolve("project")),
                workspace=Files.createDirectory(temp.resolve("workspace")), dir=Files.createDirectory(control.resolve("project_fixture"));
        try (var input=TaskCheckRegistrationBindingTest.class.getResourceAsStream("/m1c1/catalog.json")) {
            Files.copy(input, dir.resolve("task-checks.json"));
        }
        Files.writeString(dir.resolve("check_slugify.py"),"assert 2 + 2 == 4\n");
        var catalog=TrustedTaskCheckCatalog.load(control,"project_fixture",List.of(project,workspace));
        var source=mock(TaskCheckPreviewSource.class); var host=mock(HostTaskCheckCatalog.class);
        var snapshot=new TaskCheckPreviewSource.Snapshot("project_fixture","run_fixture","turn_fixture",planId,1,"RUNNING","row_transient","DRAFT",
                new Definition("A",1,"修复",List.of("正确行为"),"补丁",List.of(),List.of(),null,"DELEGATED",true),
                List.of(new Requirement("msg_fixture","9007199254740993","  用户原文😀\n约束  ")));
        when(source.read("run_fixture",planId,"A")).thenReturn(snapshot); when(host.load("project_fixture")).thenReturn(catalog);
        var previews=new TaskCheckPreviewService(source,host);
        return new TaskCheckSelectionPreviewService(previews).preview("run_fixture",planId,"A",
                "\"task-check-preview-v1-"+previews.preview("run_fixture",planId,"A").previewDigest()+"\"",List.of("slugify-semantic"));
    }
    /** Synthetic drift inputs, rehash observations so tests exercise full binding rather than stale ETag only. */
    static TaskCheckSelectionPreview changed(TaskCheckSelectionPreview input, Consumer<ObjectNode> mutate) {
        ObjectNode node=mapper.valueToTree(input); mutate.accept(node); var p=(ObjectNode)node.get("taskPreview");
        p.put("taskDefinitionDigest",CanonicalTaskJson.digest(p.get("task")));
        p.put("requirementsDigest",CanonicalTaskJson.digest(p.get("requirements")));
        var base=mapper.createObjectNode().put("version","task-check-preview-v1");
        for(String field:List.of("projectId","rootRunId","turnId","planId","planRevision","taskState","taskDefinitionDigest","requirementsDigest","registryDigest")) base.set(field,p.get(field));
        p.put("previewDigest",CanonicalTaskJson.digest(base));
        node.put("checksDigest",CanonicalTaskJson.digest(node.get("selectedChecks")));
        var selection=mapper.createObjectNode().put("version","task-check-selection-v1")
                .put("previewDigest",p.path("previewDigest").asText()).put("checksDigest",node.path("checksDigest").asText());
        selection.set("checkRefs",node.get("checkRefs")); node.put("selectionDigest",CanonicalTaskJson.digest(selection));
        return mapper.convertValue(node,TaskCheckSelectionPreview.class);
    }
    TaskCheckSelectionPreview fixture() throws Exception { return fixture(temp,"plan_fixture"); }
    @Test void realPreviewRoundTripPreservesRawUnicodeAndLargeSequence() throws Exception {
        var binding=TaskCheckRegistrationBinding.from(fixture()); var read=TaskCheckRegistrationBinding.decode(binding.canonicalSnapshot);
        assertEquals(binding.canonicalBinding,read.canonicalBinding);assertEquals(binding.snapshotDigest,read.snapshotDigest);
        assertTrue(read.canonicalBinding.contains("9007199254740993"));assertTrue(read.canonicalBinding.contains("用户原文😀"));
    }
    @Test void observationChangesDoNotRebindUntouchedTask() throws Exception {
        var p=fixture();var initial=TaskCheckRegistrationBinding.from(p);
        var later=TaskCheckRegistrationBinding.from(changed(p,n->{var task=(ObjectNode)n.get("taskPreview");task.put("planRevision",2).put("taskState","PENDING");}));
        assertEquals(initial.bindingDigest,later.bindingDigest);assertNotEquals(initial.snapshotDigest,later.snapshotDigest);
    }
    @ParameterizedTest @ValueSource(strings={"goal","acceptance","deliverable","dependsOn","inputRefs","resourceScope","executionMode","required","taskVersion"})
    void everyTaskDefinitionFieldChangesBinding(String field) throws Exception {
        var p=fixture();var changed=changed(p,n->{var task=(ObjectNode)n.path("taskPreview").path("task");
            switch(field){case "goal","deliverable"->task.put(field,"新内容");case "acceptance"->task.putArray(field).add("不同标准");
                case "dependsOn"->task.putArray(field).add("B");case "inputRefs"->task.putArray(field).addObject().put("taskId","B").put("output","deliverable");
                case "resourceScope"->{var scope=task.putObject(field);scope.putArray("read").add("src");scope.putArray("write");}
                case "executionMode"->task.put(field,"INLINE");case "required"->task.put(field,false);case "taskVersion"->task.put(field,2);default->throw new AssertionError();}});
        assertNotEquals(TaskCheckRegistrationBinding.from(p).bindingDigest,TaskCheckRegistrationBinding.from(changed).bindingDigest);
    }
    @ParameterizedTest @ValueSource(strings={"projectId","rootRunId","turnId","planId","registryDigest","imageDigest","executionPolicyDigest"})
    void scopeCatalogImageAndPolicyChangeBinding(String field) throws Exception {
        var p=fixture();var other=changed(p,n->((ObjectNode)n.get("taskPreview")).put(field,field.endsWith("Digest")?"f".repeat(64):"other_identity"));
        assertNotEquals(TaskCheckRegistrationBinding.from(p).bindingDigest,TaskCheckRegistrationBinding.from(other).bindingDigest);
    }
    @Test void rawRequirementAndSourceIdentityChangesAreBound() throws Exception {
        var p=fixture();for(String field:List.of("messageId","sequence","content")){
            var other=changed(p,n->((ObjectNode)n.path("taskPreview").path("requirements").get(0)).put(field,field.equals("sequence")?"9007199254740994":"其他原文或来源"));
            assertNotEquals(TaskCheckRegistrationBinding.from(p).bindingDigest,TaskCheckRegistrationBinding.from(other).bindingDigest);
        }
    }
    @Test void selectedVerifierDependencyBytesAreBound() throws Exception {
        var p=fixture();var other=changed(p,n->{for(String field:List.of("selectedChecks"))((ObjectNode)n.path(field).get(0).path("verifiers").get(0)).put("sha256","f".repeat(64));
            ((ObjectNode)n.path("taskPreview").path("availableChecks").get(0).path("verifiers").get(0)).put("sha256","f".repeat(64));});
        assertNotEquals(TaskCheckRegistrationBinding.from(p).bindingDigest,TaskCheckRegistrationBinding.from(other).bindingDigest);
    }
    @ParameterizedTest @ValueSource(strings={"previewDigest","taskDefinitionDigest","requirementsDigest"})
    void copiedButWrongBaseHashIsRejected(String field) throws Exception {
        var node=(ObjectNode)mapper.valueToTree(fixture());((ObjectNode)node.get("taskPreview")).put(field,"0".repeat(64));
        assertThrows(IllegalArgumentException.class,()->TaskCheckRegistrationBinding.from(mapper.convertValue(node,TaskCheckSelectionPreview.class)));
    }
    @Test void copiedCheckHashRefAndSelectedDefinitionAreRejected() throws Exception {
        var p=fixture();for(String field:List.of("checksDigest","selectionDigest")){
            var node=(ObjectNode)mapper.valueToTree(p);node.put(field,"0".repeat(64));
            assertThrows(IllegalArgumentException.class,()->TaskCheckRegistrationBinding.from(mapper.convertValue(node,TaskCheckSelectionPreview.class)));
        }
        var wrong=changed(p,n->n.putArray("checkRefs").add("unknown"));assertThrows(IllegalArgumentException.class,()->TaskCheckRegistrationBinding.from(wrong));
        var forged=changed(p,n->((ObjectNode)n.path("selectedChecks").get(0)).put("timeoutSeconds",10));assertThrows(IllegalArgumentException.class,()->TaskCheckRegistrationBinding.from(forged));
    }
    @Test void longOriginalRequirementHasIndependentStrictSnapshotDecoder() throws Exception {
        var p=changed(fixture(),n->((ObjectNode)n.path("taskPreview").path("requirements").get(0)).put("content","字".repeat(100000)));
        var b=TaskCheckRegistrationBinding.from(p);assertEquals(b.canonicalBinding,TaskCheckRegistrationBinding.decode(b.canonicalSnapshot).canonicalBinding);
    }
    @Test void damagedTrailingDuplicateOrNoncanonicalSnapshotIsRejected() throws Exception {
        var b=TaskCheckRegistrationBinding.from(fixture());
        for(String raw:List.of(b.canonicalSnapshot+"{}",b.canonicalSnapshot+" ","{\"status\":\"a\",\"status\":\"b\"}","null"))
            assertThrows(IllegalArgumentException.class,()->TaskCheckRegistrationBinding.decode(raw));
    }
    @Test void invalidStateVersionScopeOrRequirementBoundsAreRejected() throws Exception {
        var p=fixture();for(var change:List.<Consumer<ObjectNode>>of(n->((ObjectNode)n.get("taskPreview")).put("projectId","../secret"),
                n->((ObjectNode)n.path("taskPreview").path("task")).put("taskVersion",0),n->((ObjectNode)n.get("taskPreview")).put("planRevision",0),
                n->n.put("status","CONFIRMED"),n->((ObjectNode)n.path("taskPreview").path("requirements").get(0)).put("content"," "))){
            var input=changed(p,change);assertThrows(IllegalArgumentException.class,()->TaskCheckRegistrationBinding.from(input));
        }
    }
}
