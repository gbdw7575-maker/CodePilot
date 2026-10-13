package com.dsh.application;

import com.dsh.common.Digest;
import com.dsh.common.IdGen;
import com.dsh.sandbox.*;
import com.dsh.workspace.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

/** C28: compare the same registered assertion on two disposable tracked-file views. */
public final class DefectReproduction {
    public record Observation(String side, CommandOutcome outcome) {
        public String log() { return (outcome.stdout()==null?"":outcome.stdout())+"\n[stderr]\n"
                +(outcome.stderr()==null?"":outcome.stderr()); }
    }
    public record Result(boolean proven, String reason, String baselineDigest, String candidateDigest, List<Observation> observations) {}
    private final WorkspaceManager workspaces;
    private final SandboxRunner sandbox;
    public DefectReproduction(WorkspaceManager workspaces, SandboxRunner sandbox) {
        this.workspaces=workspaces; this.sandbox=sandbox;
    }
    public static String baselineDigest(WorkspaceManager workspaces, Path meta) {
        Path manifest=meta.resolve("baseline-manifest.json");
        try {
            if (!Files.isRegularFile(manifest,LinkOption.NOFOLLOW_LINKS) || SafePaths.isReparseRedirect(manifest))
                throw new IllegalStateException("基线清单不可用");
        } catch (IOException e) { throw new IllegalStateException("基线清单不可用"); }
        var hashes=workspaces.readBaselineManifest(meta);
        return digest(hashes);
    }
    private static String digest(Map<String,String> hashes) {
        var text=new StringBuilder();
        new TreeMap<>(hashes).forEach((p,h)->text.append(p).append('\0').append(h).append('\0'));
        return Digest.sha256Hex(text.toString());
    }
    public Result execute(WorkspaceLease lease, DefectReproductionPlan plan, int timeout) {
        if (plan==null) return new Result(false,"DEFINITION_MISSING",null,null,List.of());
        if (timeout<=0) return new Result(false,"TIME_BUDGET_EXPIRED",null,null,List.of());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(timeout);
        Path scratch=null;
        var observations=new ArrayList<Observation>();
        String baselineDigest=null, candidateDigest=null;
        try {
            Path meta=lease.metaDir();
            if (SafePaths.isReparseRedirect(meta)) throw new IOException("metadata redirect");
            baselineDigest=baselineDigest(workspaces,meta);
            var baseline=workspaces.readBaselineManifest(meta);
            if (baseline.isEmpty()) throw new IOException("empty baseline");
            var candidate=WorkspaceManager.fileHashes(lease.dir());
            candidateDigest=digest(candidate);
            scratch=Files.createTempDirectory(meta,"defect-check-");
            Path before=scratch.resolve("baseline"),after=scratch.resolve("candidate");
            copyView(workspaces.baselineContentDir(meta),before,baseline);
            copyView(lease.dir(),after,candidate);
            String command=probeCommand(plan);
            for (var side:List.of("baseline","candidate")) {
                Path dir=side.equals("baseline")?before:after;
                var view=new WorkspaceLease(lease.workspaceId(),lease.runId(),lease.projectId(),
                        lease.sourceRoot(),dir,meta,baselineDigest,Instant.now());
                long remaining=deadline-System.nanoTime();
                if (remaining<=0) return new Result(false,"TIME_BUDGET_EXPIRED",baselineDigest,candidateDigest,List.copyOf(observations));
                int seconds=(int)Math.min(timeout,Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(remaining)));
                var outcome=sandbox.execute(new CommandSpec(IdGen.next("op_"),command,dir,seconds),view);
                observations.add(new Observation(side,outcome));
                // Probe commands may write generated files, but cannot rewrite the assertion's inputs.
                if (!WorkspaceManager.fileHashes(dir).equals(side.equals("baseline")?baseline:candidate))
                    return new Result(false,"SNAPSHOT_CHANGED",baselineDigest,candidateDigest,List.copyOf(observations));
            }
            boolean proven=valid(observations.get(0).outcome(),plan,false)
                    && valid(observations.get(1).outcome(),plan,true);
            return new Result(proven,proven?"BASELINE_FAIL_CANDIDATE_PASS":"ASSERTION_UNPROVEN",
                    baselineDigest,candidateDigest,List.copyOf(observations));
        } catch (RuntimeException | IOException unavailable) {
            // No arbitrary parser/command/source exception text in public diagnostic summaries.
            return new Result(false,"SNAPSHOT_OR_EXECUTION_UNAVAILABLE",baselineDigest,candidateDigest,List.copyOf(observations));
        } finally {
            if (scratch!=null) deleteOwnView(scratch);
        }
    }
    static boolean valid(CommandOutcome outcome, DefectReproductionPlan plan, boolean pass) {
        if (outcome==null || outcome.termination()!=CommandSpec.Termination.COMPLETED
                || !outcome.cleanupConfirmed() || outcome.truncated() || outcome.droppedBytes()>0
                || !Objects.equals(outcome.exitCode(),pass?0:1)
                || outcome.stderr()!=null && !outcome.stderr().isBlank()) return false;
        String output=outcome.stdout()==null?"":outcome.stdout();
        var lines=output.lines().filter(x->!x.isBlank()).toList();
        long markers=lines.stream().filter(x->x.startsWith("DSH_DEFECT_V1 ")).count();
        if (markers!=1 || lines.isEmpty() || !lines.getLast().equals(plan.marker(pass))) return false;
        var tests=VerificationService.parseTestCounts(output);
        if (tests.discovered()==0 && java.util.regex.Pattern.compile(
                "(?im)(?:\\bTests run:\\s*0\\b|\\bno tests (?:ran|collected)\\b|\\bcollected 0 items\\b)")
                .matcher(output).find()) return false;
        // Dedicated protocol represents one explicit assertion. A skipped framework run is not evidence.
        return !(tests.discovered()>0 && tests.passed()==0 && tests.failures()==0)
                && (pass?tests.failures()==0:true);
    }
    static String probeCommand(DefectReproductionPlan plan) {
        String encoded=Base64.getEncoder().encodeToString(plan.content().getBytes(StandardCharsets.UTF_8));
        return "set -eu\nDSH_WORKSPACE=\"$PWD\"; export DSH_WORKSPACE\n"
                +"probe_dir=$(mktemp -d /tmp/dsh-defect.XXXXXXXX)\n"
                +"trap 'rm -rf -- \"$probe_dir\"' EXIT\n"
                +"DSH_PROBE_FILE=\"$probe_dir/"+plan.fileName()+"\"; export DSH_PROBE_FILE\n"
                +"printf '%s' '"+encoded+"' | base64 -d > \"$DSH_PROBE_FILE\"\n"
                +StrictShellCommand.wrap(plan.command())+"\n";
    }
    private static void copyView(Path source, Path target, Map<String,String> hashes) throws IOException {
        if (!Files.isDirectory(source) || SafePaths.isReparseRedirect(source) || hashes.size()>10000)
            throw new IOException("unavailable snapshot");
        Files.createDirectories(target);
        long total=0;
        for (var entry:hashes.entrySet()) {
            SafePaths.assertRelativeInside(entry.getKey());
            Path from=SafePaths.resolve(source,entry.getKey()),to=SafePaths.resolve(target,entry.getKey());
            if (!Files.isRegularFile(from,LinkOption.NOFOLLOW_LINKS) || SafePaths.isReparseRedirect(from))
                throw new IOException("incomplete snapshot");
            byte[] bytes;
            try(var input=Files.newInputStream(from)) { bytes=input.readNBytes(1024*1024+1); }
            total+=bytes.length;
            if (bytes.length>1024*1024 || total>64L*1024*1024 || !Digest.sha256Hex(bytes).equals(entry.getValue()))
                throw new IOException("snapshot drift or limit");
            Files.createDirectories(to.getParent()); Files.write(to,bytes,StandardOpenOption.CREATE_NEW);
        }
        if (!WorkspaceManager.fileHashes(target).equals(hashes)) throw new IOException("snapshot mismatch");
    }
    private static void deleteOwnView(Path root) {
        // Only the newly created directory; walkFileTree does not follow links made by an assertion.
        try { Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException {
                Files.delete(file);return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException error) throws IOException {
                if(error!=null)throw error;Files.delete(dir);return FileVisitResult.CONTINUE;
            }
        }); } catch(IOException cleanupFailure) { throw new IllegalStateException("缺陷复现副本清理未确认"); }
    }
}
