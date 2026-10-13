package com.dsh.evals;

import com.dsh.agent.*;
import com.dsh.agent.tool.ToolRegistry;
import com.dsh.application.DshProperties;
import com.dsh.evals.model.*;
import com.dsh.orchestration.ChildRunLifecycle;
import com.dsh.orchestration.task.TaskVerificationService;
import com.dsh.rules.*;
import com.dsh.workspace.WorkspaceIgnores;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** E07: trusted evaluator snapshots public fingerprints, never credentials or prompt contents. */
@Service
public class EvalManifestService {
    private final DshProperties p;
    private final LoopSteps steps;
    private final AgentLoop loop;
    private final ToolRegistry tools;
    private final ChildRunLifecycle children;
    private final ObjectProvider<TaskVerificationService> verification;
    private final Environment environment;
    private final RuleLoader rules;
    private final ObjectMapper mapper=new ObjectMapper();
    public EvalManifestService(DshProperties p, LoopSteps steps, AgentLoop loop, ToolRegistry tools,
                               ChildRunLifecycle children, ObjectProvider<TaskVerificationService> verification,
                               Environment environment, RuleLoader rules) {
        this.p=p;this.steps=steps;this.loop=loop;this.tools=tools;this.children=children;
        this.verification=verification;this.environment=environment;this.rules=rules;
    }
    public EvalExecutionManifest capture(EvalConfig config, EvalDataset dataset, Path baseline, String projectId) {
        try {
            ObjectNode factors=mapper.createObjectNode();List<String> limits=new ArrayList<>();
            factors.put("eval.strategy",config.strategy().name());
            factors.put("eval.datasetName",dataset.name());factors.put("eval.datasetVersion",dataset.version());
            factors.put("eval.datasetContentSha256",datasetDigest(dataset));
            factors.put("eval.split",config.split());factors.set("eval.sampleIds",mapper.valueToTree(config.sampleIds().stream().sorted().toList()));
            factors.put("eval.modelProfileConfigDigest",config.modelProfileConfigDigest());
            factors.set("eval.budget",mapper.valueToTree(config.budget()));factors.put("eval.repeatCount",config.repeatCount());
            if(config.seed()==null)factors.putNull("eval.seed");else factors.put("eval.seed",config.seed());
            put(factors,"budget.defaults",p.budget());put(factors,"workspace.settings",p.workspace());
            put(factors,"loop.synchronous",p.loop().synchronous());
            steps.executionSettings().forEach((k,v)->put(factors,k,v));
            factors.put("loop.verificationRetryLimit",loop.executionVerificationRetryLimit());
            var orch=mapper.valueToTree(p.orchestration());
            ((ObjectNode)orch).remove("enabled");factors.set("orchestration.settings",orch);
            factors.put("orchestration.enabled",p.orchestration().enabled());
            if(p.orchestration().enabled() != (config.strategy()==EvalStrategy.MULTI))limits.add("STRATEGY_RUNTIME_SWITCH_MISMATCH");
            TaskVerificationService gate=verification.getIfAvailable();
            factors.put("orchestration.acceptanceCoverageMode",gate==null
                    ? TaskVerificationService.normalizedCoverageMode(environment.getProperty("dsh.orchestration.acceptance-coverage-mode","KEYWORD"))
                    : gate.executionCoverageMode());
            factors.put("orchestration.childApprovalTimeoutSeconds",children.executionApprovalTimeoutSeconds());
            put(factors,"policy",p.policy());put(factors,"provider",p.provider());put(factors,"toolSearch",p.toolSearch());
            put(factors,"rules.settings",p.rules());put(factors,"memory.settings",p.memory());put(factors,"episodic.settings",p.episodic());
            put(factors,"rag.settings",p.rag());
            factors.put("security.allowedRootsSha256",EvalExecutionManifest.hashJson(mapper.valueToTree(p.security().allowedRoots())));
            factors.put("web.enabled",p.web().enabled());factors.put("mcp.enabled",p.mcp().enabled());
            // Endpoint/reference text can contain credential-bearing URLs: retain only fingerprints.
            factors.put("web.settingsSha256",EvalExecutionManifest.hashJson(mapper.valueToTree(p.web())));
            factors.put("mcp.settingsSha256",EvalExecutionManifest.hashJson(mapper.valueToTree(p.mcp())));
            if(p.web().enabled()||p.mcp().enabled())limits.add("EXTERNAL_SERVICE_STATE_UNFROZEN");
            Set<String> exclusions=new TreeSet<>();
            if(p.memory().enabled()) {
                // Match FileSystemMemoryProvider concatenation even when binding left memoryDir null.
                excludeRelative(exclusions,String.valueOf(p.memory().memoryDir()));
                if(p.memory().memoryDir()==null)limits.add("SEMANTIC_MEMORY_DIRECTORY_NULL");
                limits.add("SEMANTIC_MEMORY_STATE_UNFROZEN");
            }
            if(p.episodic().enabled())limits.add("EPISODIC_DATABASE_STATE_UNFROZEN");
            if(p.rag().enabled()) {excludeRelative(exclusions,p.rag().storageDir());limits.add("RAG_INDEX_STATE_UNFROZEN");}
            factors.set("baseline.excludedApplicationDirs",mapper.valueToTree(exclusions));
            factors.set("baseline.source",directoryFingerprint(baseline,true,exclusions));
            limits.add("BASELINE_BUILD_CACHE_CONTENTS_EXCLUDED");
            ObjectNode graderFiles=mapper.createObjectNode(),ruleViews=mapper.createObjectNode();
            for(EvalSample sample:dataset.samples()) {
                String support=sample.grader().supportFile();
                if(support!=null&&!support.isBlank())graderFiles.put(sample.id(),fileDigest(Path.of(support).toAbsolutePath().normalize()));
                if(dataset.samplesOfSplit(config.split()).stream().anyMatch(s->s.id().equals(sample.id()))
                        && (config.sampleIds().isEmpty()||config.sampleIds().contains(sample.id()))) {
                    var loaded=rules.load(projectId,sample.input());
                    ruleViews.put(sample.id(),com.dsh.common.Digest.sha256Hex(RulePrompt.build(loaded)));
                }
            }
            factors.set("grader.supportFileSha256",graderFiles);
            factors.set("prompt.initialRulesBySampleSha256",ruleViews);
            factors.put("prompt.fixedBaseSha256",com.dsh.common.Digest.sha256Hex(RulePrompt.BASE));
            factors.put("prompt.loopImplementationSha256",classDigest(LoopSteps.class));
            factors.put("prompt.childImplementationSha256",classDigest(com.dsh.orchestration.SubagentService.class));
            var core=tools.mountedDefinitions().stream().sorted(Comparator.comparing(com.dsh.agent.tool.ToolDefinition::name)).toList();
            factors.put("tools.coreDefinitionsSha256",EvalExecutionManifest.hashJson(mapper.valueToTree(core)));
            var extension=tools.extensionTools().stream().sorted(Comparator.comparing(com.dsh.agent.tool.Tool::name))
                    .map(t->{Map<String,Object> entry=new TreeMap<>();entry.put("definition",tools.definition(t.name()).orElseThrow());entry.put("metadata",t.extensionMeta());return entry;}).toList();
            factors.put("tools.extensionCatalogSha256",EvalExecutionManifest.hashJson(mapper.valueToTree(extension)));
            factors.set("sandbox",mapper.valueToTree(p.sandbox()));
            factors.put("sandbox.declaredImageDigest",config.imageDigest());
            boolean pinned=config.imageDigest().matches("sha256:[0-9a-f]{64}")
                    && (config.imageDigest().equals(p.sandbox().image())||p.sandbox().image().endsWith("@"+config.imageDigest()));
            factors.put("sandbox.imageIdentity",pinned?"PINNED_REFERENCE":"UNPINNED_REFERENCE");
            if(!pinned)limits.add("SANDBOX_IMAGE_NOT_PINNED");
            File source=buildSource();
            if(source==null) {factors.put("build.identity","UNKNOWN");limits.add("BUILD_IDENTITY_UNAVAILABLE");}
            else if(source.isFile()) {factors.put("build.identity","PACKAGED_JAR");factors.put("build.sha256",fileDigest(source.toPath()));}
            else {factors.put("build.identity","CLASSES_DIRECTORY");factors.set("build.classes",directoryFingerprint(source.toPath(),false,Set.of()));limits.add("CLASSES_MODE_DEPENDENCIES_NOT_HASHED");}
            for(String key:List.of("java.version","java.vendor","os.name","os.arch"))factors.put("runtime."+key,System.getProperty(key,"UNKNOWN"));
            factors.put("runtime.timezone",java.time.ZoneId.systemDefault().getId());
            factors.put("runtime.locale",Locale.getDefault().toLanguageTag());
            factors.put("runtime.charset",java.nio.charset.Charset.defaultCharset().name());
            return EvalExecutionManifest.create(factors,limits);
        } catch(IOException e) {throw new UncheckedIOException("执行清单快照失败，评测不能继续或发布报告",e);}
    }
    public void assertUnchanged(EvalExecutionManifest frozen, EvalConfig config, EvalDataset dataset, Path baseline, String projectId) {
        if(!frozen.executionDigest().equals(capture(config,dataset,baseline,projectId).executionDigest()))
            throw new IllegalStateException("冻结执行因子在评测期间变化，拒绝发布报告；已完成 Run 证据保留");
    }
    private static File buildSource() throws IOException {
        File source = new ApplicationHome(EvalRunner.class).getSource();
        if (source != null) return source;
        var codeSource = EvalRunner.class.getProtectionDomain().getCodeSource();
        if (codeSource != null && "file".equals(codeSource.getLocation().getProtocol())) {
            try { return Path.of(codeSource.getLocation().toURI()).toFile(); }
            catch (java.net.URISyntaxException e) { throw new IOException("构建来源路径无效", e); }
        }
        return null;
    }
    private void put(ObjectNode node,String key,Object value) {node.set(key,mapper.valueToTree(value));}
    static String datasetDigest(EvalDataset d) {
        var mapper=new ObjectMapper();ObjectNode n=mapper.valueToTree(d);
        n.set("devSplitIds",mapper.valueToTree(d.devSplitIds().stream().sorted().toList()));
        n.set("heldoutSplitIds",mapper.valueToTree(d.heldoutSplitIds().stream().sorted().toList()));
        return EvalExecutionManifest.hashJson(n);
    }
    private static void excludeRelative(Set<String> out,String text) {
        if(text==null||text.isBlank())return;
        String path=text.replace('\\','/');
        if(!path.startsWith("/")&&!path.contains(":")&&!Arrays.asList(path.split("/")).contains("..")&&!path.equals(".")){
            String normalized=Path.of(path).normalize().toString().replace('\\','/');
            if(!normalized.isEmpty()&&!normalized.equals("."))out.add(normalized);
        }
    }
    static ObjectNode directoryFingerprint(Path root,boolean sourceScope,Set<String> exclusions) throws IOException {
        if(Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("快照根必须是实际目录");
        Map<String,String> hashes=new TreeMap<>();
        Files.walkFileTree(root,new SimpleFileVisitor<>() {
            private String rel(Path path){return root.relativize(path).toString().replace('\\','/');}
            private boolean excluded(String rel,boolean dir){return sourceScope && (WorkspaceIgnores.isIgnored(rel,dir)
                    ||exclusions.stream().anyMatch(p->rel.equals(p)||rel.startsWith(p+"/")));}
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs) {
                return excluded(rel(dir),true)?FileVisitResult.SKIP_SUBTREE:FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                String relative=rel(file);if(excluded(relative,false))return FileVisitResult.CONTINUE;
                if(hashes.size()>=100000)throw new IOException("快照最多 100000 个文件");
                hashes.put(relative,fileDigest(file));return FileVisitResult.CONTINUE;
            }
        });
        ObjectNode summary=new ObjectMapper().createObjectNode();summary.put("scope",sourceScope?"WORKSPACE_SOURCE":"ALL_BUILD_FILES");
        summary.put("fileCount",hashes.size());summary.put("sha256",EvalExecutionManifest.hashJson(new ObjectMapper().valueToTree(hashes)));return summary;
    }
    static String fileDigest(Path file)throws IOException {
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("指纹文件不可读或为符号链接");
        // Check ancestors too; a non-link leaf inside a linked directory is still not frozen input.
        for(Path cursor=file.toAbsolutePath().normalize().getParent();cursor!=null;cursor=cursor.getParent())
            if(Files.isSymbolicLink(cursor))throw new IOException("指纹路径包含符号链接");
        try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {return streamDigest(input);}
    }
    private static String classDigest(Class<?> type)throws IOException {
        try(InputStream input=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
            if(input==null)throw new IOException("实现类字节不可读");return streamDigest(input);
        }
    }
    private static String streamDigest(InputStream input)throws IOException {
        try {MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int n;
            while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
