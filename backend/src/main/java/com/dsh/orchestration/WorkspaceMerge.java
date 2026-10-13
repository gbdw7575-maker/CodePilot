package com.dsh.orchestration;

import com.dsh.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 工作区文件级三方汇合（DSH-010，开发文档 §12）。
 *
 * <p>三方：写者（CODER）基线 manifest（= 派生时父工作区快照）、写者当前副本、父当前副本。
 * 对每个路径判定：写者相对基线有改动 且 父相对基线也有改动（含删除/新增错配）即为冲突。
 * 有任意冲突则<strong>整批不应用</strong>（CONFLICTS，交回父 Agent 决策）；
 * 无冲突则把写者改动全部应用到父副本（新增/覆盖/删除，二进制按字节复制），返回 APPLIED。</p>
 */
@Component
public class WorkspaceMerge {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMerge.class);

    /** 汇合结果。status: APPLIED / CONFLICTS。 */
    public record Result(String status, String writerWorkspaceRef,
                         List<String> appliedPaths, List<Conflict> conflicts) {

        public boolean applied() {
            return "APPLIED".equals(status);
        }
    }

    /** D-16 多写者汇合的写者归属：子 Run ID + 其工作区 ref。 */
    public record Writer(String runId, String workspaceRef) {
    }

    /** D-16 多写者汇合结果：整批语义不变（有冲突全部不应用）。 */
    public record MultiResult(String status, List<String> appliedPaths,
                              List<MultiConflict> conflicts, int writerCount) {

        public boolean applied() {
            return "APPLIED".equals(status);
        }
    }

    /** D-16 多方冲突：路径 + 涉及写者（含 "parent"）各方的变化类型。 */
    public record MultiConflict(String path, List<String> writerRunIds, List<String> changes) {
    }

    /** 冲突项：path + 双方相对基线的变化类型（MODIFIED/DELETED/ADDED）。 */
    public record Conflict(String path, String writerChange, String parentChange) {
    }

    public Result merge(String writerWorkspaceRef, String parentRunId,
                        WorkspaceManager workspaceManager) {
        WorkspaceManager.WorkspacePaths writer = workspaceManager.resolveByRef(writerWorkspaceRef);
        WorkspaceManager.WorkspacePaths parent;
        var parentLease = workspaceManager.find(parentRunId);
        if (parentLease != null) {
            parent = new WorkspaceManager.WorkspacePaths(parentLease.dir(), parentLease.metaDir());
        } else {
            parent = null;
        }
        if (parent == null || !Files.isDirectory(parent.dir()) || !Files.isDirectory(writer.dir())) {
            throw new IllegalStateException("汇合工作区不存在: writer=" + writerWorkspaceRef
                    + " parentRun=" + parentRunId);
        }

        Map<String, String> baseline = workspaceManager.readBaselineManifest(writer.metaDir());
        Map<String, String> writerCur = WorkspaceManager.fileHashes(writer.dir());
        Map<String, String> parentCur = WorkspaceManager.fileHashes(parent.dir());

        Set<String> all = new TreeSet<>();
        all.addAll(baseline.keySet());
        all.addAll(writerCur.keySet());
        all.addAll(parentCur.keySet());

        List<String> writerChanged = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        for (String path : all) {
            String base = baseline.get(path);
            String w = writerCur.get(path);
            String p = parentCur.get(path);
            String wChange = changeOf(base, w);
            String pChange = changeOf(base, p);
            if (wChange == null) {
                continue; // 写者未动
            }
            if (pChange != null && conflicting(base, w, p)) {
                conflicts.add(new Conflict(path, wChange, pChange));
            } else {
                writerChanged.add(path);
            }
        }

        if (!conflicts.isEmpty()) {
            log.warn("工作区汇合发现 {} 个冲突，整批不应用 writer={} parent={}",
                    conflicts.size(), writerWorkspaceRef, parentRunId);
            return new Result("CONFLICTS", writerWorkspaceRef, List.of(), conflicts);
        }

        List<String> applied = new ArrayList<>();
        for (String path : writerChanged) {
            applyOne(path, writerCur.get(path), writer.dir(), parent.dir());
            applied.add(path);
        }
        log.info("工作区汇合完成 APPLIED writer={} parent={} 文件数={}",
                writerWorkspaceRef, parentRunId, applied.size());
        return new Result("APPLIED", writerWorkspaceRef, applied, List.of());
    }

    /**
     * D-16 多写者一次预检 + 批量汇合（N+1 方）。
     *
     * <p>所有写者必须派生自同一父工作区快照（baseline manifest 一致）。对每个路径：
     * 两个及以上写者相对基线变化且目标内容不同（含删除/修改错配）即为写者间冲突；
     * 父工作区相对基线也有变化且与写者内容不同，为父子冲突。任一路径冲突则
     * <strong>整批不应用</strong>（无部分写入、无需回滚），冲突项带涉及写者 runId 与各自变化；
     * 无冲突时把全部写者改动按序应用（多写者同内容只应用一次）。</p>
     */
    public MultiResult mergeAll(List<Writer> writers, String parentRunId,
                                WorkspaceManager workspaceManager) {
        if (writers == null || writers.isEmpty()) {
            return new MultiResult("APPLIED", List.of(), List.of(), 0);
        }
        WorkspaceManager.WorkspacePaths parent;
        var parentLease = workspaceManager.find(parentRunId);
        if (parentLease != null) {
            parent = new WorkspaceManager.WorkspacePaths(parentLease.dir(), parentLease.metaDir());
        } else {
            parent = null;
        }
        if (parent == null || !Files.isDirectory(parent.dir())) {
            throw new IllegalStateException("汇合父工作区不存在: parentRun=" + parentRunId);
        }

        List<WorkspaceManager.WorkspacePaths> writerDirs = new ArrayList<>();
        Map<String, String> baseline = null;
        for (Writer writer : writers) {
            WorkspaceManager.WorkspacePaths paths = workspaceManager.resolveByRef(writer.workspaceRef());
            if (!Files.isDirectory(paths.dir())) {
                throw new IllegalStateException("汇合写者工作区不存在: " + writer.workspaceRef());
            }
            Map<String, String> writerBase = workspaceManager.readBaselineManifest(paths.metaDir());
            if (baseline == null) {
                baseline = writerBase;
            } else if (!baseline.equals(writerBase)) {
                throw new IllegalStateException("写者基线不一致，不能一次汇合: " + writer.runId()
                        + "（fan-out 子工作区必须派生自同一父快照）");
            }
            writerDirs.add(paths);
        }

        Map<String, String> parentCur = WorkspaceManager.fileHashes(parent.dir());
        List<Map<String, String>> writerCurs = new ArrayList<>();
        Set<String> all = new TreeSet<>(baseline.keySet());
        all.addAll(parentCur.keySet());
        for (WorkspaceManager.WorkspacePaths paths : writerDirs) {
            Map<String, String> cur = WorkspaceManager.fileHashes(paths.dir());
            writerCurs.add(cur);
            all.addAll(cur.keySet());
        }

        List<MultiConflict> conflicts = new ArrayList<>();
        // 无冲突时的应用计划：path → 应用内容 hash；来源目录按需从写者目录取
        Map<String, String> plannedHashes = new java.util.LinkedHashMap<>();
        for (String path : all) {
            String base = baseline.get(path);
            String parentHash = parentCur.get(path);
            String parentChange = changeOf(base, parentHash);

            List<String> sideRunIds = new ArrayList<>();
            List<String> sideChanges = new ArrayList<>();
            Set<String> distinctHashes = new java.util.LinkedHashSet<>();
            boolean writerChanged = false;
            String sideHash = null;
            for (int i = 0; i < writers.size(); i++) {
                String hash = writerCurs.get(i).get(path);
                String change = changeOf(base, hash);
                if (change == null) {
                    continue;
                }
                writerChanged = true;
                sideHash = hash;
                distinctHashes.add(hash == null ? "\0DELETED\0" : hash);
                sideRunIds.add(shortRunId(writers.get(i).runId()));
                sideChanges.add(shortRunId(writers.get(i).runId()) + ":" + change);
            }
            if (!writerChanged) {
                continue;
            }

            boolean writerConflict = distinctHashes.size() > 1;
            boolean parentConflict = parentChange != null
                    && !java.util.Objects.equals(parentHash, sideHash);
            if (writerConflict || parentConflict) {
                if (parentConflict) {
                    sideRunIds.add("parent");
                    sideChanges.add("parent:" + parentChange);
                }
                conflicts.add(new MultiConflict(path, List.copyOf(sideRunIds),
                        List.copyOf(sideChanges)));
            } else {
                plannedHashes.put(path, sideHash);
            }
        }

        if (!conflicts.isEmpty()) {
            log.warn("多写者汇合发现 {} 个冲突路径，整批不应用 parent={} writers={}",
                    conflicts.size(), parentRunId, writers.size());
            return new MultiResult("CONFLICTS", List.of(), List.copyOf(conflicts), writers.size());
        }

        List<String> applied = new ArrayList<>();
        for (Map.Entry<String, String> entry : plannedHashes.entrySet()) {
            String path = entry.getKey();
            String hash = entry.getValue();
            Path sourceDir = null;
            if (hash != null) {
                for (int i = 0; i < writers.size(); i++) {
                    if (java.util.Objects.equals(hash, writerCurs.get(i).get(path))) {
                        sourceDir = writerDirs.get(i).dir();
                        break;
                    }
                }
            }
            applyOne(path, hash, sourceDir, parent.dir());
            applied.add(path);
        }
        log.info("多写者汇合 APPLIED parent={} writers={} 文件数={}",
                parentRunId, writers.size(), applied.size());
        return new MultiResult("APPLIED", List.copyOf(applied), List.of(), writers.size());
    }

    private static String shortRunId(String runId) {
        return runId == null ? "" : (runId.length() > 20 ? runId.substring(0, 20) : runId);
    }

    /** 相对基线的变化：ADDED/MODIFIED/DELETED；无变化返回 null。内容相同不算改动。 */
    private String changeOf(String base, String cur) {
        if (base == null) {
            return cur == null ? null : "ADDED";
        }
        if (cur == null) {
            return "DELETED";
        }
        return base.equals(cur) ? null : "MODIFIED";
    }

    /**
     * 双方都相对基线变化时是否构成冲突：内容一致（含都删除）则不冲突；
     * 任一方删除而另一方修改/新增不同内容，或双方修改为不同内容，均冲突。
     */
    private boolean conflicting(String base, String writer, String parent) {
        if (writer != null && writer.equals(parent)) {
            return false; // 双方写成同一内容（或同为 null 已在 changeOf 过滤）
        }
        return true;
    }

    private void applyOne(String relPath, String writerHash, Path writerDir, Path parentDir) {
        Path target = parentDir.resolve(relPath).normalize();
        if (!target.startsWith(parentDir)) {
            throw new IllegalStateException("汇合路径越界: " + relPath);
        }
        try {
            if (writerHash == null) {
                Files.deleteIfExists(target);
                return;
            }
            Path source = writerDir.resolve(relPath).normalize();
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("汇合应用失败: " + relPath + " - " + e.getMessage(), e);
        }
    }
}
