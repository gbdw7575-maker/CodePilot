package com.dsh.agent.tool;

import com.dsh.api.dto.event.ToolOutcomeDto;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-15 访问控制（默认配置 dsh.web.enabled=false，未经授权不联网）：
 * web_search/web_fetch 为扩展工具——未挂载不可执行；即使绕过 tool_search 直接挂载，
 * 实时复查 authorized 仍拒绝；对 tool_search 不可见；定义 sideEffect=READ_ONLY。
 */
@SpringBootTest
class WebToolsAccessControlTest {

    @Autowired
    private ToolDispatcher dispatcher;
    @Autowired
    private RunToolMountStore mountStore;
    @Autowired
    private ProjectRepository projectRepository;

    @TempDir
    Path projectRoot;

    private String persistTrustedProject() {
        String id = "proj-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        projectRepository.save(new ProjectEntity(id, "web访问控制测试", projectRoot.toString(),
                true, Instant.now(), Instant.now()));
        return id;
    }

    private ToolOutcomeDto dispatch(String projectId, String runId, String tool, String argumentsJson) {
        return dispatcher.dispatch(runId, "thr-1", "turn-1", projectId,
                Instant.now().plusSeconds(600), tool, argumentsJson, "call-web-ac");
    }

    @Test
    @Transactional
    void webFetchWithoutMountIsRejected() {
        String projectId = persistTrustedProject();
        ToolOutcomeDto outcome = dispatch(projectId, "run-webnm-" + UUID.randomUUID(),
                "web_fetch", "{\"url\":\"https://example.com/doc\"}");
        assertEquals("FAILED", outcome.status());
        assertEquals("TOOL_NOT_MOUNTED", outcome.errorCode());
    }

    @Test
    @Transactional
    void mountedWebToolStillDeniedWhenUnauthorized() {
        String projectId = persistTrustedProject();
        String runId = "run-webden-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        // 绕过 tool_search 直接挂载（模拟配置变更前的历史挂载）：执行时实时复查授权仍拒绝
        mountStore.mount(runId, "web_fetch");
        mountStore.mount(runId, "web_search");
        assertEquals("DENIED", dispatch(projectId, runId, "web_fetch",
                "{\"url\":\"https://example.com/doc\"}").status());
        assertEquals("PERMISSION_DENIED", dispatch(projectId, runId, "web_search",
                "{\"query\":\"测试\"}").errorCode());
        mountStore.release(runId);
    }

    @Test
    @Transactional
    void webToolsInvisibleToToolSearchWhenUnauthorized() {
        String projectId = persistTrustedProject();
        String runId = "run-webinv-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ToolOutcomeDto search = dispatch(projectId, runId, "tool_search",
                "{\"query\":\"搜索 网页 抓取 来源 URL\"}");
        assertEquals("SUCCEEDED", search.status(), search.summary());
        String matched = search.structuredContent().path("matched").toString();
        assertFalse(matched.contains("web_fetch"), "未授权 web_fetch 不可发现");
        assertFalse(matched.contains("web_search"), "未授权 web_search 不可发现");
        mountStore.release(runId);
    }

    @Test
    @Transactional
    void webToolsAreReadOnlySideEffect() {
        assertEquals("READ_ONLY", dispatcher.sideEffectOf("web_fetch"));
        assertEquals("READ_ONLY", dispatcher.sideEffectOf("web_search"));
    }
}
