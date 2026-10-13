package com.dsh.api;

import com.dsh.agent.LoopSteps;
import com.dsh.agent.TokenEstimator;
import com.dsh.agent.model.ModelMessage;
import com.dsh.agent.model.ModelRequest;
import com.dsh.storage.MessageEntity;
import com.dsh.storage.MessageRepository;
import com.dsh.storage.RunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-04 装配集成验收：system 稳定前缀在最前（含根规则与目录规则来源/版本），
 * 用户原文逐字不变；目录规则按提及路径的作用域注入且排序在根规则之后。
 */
class RuleInjectionApiTest extends ApiTestBase {

    @Autowired
    private LoopSteps loopSteps;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private RunRepository runRepository;

    @TempDir
    Path projectRoot;

    private String bootstrap(Path root) throws Exception {
        String projectId = createProject("规则注入-" + UUID.randomUUID(),
                root.toString().replace('\\', '/'));
        trustProject(projectId, 1);
        String profileId = createProfile("fake-answer-" + UUID.randomUUID(), "fake-answer");
        return createThread(projectId, profileId);
    }

    private ModelRequest assembleAfterRun(String threadId, String input) throws Exception {
        JsonNode accepted = submitTurn(threadId, input);
        String runId = accepted.get("rootRunId").asText();
        return loopSteps.assemble(runId, loopSteps.snapshot(runId));
    }

    @Test
    void systemPrefixFirstAndUserInputVerbatim() throws Exception {
        Files.writeString(projectRoot.resolve("AGENTS.md"), "项目规则：先跑测试", StandardCharsets.UTF_8);
        String threadId = bootstrap(projectRoot);

        String input = "请解释这段代码";
        ModelRequest request = assembleAfterRun(threadId, input);
        List<ModelMessage> messages = request.messages();
        assertEquals(TokenEstimator.calibrateInput(
                        TokenEstimator.estimateMessages(messages) + TokenEstimator.estimateTools(request.tools())),
                request.estimatedInputTokens(), "输入 token 粗估不得包含预留输出 token");

        assertEquals("system", messages.get(0).role(), "首条必须是 system 稳定前缀");
        String system = messages.get(0).content();
        assertTrue(system.contains("来源: AGENTS.md"), "system 应记录规则来源: " + system);
        assertTrue(system.contains("项目规则：先跑测试"), "system 应包含规则正文");
        assertTrue(system.contains("版本"), "system 应记录内容版本");

        assertEquals("user", messages.get(1).role());
        assertEquals(input, messages.get(1).content(), "用户原文必须逐字不变");
    }

    @Test
    void directoryRulesScopedByMentionedPathAndOrderedAfterRoot() throws Exception {
        Files.writeString(projectRoot.resolve("AGENTS.md"), "根规则", StandardCharsets.UTF_8);
        Path src = Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(src.resolve("AGENTS.md"), "src 目录规则", StandardCharsets.UTF_8);
        String threadId = bootstrap(projectRoot);

        ModelRequest request = assembleAfterRun(threadId, "请修改 src/App.java 的逻辑");
        String system = request.messages().get(0).content();

        int rootIdx = system.indexOf("来源: AGENTS.md");
        int dirIdx = system.indexOf("来源: src/AGENTS.md");
        assertTrue(rootIdx >= 0 && dirIdx > rootIdx,
                "目录规则应按作用域注入且排序在根规则之后: " + system);
        assertTrue(system.contains("src 目录规则"));
    }

    @Test
    void unmentionedDirectoryRulesNotInjected() throws Exception {
        Files.writeString(projectRoot.resolve("AGENTS.md"), "根规则", StandardCharsets.UTF_8);
        Path other = Files.createDirectories(projectRoot.resolve("other"));
        Files.writeString(other.resolve("AGENTS.md"), "无关目录规则", StandardCharsets.UTF_8);
        String threadId = bootstrap(projectRoot);

        ModelRequest request = assembleAfterRun(threadId, "与目录无关的问题");
        String system = request.messages().get(0).content();
        assertTrue(system.contains("来源: AGENTS.md"));
        assertTrue(!system.contains("无关目录规则"), "未提及目录的规则不应注入");
    }

    @Test
    void currentTurnRulesReplacePreviousTurnScope() throws Exception {
        Files.writeString(projectRoot.resolve("AGENTS.md"), "根规则");
        Path a = Files.createDirectories(projectRoot.resolve("a"));
        Path b = Files.createDirectories(projectRoot.resolve("b"));
        Files.writeString(a.resolve("AGENTS.md"), "A 规则");
        Files.writeString(b.resolve("AGENTS.md"), "B 规则");
        String threadId = bootstrap(projectRoot);
        assembleAfterRun(threadId, "查看 a/One.java");
        ModelRequest second = assembleAfterRun(threadId, "查看 b/Two.java");
        String system = second.messages().get(0).content();
        assertTrue(system.contains("B 规则"));
        assertTrue(!system.contains("A 规则"));
        assertEquals("查看 b/Two.java", second.messages().stream()
                .filter(m -> "user".equals(m.role())).reduce((x, y) -> y).orElseThrow().content());
    }

    @Test
    void successfulFileAccessActivatesScopedRulesButDeniedAccessDoesNot() throws Exception {
        Files.writeString(projectRoot.resolve("AGENTS.md"), "根规则");
        Path src = Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(src.resolve("AGENTS.md"), "src 规则");
        Path other = Files.createDirectories(projectRoot.resolve("other"));
        Files.writeString(other.resolve("AGENTS.md"), "other 规则");
        String threadId = bootstrap(projectRoot);
        String runId = submitTurn(threadId, "检查文件").get("rootRunId").asText();
        String turnId = runRepository.findById(runId).orElseThrow().getTurnId();
        long sequence = messageRepository.findFirstByThreadIdOrderBySequenceDesc(threadId)
                .orElseThrow().getSequence() + 1;
        messageRepository.save(new MessageEntity("msg_" + UUID.randomUUID(), threadId, turnId,
                LoopSteps.ROLE_ASSISTANT_TOOL,
                "{\"calls\":[{\"callId\":\"call_ok\",\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"src/App.java\\\"}\"},{\"callId\":\"call_denied\",\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"other/App.java\\\"}\"}]}",
                sequence, Instant.now()));
        messageRepository.save(new MessageEntity("msg_" + UUID.randomUUID(), threadId, turnId,
                LoopSteps.ROLE_TOOL, "{\"callId\":\"call_ok\",\"name\":\"read_file\",\"status\":\"SUCCEEDED\",\"summary\":\"read\"}",
                sequence + 1, Instant.now()));
        messageRepository.save(new MessageEntity("msg_" + UUID.randomUUID(), threadId, turnId,
                LoopSteps.ROLE_TOOL, "{\"callId\":\"call_denied\",\"name\":\"read_file\",\"status\":\"DENIED\",\"summary\":\"denied\"}",
                sequence + 2, Instant.now()));
        String system = loopSteps.assemble(runId, loopSteps.snapshot(runId)).messages().get(0).content();
        assertTrue(system.contains("src 规则"));
        assertTrue(!system.contains("other 规则"));
    }
}
