package com.dsh.agent.mcp;

import com.dsh.agent.tool.RunToolMountStore;
import com.dsh.agent.tool.ToolDispatcher;
import com.dsh.agent.tool.ToolRegistry;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicitly invoked live probe; excluded from the normal *Test suite. */
@SpringBootTest
class ExternalMcpLiveIT {

    @DynamicPropertySource
    static void publicReadOnlyServer(DynamicPropertyRegistry registry) {
        registry.add("dsh.mcp.enabled", () -> "true");
        registry.add("dsh.mcp.endpoint", () -> "https://mcp.marionettejs.com/mcp");
        registry.add("dsh.mcp.connect-timeout-seconds", () -> "20");
        registry.add("dsh.mcp.read-timeout-seconds", () -> "30");
        registry.add("dsh.mcp.max-chars", () -> "20000");
    }

    @Autowired private McpClient client;
    @Autowired private ToolRegistry toolRegistry;
    @Autowired private RunToolMountStore mountStore;
    @Autowired private ToolDispatcher dispatcher;
    @Autowired private ProjectRepository projectRepository;

    @TempDir Path projectRoot;

    @Test
    @Transactional
    void publicReadOnlySearchIsRegisteredMountedAndCalled() {
        assertTrue(client.enabled());
        var definition = toolRegistry.definition("mcp_search_docs");
        assertTrue(definition.isPresent(), "server readOnlyHint should permit registration");
        assertEquals("READ_ONLY", definition.get().sideEffect());

        String projectId = "proj-mcp-live-" + UUID.randomUUID().toString().substring(0, 8);
        String runId = "run-mcp-live-" + UUID.randomUUID().toString().substring(0, 8);
        projectRepository.save(new ProjectEntity(projectId, "public MCP live probe",
                projectRoot.toString(), true, Instant.now(), Instant.now()));
        mountStore.mount(runId, "mcp_search_docs");
        try {
            ToolOutcomeDto outcome = dispatcher.dispatch(runId, "thread-live", "turn-live", projectId,
                    Instant.now().plusSeconds(60), "mcp_search_docs",
                    "{\"query\":\"Region\",\"version\":\"5.0.0-rc.1\",\"limit\":2}",
                    "call-mcp-live");
            assertEquals("SUCCEEDED", outcome.status(), outcome.summary());
            assertEquals("mcp_search_docs", outcome.source());
            assertFalse(outcome.content().isEmpty());
            assertTrue(outcome.structuredContent().path("blockCount").asInt() > 0);
        } finally {
            mountStore.release(runId);
        }
    }
}
