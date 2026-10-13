package com.dsh.sandbox;

import com.dsh.orchestration.acceptance.CanonicalTaskJson;
import com.dsh.orchestration.acceptance.TaskCheckExecutionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

import static com.dsh.sandbox.TaskCheckExecutionOutcome.Presence;
import static com.dsh.sandbox.TaskCheckExecutionOutcome.Termination;

/** Narrow trusted Maven SOURCE adapter. No model argv, host shell, socket, task transition or PASS flag. */
final class DockerTaskCheckExecutor {
    interface Client {
        Reply run(List<String> argv, byte[] input, int limit, long deadline, BooleanSupplier cancelled) throws Exception;
    }
    record Reply(int code, byte[] stdout, byte[] stderr, long total, long dropped, boolean truncated) {
        String text(){return new String(stdout,StandardCharsets.UTF_8);}
    }
    static final class Stop extends Exception {
        final Termination termination;
        Stop(Termination termination){this.termination=termination;}
    }
    private static final BooleanSupplier NEVER=()->false;
    private static final String REPORT_ROOT="/tmp/check/target/surefire-reports";
    private final Client client;
    private final java.util.function.LongSupplier clock;
    private final ObjectMapper json=new ObjectMapper();

    DockerTaskCheckExecutor(){this(new CheckDockerProcess(),System::nanoTime);}
    DockerTaskCheckExecutor(Client client,java.util.function.LongSupplier clock){this.client=client;this.clock=clock;}

    TaskCheckExecutionOutcome execute(TaskCheckExecutionRequest request,BooleanSupplier cancelled){
        Objects.requireNonNull(request);Objects.requireNonNull(cancelled);
        long start=clock.getAsLong(),deadline=start+request.check().timeoutSeconds()*1_000_000_000L;
        var plan=DockerCheckIsolationPlan.from(request);
        String id=null,diagnostic="NONE";boolean createAttempted=false;
        Termination termination=Termination.LAUNCH_FAILED;Integer exit=null;
        Presence presence=Presence.UNKNOWN;
        var reports=new TreeMap<String,byte[]>();
        var logs=new TaskCheckExecutionOutcome.Logs("","",0,0,false);
        boolean interrupted=false;
        try {
            guard(deadline,cancelled);
            Path[] mounts=materialize(request,deadline,cancelled);
            var image=inspectImage(plan.image(),deadline,cancelled);
            var argv=new ArrayList<>(List.of("docker","create","--pull=never"));argv.addAll(plan.createOptions());
            // Bound Docker's on-disk logs as well as the controller's in-memory capture.
            argv.addAll(List.of("--log-driver","local","--log-opt","max-size=1m","--log-opt","max-file=1","--log-opt","compress=false"));
            for(int i=0;i<3;i++)argv.addAll(List.of("--mount","type=bind,src="+mountSource(mounts[i])+",dst="+plan.readOnlyMountTargets().get(i)+",readonly"));
            argv.add(plan.image());argv.addAll(plan.argv());
            createAttempted=true;
            var created=call(argv,new byte[0],4096,deadline,cancelled);
            require(created.code()==0 && !created.truncated(),"CREATE_FAILED");
            id=created.text().strip();require(id.matches("[a-f0-9]{64}"),"INVALID_CONTAINER_ID");
            var actual=inspect(id,deadline,cancelled);
            require(owned(actual,id,plan),"OWNERSHIP_MISMATCH");
            require(policyMatches(actual,plan,mounts,image),"ISOLATION_MISMATCH");
            guard(deadline,cancelled);
            require(call(List.of("docker","start",id),new byte[0],4096,deadline,cancelled).code()==0,"START_FAILED");
            termination=Termination.LOST;
            Integer mavenExit=null;
            while(mavenExit==null){
                var marker=script(id,"test ! -L /tmp/check && test -f /tmp/check/maven.exit && test ! -L /tmp/check/maven.exit && cat /tmp/check/maven.exit\n",128,deadline,cancelled);
                if(marker.code()==0){require(!marker.truncated()&&marker.text().matches("[0-9]{1,3}"),"INVALID_MAVEN_EXIT");
                    mavenExit=Integer.parseInt(marker.text());require(mavenExit<=255,"INVALID_MAVEN_EXIT");}
                else {var state=inspect(id,deadline,cancelled);require(state.path("State").path("Running").asBoolean(),"EXIT_BEFORE_CAPTURE");
                    guard(deadline,cancelled);Thread.sleep(100);}
            }
            try { reports.putAll(collect(id,request,deadline,cancelled)); }
            catch(Refusal refused){diagnostic=refused.getMessage();reports.clear();}
            catch(IOException incomplete){diagnostic="REPORT_CAPTURE_FAILED";reports.clear();}
            require(script(id,"touch /tmp/check/release\n",128,deadline,cancelled).code()==0,"RELEASE_FAILED");
            while(true){
                var state=inspect(id,deadline,cancelled).path("State");
                if(!state.path("Running").asBoolean()){
                    require("exited".equals(state.path("Status").asText()) && !state.path("OOMKilled").asBoolean()
                            && !state.path("Dead").asBoolean() && state.path("Error").asText().isEmpty(),"ABNORMAL_EXIT");
                    int actualExit=state.path("ExitCode").asInt(-1);require(actualExit==mavenExit,"EXIT_MISMATCH");
                    guard(deadline,cancelled);exit=actualExit;termination=Termination.COMPLETED;break;
                }
                guard(deadline,cancelled);Thread.sleep(100);
            }
        } catch(Stop stopped){termination=stopped.termination;diagnostic=stopped.termination.name();exit=null;
        } catch(InterruptedException stopped){interrupted=true;Thread.interrupted();termination=Termination.CANCELLED;diagnostic="CANCELLED";exit=null;
        } catch(Exception failed){diagnostic=failed instanceof Refusal ? failed.getMessage():"CONTROL_FAILED";exit=null;
        } finally {
            interrupted|=Thread.interrupted();
            // This fixed recovery budget cannot grant additional time to the check itself.
            long recovery=clock.getAsLong()+15_000_000_000L;
            try {
                if(id==null && createAttempted){
                    var discovered=inspect(plan.containerName(),recovery,NEVER);String candidate=discovered.path("Id").asText();
                    if(candidate.matches("[a-f0-9]{64}")&&owned(discovered,candidate,plan))id=candidate;
                }
                if(id!=null){
                    var actual=inspect(id,recovery,NEVER);
                    if(owned(actual,id,plan)){
                        try{
                            var output=call(List.of("docker","logs",id),new byte[0],request.execution().limits().logBytes()/2,
                                    Math.min(recovery,clock.getAsLong()+3_000_000_000L),NEVER);
                            logs=new TaskCheckExecutionOutcome.Logs(output.text(),new String(output.stderr(),StandardCharsets.UTF_8),
                                    output.total(),output.dropped(),output.truncated()||output.code()!=0);
                        }catch(Exception failed){logs=new TaskCheckExecutionOutcome.Logs("","",0,0,true);}
                        call(List.of("docker","rm","-f",id),new byte[0],4096,recovery,NEVER);
                        presence=presence(id,recovery);
                    }else diagnostic="CLEANUP_OWNERSHIP_MISMATCH";
                } else if(!createAttempted)presence=Presence.ABSENT;
                else {
                    var names=call(List.of("docker","ps","-a","--no-trunc","--filter","name=^/"+plan.containerName()+"$","--format","{{.ID}}"),new byte[0],4096,recovery,NEVER);
                    if(names.code()==0&&!names.truncated()&&names.text().isBlank())presence=Presence.ABSENT;
                }
            }catch(Exception unknown){presence=Presence.UNKNOWN;}
            // Cancellation wins even over completion/cleanup races; never restore a natural exit code.
            if(interrupted||Thread.currentThread().isInterrupted()||cancelled.getAsBoolean()){
                termination=Termination.CANCELLED;diagnostic="CANCELLED";exit=null;
            }
            if(interrupted)Thread.currentThread().interrupt();
        }
        return new TaskCheckExecutionOutcome(request.operationId(),id,termination,exit,diagnostic,presence,logs,
                Math.max(0,(clock.getAsLong()-start)/1_000_000L),reports);
    }

    private Map<String,byte[]> collect(String id,TaskCheckExecutionRequest request,long deadline,BooleanSupplier cancelled)throws Exception {
        var limits=request.execution().limits();
        // Flat Surefire XML only; reject any linked ancestor/entry and all unexpected directories.
        String listing="for p in /tmp/check /tmp/check/target "+REPORT_ROOT+"; do test -d $p && test ! -L $p || exit 2; done\n"
                +"find "+REPORT_ROOT+" -mindepth 1 -maxdepth 1 -printf '%f\\t%y\\t%s\\n'\n";
        var entries=script(id,listing,32768,deadline,cancelled);
        require(entries.code()==0&&!entries.truncated(),"REPORT_LIST_FAILED");
        var result=new TreeMap<String,byte[]>();var aliases=new HashSet<String>();long total=0;
        for(String entry:entries.text().split("\n")){
            if(entry.isEmpty())continue;String[] fields=entry.split("\t",-1);
            require(fields.length==3&&fields[1].equals("f")&&fields[2].matches("[0-9]{1,10}"),"REPORT_NOT_REGULAR");
            String name=fields[0];require(name.matches("[A-Za-z0-9_.$-]{1,240}\\.(xml|txt)"),"REPORT_PATH_INVALID");
            if(!name.endsWith(".xml"))continue;
            require(name.startsWith("TEST-")&&aliases.add(name.toLowerCase(Locale.ROOT)),"REPORT_PATH_INVALID");
            long size=Long.parseLong(fields[2]);total+=size;
            require(size>0&&size<=limits.reportFileBytes()&&total<=limits.reportTotalBytes()
                    &&result.size()<limits.reportFiles(),"REPORT_LIMIT");
            // cat's output is raw bytes. A growing/replaced file or nonzero exit is never accepted.
            var raw=script(id,"test -f "+REPORT_ROOT+"/"+name+" && test ! -L "+REPORT_ROOT+"/"+name+" && cat "+REPORT_ROOT+"/"+name+"\n",
                    limits.reportFileBytes(),deadline,cancelled);
            require(raw.code()==0&&!raw.truncated()&&raw.stdout().length==size,"REPORT_CAPTURE_FAILED");
            result.put("target/surefire-reports/"+name,raw.stdout());
        }
        require(!result.isEmpty(),"REPORT_MISSING");return result;
    }

    private Path[] materialize(TaskCheckExecutionRequest request,long deadline,BooleanSupplier cancelled)throws Exception {
        Path cache=request.execution().cacheRoot();safeDirectory(cache);
        Path parent=cache.getParent().resolve(".check-executions");
        if(!Files.exists(parent,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(parent);
        safeDirectory(parent);Path root=parent.resolve(request.operationId());Files.createDirectory(root);
        Path candidate=Files.createDirectory(root.resolve("candidate")),verifier=Files.createDirectory(root.resolve("verifier"));
        for(var source:request.candidateFiles()){
            guard(deadline,cancelled);Path original=request.candidateRoot().resolve(source.path());safeDirectory(original.getParent());
            require(Files.isRegularFile(original,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(original)
                    &&!com.dsh.workspace.SafePaths.isReparseRedirect(original)&&Files.size(original)==source.bytes(),"SOURCE_DRIFT");
            byte[] bytes;
            try(var in=Files.newInputStream(original,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes((int)source.bytes()+1);}
            require(bytes.length==source.bytes()&&CanonicalTaskJson.sha256(bytes).equals(source.sha256()),"SOURCE_DRIFT");
            // Trusted POM's /candidate source directory sees only declared Java files.
            Path destination=candidate.resolve(source.path().substring("src/main/java/".length()));
            Files.createDirectories(destination.getParent());Files.write(destination,bytes,StandardOpenOption.CREATE_NEW);
        }
        for(var dependency:request.check().verifiers()){
            guard(deadline,cancelled);byte[] bytes=request.verifierBytes(dependency.path());
            require(CanonicalTaskJson.sha256(bytes).equals(dependency.sha256()),"VERIFIER_DRIFT");
            Path destination=verifier.resolve(dependency.path()).normalize();require(destination.startsWith(verifier),"VERIFIER_PATH_INVALID");
            Files.createDirectories(destination.getParent());Files.write(destination,bytes,StandardOpenOption.CREATE_NEW);
        }
        guard(deadline,cancelled);return new Path[]{candidate,verifier,cache};
    }
    private static void safeDirectory(Path path)throws IOException {
        for(Path current=path.toAbsolutePath().normalize();current!=null;current=current.getParent()){
            var attributes=Files.readAttributes(current,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            require(attributes.isDirectory()&&!attributes.isSymbolicLink()&&!attributes.isOther()
                    &&!com.dsh.workspace.SafePaths.isReparseRedirect(current),"UNSAFE_DIRECTORY");
        }
    }
    private static String mountSource(Path path){
        String value=path.toAbsolutePath().normalize().toString().replace('\\','/');
        require(!value.contains(",")&&!value.contains("\n")&&!value.contains("\r"),"UNSUPPORTED_MOUNT_PATH");return value;
    }
    private JsonNode inspect(String id,long deadline,BooleanSupplier cancelled)throws Exception {
        var result=call(List.of("docker","inspect",id),new byte[0],1024*1024,deadline,cancelled);
        require(result.code()==0&&!result.truncated(),"INSPECT_FAILED");return one(result.stdout());
    }
    private JsonNode inspectImage(String image,long deadline,BooleanSupplier cancelled)throws Exception {
        var result=call(List.of("docker","image","inspect",image),new byte[0],1024*1024,deadline,cancelled);
        require(result.code()==0&&!result.truncated(),"IMAGE_UNAVAILABLE");var data=one(result.stdout());
        require(image.equals(data.path("Id").asText()),"IMAGE_MISMATCH");return data;
    }
    private JsonNode one(byte[] bytes)throws IOException {
        var value=json.readTree(bytes);require(value!=null&&value.isArray()&&value.size()==1&&value.get(0).isObject(),"INVALID_INSPECT");return value.get(0);
    }
    static boolean owned(JsonNode actual,String id,DockerCheckIsolationPlan plan){
        return id.equals(actual.path("Id").asText())&&("/"+plan.containerName()).equals(actual.path("Name").asText())
                &&plan.inputs().operationId().equals(actual.path("Config").path("Labels").path("dsh.check-operation").asText());
    }
    static boolean policyMatches(JsonNode actual,DockerCheckIsolationPlan plan,Path[] mounts,JsonNode image){
        var config=actual.path("Config");var host=actual.path("HostConfig");var limits=plan.inputs().execution().limits();
        if(!plan.image().equals(actual.path("Image").asText())||!"1000:1000".equals(config.path("User").asText())
                ||!"/verifier".equals(config.path("WorkingDir").asText())||!config.path("Cmd").equals(new ObjectMapper().valueToTree(plan.argv()))
                ||!config.path("Entrypoint").equals(image.path("Config").path("Entrypoint"))
                ||!config.path("Env").equals(image.path("Config").path("Env"))||!"none".equals(host.path("NetworkMode").asText())
                ||!host.path("ReadonlyRootfs").asBoolean()||host.path("Privileged").asBoolean()||host.path("AutoRemove").asBoolean()
                ||host.path("Memory").asLong()!=limits.memoryBytes()||host.path("NanoCpus").asLong()!=limits.cpuMillis()*1_000_000L
                ||host.path("PidsLimit").asLong()!=limits.pids()||!empty(host.path("CapAdd"))||!empty(host.path("Devices"))
                ||!empty(host.path("DeviceRequests"))||!empty(host.path("PortBindings"))||!empty(host.path("VolumesFrom"))
                ||!empty(host.path("PidMode"))||!"private".equals(host.path("IpcMode").asText())
                ||!host.path("CapDrop").equals(new ObjectMapper().valueToTree(List.of("ALL")))
                ||!host.path("SecurityOpt").equals(new ObjectMapper().valueToTree(List.of("no-new-privileges"))))return false;
        var logging=host.path("LogConfig");
        if(!"local".equals(logging.path("Type").asText())||!logging.path("Config").equals(new ObjectMapper().valueToTree(
                Map.of("max-size","1m","max-file","1","compress","false"))))return false;
        var tmp=host.path("Tmpfs");
        if(tmp.size()!=1||!tmp.path("/tmp").asText().equals("rw,exec,nosuid,nodev,size="+limits.tmpfsBytes()+",mode=1777"))return false;
        var actualMounts=actual.path("Mounts");if(!actualMounts.isArray()||actualMounts.size()!=3)return false;
        var seen=new HashSet<String>();
        for(var mount:actualMounts){String target=mount.path("Destination").asText();int index=plan.readOnlyMountTargets().indexOf(target);
            if(index<0||!seen.add(target)||!"bind".equals(mount.path("Type").asText())||mount.path("RW").asBoolean()
                    ||!mountSource(mounts[index]).equals(mount.path("Source").asText()))return false;}
        return true;
    }
    private static boolean empty(JsonNode node){return node.isMissingNode()||node.isNull()||node.isContainerNode()&&node.isEmpty()||node.isTextual()&&node.asText().isEmpty();}
    private Presence presence(String id,long deadline)throws Exception {
        var state=call(List.of("docker","ps","-a","--no-trunc","--filter","id="+id,"--format","{{.ID}}"),new byte[0],4096,deadline,NEVER);
        if(state.code()!=0||state.truncated())return Presence.UNKNOWN;
        if(state.text().isBlank())return Presence.ABSENT;
        return state.text().strip().equals(id)?Presence.PRESENT:Presence.UNKNOWN;
    }
    private Reply script(String id,String script,int limit,long deadline,BooleanSupplier cancelled)throws Exception {
        return call(List.of("docker","exec","-i",id,"sh","-c","base64 -d | sh"),
                Base64.getEncoder().encode(script.getBytes(StandardCharsets.UTF_8)),limit,deadline,cancelled);
    }
    private Reply call(List<String> argv,byte[] input,int limit,long deadline,BooleanSupplier cancelled)throws Exception {
        guard(deadline,cancelled);var reply=client.run(List.copyOf(argv),input,limit,deadline,cancelled);guard(deadline,cancelled);return reply;
    }
    private void guard(long deadline,BooleanSupplier cancelled)throws Stop {
        if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new Stop(Termination.CANCELLED);
        if(clock.getAsLong()>=deadline)throw new Stop(Termination.TIMEOUT);
    }
    static void check(long deadline,BooleanSupplier cancelled)throws Stop {
        if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new Stop(Termination.CANCELLED);
        if(System.nanoTime()>=deadline)throw new Stop(Termination.TIMEOUT);
    }
    private static final class Refusal extends IllegalArgumentException {Refusal(String diagnostic){super(diagnostic);}}
    private static void require(boolean valid,String diagnostic){if(!valid)throw new Refusal(diagnostic);}
}
