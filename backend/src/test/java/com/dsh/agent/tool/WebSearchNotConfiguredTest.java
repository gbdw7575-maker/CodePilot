package com.dsh.agent.tool;

import com.dsh.api.dto.event.ToolOutcomeDto;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * D-15 web_search 未配置搜索端点（dsh.web.search-endpoint 留空）：
 * 工具已授权可挂载，但执行时明确报 SEARCH_NOT_CONFIGURED 而非静默失败。
 */
@SpringBootTest
class WebSearchNotConfiguredTest {

    @DynamicPropertySource
    static void webProperties(DynamicPropertyRegistry registry) {
        registry.add("dsh.web.enabled", () -> "true");
        registry.add("dsh.web.allow-loopback", () -> "true");
        // search-endpoint 不配置（留空默认）
    }

    @Autowired
    private ToolDispatcher dispatcher;
    @Autowired
    private RunToolMountStore mountStore;
    @Autowired
    private ProjectRepository projectRepository;

    @TempDir
    Path projectRoot;

    @Test
    @Transactional
    void searchWithoutEndpointFailsExplicitly() {
        String projectId = "proj-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        projectRepository.save(new ProjectEntity(projectId, "web搜索未配置测试", projectRoot.toString(),
                true, Instant.now(), Instant.now()));
        String runId = "run-webnc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        mountStore.mount(runId, "web_search");
        ToolOutcomeDto outcome = dispatcher.dispatch(runId, "thr-1", "turn-1", projectId,
                Instant.now().plusSeconds(600), "web_search", "{\"query\":\"测试\"}", "call-webnc");
        assertEquals("FAILED", outcome.status());
        assertEquals("SEARCH_NOT_CONFIGURED", outcome.errorCode());
        mountStore.release(runId);
    }
}
