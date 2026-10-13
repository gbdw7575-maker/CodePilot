package com.dsh.orchestration.acceptance;

import com.dsh.workspace.SafePaths;
import com.dsh.workspace.WorkspaceIgnores;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.*;

/** Bounded immutable delivery bytes. Narrow SOURCE adapter rejects unsupported changed files. */
final class TaskCheckDelivery {
    static final int FILE_BYTES=1048576,TOTAL_BYTES=32*1048576;
    record File(String path,long bytes,String sha256) { }
    private final List<File> files;
    private final byte[] zip;
    private final String digest;
    private final String baselineDigest;
    private TaskCheckDelivery(List<File> files,byte[] zip,byte[] baseline) {
        this.files=List.copyOf(files);this.zip=zip.clone();this.baselineDigest=CanonicalTaskJson.sha256(baseline);
        this.digest=CanonicalTaskJson.digest(new ObjectMapper().valueToTree(Map.of("files",files,"baselineDigest",baselineDigest)));
    }
    List<File> files(){return files;}
    String digest(){return digest;}
    String baselineDigest(){return baselineDigest;}
    byte[] zip(){return zip.clone();}

    static TaskCheckDelivery capture(Path candidate,Path meta,List<TaskCheckExecutionRequest.SourceFile> source) {
        try {
            TaskCheckExecutionRequestFactory.safeDirectory(candidate);
            TaskCheckExecutionRequestFactory.safeDirectory(meta);
            byte[] manifest=read(meta.resolve("baseline-manifest.json"),2*1048576);
            var baseline=CanonicalTaskJson.parse(manifest);
            if(!baseline.isObject() || baseline.size()>4096) throw invalid();
            var baselineHashes=new TreeMap<String,String>();
            var keys=baseline.fieldNames();while(keys.hasNext()){
                String path=keys.next();var value=baseline.get(path);
                if(!relative(path) || !value.isTextual() || !value.textValue().matches("[a-f0-9]{64}")) throw invalid();
                if(!WorkspaceIgnores.isIgnored(path)) baselineHashes.put(path,value.textValue());
            }
            var current=new TreeMap<String,byte[]>();var aliases=new HashSet<String>();int[] dirs={0};long[] total={0};
            Files.walkFileTree(candidate,EnumSet.noneOf(FileVisitOption.class),32,new SimpleFileVisitor<>(){
                @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException {
                    String path=candidate.relativize(dir).toString().replace('\\','/');
                    if(++dirs[0]>8192 || SafePaths.isReparseRedirect(dir)) throw invalid();
                    if(WorkspaceIgnores.isIgnored(path,true)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                    String path=candidate.relativize(file).toString().replace('\\','/');
                    if(WorkspaceIgnores.isIgnored(path)) return FileVisitResult.CONTINUE;
                    if(!relative(path) || !attrs.isRegularFile() || SafePaths.isReparseRedirect(file)
                            || !aliases.add(path.toLowerCase(Locale.ROOT)) || current.size()>=4096) throw invalid();
                    byte[] bytes=read(file,FILE_BYTES);if((total[0]+=bytes.length)>TOTAL_BYTES) throw invalid();
                    current.put(path,bytes);return FileVisitResult.CONTINUE;
                }
            });
            var files=current.entrySet().stream().map(e->new File(e.getKey(),e.getValue().length,CanonicalTaskJson.sha256(e.getValue()))).toList();
            var actualSource=files.stream().filter(f->f.path().startsWith("src/main/java/")&&f.path().endsWith(".java"))
                    .map(f->new TaskCheckExecutionRequest.SourceFile(f.path(),f.bytes(),f.sha256())).toList();
            if(!source.equals(actualSource)) throw invalid();
            var hashes=new TreeMap<String,String>();for(File f:files) hashes.put(f.path(),f.sha256());
            var paths=new TreeSet<>(baselineHashes.keySet());paths.addAll(hashes.keySet());
            for(String path:paths) if(!Objects.equals(baselineHashes.get(path),hashes.get(path))
                    && !(path.startsWith("src/main/java/")&&path.endsWith(".java"))) throw invalid();
            var output=new ByteArrayOutputStream();try(var archive=new ZipOutputStream(output)){
                for(var entry:current.entrySet()){var zipEntry=new ZipEntry(entry.getKey());zipEntry.setTime(0);archive.putNextEntry(zipEntry);archive.write(entry.getValue());archive.closeEntry();}
            }
            if(!Arrays.equals(manifest,read(meta.resolve("baseline-manifest.json"),2*1048576))) throw invalid();
            return new TaskCheckDelivery(files,output.toByteArray(),manifest);
        } catch(IOException unsafe){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("INVALID_DELIVERY");}
    private static boolean relative(String path){return path!=null && !path.isBlank() && path.length()<=512
            && !path.startsWith("/") && !path.contains("\\") && !path.contains(":") && path.chars().noneMatch(c->c<32)
            && Arrays.stream(path.split("/",-1)).noneMatch(p->p.isEmpty()||p.equals(".")||p.equals(".."));}
    private static byte[] read(Path file,int limit)throws IOException {
        if(Files.isSymbolicLink(file)||SafePaths.isReparseRedirect(file)) throw invalid();
        var before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile()||before.size()>limit)throw invalid();
        byte[] bytes;try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(limit+1);}
        var after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(bytes.length!=before.size()||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(),after.fileKey()))throw invalid();return bytes;
    }
}
