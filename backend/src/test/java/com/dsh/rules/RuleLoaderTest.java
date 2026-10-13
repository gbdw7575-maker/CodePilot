package com.dsh.rules;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.application.DshProperties;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-04 规则加载单元：根规则、目录作用域、优先级（根→浅→深）、截断、逃逸拒绝。
 */
@SpringBootTest
class RuleLoaderTest {

    @Autowired
    private ProjectRepository projectRepository;

    @TempDir
    Path projectRoot;

    private String persistProject(boolean trusted) {
        String id = "proj_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        projectRepository.save(new ProjectEntity(id, "规则测试", projectRoot.toString(),
                trusted, Instant.now(), Instant.now()));
        return id;
    }

    private RuleLoader loader(int maxBytes, int maxDepth, int maxFiles) {
        return new RuleLoader(projectRepository,
                new DshProperties(null, null, null, null, null, null, null, null, null, null,
                        new DshProperties.Rules("AGENTS.md", maxBytes, maxDepth, maxFiles), null, null, null, null));
    }

    @Test
    void rootRulePresentAndShasMatchesContent() throws Exception {
        String pid = persistProject(true);
        Files.writeString(projectRoot.resolve("AGENTS.md"), "全局规则", StandardCharsets.UTF_8);
        RuleLoader.LoadedRules r = loader(30, 4, 20).load(pid, null);
        assertEquals(1, r.files().size());
        assertEquals("AGENTS.md", r.files().get(0).relativePath());
        assertEquals("全局规则", r.files().get(0).content());
        assertFalse(r.files().get(0).truncated());
        assertEquals(12, r.files().get(0).version().length());
        assertNotNull(r.effectiveVersion());
    }

    @Test
    void directoryRuleScopedByUserInputMentionedPaths() throws Exception {
        String pid = persistProject(true);
        Path sub = Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.writeString(projectRoot.resolve("AGENTS.md"), "根规则", StandardCharsets.UTF_8);
        Files.writeString(sub.resolve("AGENTS.md"), "java规则", StandardCharsets.UTF_8);
        RuleLoader.LoadedRules r = loader(30, 4, 20).load(pid, "看看 src/main/java/App.java 的改动");
        assertEquals(2, r.files().size());
        assertEquals("AGENTS.md", r.files().get(0).relativePath());
        assertEquals("src/main/java/AGENTS.md", r.files().get(1).relativePath());
    }

    @Test
    void rootPriorityFirstAndDepthAscending() throws Exception {
        String pid = persistProject(true);
        Path d1 = Files.createDirectories(projectRoot.resolve("a"));
        Path d2 = Files.createDirectories(d1.resolve("b"));
        Files.writeString(projectRoot.resolve("AGENTS.md"), "root", StandardCharsets.UTF_8);
        Files.writeString(d1.resolve("AGENTS.md"), "a", StandardCharsets.UTF_8);
        Files.writeString(d2.resolve("AGENTS.md"), "ab", StandardCharsets.UTF_8);
        RuleLoader.LoadedRules r = loader(30, 4, 20).load(pid, "a/b/c.txt");
        List<String> paths = r.files().stream().map(RuleLoader.RuleFile::relativePath).toList();
        assertEquals(List.of("AGENTS.md", "a/AGENTS.md", "a/b/AGENTS.md"), paths);
    }

    @Test
    void deeperRuleOverridesShallowerEffectiveVersionChanges() throws Exception {
        String pid = persistProject(true);
        Path d = Files.createDirectories(projectRoot.resolve("x"));
        Files.writeString(projectRoot.resolve("AGENTS.md"), "root", StandardCharsets.UTF_8);
        Files.writeString(d.resolve("AGENTS.md"), "x-rule", StandardCharsets.UTF_8);
        String v1 = loader(30, 4, 20).load(pid, "x/file.txt").effectiveVersion();
        Files.writeString(d.resolve("AGENTS.md"), "x-rule-v2", StandardCharsets.UTF_8);
        String v2 = loader(30, 4, 20).load(pid, "x/file.txt").effectiveVersion();
        assertNotEquals(v1, v2, "内容更新应改变 effectiveVersion");
    }

    @Test
    void truncationFlagSetWhenExceedingMaxBytes() throws Exception {
        String pid = persistProject(true);
        String longText = "a".repeat(50);
        Files.writeString(projectRoot.resolve("AGENTS.md"), longText, StandardCharsets.UTF_8);
        RuleLoader.LoadedRules r = loader(30, 4, 20).load(pid, null);
        assertTrue(r.files().get(0).truncated());
        assertEquals(30, r.files().get(0).content().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void absoluteAndEscapePathsIgnored() throws Exception {
        String pid = persistProject(true);
        Files.writeString(projectRoot.resolve("AGENTS.md"), "root", StandardCharsets.UTF_8);
        RuleLoader.LoadedRules r = loader(30, 4, 20).load(pid, "请查看 /etc/passwd 和 ../../secret");
        assertEquals(1, r.files().size());
        assertEquals("AGENTS.md", r.files().get(0).relativePath());
    }

    @Test
    void missingRootRuleReturnsEmptyFiles() throws Exception {
        String pid = persistProject(true);
        RuleLoader.LoadedRules r = loader(30, 4, 20).load(pid, null);
        assertTrue(r.files().isEmpty());
        assertNotNull(r.effectiveVersion());
    }

    @Test
    void untrustedProjectRejected() {
        String pid = persistProject(false);
        RuleLoader l = loader(30, 4, 20);
        ApiException ex = assertThrows(ApiException.class, () -> l.load(pid, null));
        assertEquals(ErrorCode.PROJECT_NOT_TRUSTED, ex.code());
    }

    @Test
    void maxDepthLimitsDirectoryTraversal() throws Exception {
        String pid = persistProject(true);
        Path deep = projectRoot.resolve("a/b/c/d");
        Files.createDirectories(deep);
        Files.writeString(projectRoot.resolve("AGENTS.md"), "root", StandardCharsets.UTF_8);
        Files.writeString(deep.resolve("AGENTS.md"), "deep", StandardCharsets.UTF_8);
        // maxDepth=4: a/b/c/d is depth 4, allowed
        RuleLoader.LoadedRules r = loader(30, 4, 20).load(pid, "a/b/c/d/file.txt");
        assertEquals(2, r.files().size());
        // depth 5 capped to maxDepth=4 -> directory capped to a/b/c/d, not e
        Path e = Files.createDirectories(projectRoot.resolve("a/b/c/d/e"));
        Files.writeString(e.resolve("AGENTS.md"), "e", StandardCharsets.UTF_8);
        RuleLoader.LoadedRules r2 = loader(30, 4, 20).load(pid, "a/b/c/d/e/file.txt");
        assertEquals(2, r2.files().size(), "超过 maxDepth 的目录应被截断到允许层");
        assertTrue(r2.files().stream().anyMatch(f -> f.relativePath().equals("a/b/c/d/AGENTS.md")));
        assertFalse(r2.files().stream().anyMatch(f -> f.relativePath().equals("a/b/c/d/e/AGENTS.md")));
    }

    @Test
    void maxFilesLimitsExtraDirectories() throws Exception {
        String pid = persistProject(true);
        Path d1 = Files.createDirectories(projectRoot.resolve("d1"));
        Path d2 = Files.createDirectories(projectRoot.resolve("d2"));
        Path d3 = Files.createDirectories(projectRoot.resolve("d3"));
        Files.writeString(projectRoot.resolve("AGENTS.md"), "root", StandardCharsets.UTF_8);
        Files.writeString(d1.resolve("AGENTS.md"), "d1", StandardCharsets.UTF_8);
        Files.writeString(d2.resolve("AGENTS.md"), "d2", StandardCharsets.UTF_8);
        Files.writeString(d3.resolve("AGENTS.md"), "d3", StandardCharsets.UTF_8);
        RuleLoader.LoadedRules r = loader(30, 4, 2).load(pid, "d1/x d2/y d3/z");
        assertEquals(2, r.files().size(), "maxFiles=2 应限制根+1 目录规则");
    }
}
