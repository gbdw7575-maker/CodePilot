package com.dsh.api;

import com.dsh.application.ArtifactStore;
import com.dsh.storage.ArtifactEntity;
import com.dsh.storage.ArtifactRepository;
import com.dsh.storage.MessageRepository;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnRepository;
import com.dsh.api.dto.event.ContentBlock;
import com.dsh.api.dto.event.ToolOutcomeDto;
import com.dsh.agent.LoopSteps;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-08 输出与压缩：大工具输出完整落 ArtifactStore，失败证据可重读；
 * 消息内只存截断视图与产物引用，长日志不阻塞 Run 继续运行。
 */
class LargeToolOutputTest extends ApiTestBase {

    @Autowired
    private ArtifactStore artifactStore;

    @Autowired
    private ArtifactRepository artifactRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ThreadRepository threadRepository;

    @Autowired
    private TurnRepository turnRepository;

    @Autowired
    private LoopSteps loopSteps;

    @Test
    void oversizedStructuredMcpResultIsSavedAsCompleteJsonArtifact() throws Exception {
        String projectId = createProject("结构化大输出-" + java.util.UUID.randomUUID(),
                "D:/code/MyAgent/DSH/backend/target/test-run");
        trustProject(projectId, 1);
        String profileId = createProfile("structured-" + java.util.UUID.randomUUID(), "fake-answer");
        String threadId = createThread(projectId, profileId);
        JsonNode accepted = submitTurn(threadId, "保留结构化 MCP 内容块");
        String runId = accepted.path("rootRunId").asText();
        var thread = threadRepository.findById(threadId).orElseThrow();
        var turn = turnRepository.findByThreadId(threadId).get(0);

        var original = mapper.createObjectNode();
        var image = mapper.createObjectNode();
        image.put("type", "image");
        image.put("mimeType", "image/png");
        image.put("data", "A".repeat(100_000));
        original.set("content", mapper.createArrayNode().add(image));
        original.put("isError", false);
        var outcome = new ToolOutcomeDto("SUCCEEDED", null, "MCP 多块结果",
                List.of(new ContentBlock.TextBlock("[image 内容块]")), original,
                List.of(), false, 1, null, false, "call-structured", "mcp_multi");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(loopSteps, "saveToolResult",
                "call-structured", "mcp_multi", outcome, thread, turn);

        JsonNode stored = mapper.readTree(messageRepository.listByThread(threadId).stream()
                .filter(m -> "tool".equals(m.getRole()) && m.getContent().contains("call-structured"))
                .findFirst().orElseThrow().getContent());
        String artifactId = stored.path("structuredArtifactId").asText();
        assertFalse(artifactId.isBlank(), "超大结构化内容须有独立产物引用");
        assertFalse(stored.has("structuredContent"), "消息不应内联超大图像数据");
        assertEquals("application/json", artifactRepository.findById(artifactId).orElseThrow().getMediaType());
        JsonNode restored = mapper.readTree(artifactStore.readContent(artifactId));
        assertEquals(100_000, restored.path("content").get(0).path("data").asText().length());
        assertEquals("image/png", restored.path("content").get(0).path("mimeType").asText());
        assertEquals(runId, artifactRepository.findById(artifactId).orElseThrow().getRunId());
    }

    @Test
    void largeToolOutputIsPreservedInArtifactStoreAndRunCompletes() throws Exception {
        String projectId = createProject("大输出项目-" + java.util.UUID.randomUUID(),
                "D:/code/MyAgent/DSH/backend/target/test-run");
        trustProject(projectId, 1);
        String profileId = createProfile("loop-tool-big-" + java.util.UUID.randomUUID(), "fake-tool-big");
        String threadId = createThread(projectId, profileId);

        JsonNode accepted = submitTurn(threadId, "执行一个大输出工具");
        String runId = accepted.get("rootRunId").asText();

        // Run 应正常完成（长日志不阻塞）
        mockMvc.perform(get("/api/v1/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.budget.toolCallsUsed").value(1));

        // 该 Run 应产生至少一个产物（完整工具输出）
        List<ArtifactEntity> artifacts = artifactRepository.findAll().stream()
                .filter(a -> runId.equals(a.getRunId()))
                .toList();
        assertFalse(artifacts.isEmpty(), "大工具输出应落 ArtifactStore 保全完整证据");

        ArtifactEntity artifact = artifacts.get(0);
        assertEquals("text/plain", artifact.getMediaType());
        assertTrue(artifact.getSizeBytes() > LoopStepsLargeOutputRef.LARGE_TOOL_OUTPUT_CHARS,
                "产物应保存完整输出（超过阈值），实际字节: " + artifact.getSizeBytes());

        // 产物内容可重读，且为完整 echo 输出
        byte[] content = artifactStore.readContent(artifact.getId());
        String text = new String(content, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("echo: "), "产物内容应为完整工具输出");
        assertTrue(text.length() > LoopStepsLargeOutputRef.LARGE_TOOL_OUTPUT_CHARS,
                "重读内容应完整未截断");

        var toolMessage = messageRepository.listByThread(threadId).stream()
                .filter(m -> "tool".equals(m.getRole())).findFirst().orElseThrow();
        JsonNode modelView = mapper.readTree(toolMessage.getContent());
        assertEquals(artifact.getId(), modelView.path("fullOutputArtifactId").asText());
        assertTrue(modelView.path("truncated").asBoolean());
        assertTrue(modelView.path("content").get(0).path("text").asText().length() < 9000,
                "当前轮工具视图也应有界，不能把完整长日志送入模型");
    }

    /** 引用 LoopSteps 的阈值常量，避免硬编码。 */
    static class LoopStepsLargeOutputRef {
        static final int LARGE_TOOL_OUTPUT_CHARS = com.dsh.agent.LoopSteps.LARGE_TOOL_OUTPUT_CHARS;
    }
}
