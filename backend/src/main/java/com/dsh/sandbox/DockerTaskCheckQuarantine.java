package com.dsh.sandbox;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Fixed Docker control only. Reservations are intentionally retained across application restarts. */
final class DockerTaskCheckQuarantine {
    private final DockerTaskCheckExecutor.Client client;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    DockerTaskCheckQuarantine(){this(new CheckDockerProcess());}
    DockerTaskCheckQuarantine(DockerTaskCheckExecutor.Client client){this.client=client;}
    TaskCheckQuarantine fence(String op,String image,String owner){
        inputs(op,image,owner);String id=null;boolean interrupted=Thread.interrupted();
        long deadline=System.nanoTime()+30_000_000_000L;
        try{
            var existing=list(op,deadline);
            if(existing.size()==1){
                id=existing.getFirst();var n=inspect(id,deadline);
                if(n.path("Config").path("Labels").has("dsh.check-quarantine"))
                    return result(op,id,owner,image,valid(n,op,id,owner,image));
                if(!image.equals(n.path("Image").asText()))return result(op,id,owner,image,false);
            }
            if(existing.size()>1)return result(op,null,owner,image,false);
            if(!existing.isEmpty()){
                var cleaned=new DockerTaskCheckRecovery(client,System::nanoTime).cleanup(op,id);
                if(cleaned.presence()!=TaskCheckExecutionOutcome.Presence.ABSENT)return result(op,id,owner,image,false);
            }
            // Docker reserves the name atomically. A late original create may win this race;
            // conflict is UNKNOWN, never proof. A later recovery can inspect/remove that original.
            var created=call(List.of("docker","create","--pull=never","--name","dsh-check-"+op,
                "--label","dsh.check-operation="+op,"--label","dsh.check-quarantine="+owner,
                "--network","none","--read-only","--cap-drop","ALL","--security-opt","no-new-privileges",
                "--user","65534:65534","--memory","32m","--cpus","0.1","--pids-limit","16",
                "--entrypoint","/bin/true",image),4096,deadline);
            if(created.code()==0&&created.text().strip().matches("[a-f0-9]{64}"))id=created.text().strip();
            else {var after=list(op,deadline);if(after.size()!=1)return result(op,null,owner,image,false);id=after.getFirst();}
            return result(op,id,owner,image,verify(op,id,owner,image,deadline));
        }catch(Exception failed){return result(op,id,owner,image,false);}
        finally{interrupted|=Thread.interrupted();if(interrupted)Thread.currentThread().interrupt();}
    }
    boolean verify(String op,String id,String owner,String image){
        inputs(op,image,owner);if(id==null||!id.matches("[a-f0-9]{64}"))return false;
        try{return verify(op,id,owner,image,System.nanoTime()+15_000_000_000L);}catch(Exception failed){return false;}
    }
    private boolean verify(String op,String id,String owner,String image,long deadline)throws Exception{
        return list(op,deadline).equals(List.of(id))&&valid(inspect(id,deadline),op,id,owner,image);
    }
    private boolean valid(JsonNode n,String op,String id,String owner,String image){
        var labels=n.path("Config").path("Labels");var h=n.path("HostConfig");
        return id.equals(n.path("Id").asText())&&("/dsh-check-"+op).equals(n.path("Name").asText())
            &&image.equals(n.path("Image").asText())&&op.equals(labels.path("dsh.check-operation").asText())
            &&owner.equals(labels.path("dsh.check-quarantine").asText())&&"created".equals(n.path("State").path("Status").asText())
            &&!n.path("State").path("Running").asBoolean(true)&&"none".equals(h.path("NetworkMode").asText())
            &&h.path("ReadonlyRootfs").asBoolean(false)&&h.path("Memory").asLong()==33554432L
            &&h.path("PidsLimit").asLong()==16&&"65534:65534".equals(n.path("Config").path("User").asText())
            &&n.path("Mounts").isArray()&&n.path("Mounts").isEmpty();
    }
    private JsonNode inspect(String id,long deadline)throws Exception{var r=call(List.of("docker","inspect",id),1048576,deadline);
        if(r.code()!=0)throw new IllegalStateException("Inspect unavailable");var n=json.readTree(r.stdout());
        if(n==null||!n.isArray()||n.size()!=1)throw new IllegalStateException("Inspect invalid");return n.get(0);}
    private List<String> list(String op,long deadline)throws Exception{
        var names=ids("name=^/dsh-check-"+op+"$",deadline);
        var labels=ids("label=dsh.check-operation="+op,deadline);
        if(!names.equals(labels)||names.size()>1)throw new IllegalStateException("Identity conflict");return names;
    }
    private List<String> ids(String filter,long deadline)throws Exception{var r=call(List.of("docker","ps","-a","--no-trunc","--filter",filter,"--format","{{.ID}}"),4096,deadline);
        if(r.code()!=0)throw new IllegalStateException("List unavailable");var ids=r.text().lines().sorted().toList();
        if(ids.stream().anyMatch(s->!s.matches("[a-f0-9]{64}"))||new HashSet<>(ids).size()!=ids.size())throw new IllegalStateException("List invalid");return ids;}
    private DockerTaskCheckExecutor.Reply call(List<String> argv,int limit,long deadline)throws Exception{
        DockerTaskCheckExecutor.check(deadline,()->false);var r=client.run(argv,new byte[0],limit,deadline,()->false);
        DockerTaskCheckExecutor.check(deadline,()->false);
        if(r==null||r.truncated()||r.dropped()!=0||r.stdout().length>limit||r.stderr().length>limit)throw new IllegalStateException("Incomplete transport");return r;
    }
    private static void inputs(String op,String image,String owner){if(op==null||!op.matches("[a-f0-9]{32}")||owner==null||!owner.matches("[a-f0-9]{32}")||image==null||!image.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid quarantine identity");}
    private static TaskCheckQuarantine result(String op,String id,String owner,String image,boolean fenced){return new TaskCheckQuarantine(op,id,owner,image,fenced);}
}
