package com.dsh.agent.tool;

import com.dsh.api.dto.event.ToolOutcomeDto;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-009 挂载与授权执行链（内部接口契约 §1/§5 / 开发文档 §7.3）：
 * 扩展工具未经 tool_search 挂载不能执行；挂载后可执行；授权被撤销的工具
 * 即使已挂载/仍在历史中也实时拒绝；tool_search 查询次数有上限。
 * 通过 @TestConfiguration 注入 fake 扩展工具（含 unauthorized），不影响主上下文。
 */
@SpringBootTest
class ToolSearchMountIntegrationTest {

    @Autowired
    private ToolDispatcher dispatcher;
    @Autowired
    private RunToolMountStore mountStore;
    @Autowired
    private ProjectRepository projectRepository;

    @TempDir
    Path projectRoot;

    /** 未授权扩展工具：定义合法但 authorized=false。 */
    @TestConfiguration
    static class FakeExtensionTools {
        @Bean
        Tool fakeRevokedTool() {
            return new Tool() {
                @Override
                public String name() {
                    return "fake_revoked";
                }

                @Override
                public ToolDefinition customDefinition() {
                    com.fasterxml.jackson.databind.node.ObjectNode schema =
                            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                    schema.put("type", "object");
                    return new ToolDefinition("fake_revoked", "已撤销授权的测试工具", "1.0.0",
                            "EXTENSION", "NONE", schema);
                }

                @Override
                public ExtensionMeta extensionMeta() {
                    return new ExtensionMeta("已撤销授权的测试工具", false);
                }

                @Override
                public boolean requiresWorkspace() {
                    return false;
                }

                @Override
                public ToolOutcome execute(com.fasterxml.jackson.databind.JsonNode arguments,
                                           ToolExecutionContext context) {
                    throw new AssertionError("未授权工具不应被执行");
                }
            };
        }
    }

    private String persistTrustedProject() {
        String id = "proj-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        projectRepository.save(new ProjectEntity(id, "挂载测试项目", projectRoot.toString(),
                true, Instant.now(), Instant.now()));
        return id;
    }

    private ToolOutcomeDto dispatch(String projectId, String runId, String tool, String argumentsJson) {
        return dispatcher.dispatch(runId, "thr-1", "turn-1", projectId,
                Instant.now().plusSeconds(600), tool, argumentsJson, "call-search");
    }

    @Test
    @Transactional
    void extensionToolWithoutMountIsRejected() {
        String projectId = persistTrustedProject();
        ToolOutcomeDto outcome = dispatch(projectId, "run-nomount-" + UUID.randomUUID(),
                "memory_recall", "{}");
        assertEquals("FAILED", outcome.status());
        assertEquals("TOOL_NOT_MOUNTED", outcome.errorCode());
    }

    @Test
    @Transactional
    void mountedExtensionToolExecutesAndIsRunIsolated() throws Exception {
        String projectId = persistTrustedProject();
        String runId = "run-mount-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        assertEquals("FAILED", dispatch(projectId, runId, "memory_recall", "{}").status());

        mountStore.mount(runId, "memory_recall");
        ToolOutcomeDto outcome = dispatch(projectId, runId, "memory_recall", "{}");
        assertEquals("SUCCEEDED", outcome.status(), outcome.summary());

        // 挂载按 Run 隔离：其他 Run 未挂载仍拒绝
        assertEquals("TOOL_NOT_MOUNTED",
                dispatch(projectId, "run-other-" + UUID.randomUUID(), "memory_recall", "{}").errorCode());
        mountStore.release(runId);
    }

    @Test
    @Transactional
    void revokedAuthorizationIsRecheckedAtExecution() {
        String projectId = persistTrustedProject();
        String runId = "run-revoke-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        // 绕过 tool_search 直接挂载（模拟历史会话遗留挂载）：执行时实时复查授权仍拒绝
        mountStore.mount(runId, "fake_revoked");
        ToolOutcomeDto outcome = dispatch(projectId, runId, "fake_revoked", "{}");
        assertEquals("DENIED", outcome.status());
        assertEquals("PERMISSION_DENIED", outcome.errorCode());
        mountStore.release(runId);
    }

    @Test
    @Transactional
    void unauthorizedToolIsInvisibleToSearchButRegisteredInCatalog() {
        String projectId = persistTrustedProject();
        String runId = "run-search-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        // 查询词匹配未授权工具 fake_revoked 的描述（"已撤销授权的测试工具"），
        // 但 D-11 BM25 主排序 + 低相关返回空：未授权工具被过滤且无授权工具命中时结果为空。
        ToolOutcomeDto search = dispatch(projectId, runId, "tool_search", "{\"query\":\"撤销 测试工具\"}");
        assertEquals("SUCCEEDED", search.status(), search.summary());
        assertFalse(search.structuredContent().path("matched").toString().contains("fake_revoked"),
                "未授权扩展工具不可发现");
        // 授权工具查询可正常命中，证明搜索链路可用
        ToolOutcomeDto authorized = dispatch(projectId, runId, "tool_search", "{\"query\":\"经验索引\"}");
        assertEquals("SUCCEEDED", authorized.status());
        assertTrue(authorized.structuredContent().path("matched").toString().contains("memory_recall"),
                "授权扩展工具应可被发现");
        mountStore.release(runId);
    }

    @Test
    @Transactional
    void searchCountLimitPerRunIsEnforced() throws Exception {
        String projectId = persistTrustedProject();
        String runId = "run-limit-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ToolOutcomeDto last = null;
        for (int i = 0; i < 6; i++) {
            last = dispatch(projectId, runId, "tool_search", "{\"query\":\"memory\"}");
            assertEquals("SUCCEEDED", last.status());
        }
        // 配置 max-searches-per-run=5：第 6 次达到上限，返回 limitReached 而不冒充失败
        assertTrue(last.structuredContent().path("limitReached").asBoolean());
        mountStore.release(runId);
    }
}
