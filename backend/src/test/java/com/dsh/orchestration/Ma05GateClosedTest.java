package com.dsh.orchestration;

import com.dsh.agent.tool.ToolDispatcher;
import com.dsh.agent.tool.ToolRegistry;
import com.dsh.api.dto.event.ToolOutcomeDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-05 开关门控（规范 §13：dsh.orchestration.enabled 默认必须保持 false）：
 * 关闭时四个调度工具没有 Spring bean → 不可发现（lookup 空、不进 mounted 目录），
 * 执行点返回 UNKNOWN_TOOL。契约 schema 仍可包含定义（可见性与可执行性分离）。
 */
@SpringBootTest
@TestPropertySource(properties = "dsh.orchestration.enabled=false")
class Ma05GateClosedTest {

    private static final List<String> TASK_TOOLS =
            List.of("dispatch_tasks", "await_tasks", "read_task_result", "cancel_tasks");

    @Autowired private ToolRegistry toolRegistry;
    @Autowired private ToolDispatcher toolDispatcher;

    @Test
    void schedulingToolsAreNotDiscoverableWhenGateClosed() {
        for (String name : TASK_TOOLS) {
            assertTrue(toolRegistry.lookup(name).isEmpty(),
                    "开关关闭时不得装载工具 bean: " + name);
            assertFalse(toolRegistry.mountedDefinitions().stream()
                    .anyMatch(d -> name.equals(d.name())),
                    "开关关闭时工具不得出现在模型挂载目录: " + name);
        }
    }

    @Test
    void dispatcherReturnsUnknownToolWhenGateClosed() {
        for (String name : TASK_TOOLS) {
            ToolOutcomeDto outcome = toolDispatcher.dispatch("run-ma05-closed", "thr", "turn",
                    "proj", Instant.now().plusSeconds(60), name, "{}", "call-closed-" + name);
            assertEquals("FAILED", outcome.status(), name);
            assertEquals("UNKNOWN_TOOL", outcome.errorCode(), name);
        }
    }
}
