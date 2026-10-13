package com.dsh.evals;

import com.dsh.evals.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvalExecutionManifestTest {
    ObjectMapper mapper=new ObjectMapper();
    ObjectNode factors(){return mapper.createObjectNode().put("loop.historyCompactionMode","WINDOW_LASTUSER").put("orchestration.enabled",false).put("eval.strategy","SINGLE");}
    @Test void keyOrderAndLimitationOrderDoNotChangeIdentity() {
        ObjectNode a=factors(),b=mapper.createObjectNode();a.fields().forEachRemaining(e->b.set(e.getKey(),e.getValue()));
        ObjectNode reverse=mapper.createObjectNode();var keys=new ArrayList<String>();b.fieldNames().forEachRemaining(keys::add);Collections.reverse(keys);keys.forEach(k->reverse.set(k,b.get(k)));
        assertEquals(EvalExecutionManifest.create(a,List.of("z","a")).executionDigest(),EvalExecutionManifest.create(reverse,List.of("a","z","a")).executionDigest());
    }
    @Test void manifestIsDefensivelyCopiedAtCreationAndAccess() {
        var a=factors();var manifest=EvalExecutionManifest.create(a,List.of());String hash=manifest.executionDigest();
        a.put("x",1);manifest.factors().put("y",2);assertEquals(hash,manifest.executionDigest());assertFalse(manifest.factors().has("x"));assertFalse(manifest.factors().has("y"));
    }
    @Test void everyUncoveredRuntimeFactorChangesIdentity() {
        var original=EvalExecutionManifest.create(factors(),List.of());
        for(String key:List.of("orchestration.acceptanceCoverageMode","loop.compactionWatermarkPercent","loop.verificationRetryLimit","loop.compactionProtectedTailUnits","tools.coreDefinitionsSha256","prompt.fixedBaseSha256","build.sha256","grader.supportFileSha256","eval.datasetContentSha256","memory.settings","sandbox","runtime.java.version")) {
            var changed=factors().put(key,"different");assertNotEquals(original.executionDigest(),EvalExecutionManifest.create(changed,List.of()).executionDigest(),key);
            assertThrows(IllegalArgumentException.class,()->EvalExecutionManifest.comparisonWarnings(original,EvalExecutionManifest.create(changed,List.of())),key);
        }
    }
    @Test void numericNodeWidthsNormalizeWithoutLosingLargeValues() throws Exception {
        var f = factors().put("timeout", 60L).put("large", 3_000_000_000L);
        var m = EvalExecutionManifest.create(f, List.of());
        assertEquals(m, mapper.readValue(mapper.writeValueAsString(m), EvalExecutionManifest.class));
        assertEquals(3_000_000_000L, m.factors().path("large").asLong());
        assertEquals(m.executionDigest(), EvalExecutionManifest.create(factors().put("timeout",60).put("large",3_000_000_000L),List.of()).executionDigest());
    }
    @Test void arraysRetainExecutionOrderAndMissingIsNotNull() {
        var a=factors();a.putArray("order").add("a").add("b");var b=factors();b.putArray("order").add("b").add("a");
        assertNotEquals(EvalExecutionManifest.create(a,List.of()).executionDigest(),EvalExecutionManifest.create(b,List.of()).executionDigest());
        assertNotEquals(EvalExecutionManifest.create(factors(),List.of()).executionDigest(),EvalExecutionManifest.create(factors().putNull("x"),List.of()).executionDigest());
    }
    @Test void tamperedDigestAndUnknownVersionAreRejectedOnJsonRead() throws Exception {
        var m=EvalExecutionManifest.create(factors(),List.of());var json=(ObjectNode)mapper.readTree(mapper.writeValueAsString(m));
        json.put("executionDigest","0".repeat(64));assertThrows(Exception.class,()->mapper.treeToValue(json,EvalExecutionManifest.class));
        json.put("executionDigest",m.executionDigest());json.put("version","future-v2");assertThrows(Exception.class,()->mapper.treeToValue(json,EvalExecutionManifest.class));
    }
    @Test void onlyDeclaredStrategyVariableMayDifferAndLimitationsAreWarned() {
        var single=EvalExecutionManifest.create(factors(),List.of("MEMORY_UNFROZEN"));
        var multi=EvalExecutionManifest.create(factors().put("eval.strategy","MULTI").put("orchestration.enabled",true),List.of("MEMORY_UNFROZEN"));
        assertEquals(single.comparisonFactorDigest(),multi.comparisonFactorDigest());assertEquals(1,EvalExecutionManifest.comparisonWarnings(single,multi).size());
        assertNotEquals(single.executionDigest(),multi.executionDigest());
    }
    @Test void legacyPairHasExplicitWarningAndMixedPairIsRejected() {
        assertTrue(EvalExecutionManifest.comparisonWarnings(null,null).getFirst().contains("LEGACY_EXECUTION_UNVERIFIED"));
        assertThrows(IllegalArgumentException.class,()->EvalExecutionManifest.comparisonWarnings(null,EvalExecutionManifest.create(factors(),List.of())));
    }
    @Test void manifestMustBelongToTheDeclaredConfig() {
        var budget=new EvalBudget(4,10,10000,1);String legacy=EvalConfig.computeDigest("d","v1","dev","model",budget,"image",1,null);
        var config=new EvalConfig("d","v1","dev",Set.of(),"profile","model",budget,null,"image",1,legacy);
        var f=factors();f.put("eval.datasetName","d");f.put("eval.datasetVersion","v1");f.put("eval.split","dev");
        f.put("eval.modelProfileConfigDigest","model");f.set("eval.budget",mapper.valueToTree(budget));f.put("eval.repeatCount",1);
        f.putArray("eval.sampleIds");f.putNull("eval.seed");f.put("sandbox.declaredImageDigest","image");
        EvalExecutionManifest.create(f,List.of()).validateFor(config);
        f.put("eval.repeatCount",2);assertThrows(IllegalArgumentException.class,()->EvalExecutionManifest.create(f,List.of()).validateFor(config));
        f.put("eval.repeatCount",1);f.put("orchestration.enabled",true);
        assertThrows(IllegalArgumentException.class,()->EvalExecutionManifest.create(f,List.of()).validateFor(config));
    }
    @Test void oldReportJsonLoadsWithoutInventingManifestOrChangingDigest() throws Exception {
        var old=new EvalReport("old-digest","d","v1","dev",0,0,0,0,Map.of(),0,0,0,0,null,null,List.of(),List.of());
        String raw=mapper.writeValueAsString(old);assertFalse(raw.contains("executionManifest"));
        var read=mapper.readValue(raw,EvalReport.class);assertNull(read.executionManifest());assertEquals("old-digest",read.configDigest());
        var fresh=old.withExecutionManifest(EvalExecutionManifest.create(factors(),List.of()));
        assertEquals(fresh,mapper.readValue(mapper.writeValueAsString(fresh),EvalReport.class));
    }
}
