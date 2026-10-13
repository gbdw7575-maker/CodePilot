package com.dsh.orchestration.acceptance;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.TaskCheckPreview.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskCheckSelectionPreviewTest {
    @TempDir Path temp;Path control,catalogFile;TrustedTaskCheckCatalog.Snapshot catalog;
    TaskCheckPreviewSource sources;HostTaskCheckCatalog host;TaskCheckPreviewService previews;
    TaskCheckSelectionPreviewService selections;TaskCheckPreviewSource.Snapshot source;ObjectMapper mapper=new ObjectMapper();
    @BeforeEach void setup()throws Exception{
        control=Files.createDirectory(temp.resolve("control"));Path privateDir=Files.createDirectory(control.resolve("project_fixture"));
        Files.createDirectory(temp.resolve("project"));Files.createDirectory(temp.resolve("workspace"));
        ObjectNode document;try(var stream=getClass().getResourceAsStream("/m1c1/catalog.json")){document=(ObjectNode)mapper.readTree(stream);}
        var original=document.path("checks").get(0).deepCopy();var checks=document.putArray("checks");
        for(int i=1;i<=9;i++){var check=(ObjectNode)original.deepCopy();check.put("id","check_"+i);checks.add(check);}
        catalogFile=privateDir.resolve("task-checks.json");Files.write(catalogFile,mapper.writeValueAsBytes(document));
        Files.writeString(privateDir.resolve("check_slugify.py"),"assert 2 + 2 == 4\n");catalog=loadCatalog();
        sources=mock(TaskCheckPreviewSource.class);host=mock(HostTaskCheckCatalog.class);
        source=source("修复"," 用户原文\n约束 ");when(sources.read("run_one","plan_one","A")).thenReturn(source);
        when(host.load("project_fixture")).thenReturn(catalog);previews=new TaskCheckPreviewService(sources,host);selections=new TaskCheckSelectionPreviewService(previews);
    }
    private TrustedTaskCheckCatalog.Snapshot loadCatalog(){return TrustedTaskCheckCatalog.load(control,"project_fixture",List.of(temp.resolve("project"),temp.resolve("workspace")));}
    private TaskCheckPreviewSource.Snapshot source(String goal,String input){return new TaskCheckPreviewSource.Snapshot("project_fixture","run_one","turn_one","plan_one",1,"RUNNING","row_one","DRAFT",new Definition("A",1,goal,List.of("正确行为"),"补丁",List.of(),List.of(),null,"DELEGATED",true),List.of(new Requirement("msg_one","9007199254740993",input)));}
    private String etag(){return "\"task-check-preview-v1-"+previews.preview("run_one","plan_one","A").previewDigest()+"\"";}
    private com.dsh.api.dto.TaskCheckSelectionPreview select(String tag,List<String> refs){return selections.preview("run_one","plan_one","A",tag,refs);}
    @Test void actualDefinitionsMatchFrozenCatalogCanonicalDigestAndAreImmutable(){
        var result=select(etag(),List.of("check_2","check_1"));assertEquals("SELECTED_PREVIEW_ONLY",result.status());
        assertEquals(List.of("check_1","check_2"),result.checkRefs());assertEquals(catalog.select(result.checkRefs()),result.selectedChecks());
        assertEquals(catalog.checksDigest(result.checkRefs()),result.checksDigest());assertEquals(9,result.taskPreview().availableChecks().size());
        assertEquals(" 用户原文\n约束 ",result.taskPreview().requirements().getFirst().content());
        assertEquals("9007199254740993",result.taskPreview().requirements().getFirst().sequence());
        var binding=mapper.createObjectNode().put("version","task-check-selection-v1").put("previewDigest",result.taskPreview().previewDigest()).put("checksDigest",result.checksDigest());binding.set("checkRefs",mapper.valueToTree(result.checkRefs()));
        assertEquals(CanonicalTaskJson.digest(binding),result.selectionDigest());
        assertThrows(UnsupportedOperationException.class,()->result.checkRefs().clear());assertThrows(UnsupportedOperationException.class,()->result.selectedChecks().clear());
    }
    @Test void permutationsHaveOneIdentityAndDifferentSetsHaveDifferentIdentity(){String tag=etag();var a=select(tag,List.of("check_2","check_1"));assertEquals(a,select(tag,List.of("check_1","check_2")));assertNotEquals(a.selectionDigest(),select(tag,List.of("check_1")).selectionDigest());}
    @Test void upToEightChecksFromLargerRegistryAreAccepted(){var refs=java.util.stream.IntStream.rangeClosed(1,8).mapToObj(i->"check_"+i).toList();assertEquals(8,select(etag(),refs).selectedChecks().size());}
    static Stream<List<String>> invalidRefs(){return Stream.of(null,List.of(),List.of(""),List.of(" "),List.of("check_1","check_1"),List.of("unknown"),List.of("../check_1"),List.of("echo passed"),List.of("a".repeat(65)),List.of("check_1,check_2"),java.util.stream.IntStream.rangeClosed(1,9).mapToObj(i->"check_"+i).toList(),Arrays.asList((String)null));}
    @ParameterizedTest @MethodSource("invalidRefs") void invalidSelectionCannotBecomeAnEmptyOrReducedSet(List<String> refs){assertEquals(ErrorCode.VALIDATION_FAILED,assertThrows(ApiException.class,()->select(etag(),refs)).code());}
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" "}) void missingHeaderRequiresActualPreview(String tag){assertEquals(ErrorCode.PRECONDITION_REQUIRED,assertThrows(ApiException.class,()->select(tag,List.of("check_1"))).code());verifyNoInteractions(sources,host);}
    @ParameterizedTest @ValueSource(strings={"*","\"v1\"","W/\"task-check-preview-v1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","\"task-check-selection-v1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"","\"task-check-preview-v1-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"","\"task-check-preview-v1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\", \"v1\""})
    void invalidHeadersCannotBypassPrecondition(String tag){assertEquals(ErrorCode.VALIDATION_FAILED,assertThrows(ApiException.class,()->select(tag,List.of("check_1"))).code());verifyNoInteractions(sources,host);}
    @Test void wellFormedStaleHeaderIs412(){assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(ApiException.class,()->select("\"task-check-preview-v1-"+"0".repeat(64)+"\"",List.of("check_1"))).code());}
    @Test void sameVersionGoalChangeRequiresReread(){String tag=etag();when(sources.read("run_one","plan_one","A")).thenReturn(source("新目标"," 用户原文\n约束 "));assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(ApiException.class,()->select(tag,List.of("check_1"))).code());}
    @Test void changedRequirementsRequireReread(){String tag=etag();when(sources.read("run_one","plan_one","A")).thenReturn(source("修复","补充后的原文"));assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(ApiException.class,()->select(tag,List.of("check_1"))).code());}
    @Test void unrelatedCatalogWhitespaceStillRequiresReread()throws Exception{String tag=etag();Files.writeString(catalogFile,Files.readString(catalogFile)+"\n");when(host.load("project_fixture")).thenReturn(loadCatalog());assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(ApiException.class,()->select(tag,List.of("check_1"))).code());}
    @Test void authorityFailureIsNeverReplacedWithLocalChecks(){String tag=etag();var disabled=new HostTaskCheckCatalog("",mock(com.dsh.storage.ProjectRepository.class),mock(com.dsh.workspace.WorkspaceManager.class));selections=new TaskCheckSelectionPreviewService(new TaskCheckPreviewService(sources,disabled));assertEquals(ErrorCode.SERVICE_UNAVAILABLE,assertThrows(ApiException.class,()->select(tag,List.of("check_1"))).code());}
}
