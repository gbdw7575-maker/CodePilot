package com.dsh.memory;

/**
 * Auto Memory 供给端口（开发文档 §10 / 内部契约 §18.3）：
 * 经验层按项目隔离存于 <项目根>/.myagent/memory/<projectId>/；
 * 写入采用版本检查 + 原子替换；全局/项目级禁用时抛 MEMORY_DISABLED。
 * 实现不提供任何绕过权限的路径写入：category 白名单校验在实现内完成。
 */
public interface MemoryProvider {

    /** 完整视图（用户查看；含并发版本与渲染索引）。 */
    MemoryView view(String projectId);

    /** 有界索引读取（memory_recall 用）。 */
    MemoryIndex retrieve(String projectId);

    /** 单条经验全文（主题文件按需读取的等价实现）。 */
    MemoryEntry entry(String projectId, String entryId);

    /** 新增经验；expectedVersion 严格并发校验，冲突抛 VERSION_CONFLICT。 */
    MemoryEntry save(String projectId, String category, String content, String scope,
                     String sourceRef, long expectedVersion);

    /** 编辑经验正文/有效范围；并发校验同上。 */
    MemoryEntry updateEntry(String projectId, String entryId, String content, String scope,
                            long expectedVersion);

    /** 删除经验（用户删除立即对后续 recall 生效）；并发校验同上。 */
    void deleteEntry(String projectId, String entryId, long expectedVersion);

    /** 启停 Auto Memory（项目级）；并发校验同上。 */
    MemoryView setEnabled(String projectId, boolean enabled, long expectedVersion);

    /** 规则层读取：记录路径、哈希和版本；过大按上限截断并标记。 */
    RulesView loadRules(String projectId);
}
