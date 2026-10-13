package com.dsh.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Docker control CLI only. Raw report bytes never pass through text decoding or a host shell. */
final class CheckDockerProcess implements DockerTaskCheckExecutor.Client {
    @Override public DockerTaskCheckExecutor.Reply run(List<String> argv,byte[] input,int limit,
                                                      long deadline,BooleanSupplier cancelled) throws Exception {
        DockerTaskCheckExecutor.check(deadline,cancelled);
        Process process=new ProcessBuilder(argv).start();
        boolean logs=argv.size()>1&&argv.get(1).equals("logs");
        var out=new Capture(process.getInputStream(),limit,logs);
        var err=new Capture(process.getErrorStream(),logs?limit:Math.min(limit,8192),logs);
        var failure=new java.util.concurrent.atomic.AtomicReference<IOException>();
        Thread stdout=Thread.ofPlatform().daemon().start(out),stderr=Thread.ofPlatform().daemon().start(err);
        Thread stdin=Thread.ofPlatform().daemon().start(()->{
            try(var pipe=process.getOutputStream()){pipe.write(input);}catch(IOException e){failure.set(e);}
        });
        try {
            while(true){
                DockerTaskCheckExecutor.check(deadline,cancelled);
                if(process.waitFor(50,TimeUnit.MILLISECONDS))break;
            }
            for(Thread thread:List.of(stdin,stdout,stderr)) {
                while(thread.isAlive()) { DockerTaskCheckExecutor.check(deadline,cancelled);thread.join(25); }
            }
            DockerTaskCheckExecutor.check(deadline,cancelled);
            if(failure.get()!=null || out.failed || err.failed)throw new IOException("Incomplete Docker transport");
            return new DockerTaskCheckExecutor.Reply(process.exitValue(),out.bytes(),err.bytes(),out.total+err.total,
                    out.dropped()+err.dropped(),out.overflow||err.overflow);
        } finally {
            if(process.isAlive()){
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            // Closing pipes releases readers; no unbounded join beyond the operation deadline.
            try{process.getInputStream().close();}catch(IOException ignored){}
            try{process.getErrorStream().close();}catch(IOException ignored){}
            try{process.getOutputStream().close();}catch(IOException ignored){}
        }
    }
    private static final class Capture implements Runnable {
        private final java.io.InputStream stream; private final int limit;
        private final BoundedStreamCollector logCollector;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private volatile long total; private volatile boolean overflow,failed;
        Capture(java.io.InputStream stream,int limit,boolean logs){this.stream=stream;this.limit=limit;
            this.logCollector=logs?new BoundedStreamCollector(stream,limit):null;}
        @Override public void run(){
            if(logCollector!=null){logCollector.run();total=logCollector.totalBytes();overflow=logCollector.truncated();return;}
            try(var in=stream){byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1){total+=n;int keep=Math.min(n,Math.max(0,limit-bytes.size()));
                    bytes.write(buffer,0,keep);if(keep<n)overflow=true;}
            }catch(IOException e){failed=true;}
        }
        byte[] bytes(){return logCollector==null?bytes.toByteArray():logCollector.text().getBytes(StandardCharsets.UTF_8);}
        long dropped(){return logCollector==null?Math.max(0,total-bytes.size()):logCollector.droppedBytes();}
    }
}
