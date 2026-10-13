package com.dsh.application;

import com.dsh.storage.RunEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RunVerificationRequirementTest {
    @TempDir Path root;
    ObjectMapper json=new ObjectMapper();
    RunEntity run() { var now=Instant.now();return new RunEntity("run_test","turn_test",null,"MAIN","QUEUED","PENDING",1,"ws_test","digest",24,80,200000,now.plusSeconds(60),now,now); }
    Map<String,Object> definition(String source) { return Map.of("assertionId","sample","fileName","check.sh","content",source,"command","sh \"$DSH_PROBE_FILE\""); }
    RunVerificationRequirement parse(String description,Object definition) { return RunVerificationRequirement.parse(json.valueToTree(Map.of("purpose","DEFECT_FIX","acceptanceDescription",description,"defectReproduction",definition))); }
    @Test void strictRoundTripKeepsExactSourceAndDescription() {
        var value=parse("中文\n原样验收",definition("echo test\n"));var restored=RunVerificationRequirement.decode(value.encode());assertEquals(value,restored);
    }
    @Test void byteLimitRejectsMultibyteSourceAndAllowsBoundary() {
        assertNotNull(parse("验收",definition("a".repeat(16384))));
        assertThrows(IllegalArgumentException.class,()->parse("验收",definition("中".repeat(5462))));
        assertThrows(IllegalArgumentException.class,()->parse("验收",definition("a".repeat(16385))));
    }
    @Test void descriptionBoundsAndNulAreRejected() {
        for(String bad:new String[]{" ","a".repeat(2001),"hello\0world"}) assertThrows(IllegalArgumentException.class,()->parse(bad,definition("echo x")));
    }
    @Test void nestedUnknownFieldsTypesAndMissingFieldsAreRejected() {
        var extra=new java.util.HashMap<String,Object>(definition("echo x"));extra.put("timeoutSeconds",600);
        assertThrows(IllegalArgumentException.class,()->parse("验收",extra));
        assertThrows(IllegalArgumentException.class,()->parse("验收",Map.of("assertionId","sample")));
        assertThrows(IllegalArgumentException.class,()->parse("验收",false));
    }
    @Test void corruptedOrOversizedPersistedDeclarationNeverFallsBack() {
        for(String bad:new String[]{"not json","{}","null"," ".repeat(65537),"{\"purpose\":\"DEFECT_FIX\"}","{\"purpose\":\"GENERAL\"} {}", "{\"purpose\":\"GENERAL\",\"purpose\":\"GENERAL\"}"})
            assertThrows(IllegalArgumentException.class,()->RunVerificationRequirement.decode(bad));
    }
    @Test void manuallyConstructedDeclarationsCannotSilentlyDropAcceptanceFields() {
        assertThrows(IllegalArgumentException.class,()->new RunVerificationRequirement("GENERAL","unexpected",null));
        assertThrows(IllegalArgumentException.class,()->new RunVerificationRequirement("DEFECT_FIX","expected",null));
    }
    @Test void generalOverrideRemovesLegacyProjectDefectDefinitionAndKeepsRegressionCommand() throws Exception {
        Files.createDirectories(root.resolve(".dsh"));Files.writeString(root.resolve(".dsh/verify.yaml"),"command: echo regression\nexpectTests: false\npurpose: DEFECT_FIX\n");
        var run=run();assertTrue(VerificationPlan.forRun(run,root).defectFix());run.registerVerificationRequirement(RunVerificationRequirement.general());
        var effective=VerificationPlan.forRun(run,root);assertFalse(effective.defectFix());assertNull(effective.reproduction());assertEquals("echo regression",effective.commandSummary());
        assertThrows(IllegalStateException.class,()->run.registerVerificationRequirement(RunVerificationRequirement.general()));
    }
    @Test void registrationStaysFrozenWhileProjectRegressionChanges() throws Exception {
        var run=run();run.registerVerificationRequirement(parse("验收行为",definition("echo x")).encode());
        Files.createDirectories(root.resolve(".dsh"));Files.writeString(root.resolve(".dsh/verify.yaml"),"command: echo first\n");
        var before=VerificationPlan.forRun(run,root);Files.writeString(root.resolve(".dsh/verify.yaml"),"command: echo second\n");
        var after=VerificationPlan.forRun(run,root);assertEquals(before.reproduction(),after.reproduction());assertNotEquals(before.displayDigest(),after.displayDigest());
    }
}
