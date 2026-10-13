package com.dsh.evals;

import com.dsh.evals.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvalLaunchGateTest {
    @TempDir Path root;
    final ObjectMapper mapper=new ObjectMapper();final EvalLaunchGate gate=new EvalLaunchGate();
    EvalConfig config;EvalExecutionManifest manifest;Path file;
    @BeforeEach void setup()throws Exception {
        var budget=new EvalBudget(2,3,4000,1);
        String digest=EvalConfig.computeDigest("d","v","dev","model",budget,"image",1,null);
        config=new EvalConfig("d","v","dev",Set.of(),"profile","model",budget,null,"image",1,digest);
        ObjectNode f=mapper.createObjectNode();f.put("eval.strategy","SINGLE");f.put("eval.datasetName","d");
        f.put("eval.datasetVersion","v");f.put("eval.split","dev");f.put("eval.modelProfileConfigDigest","model");
        f.set("eval.budget",mapper.valueToTree(budget));f.put("eval.repeatCount",1);f.putArray("eval.sampleIds");f.putNull("eval.seed");
        f.put("sandbox.declaredImageDigest","image");f.put("orchestration.enabled",false);f.put("double",0.0);
        manifest=EvalExecutionManifest.create(f,List.of());file=root.resolve("expected.json");write(mapper.valueToTree(manifest));
    }
    void write(ObjectNode n)throws Exception{mapper.writeValue(file.toFile(),n);}
    @Test void acceptsProductionDigestIncludingDoubleZero(){assertEquals(manifest,gate.load(file,config));}
    @Test void missingPathRejected(){assertThrows(IllegalArgumentException.class,()->gate.load(null,config));}
    @Test void nonexistentFileRejected(){assertThrows(IllegalArgumentException.class,()->gate.load(root.resolve("absent"),config));}
    @Test void directoryRejected(){assertThrows(IllegalArgumentException.class,()->gate.load(root,config));}
    @Test void oversizedFileRejected()throws Exception{Files.writeString(file," ".repeat(1024*1024+1));assertThrows(IllegalArgumentException.class,()->gate.load(file,config));}
    @Test void linkedFileRejected()throws Exception{Path link=root.resolve("link");Files.createSymbolicLink(link,file);assertThrows(IllegalArgumentException.class,()->gate.load(link,config));}
    @Test void linkedAncestorRejected()throws Exception{Path link=root.resolve("alias");Files.createSymbolicLink(link,root);assertThrows(IllegalArgumentException.class,()->gate.load(link.resolve("expected.json"),config));}
    @Test void invalidDigestRejected()throws Exception{ObjectNode n=mapper.valueToTree(manifest);n.put("executionDigest","0".repeat(64));write(n);assertThrows(RuntimeException.class,()->gate.load(file,config));}
    @Test void wrongConfigEvenWithValidDigestRejected()throws Exception{var f=manifest.factors();f.put("eval.repeatCount",2);write(mapper.valueToTree(EvalExecutionManifest.create(f,List.of())));assertThrows(IllegalArgumentException.class,()->gate.load(file,config));}
    @Test void trailingJsonRejected()throws Exception{Files.writeString(file,Files.readString(file)+" {}");assertThrows(RuntimeException.class,()->gate.load(file,config));}
    @Test void duplicateKeyRejected()throws Exception{String s=Files.readString(file);Files.writeString(file,s.replaceFirst("\\{","{\"version\":\"dsh-execution-v1\","));assertThrows(RuntimeException.class,()->gate.load(file,config));}
    @Test void unknownRootFieldRejected()throws Exception{ObjectNode n=mapper.valueToTree(manifest);n.put("ignored",true);write(n);assertThrows(RuntimeException.class,()->gate.load(file,config));}
    @Test void nullAndMalformedRejected()throws Exception{Files.writeString(file,"null");assertThrows(IllegalArgumentException.class,()->gate.load(file,config));Files.writeString(file,"{bad");assertThrows(RuntimeException.class,()->gate.load(file,config));}
    @Test void driftRejectedAndMatchingManifestAccepted(){gate.assertMatches(manifest,manifest);var f=manifest.factors();f.put("runtime","other");assertThrows(IllegalStateException.class,()->gate.assertMatches(manifest,EvalExecutionManifest.create(f,List.of())));}
}
