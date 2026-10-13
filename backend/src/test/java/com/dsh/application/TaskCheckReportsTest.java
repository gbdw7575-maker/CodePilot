package com.dsh.application;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TaskCheckReportsTest {
    byte[] xml(String name,String body){return ("<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\""+(body.isEmpty()?0:1)+"\"><testcase classname=\"T\" name=\""+name+"\">"+body+"</testcase></testsuite>").getBytes(StandardCharsets.UTF_8);}
    @Test void aggregatesDistinctTests(){assertEquals(new TestCounts(2,2,0,0),TaskCheckReports.parseAll(List.of(xml("a",""),xml("b",""))).counts());}
    @Test void refusesCrossReportDuplicates(){assertEquals("REPORT_TEST_IDENTITY",TaskCheckReports.parseAll(List.of(xml("a",""),xml("a",""))).error());}
    @Test void keepsSkippedCountsWithoutSuccessVerdict(){assertEquals(new TestCounts(1,0,0,1),TaskCheckReports.parse(xml("a","<skipped/>" )).counts());}
    @Test void missingIsExplicit(){assertEquals("REPORT_MISSING",TaskCheckReports.parseAll(List.of()).error());}
    @Test void sizeBoundBeforeXml(){assertEquals("REPORT_BYTE_LIMIT",TaskCheckReports.parse(new byte[1048577]).error());}
    @Test void dtdCannotAccessExternalContent(){assertNotNull(TaskCheckReports.parse("<!DOCTYPE testsuite SYSTEM 'file:///etc/passwd'><testsuite/>".getBytes(StandardCharsets.UTF_8)).error());}
    @Test void excessFileCountIsBounded(){assertEquals("REPORT_FILE_LIMIT",TaskCheckReports.parseAll(java.util.Collections.nCopies(65,xml("a",""))).error());}
}
