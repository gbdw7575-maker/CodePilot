package com.dsh.memory;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.application.DshProperties;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-009 经验记忆（开发文档 §10.3）：跨项目隔离、写入版本检查+原子替换、
 * 经验删除生效、索引有界截断（整行/完整字符）、规则文件只读、category 白名单。
 */
@SpringBootTest
class FileSystemMemoryProviderTest {

    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path projectRoot;
    @TempDir
    Path otherRoot;

    private FileSystemMemoryProvider provider(DshProperties properties) {
        return new FileSystemMemoryProvider(projectRepository, properties, objectMapper);
    }

    private static DshProperties memoryProperties(int indexMaxLines, int indexMaxBytes) {
        return new DshProperties(null, null, null, null, null, null, null, null,
                new DshProperties.Memory(true, true, true, "AGENTS.md",
                        indexMaxLines, indexMaxBytes, ".myagent/memory", 0.15), null, null, null, null, null, null);
    }

    private String persistProject(String name, Path root) {
        String id = "proj-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        projectRepository.save(new ProjectEntity(id, name, root.toString(),
                true, Instant.now(), Instant.now()));
        return id;
    }

    @Test
    @Transactional
    void savePersistsFilesAndIncrementsVersion() throws Exception {
        String projectId = persistProject("记忆项目A", projectRoot);
        FileSystemMemoryProvider provider = provider(memoryProperties(200, 25000));

        MemoryView before = provider.view(projectId);
        assertEquals(1, before.version());
        assertTrue(before.entries().isEmpty());

        MemoryEntry saved = provider.save(projectId, "project", "构建命令是 mvnw test",
                "全局", "run-1/turn-1", 1);
        assertNotNull(saved.id());

        // 版本推进 + 索引可见
        MemoryView after = provider.view(projectId);
        assertEquals(2, after.version());
        assertEquals(1, after.entries().size());
        assertTrue(after.indexText().contains(saved.id()));
        assertFalse(after.indexTruncated());

        // 文件布局：state.json + MEMORY.md + 主题文件
        Path dir = projectRoot.resolve(".myagent/memory/" + projectId);
        assertTrue(Files.isRegularFile(dir.resolve("state.json")));
        assertTrue(Files.isRegularFile(dir.resolve("MEMORY.md")));
        assertTrue(Files.readString(dir.resolve("project.md")).contains("构建命令是 mvnw test"));
        // 单条全文按需读取
        assertEquals("构建命令是 mvnw test", provider.entry(projectId, saved.id()).content());
    }

    @Test
    @Transactional
    void versionCheckPreventsConcurrentOverwrite() {
        String projectId = persistProject("记忆项目B", projectRoot);
        FileSystemMemoryProvider provider = provider(memoryProperties(200, 25000));
        provider.save(projectId, "user", "用户偏好中文回复", "全局", "u1", 1);

        // 过期版本（1）已被推进到 2，基于 1 的写入必须冲突
        ApiException conflict = assertThrows(ApiException.class,
                () -> provider.save(projectId, "user", "第二条", "全局", "u2", 1));
        assertEquals(ErrorCode.VERSION_CONFLICT, conflict.code());

        // 读取最新版本后写入成功
        MemoryView current = provider.view(projectId);
        MemoryEntry saved = provider.save(projectId, "user", "第二条", "全局", "u2", current.version());
        assertEquals(current.version() + 1, provider.view(projectId).version());
        assertNotNull(saved.id());
    }

    @Test
    @Transactional
    void deleteTakesEffectForLaterRecall() {
        String projectId = persistProject("记忆项目C", projectRoot);
        FileSystemMemoryProvider provider = provider(memoryProperties(200, 25000));
        MemoryEntry entry = provider.save(projectId, "feedback", "用户纠正：不要用 tab", "全局", "f1", 1);

        MemoryIndex index = provider.retrieve(projectId);
        assertTrue(index.text().contains(entry.id()));

        provider.deleteEntry(projectId, entry.id(), index.version());

        MemoryIndex after = provider.retrieve(projectId);
        assertFalse(after.text().contains(entry.id()), "删除后必须立即对 recall 生效");
        assertEquals(0, after.entryCount());
        assertThrows(ApiException.class, () -> provider.entry(projectId, entry.id()));
    }

    @Test
    @Transactional
    void projectIsolationAndIdValidation() {
        String projectIdA = persistProject("记忆项目D", projectRoot);
        String projectIdB = persistProject("记忆项目E", otherRoot);
        FileSystemMemoryProvider provider = provider(memoryProperties(200, 25000));

        provider.save(projectIdA, "project", "A 项目专属背景", "全局", "r1", 1);

        // B 项目读不到 A 的经验（路径按项目隔离）
        MemoryView viewB = provider.view(projectIdB);
        assertTrue(viewB.entries().isEmpty());
        // 文件不在 B 的目录下
        assertFalse(Files.exists(otherRoot.resolve(".myagent/memory/" + projectIdA)));

        // projectId 非法字符拒绝（防路径穿越）
        ApiException invalid = assertThrows(ApiException.class,
                () -> provider.view("../evil"));
        assertEquals(ErrorCode.INVALID_INPUT, invalid.code());
    }

    @Test
    @Transactional
    void perProjectDisableBlocksToolPathsButKeepsManagement() {
        String projectId = persistProject("记忆项目F", projectRoot);
        FileSystemMemoryProvider provider = provider(memoryProperties(200, 25000));
        MemoryEntry entry = provider.save(projectId, "reference", "部署文档在 ops/docs", "全局", "r1", 1);

        MemoryView disabled = provider.setEnabled(projectId, false, 2);
        assertFalse(disabled.enabled());

        // 工具路径被拒
        ApiException blocked = assertThrows(ApiException.class, () -> provider.retrieve(projectId));
        assertEquals(ErrorCode.MEMORY_DISABLED, blocked.code());
        ApiException blockedSave = assertThrows(ApiException.class,
                () -> provider.save(projectId, "user", "x", "全局", "r2", 3));
        assertEquals(ErrorCode.MEMORY_DISABLED, blockedSave.code());

        // 管理路径（查看/编辑/删除/启停）仍可用
        assertTrue(provider.view(projectId).version() >= 3);
        assertEquals("部署文档在 ops/docs", provider.entry(projectId, entry.id()).content());
        provider.setEnabled(projectId, true, provider.view(projectId).version());
        assertTrue(provider.retrieve(projectId).text().contains(entry.id()));
    }

    @Test
    @Transactional
    void indexTruncatesOnLineLimitWithWholeLines() {
        String projectId = persistProject("记忆项目G", projectRoot);
        // 上限 4 行（标题+空行+2 条经验）→ 第 3 条触发截断标记
        FileSystemMemoryProvider provider = provider(memoryProperties(4, 25000));
        provider.save(projectId, "user", "经验一", "全局", "r1", 1);
        MemoryView view = provider.view(projectId);
        provider.save(projectId, "user", "经验二", "全局", "r1", view.version());
        provider.save(projectId, "user", "经验三", "全局", "r1",
                provider.view(projectId).version());

        MemoryIndex index = provider.retrieve(projectId);
        assertEquals(3, index.entryCount());
        assertTrue(index.truncated(), "超过行数上限必须标记截断");
        assertTrue(index.text().contains("经验二"));
        assertFalse(index.text().contains("经验三"), "截断后的整行不得残缺出现");
        assertTrue(index.text().endsWith("\n"), "按整行截断，UTF-8 字符完整");
    }

    @Test
    @Transactional
    void rulesFileIsReadWithHashAndNeverWrittenByMemory() throws Exception {
        String projectId = persistProject("记忆项目H", projectRoot);
        Files.writeString(projectRoot.resolve("AGENTS.md"), "# 规则\n构建用 mvnw\n");
        FileSystemMemoryProvider provider = provider(memoryProperties(200, 25000));

        RulesView rules = provider.loadRules(projectId);
        assertTrue(rules.exists());
        assertEquals(64, rules.sha256().length());
        assertEquals(12, rules.version().length());
        assertTrue(rules.content().contains("构建用 mvnw"));
        assertFalse(rules.truncated());

        // Auto Memory 写入不影响规则文件（结构上 save 不接受路径；内容零变化）
        provider.save(projectId, "user", "偏好中文", "全局", "r1", 1);
        assertEquals("# 规则\n构建用 mvnw\n", Files.readString(projectRoot.resolve("AGENTS.md")));
        RulesView after = provider.loadRules(projectId);
        assertEquals(rules.sha256(), after.sha256());
    }

    @Test
    @Transactional
    void categoryWhitelistAndMissingRules() {
        String projectId = persistProject("记忆项目I", projectRoot);
        FileSystemMemoryProvider provider = provider(memoryProperties(200, 25000));

        ApiException badCategory = assertThrows(ApiException.class,
                () -> provider.save(projectId, "secret", "x", "全局", "r", 1));
        assertEquals(ErrorCode.INVALID_INPUT, badCategory.code());

        ApiException blank = assertThrows(ApiException.class,
                () -> provider.save(projectId, "user", "  ", "全局", "r", 1));
        assertEquals(ErrorCode.INVALID_INPUT, blank.code());

        // 缺失规则文件：exists=false，不报错
        RulesView missing = provider.loadRules(projectId);
        assertFalse(missing.exists());
        // 未知项目
        assertThrows(ApiException.class, () -> provider.view("proj-not-exist"));
        assertNotEquals(0, provider.view(projectId).version());
    }
}
