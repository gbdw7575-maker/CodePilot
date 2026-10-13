package com.dsh.agent.tool;

import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tool Search 关闭时的模型可见能力与执行入口必须一致。 */
@SpringBootTest(properties = "dsh.tool-search.enabled=false")
class ToolSearchDisabledAvailabilityTest {

    @Autowired ToolRegistry registry;
    @Autowired ToolDispatcher dispatcher;
    @Autowired ProjectRepository projects;
    @TempDir Path projectRoot;

    @Test
    void authorizedMemoryIsMountedWithoutSearchAndUnavailableExtensionsStayHidden() {
        List<String> visible = registry.mountedDefinitions().stream().map(ToolDefinition::name).toList();
        assertTrue(visible.containsAll(List.of("list_files", "read_file", "search_code",
                "run_command", "memory_recall", "memory_save")));
        assertFalse(visible.contains("tool_search"));
        assertFalse(visible.contains("web_fetch"));

        String projectId = "proj-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        projects.save(new ProjectEntity(projectId, "工具可达性", projectRoot.toString(),
                true, Instant.now(), Instant.now()));
        var result = dispatcher.dispatch("run-" + UUID.randomUUID(), "thr-test", "turn-test",
                projectId, Instant.now().plusSeconds(30), "memory_recall", "{}", "call-test");
        assertEquals("SUCCEEDED", result.status(), result.summary());
    }
}
