package com.dsh.orchestration;

import com.dsh.application.ArtifactStore;
import com.dsh.common.Digest;
import com.dsh.storage.ArtifactEntity;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.workspace.WorkspaceLease;
import com.dsh.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 依赖产物物化服务（MA-07，规范 §8.1）。
 *
 * <p>子任务启动前把上游 SUCCEEDED 任务的已验收产物实际写入子工作区 upstream/ 目录，
 * 使下游任务能真正编译/使用上游产出，而非仅凭 prompt 摘要。多个上游产物同名且内容
 * 不同时进入 INPUT_CONFLICT，任务转 BLOCKED，不允许主模型凭摘要绕过（§10.2 输入冲突）。</p>
 *
 * <p>物化发生在 InputSnapshotService.freeze 之后、childRunFactory.create 之前：
 * freeze 已冻结产物 sha256 与写范围，物化据此把字节落子工作区并刷新基线，使后续 diff
 * 不把 upstream/ 误判为子 Run 新增产出。</p>
 */
@Service
public class DependencyMaterializationService {

    private static final Logger log = LoggerFactory.getLogger(DependencyMaterializationService.class);
    /** 物化产物落地子工作区的子目录名（相对副本根）。 */
    public static final String UPSTREAM_DIR = "upstream";

    private final WorkspaceManager workspaceManager;
    private final ArtifactStore artifactStore;
    private final ProjectRepository projectRepository;

    public DependencyMaterializationService(WorkspaceManager workspaceManager,
                                            ArtifactStore artifactStore,
                                            ProjectRepository projectRepository) {
        this.workspaceManager = workspaceManager;
        this.artifactStore = artifactStore;
        this.projectRepository = projectRepository;
    }

    /** 物化结果：ok=true 时 materializedPaths 列出已落盘相对路径；ok=false 时 errorCode+errorMessage。 */
    public record MaterializeResult(boolean ok, String errorCode, String errorMessage,
                                     List<String> materializedPaths) {
        public static MaterializeResult ok(List<String> paths) {
            return new MaterializeResult(true, null, null, paths);
        }

        public static MaterializeResult fail(String code, String msg) {
            return new MaterializeResult(false, code, msg, List.of());
        }
    }

    /**
     * 把上游产物物化到子工作区 upstream/ 目录。
     *
     * <p>空 artifactRefs 直接返回 ok（无操作，worker 无依赖任务或上游无产物时走此路径）。
     * 已有 lease（seedWorkspace 场景）复用；否则提前 prepare（worker 场景，agentLoop 内部
     * 后续 prepare 命中 lease 直接返回，不会重复复制）。物化后刷新基线避免污染 diff。</p>
     */
    public MaterializeResult materialize(String childRunId, String projectId,
                                         List<String> artifactRefs) {
        if (artifactRefs == null || artifactRefs.isEmpty()) {
            return MaterializeResult.ok(List.of());
        }
        WorkspaceLease lease = workspaceManager.find(childRunId);
        if (lease == null) {
            ProjectEntity project = projectRepository.findById(projectId).orElse(null);
            if (project == null) {
                return MaterializeResult.fail("WORKSPACE_UNAVAILABLE",
                        "项目不存在: " + projectId);
            }
            try {
                lease = workspaceManager.prepare(childRunId, project);
            } catch (RuntimeException e) {
                return MaterializeResult.fail("WORKSPACE_UNAVAILABLE",
                        "为物化上游产物准备工作区失败: " + e.getMessage());
            }
        }

        Path upstreamDir = lease.dir().resolve(UPSTREAM_DIR);
        try {
            Files.createDirectories(upstreamDir);
        } catch (IOException e) {
            return MaterializeResult.fail("WORKSPACE_UNAVAILABLE",
                    "创建 upstream 目录失败: " + e.getMessage());
        }

        // 同名产物（按 artifact name）内容冲突检测：多上游产出同名文件但 sha256 不同
        // 进入 INPUT_CONFLICT，任务转 BLOCKED（§10.2）。同名同内容幂等覆盖。
        Map<String, String> seenNameToSha = new HashMap<>();
        List<String> materialized = new ArrayList<>();
        for (String artifactId : artifactRefs) {
            ArtifactEntity entity;
            byte[] content;
            try {
                entity = artifactStore.get(artifactId);
                content = artifactStore.readVerifiedContent(artifactId,32L*1048576);
                if(!projectId.equals(entity.getProjectId()))throw new IllegalArgumentException("依赖产物项目不匹配");
            } catch (RuntimeException e) {
                return MaterializeResult.fail("INVALID_ARGUMENT",
                        "依赖产物读取失败: " + artifactId + " - " + e.getMessage());
            }
            String name = entity.getName() == null || entity.getName().isBlank()
                    ? artifactId : entity.getName();
            // Receipt/manifests from separate checks legitimately have different bytes and identical names.
            // Namespace them; expose the verified delivery tree so downstream agents can actually read code.
            if(name.equals("check-delivery.zip")&&"application/zip".equals(entity.getMediaType())){
                try{var paths=materializeDelivery(upstreamDir,entity.getRunId(),content,seenNameToSha);materialized.addAll(paths);continue;}
                catch(IOException|IllegalArgumentException invalid){return MaterializeResult.fail("INPUT_CONFLICT","强交付 ZIP 无效或输入路径冲突");}
            }
            if(name.equals("check-completion.json")||name.equals("check-evidence.json"))name=entity.getRunId()+"/evidence/"+artifactId+"-"+name;
            if(!safeRelative(name))return MaterializeResult.fail("INVALID_ARGUMENT","依赖产物名称越界");
            String sha = Digest.sha256Hex(content);
            String prev = seenNameToSha.get(name);
            if (prev != null && !prev.equals(sha)) {
                log.warn("多依赖产物同名冲突 child={} name={} sha1={} sha2={}",
                        childRunId, name, prev, sha);
                return MaterializeResult.fail("INPUT_CONFLICT",
                        "多个上游产物同名且内容不同: " + name
                                + "（sha256 " + prev + " != " + sha + "）");
            }
            seenNameToSha.put(name, sha);
            Path target = upstreamDir.resolve(name);
            try {
                requireSafeAncestors(upstreamDir,target);
                Files.createDirectories(target.getParent());
                Files.write(target, content);
            } catch (IOException e) {
                return MaterializeResult.fail("WORKSPACE_UNAVAILABLE",
                        "物化产物写入失败 name=" + name + ": " + e.getMessage());
            }
            materialized.add(UPSTREAM_DIR + "/" + name);
        }

        // 刷新基线：把物化的 upstream/ 文件纳入 baseline manifest + baseline-content，
        // 使子 Run 封存 diff 跳过未改动的 upstream/（不污染 patch）。
        try {
            workspaceManager.refreshBaseline(childRunId);
        } catch (RuntimeException e) {
            return MaterializeResult.fail("WORKSPACE_UNAVAILABLE",
                    "物化后刷新基线失败: " + e.getMessage());
        }

        log.info("上游产物已物化 child={} artifacts={} paths={}",
                childRunId, artifactRefs.size(), materialized);
        return MaterializeResult.ok(materialized);
    }
    private static List<String> materializeDelivery(Path root,String run,byte[] bytes,Map<String,String> seen)throws IOException{
        if(run==null||!run.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException();
        var pending=new java.util.LinkedHashMap<String,byte[]>();var aliases=new java.util.HashSet<String>();long total=0;
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))){
            java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null){String path=entry.getName();
                if(entry.isDirectory()||!safeRelative(path)||!aliases.add(path.toLowerCase(java.util.Locale.ROOT))||pending.size()>=4096)throw new IllegalArgumentException();
                byte[] value=zip.readNBytes(1048577);if(value.length>1048576||(total+=value.length)>32L*1048576)throw new IllegalArgumentException();
                String name=run+"/delivery/"+path;String sha=Digest.sha256Hex(value);String previous=seen.get(name);
                if(previous!=null&&!previous.equals(sha))throw new IllegalArgumentException();pending.put(name,value);zip.closeEntry();
            }
        }
        if(pending.isEmpty())throw new IllegalArgumentException();
        for(var entry:pending.entrySet())requireSafeAncestors(root,root.resolve(entry.getKey()));
        var paths=new ArrayList<String>();for(var entry:pending.entrySet()){
            Path target=root.resolve(entry.getKey());Files.createDirectories(target.getParent());Files.write(target,entry.getValue());
            seen.put(entry.getKey(),Digest.sha256Hex(entry.getValue()));paths.add(UPSTREAM_DIR+"/"+entry.getKey());
        }return paths;
    }
    private static boolean safeRelative(String name){return name!=null&&!name.isBlank()&&name.length()<=512&&!name.startsWith("/")&&!name.contains("\\")&&!name.contains(":")
            &&name.chars().noneMatch(c->c<32)&&java.util.Arrays.stream(name.split("/",-1)).noneMatch(p->p.isEmpty()||p.equals(".")||p.equals(".."));}
    private static void requireSafeAncestors(Path root,Path file)throws IOException{
        Path absolute=root.toAbsolutePath().normalize();Path target=file.toAbsolutePath().normalize();if(!target.startsWith(absolute))throw new IllegalArgumentException();
        for(Path p=target;p!=null&&p.startsWith(absolute);p=p.getParent())if(Files.exists(p,java.nio.file.LinkOption.NOFOLLOW_LINKS)&&com.dsh.workspace.SafePaths.isReparseRedirect(p))throw new IllegalArgumentException();
    }
}
