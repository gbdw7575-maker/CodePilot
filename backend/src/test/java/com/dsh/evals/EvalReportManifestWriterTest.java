package com.dsh.evals;

import com.dsh.evals.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EvalReportManifestWriterTest {
    @TempDir Path root;
    @Test void repeatedWritesNeverOverwritePriorReportsAndManifestIsPrivate()throws Exception {
        var report=new EvalReport("digest","d","v1","dev",0,0,0,0,Map.of(),0,0,0,0,null,null,List.of(),List.of());
        var writer=new EvalReportWriter();Path first=writer.write(report,root);byte[] original=Files.readAllBytes(first);
        Path second=writer.write(report,root);assertNotEquals(first,second);assertArrayEquals(original,Files.readAllBytes(first));
        var manifest=EvalExecutionManifest.create(new ObjectMapper().createObjectNode().put("factor","value"),List.of());
        Path sealed=writer.writeExecutionManifest(manifest,root);assertEquals(root.resolve(".execution-manifests"),sealed.getParent());
        assertEquals(manifest,new ObjectMapper().readValue(Files.readString(sealed),EvalExecutionManifest.class));
    }
}
