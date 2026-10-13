package com.dsh.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurefireReportsTest {
    @TempDir Path root;
    static final Instant START = Instant.parse("2026-10-02T00:00:00Z"), END = START.plusSeconds(10);
    static final TestReportSpec SPEC = new TestReportSpec("MAVEN_SUREFIRE", List.of("target/surefire-reports"));
    static String xml(int passed, int failed, int errors, int skipped) {
        var text = new StringBuilder("<testsuite name=\"sample\" tests=\"" + (passed+failed+errors+skipped)
                + "\" failures=\""+failed+"\" errors=\""+errors+"\" skipped=\""+skipped+"\">");
        int id=0;
        for (var kind : List.of("passed","failure","error","skipped")) {
            int number = switch(kind) { case "passed" -> passed; case "failure" -> failed; case "error" -> errors; default -> skipped; };
            for(int i=0;i<number;i++) text.append("<testcase classname=\"Sample\" name=\"test").append(id++)
                    .append("\">").append(kind.equals("passed")?"":"<"+kind+"/>").append("</testcase>");
        }
        return text.append("</testsuite>").toString();
    }
    Path write(String path,String content) throws Exception {
        Path file=root.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,content);
        Files.setLastModifiedTime(file, FileTime.from(START.plusSeconds(1)));return file;
    }
    SurefireReports.Result after(SurefireReports.Snapshot before) { return SurefireReports.after(root,SPEC,before,START,END); }
    @Test void readsFreshMultiModuleReportsAndSealsExactBytes() throws Exception {
        var spec=new TestReportSpec("MAVEN_SUREFIRE",List.of("a/target/surefire-reports","b/target/surefire-reports"));
        var before=SurefireReports.before(root,spec);
        write("a/target/surefire-reports/TEST-A.xml",xml(2,1,0,1));
        write("b/target/surefire-reports/TEST-B.xml",xml(3,0,1,2));
        var result=SurefireReports.after(root,spec,before,START,END);
        assertTrue(result.valid());assertEquals(new TestCounts(10,5,2,3),result.counts());
        var evidence=new ObjectMapper().readTree(result.evidence());assertEquals(2,evidence.path("reports").size());
        for(var file:evidence.path("reports")) {
            byte[] bytes=Base64.getDecoder().decode(file.path("base64").asText());
            assertArrayEquals(Files.readAllBytes(root.resolve(file.path("path").asText())),bytes);
            assertEquals(com.dsh.common.Digest.sha256Hex(bytes),file.path("sha256").asText());
        }
    }
    @Test void staleReportIsRejectedEvenIfOnlyTimestampIsTouched() throws Exception {
        var file=write("target/surefire-reports/TEST-A.xml",xml(1,0,0,0));
        var before=SurefireReports.before(root,SPEC);
        Files.setLastModifiedTime(file,FileTime.from(START.plusSeconds(2)));
        assertEquals("REPORT_UNCHANGED",after(before).error());
    }
    @Test void freshChangedReportReplacesPreviousFailureCounts() throws Exception {
        write("target/surefire-reports/TEST-A.xml",xml(0,1,0,0));var before=SurefireReports.before(root,SPEC);
        write("target/surefire-reports/TEST-A.xml",xml(1,0,0,0));
        assertTrue(after(before).valid());assertEquals(1,after(before).counts().passed());
    }
    @Test void newlyCopiedOldOrFutureReportCannotPass() throws Exception {
        var before=SurefireReports.before(root,SPEC);
        var file=write("target/surefire-reports/TEST-A.xml",xml(1,0,0,0));
        for(var timestamp:List.of(START.minusSeconds(1),END.plusSeconds(1))) {
            Files.setLastModifiedTime(file,FileTime.from(timestamp));assertEquals("REPORT_STALE_TIME",after(before).error());
        }
    }
    @Test void eachDeclaredModuleMustProvideReports() throws Exception {
        var spec=new TestReportSpec("MAVEN_SUREFIRE",List.of("a/target/surefire-reports","b/target/surefire-reports"));
        var before=SurefireReports.before(root,spec);write("a/target/surefire-reports/TEST-A.xml",xml(1,0,0,0));
        assertEquals("REPORT_DIRECTORY_MISSING",SurefireReports.after(root,spec,before,START,END).error());
        Files.createDirectories(root.resolve("b/target/surefire-reports"));
        assertEquals("REPORT_FILES_MISSING",SurefireReports.after(root,spec,before,START,END).error());
    }
    @Test void zeroAndAllSkippedAreRejected() throws Exception {
        var before=SurefireReports.before(root,SPEC);
        write("target/surefire-reports/TEST-A.xml",xml(0,0,0,0));assertEquals("REPORT_ZERO_TESTS",after(before).error());
        write("target/surefire-reports/TEST-A.xml",xml(0,0,0,3));assertEquals("REPORT_ALL_SKIPPED",after(before).error());
    }
    @ParameterizedTest @ValueSource(strings={"<", "<testsuites/>",
            "<testsuite tests='1' failures='0' errors='0' skipped='0'/>",
            "<testsuite tests='-1' failures='0' errors='0' skipped='0'/>",
            "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase name='t' classname='A'><failure/></testcase></testsuite>",
            "<testsuite tests='2' failures='0' errors='0' skipped='0'><testcase name='t' classname='A'/><testcase name='t' classname='A'/></testsuite>",
            "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase name='t' classname='A'><flakyFailure/></testcase></testsuite>",
            "<!DOCTYPE testsuite [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><testsuite tests='0' failures='0' errors='0' skipped='0'>&x;</testsuite>",
            "<!DOCTYPE testsuite SYSTEM 'http://127.0.0.1:9/xxe'><testsuite/>",
            "<!DOCTYPE testsuite [<!ENTITY a 'expanded'>]><testsuite>&a;</testsuite>"})
    void rejectsMalformedUnsupportedAndUnsafeXmlButKeepsBytes(String content) throws Exception {
        var before=SurefireReports.before(root,SPEC);write("target/surefire-reports/TEST-A.xml",content);
        var result=after(before);assertFalse(result.valid());assertEquals(content,new String(result.files().getFirst().bytes(),java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void rejectsSymlinkFileAndIntermediateDirectoryEvenInsideWorkspace() throws Exception {
        write("inside/TEST-A.xml",xml(1,0,0,0));Files.createDirectories(root.resolve("target"));
        Files.createSymbolicLink(root.resolve("target/surefire-reports"),root.resolve("inside"));
        assertEquals("REPORT_PATH_UNSAFE",SurefireReports.before(root,SPEC).error());
        Files.delete(root.resolve("target/surefire-reports"));Files.createDirectory(root.resolve("target/surefire-reports"));
        Files.createSymbolicLink(root.resolve("target/surefire-reports/TEST-A.xml"),root.resolve("inside/TEST-A.xml"));
        assertEquals("REPORT_PATH_UNSAFE",SurefireReports.before(root,SPEC).error());
    }
    @Test void refusesOversizedAndExcessReports() throws Exception {
        write("target/surefire-reports/TEST-A.xml"," ".repeat(SurefireReports.MAX_FILE_BYTES+1));
        assertEquals("REPORT_BYTE_LIMIT",SurefireReports.before(root,SPEC).error());
        Files.delete(root.resolve("target/surefire-reports/TEST-A.xml"));
        for(int i=0;i<=SurefireReports.MAX_FILES;i++)write("target/surefire-reports/TEST-"+i+".xml",xml(1,0,0,0));
        assertEquals("REPORT_FILE_LIMIT",SurefireReports.before(root,SPEC).error());
    }
    @Test void totalBytesAreBoundedAndNonMatchingFilesDoNotCount() throws Exception {
        write("target/surefire-reports/readme.xml",xml(1,0,0,0));
        assertEquals("REPORT_FILES_MISSING",after(SurefireReports.before(root,SPEC)).error());
        for(int i=0;i<5;i++)write("target/surefire-reports/TEST-"+i+".xml"," ".repeat(SurefireReports.MAX_FILE_BYTES));
        assertEquals("REPORT_BYTE_LIMIT",SurefireReports.before(root,SPEC).error());
    }
    @ParameterizedTest @ValueSource(strings={"../out","/tmp/report","C:/reports","a\\b","a//b","a/./b","a/../b","a/.git/b","target/**",""})
    void rejectsUnsafeConfiguredDirectories(String path) {
        assertThrows(IllegalArgumentException.class,()->new TestReportSpec("MAVEN_SUREFIRE",List.of(path)));
    }
    @Test void rejectsUnknownFormatDuplicatesAndUnknownFields() {
        assertThrows(IllegalArgumentException.class,()->new TestReportSpec("UNKNOWN",SPEC.directories()));
        assertThrows(IllegalArgumentException.class,()->new TestReportSpec("MAVEN_SUREFIRE",List.of("target","target")));
        assertThrows(IllegalArgumentException.class,()->new TestReportSpec("MAVEN_SUREFIRE",List.of("Target","target")));
        assertThrows(IllegalArgumentException.class,()->TestReportSpec.parse(Map.of("format","MAVEN_SUREFIRE","directories",SPEC.directories(),"extra",true)));
    }
}
