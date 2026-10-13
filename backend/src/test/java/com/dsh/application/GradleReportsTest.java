package com.dsh.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GradleReportsTest {
    @TempDir Path root;
    static final Instant START=SurefireReportsTest.START, END=SurefireReportsTest.END;
    static final TestReportSpec SPEC=new TestReportSpec("GRADLE", List.of("build/test-results/test"));
    static String xml(String timestamp,int passed,int failed,int errors,int skipped) {
        return SurefireReportsTest.xml(passed,failed,errors,skipped).replace("<testsuite ","<testsuite timestamp=\""+timestamp+"\" ");
    }
    void write(String dir,String xml) throws Exception {
        Path f=root.resolve(dir+"/TEST-Sample.xml");Files.createDirectories(f.getParent());Files.writeString(f,xml);
        Files.setLastModifiedTime(f,FileTime.from(START.plusSeconds(2)));
    }
    SurefireReports.Result after(SurefireReports.Snapshot before) {return SurefireReports.after(root,SPEC,before,START,END);}
    @Test void multiModuleAndCustomTaskDirectoriesSealFormatAndPolicy() throws Exception {
        var spec=new TestReportSpec("GRADLE",List.of("a/build/test-results/test","b/build/test-results/test","b/custom/xml"));
        var before=SurefireReports.before(root,spec);
        for(String dir:spec.directories())write(dir,xml(START.plusMillis(1500).toString(),2,1,0,1));
        var result=SurefireReports.after(root,spec,before,START,END);
        assertTrue(result.valid());assertEquals(new TestCounts(12,6,3,3),result.counts());
        var seal=new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.evidence());
        assertEquals("GRADLE",seal.path("format").asText());assertEquals("gradle-reports-v1",seal.path("version").asText());
        assertEquals(3,seal.path("reports").size());
    }
    @ParameterizedTest @ValueSource(strings={"2026-10-01T23:59:59.999Z","2026-10-02T00:00:10.001Z"})
    void restoredCacheOrFutureExecutionFailsDespiteFreshMtimeAndNoBeforeFile(String timestamp) throws Exception {
        var before=SurefireReports.before(root,SPEC);assertTrue(before.files().isEmpty());
        write(SPEC.directories().getFirst(),xml(timestamp,1,0,0,0));
        var result=after(before);assertEquals("REPORT_EXECUTION_TIME_STALE",result.error());assertEquals(1,result.files().size());
    }
    @ParameterizedTest @ValueSource(strings={"","bad","2026-10-02T00:00:01","2026-10-02T00:00:01 UTC"})
    void missingOrNonStandardTimestampIsRejected(String timestamp) throws Exception {
        var before=SurefireReports.before(root,SPEC);write(SPEC.directories().getFirst(),xml(timestamp,1,0,0,0));
        assertEquals("REPORT_EXECUTION_TIME_INVALID",after(before).error());
    }
    @Test void upToDateAndTouchedOldBytesFailButFreshRerunSucceeds() throws Exception {
        write(SPEC.directories().getFirst(),xml(START.minusSeconds(5).toString(),1,0,0,0));
        var before=SurefireReports.before(root,SPEC);
        assertEquals("REPORT_UNCHANGED",after(before).error());
        write(SPEC.directories().getFirst(),xml(START.plusSeconds(1).toString(),1,0,0,0));assertTrue(after(before).valid());
    }
    @Test void gradleFailureAndErrorCountsRemainFailures() throws Exception {
        var before=SurefireReports.before(root,SPEC);write(SPEC.directories().getFirst(),xml(START.plusSeconds(1).toString(),1,1,1,1));
        assertEquals(new TestCounts(4,1,2,1),after(before).counts());
    }
    @Test void everyDeclaredTaskMustHaveReportAndNoSourceIsNotSuccess() throws Exception {
        var spec=new TestReportSpec("GRADLE",List.of("build/test-results/test","build/test-results/integrationTest"));
        var before=SurefireReports.before(root,spec);write(spec.directories().getFirst(),xml(START.plusSeconds(1).toString(),1,0,0,0));
        assertEquals("REPORT_DIRECTORY_MISSING",SurefireReports.after(root,spec,before,START,END).error());
    }
    @Test void gradleStillRejectsXxeAndAllSkipped() throws Exception {
        var before=SurefireReports.before(root,SPEC);String dir=SPEC.directories().getFirst();
        write(dir,"<!DOCTYPE testsuite [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><testsuite>&x;</testsuite>");
        assertEquals("REPORT_INVALID_XML",after(before).error());
        write(dir,xml(START.plusSeconds(1).toString(),0,0,0,2));assertEquals("REPORT_ALL_SKIPPED",after(before).error());
    }
}
