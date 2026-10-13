package com.dsh.sandbox;

import com.dsh.orchestration.acceptance.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Stateful CLI protocol controls, not a replacement for actual Docker validation. */
class DockerTaskCheckExecutorTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    TaskCheckExecutionRequest request;
    AtomicLong time=new AtomicLong(100);
    AtomicBoolean cancelled=new AtomicBoolean();
    Fake client;
    final byte[] xml="<testsuite tests=\"1\"><testcase name=\"中文\"/></testsuite>\r\n".getBytes(StandardCharsets.UTF_8);
    @BeforeEach void prepare()throws Exception {
        request=mock(TaskCheckExecutionRequest.class);var execution=mock(TrustedTaskExecutionSpec.Snapshot.class);
        Path candidate=Files.createDirectory(temp.resolve("workspace"));Files.createDirectories(candidate.resolve("src/main/java/pkg"));
        byte[] source="package pkg; class Value {}".getBytes(StandardCharsets.UTF_8);Files.write(candidate.resolve("src/main/java/pkg/Value.java"),source);
        Path control=Files.createDirectory(temp.resolve("control"));Path cache=Files.createDirectory(control.resolve("runtime-cache"));
        when(request.operationId()).thenReturn("0".repeat(32));when(request.candidateRoot()).thenReturn(candidate);
        when(request.execution()).thenReturn(execution);when(execution.cacheRoot()).thenReturn(cache);when(execution.imageDigest()).thenReturn("1".repeat(64));
        when(execution.limits()).thenReturn(new TrustedTaskExecutionSpec.Limits(1000,805306368,128,268435456,1024,2,1024,2048));
        var files=new ArrayList<TrustedTaskCheckCatalog.Verifier>();
        for(String path:List.of("pom.xml","run-check.sh","tests/TrustedTest.java")){
            byte[] bytes=("trusted "+path).getBytes(StandardCharsets.UTF_8);files.add(new TrustedTaskCheckCatalog.Verifier(path,CanonicalTaskJson.sha256(bytes)));
            when(request.verifierBytes(path)).thenAnswer(i->bytes.clone());
        }
        when(request.check()).thenReturn(new TrustedTaskExecutionSpec.CheckProfile("check",List.of("sh","/verifier/run-check.sh","normal"),2,1,files,"2".repeat(64)));
        when(request.candidateFiles()).thenReturn(List.of(new TaskCheckExecutionRequest.SourceFile("src/main/java/pkg/Value.java",source.length,CanonicalTaskJson.sha256(source))));
        client=new Fake();
    }
    TaskCheckExecutionOutcome run(){return new DockerTaskCheckExecutor(client,time::get).execute(request,cancelled::get);}
    final class Fake implements DockerTaskCheckExecutor.Client {
        final String id="a".repeat(64);String mode="normal";boolean released,started,removed;int inspection;
        ObjectNode actual;
        List<List<String>> calls=new ArrayList<>();List<Long> deadlines=new ArrayList<>();
        DockerTaskCheckExecutor.Reply reply(int code,byte[] bytes){return new DockerTaskCheckExecutor.Reply(code,bytes,new byte[0],bytes.length,0,false);}
        DockerTaskCheckExecutor.Reply text(int code,String text){return reply(code,text.getBytes(StandardCharsets.UTF_8));}
        ObjectNode image(){var root=json.createObjectNode().put("Id","sha256:"+"1".repeat(64));var config=root.putObject("Config");config.putArray("Env").add("PATH=/bin");config.putArray("Entrypoint").add("/entrypoint.sh");return root;}
        @Override public DockerTaskCheckExecutor.Reply run(List<String> argv,byte[] input,int limit,long deadline,java.util.function.BooleanSupplier cancel)throws Exception {
            calls.add(argv);deadlines.add(deadline);String command=argv.get(1);
            if(command.equals("image"))return text(0,json.writeValueAsString(List.of(image())));
            if(command.equals("create")){
                if(mode.equals("create-failure"))return text(125,"");
                actual=json.createObjectNode().put("Id",id).put("Name","/dsh-check-"+request.operationId()).put("Image","sha256:"+"1".repeat(64));
                var config=actual.putObject("Config").put("User","1000:1000").put("WorkingDir","/verifier");
                config.set("Env",image().path("Config").path("Env").deepCopy());config.set("Entrypoint",image().path("Config").path("Entrypoint").deepCopy());config.set("Cmd",json.valueToTree(request.check().argv()));config.putObject("Labels").put("dsh.check-operation",request.operationId());
                var host=actual.putObject("HostConfig").put("NetworkMode","none").put("ReadonlyRootfs",true).put("Privileged",false).put("AutoRemove",false)
                        .put("Memory",805306368).put("NanoCpus",1000000000).put("PidsLimit",128).put("IpcMode","private").put("PidMode","");
                host.putArray("CapDrop").add("ALL");host.putArray("SecurityOpt").add("no-new-privileges");host.putObject("Tmpfs").put("/tmp","rw,exec,nosuid,nodev,size=268435456,mode=1777");
                var logging=host.putObject("LogConfig").put("Type","local");logging.putObject("Config").put("max-size","1m").put("max-file","1").put("compress","false");
                var mounts=actual.putArray("Mounts");for(int i=0;i<argv.size();i++)if(argv.get(i).equals("--mount")){
                    String value=argv.get(i+1);String src=value.substring(value.indexOf("src=")+4,value.indexOf(",dst="));String dst=value.substring(value.indexOf("dst=")+4,value.indexOf(",readonly"));
                    mounts.addObject().put("Type","bind").put("Source",src).put("Destination",dst).put("RW",false);
                }
                if(mode.startsWith("policy:"))mutate(mode.substring(7),host,config);
                if(mode.equals("cancel-after-create"))cancelled.set(true);
                if(mode.equals("timeout-after-create"))time.set(2_000_000_100L);
                return text(0,id+"\n");
            }
            if(command.equals("inspect")){
                if(actual==null)return text(1,"");inspection++;
                actual.putObject("State").put("Running",started&&!released).put("Status",released?"exited":started?"running":"created")
                        .put("ExitCode",mode.equals("nonzero")?1:mode.equals("mismatch")?7:0).put("Error","").put("Dead",false).put("OOMKilled",false);
                return text(0,json.writeValueAsString(List.of(actual)));
            }
            if(command.equals("start")){started=true;return text(mode.equals("start-failure")?1:0,id);}
            if(command.equals("exec")){
                String script=new String(Base64.getDecoder().decode(input),StandardCharsets.UTF_8);
                if(script.contains("maven.exit")){
                    if(mode.equals("invalid-marker"))return text(0,"999");
                    if(mode.equals("early-exit")){started=false;return text(1,"");}
                    if(mode.equals("poll-timeout")){time.addAndGet(1_500_000_000L);return text(1,"");}
                    return text(0,mode.equals("nonzero")?"1":"0");
                }
                if(script.contains("find ")){
                    String listing="TEST-TrustedTest.xml\tf\t"+xml.length+"\n";
                    listing=switch(mode){case "link-report"->"TEST-x.xml\tl\t10\n";case "directory-report"->"nested\td\t10\n";
                        case "path-report"->"../TEST-x.xml\tf\t10\n";case "case-report"->"TEST-x.xml\tf\t10\nTEST-X.xml\tf\t10\n";
                        case "large-report"->"TEST-x.xml\tf\t1025\n";case "count-report"->"TEST-a.xml\tf\t"+xml.length+"\nTEST-b.xml\tf\t"+xml.length+"\nTEST-c.xml\tf\t"+xml.length+"\n";
                        case "no-report"->"";default->listing;};
                    if(mode.equals("listing-failure"))return text(1,"");return text(0,listing);
                }
                if(script.contains("touch ")){released=true;if(mode.equals("cancel-release"))cancelled.set(true);return text(mode.equals("release-failure")?1:0,"");}
                if(mode.equals("capture-failure"))return text(1,"");if(mode.equals("short-report"))return text(0,"short");
                return reply(0,xml);
            }
            if(command.equals("logs")){if(mode.equals("cancel-cleanup"))cancelled.set(true);return text(0,"bounded log");}
            if(command.equals("rm")){removed=true;return text(mode.equals("rm-failure")?1:0,id);}
            if(command.equals("ps"))return text(mode.equals("presence-unknown")?1:0,mode.equals("presence-present")?id:"");
            throw new AssertionError(argv);
        }
        void mutate(String key,ObjectNode host,ObjectNode config){
            switch(key){case "image"->actual.put("Image","sha256:"+"9".repeat(64));case "user"->config.put("User","0:0");case "workdir"->config.put("WorkingDir","/candidate");
                case "argv"->config.putArray("Cmd").add("evil");case "env"->config.putArray("Env").add("KEY=not-a-real-key");case "entrypoint"->config.putArray("Entrypoint").add("evil");
                case "network"->host.put("NetworkMode","host");case "readonly"->host.put("ReadonlyRootfs",false);case "privileged"->host.put("Privileged",true);
                case "auto-remove"->host.put("AutoRemove",true);case "memory"->host.put("Memory",0);case "cpu"->host.put("NanoCpus",0);case "pids"->host.put("PidsLimit",0);
                case "cap-add"->host.putArray("CapAdd").add("SYS_ADMIN");case "cap-drop"->host.putArray("CapDrop");case "security"->host.putArray("SecurityOpt");
                case "device"->host.putArray("Devices").add("/dev/x");case "ports"->host.putObject("PortBindings").put("80","80");case "volumes"->host.putArray("VolumesFrom").add("foreign");
                case "pid-mode"->host.put("PidMode","host");case "ipc-mode"->host.put("IpcMode","host");case "tmpfs"->host.putObject("Tmpfs").put("/tmp","rw");
                case "mount-rw"->((ObjectNode)actual.path("Mounts").get(0)).put("RW",true);case "mount-source"->((ObjectNode)actual.path("Mounts").get(0)).put("Source","/other");
                case "extra-mount"->((com.fasterxml.jackson.databind.node.ArrayNode)actual.path("Mounts")).addObject().put("Destination","/socket");
                case "log-driver"->((ObjectNode)host.path("LogConfig")).put("Type","json-file");case "log-limit"->((ObjectNode)host.path("LogConfig").path("Config")).put("max-size","1g");
                case "owner"->config.putObject("Labels").put("dsh.check-operation","foreign");default->throw new AssertionError(key);
            }
        }
    }
    @Test void capturesRawBytesFromSnapshotThenReleasesAndConfirmsAbsence()throws Exception {
        var result=run();assertEquals(TaskCheckExecutionOutcome.Termination.COMPLETED,result.termination());assertEquals(0,result.exitCode());
        assertEquals("NONE",result.diagnostic());assertTrue(result.cleanupConfirmed());assertTrue(client.released);assertTrue(client.removed);
        String path="target/surefire-reports/TEST-TrustedTest.xml";assertArrayEquals(xml,result.reportBytes(path));var copy=result.reportBytes(path);copy[0]^=1;assertArrayEquals(xml,result.reportBytes(path));
        assertThrows(UnsupportedOperationException.class,()->result.reportPaths().clear());assertThrows(IllegalArgumentException.class,()->result.reportBytes("other"));
        Path copied=temp.resolve("control/.check-executions/"+request.operationId()+"/candidate/pkg/Value.java");assertArrayEquals(Files.readAllBytes(request.candidateRoot().resolve("src/main/java/pkg/Value.java")),Files.readAllBytes(copied));
        assertFalse(Files.exists(copied.getParent().resolve("pom.xml")));assertEquals(client.id,result.containerId());
        var create=client.calls.stream().filter(c->c.get(1).equals("create")).findFirst().orElseThrow();assertTrue(create.contains("--pull=never"));assertFalse(create.contains("--rm"));assertFalse(create.contains("--env"));
        assertTrue(client.calls.stream().filter(c->List.of("start","rm","logs").contains(c.get(1))).allMatch(c->c.contains(client.id)));
        // The final ownership inspection starts the independent bounded recovery window.
        assertEquals(1,client.deadlines.subList(0,client.calls.indexOf(client.calls.stream().filter(c->c.get(1).equals("logs")).findFirst().orElseThrow())-1).stream().distinct().count());
    }
    @ParameterizedTest @ValueSource(strings={"image","user","workdir","argv","env","entrypoint","network","readonly","privileged","auto-remove","memory","cpu","pids","cap-add","cap-drop","security","device","ports","volumes","pid-mode","ipc-mode","tmpfs","mount-rw","mount-source","extra-mount","owner","log-driver","log-limit"})
    void actualIsolationDriftNeverStarts(String field){client.mode="policy:"+field;var result=run();assertFalse(client.started);assertNull(result.exitCode());
        assertEquals(TaskCheckExecutionOutcome.Termination.LAUNCH_FAILED,result.termination());
        if(field.equals("owner")){assertFalse(client.removed);assertFalse(result.cleanupConfirmed());}else{assertTrue(client.removed);assertTrue(result.cleanupConfirmed());}}
    @ParameterizedTest @ValueSource(strings={"link-report","directory-report","path-report","case-report","large-report","count-report","no-report","listing-failure","capture-failure","short-report"})
    void invalidReportsNeverAppearAsValidEvidence(String mode){client.mode=mode;var result=run();assertTrue(result.reportPaths().isEmpty());assertTrue(result.diagnostic().startsWith("REPORT_"));assertTrue(result.cleanupConfirmed());}
    @ParameterizedTest @ValueSource(strings={"cancel-after-create","cancel-release","cancel-cleanup"})
    void cancellationWinsEveryLateRace(String mode){client.mode=mode;var result=run();assertEquals(TaskCheckExecutionOutcome.Termination.CANCELLED,result.termination());assertNull(result.exitCode());assertTrue(result.cleanupConfirmed());}
    @ParameterizedTest @ValueSource(strings={"timeout-after-create","poll-timeout"})
    void cumulativeDeadlineIsNotRenewedByPolls(String mode){client.mode=mode;var result=run();assertEquals(TaskCheckExecutionOutcome.Termination.TIMEOUT,result.termination());assertNull(result.exitCode());assertTrue(result.cleanupConfirmed());}
    @ParameterizedTest @ValueSource(strings={"mismatch","early-exit","invalid-marker","release-failure"})
    void protocolLossHasNoNaturalExit(String mode){client.mode=mode;var result=run();assertEquals(TaskCheckExecutionOutcome.Termination.LOST,result.termination());assertNull(result.exitCode());assertTrue(result.cleanupConfirmed());}
    @ParameterizedTest @ValueSource(strings={"presence-present","presence-unknown"})
    void rmSuccessIsNotAbsence(String mode){client.mode=mode;var result=run();assertEquals(0,result.exitCode());assertFalse(result.cleanupConfirmed());}
    @Test void failedRmCanStillBeAbsentOnlyThroughSuccessfulProbe(){client.mode="rm-failure";assertTrue(run().cleanupConfirmed());}
    @Test void naturalNonzeroIsPreserved(){client.mode="nonzero";var result=run();assertEquals(1,result.exitCode());assertEquals(TaskCheckExecutionOutcome.Termination.COMPLETED,result.termination());}
    @Test void sourceDriftBeforeCreateRefuses()throws Exception {Files.writeString(request.candidateRoot().resolve("src/main/java/pkg/Value.java"),"changed");var result=run();assertEquals("SOURCE_DRIFT",result.diagnostic());assertTrue(client.calls.isEmpty());assertTrue(result.cleanupConfirmed());}
    @Test void sourceLinkBeforeCreateRefuses()throws Exception {Path file=request.candidateRoot().resolve("src/main/java/pkg/Value.java");Files.delete(file);Files.createSymbolicLink(file,temp.resolve("outside"));assertEquals("SOURCE_DRIFT",run().diagnostic());assertTrue(client.calls.isEmpty());}
    @Test void stagingLinkRefuses()throws Exception {Path control=temp.resolve("control");Files.createSymbolicLink(control.resolve(".check-executions"),request.candidateRoot());assertEquals("UNSAFE_DIRECTORY",run().diagnostic());assertTrue(client.calls.isEmpty());}
    @Test void repeatedOperationNeverOverwritesStaging(){assertEquals(0,run().exitCode());int count=client.calls.size();assertEquals("CONTROL_FAILED",run().diagnostic());assertEquals(count,client.calls.size());}
    @Test void cancelledBeforePreparationLaunchesNothing(){cancelled.set(true);var result=run();assertEquals(TaskCheckExecutionOutcome.Termination.CANCELLED,result.termination());assertTrue(client.calls.isEmpty());assertTrue(result.cleanupConfirmed());}
    @Test void localCannotFallBackToCommandExecution(){var local=new LocalProcessSandboxRunner(mock(com.dsh.application.DshProperties.class,RETURNS_DEEP_STUBS));assertThrows(UnsupportedOperationException.class,()->local.executeCheck(request,()->false));}
    @Test void nonNaturalOutcomeCannotInventExitCode(){assertThrows(IllegalArgumentException.class,()->new TaskCheckExecutionOutcome("op",null,TaskCheckExecutionOutcome.Termination.TIMEOUT,0,"",TaskCheckExecutionOutcome.Presence.UNKNOWN,new TaskCheckExecutionOutcome.Logs("","",0,0,false),0,Map.of()));}
    @Test void sourceServiceRevalidatesBeforeAndAfterNaturalCompletion(){
        var factory=mock(TaskCheckExecutionRequestFactory.class);var runner=mock(SandboxRunner.class);var signal=(java.util.function.BooleanSupplier)()->false;
        var outcome=run();when(runner.executeCheck(request,signal)).thenReturn(outcome);
        assertSame(outcome,new TaskCheckExecutionService(factory,runner).execute(request,signal));
        var order=inOrder(factory,runner);order.verify(factory).requireCurrent(request);order.verify(runner).executeCheck(request,signal);order.verify(factory).requireCurrent(request);
    }
    @Test void staleInputsCannotReachRunner(){
        var factory=mock(TaskCheckExecutionRequestFactory.class);var runner=mock(SandboxRunner.class);
        doThrow(new IllegalArgumentException("stale")).when(factory).requireCurrent(request);
        assertThrows(IllegalArgumentException.class,()->new TaskCheckExecutionService(factory,runner).execute(request,()->false));verifyNoInteractions(runner);
    }
    @Test void postExecutionDriftCannotReturnCurrentEvidence(){
        var factory=mock(TaskCheckExecutionRequestFactory.class);var runner=mock(SandboxRunner.class);var outcome=run();when(runner.executeCheck(eq(request),any())).thenReturn(outcome);
        doNothing().doThrow(new IllegalArgumentException("changed cache")).when(factory).requireCurrent(request);
        var result=new TaskCheckExecutionService(factory,runner).execute(request,()->false);
        assertEquals("INPUTS_STALE",result.diagnostic());assertEquals(TaskCheckExecutionOutcome.Termination.LOST,result.termination());
        assertNull(result.exitCode());assertTrue(result.cleanupConfirmed());assertEquals(outcome.reportPaths(),result.reportPaths());
    }
    @ParameterizedTest @ValueSource(strings={"create-failure","start-failure"})
    void launchFailureCannotInventExit(String mode){client.mode=mode;var result=run();assertEquals(TaskCheckExecutionOutcome.Termination.LAUNCH_FAILED,result.termination());assertNull(result.exitCode());}
    @Test void realProcessTransportIsByteBoundedAndDrainsLargeOutput()throws Exception {
        // This unit test runs only inside the Maven Docker test sandbox.
        var reply=new CheckDockerProcess().run(List.of("sh","-c","yes x | head -c 10000"),new byte[0],1024,System.nanoTime()+2_000_000_000L,()->false);
        assertEquals(0,reply.code());assertEquals(1024,reply.stdout().length);assertEquals(10000,reply.total());assertEquals(8976,reply.dropped());assertTrue(reply.truncated());
    }
    @Test void realProcessTimeoutKillsOwnedChildWithoutWaitingForItsSleep(){
        long start=System.nanoTime();var stop=assertThrows(DockerTaskCheckExecutor.Stop.class,()->new CheckDockerProcess().run(
                List.of("sh","-c","sleep 5"),new byte[0],1024,start+100_000_000L,()->false));
        assertEquals(TaskCheckExecutionOutcome.Termination.TIMEOUT,stop.termination);assertTrue(System.nanoTime()-start<2_000_000_000L);
    }
    @Test void alreadyCancelledTransportNeverStartsEvenAnInvalidExecutable(){
        var stop=assertThrows(DockerTaskCheckExecutor.Stop.class,()->new CheckDockerProcess().run(List.of("no-such-executable"),new byte[0],1024,System.nanoTime()+1_000_000_000L,()->true));
        assertEquals(TaskCheckExecutionOutcome.Termination.CANCELLED,stop.termination);
    }
}
