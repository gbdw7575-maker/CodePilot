package com.dsh.orchestration.acceptance;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.TaskCheckPreview.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskCheckPreviewServiceTest {
    @TempDir Path temp;private TaskCheckPreviewSource sources;private HostTaskCheckCatalog catalogs;
    private TaskCheckPreviewService service;private TaskCheckPreviewSource.Snapshot snapshot;
    private TrustedTaskCheckCatalog.Snapshot catalog;private Path catalogFile;
    @BeforeEach void setup() throws Exception {
        Path control=Files.createDirectory(temp.resolve("control")), project=Files.createDirectory(temp.resolve("project")), workspace=Files.createDirectory(temp.resolve("workspace"));
        Path checks=Files.createDirectory(control.resolve("project_fixture"));catalogFile=checks.resolve("task-checks.json");
        try(var input=getClass().getResourceAsStream("/m1c1/catalog.json")){Files.copy(input,catalogFile);}Files.writeString(checks.resolve("check_slugify.py"),"assert 2 + 2 == 4\n");
        catalog=TrustedTaskCheckCatalog.load(control,"project_fixture",List.of(project,workspace));
        sources=mock(TaskCheckPreviewSource.class);catalogs=mock(HostTaskCheckCatalog.class);
        snapshot=snapshot(" 修复 ","原文\n要求",1);
        when(sources.read("run_one","plan_one","A")).thenReturn(snapshot);when(catalogs.load("project_fixture")).thenReturn(catalog);
        service=new TaskCheckPreviewService(sources,catalogs);
    }
    private TaskCheckPreviewSource.Snapshot snapshot(String goal,String input,int revision){return new TaskCheckPreviewSource.Snapshot("project_fixture","run_one","turn_one","plan_one",revision,"RUNNING","row_one","DRAFT",new Definition("A",1,goal,List.of("断言"),"交付",List.of(),List.of(),null,"DELEGATED",true),List.of(new Requirement("msg_one","9007199254740993",input)));}
    private com.dsh.api.dto.TaskCheckPreview preview(){return service.preview("run_one","plan_one","A");}
    @Test void fullCatalogIsAvailableAndHashesHaveNoSelectionOrSuccessClaim(){
        var result=preview();assertEquals("PREVIEW_ONLY",result.status());assertEquals(1,result.availableChecks().size());
        var mapper=new ObjectMapper();assertEquals(CanonicalTaskJson.digest(mapper.valueToTree(snapshot.task())),result.taskDefinitionDigest());
        assertEquals(CanonicalTaskJson.digest(mapper.valueToTree(snapshot.requirements())),result.requirementsDigest());
        assertEquals(catalog.registryDigest(),result.registryDigest());verify(catalogs).requireCurrent(catalog);verify(sources,times(2)).read("run_one","plan_one","A");
        assertThrows(UnsupportedOperationException.class,()->result.availableChecks().clear());
    }
    @Test void taskChangedWithoutVersionBumpIsRejectedWithinRead(){when(sources.read("run_one","plan_one","A")).thenReturn(snapshot,snapshot("changed", "原文\n要求",1));assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(ApiException.class,this::preview).code());}
    @Test void appendedRequirementsInvalidateTheObservedPreview(){when(sources.read("run_one","plan_one","A")).thenReturn(snapshot,snapshot(" 修复 ","原文\n要求变更",1));assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(ApiException.class,this::preview).code());}
    @Test void planRevisionChangesRequireFreshRead(){when(sources.read("run_one","plan_one","A")).thenReturn(snapshot,snapshot(" 修复 ","原文\n要求",2));assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(ApiException.class,this::preview).code());}
    @Test void sameVersionDefinitionRequirementAndRevisionAffectDifferentDigests(){
        var first=preview();when(sources.read("run_one","plan_one","A")).thenReturn(snapshot("changed","原文\n要求",1));var task=preview();assertNotEquals(first.taskDefinitionDigest(),task.taskDefinitionDigest());assertEquals(first.requirementsDigest(),task.requirementsDigest());
        when(sources.read("run_one","plan_one","A")).thenReturn(snapshot(" 修复 ","新要求",1));var requirements=preview();assertNotEquals(first.requirementsDigest(),requirements.requirementsDigest());
        when(sources.read("run_one","plan_one","A")).thenReturn(snapshot(" 修复 ","原文\n要求",2));var revision=preview();assertEquals(first.taskDefinitionDigest(),revision.taskDefinitionDigest());assertNotEquals(first.previewDigest(),revision.previewDigest());
    }
    @Test void rawCatalogWhitespaceChangesPreviewEvenWithSameChecks() throws Exception {
        var first=preview();Files.writeString(catalogFile,Files.readString(catalogFile)+"\n");
        var current=TrustedTaskCheckCatalog.load(catalogFile.getParent().getParent(),"project_fixture",List.of(temp.resolve("project"),temp.resolve("workspace")));
        when(catalogs.load("project_fixture")).thenReturn(current);assertNotEquals(first.previewDigest(),preview().previewDigest());
    }
    @Test void disabledHostMapsTo503AndNeverFallsBackToProjectFile(){
        catalogs=new HostTaskCheckCatalog("",mock(com.dsh.storage.ProjectRepository.class),mock(com.dsh.workspace.WorkspaceManager.class));service=new TaskCheckPreviewService(sources,catalogs);
        var error=assertThrows(ApiException.class,this::preview);assertEquals(ErrorCode.SERVICE_UNAVAILABLE,error.code());assertFalse(error.getMessage().contains(temp.toString()));
    }
}
