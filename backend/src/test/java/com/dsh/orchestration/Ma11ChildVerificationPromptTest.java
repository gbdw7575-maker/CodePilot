package com.dsh.orchestration;

import com.dsh.application.ModelProfileService;
import com.dsh.common.IdGen;
import com.dsh.storage.MessageEntity;
import com.dsh.storage.MessageRepository;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-11c-2 子 Agent 验证与写范围引导测试（Fake，不产生真实费用）。
 *
 * <p>背景（验收记录 §11.7 宽预算诊断）：子 Agent 不知道项目已有 {@code .dsh/verify.yaml}
 * 验证契约，只能在 {@code /tmp} 自造临时检查程序——单轮委派实测 194 次 {@code run_command}
 * 中绝大多数属此类，并带动上下文 4–5 倍膨胀；另有 3 次因写工作区外被判 SCOPE_VIOLATION
 * 而整任务硬失败不可重试。</p>
 *
 * <p>本切片把「既有验证命令」与「冻结写范围」显式注入子 Run 首条消息。断言的是消息内容
 * （子 Agent 实际可见的输入），不依赖模型行为，因此不引入真实调用。</p>
 */
@SpringBootTest
class Ma11ChildVerificationPromptTest {

    @Autowired private SubagentService subagentService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ModelProfileRepository profileRepository;
    @Autowired private ThreadRepository threadRepository;
    @Autowired private TurnRepository turnRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private MessageRepository messageRepository;

    @TempDir Path projectRoot;

    @Test
    void childPromptCarriesVerifyContractAndTempPathWarning() throws Exception {
        // 基线项目：带 .dsh/verify.yaml 的既定验证入口
        Files.createDirectories(projectRoot.resolve(".dsh"));
        Files.writeString(projectRoot.resolve(".dsh/verify.yaml"),
                "command: javac -encoding UTF-8 -d out src/App.java\n"
                        + "expectTests: false\n"
                        + "timeoutSeconds: 120\n"
                        + "planVersion: eval-v1\n");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/App.java"), "public class App {}\n");

        Chain c = buildChain();
        SubagentResult result = subagentService.run(
                new SubagentRequest(c.runId(), "CODER", "修复 App 的编译错误",
                        List.of(), null));
        assertEquals("COMPLETED", result.state(), result.stopReason());

        String firstUser = firstUserMessage(result.runId());
        // 证据留档：把子 Run 实际收到的首条消息写到固定位置，供验收核对（不参与断言）
        Path dump = Path.of(System.getProperty("ma11.prompt.dump", "target/ma11c2-child-prompt.txt"));
        try {
            Files.createDirectories(dump.toAbsolutePath().getParent());
            Files.writeString(dump, firstUser);
        } catch (Exception ignored) {
            // 留档失败不影响断言
        }
        assertTrue(firstUser.contains("javac -encoding UTF-8 -d out src/App.java"),
                "子 Run 首条消息应包含项目既有验证命令；实际: " + firstUser);
        assertTrue(firstUser.contains("不要为验证而在工作区外或临时目录编写新的检查程序")
                        || firstUser.contains("不要为验证而在工作区外或临时目录"),
                "应明确禁止自造验证脚手架；实际: " + firstUser);
        assertTrue(firstUser.contains("/tmp"),
                "应点名 /tmp 这类工作区外路径作为反例；实际: " + firstUser);
    }

    @Test
    void childPromptCarriesWriteScopeAndScopeViolationWarning() {
        Chain c = buildChain();
        SubagentResult result = subagentService.run(
                new SubagentRequest(c.runId(), "CODER", "只改 src/App.java",
                        List.of(), null,
                        "{\"read\":[\"src\"],\"write\":[\"src/App.java\"]}"));
        assertEquals("COMPLETED", result.state(), result.stopReason());

        String firstUser = firstUserMessage(result.runId());
        assertTrue(firstUser.contains("src/App.java"),
                "子 Run 首条消息应列出冻结写范围；实际: " + firstUser);
        assertTrue(firstUser.contains("SCOPE_VIOLATION"),
                "应事先警告越界会判 SCOPE_VIOLATION 且不可重试；实际: " + firstUser);
    }

    /** 无 .dsh/verify.yaml 时按项目类型探测：Java 项目回退 Maven。 */
    @Test
    void childPromptFallsBackToAutoDetectedVerifyCommand() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>\n");
        Chain c = buildChain();
        SubagentResult result = subagentService.run(
                new SubagentRequest(c.runId(), "CODER", "修复构建", List.of(), null));
        assertEquals("COMPLETED", result.state(), result.stopReason());

        String firstUser = firstUserMessage(result.runId());
        assertTrue(firstUser.contains("mvn test -q"),
                "无 verify.yaml 的 Java 项目应回退到自动探测命令；实际: " + firstUser);
    }

    // ------------------------------------------------------- helpers

    private String firstUserMessage(String childRunId) {
        RunEntity child = runRepository.findById(childRunId).orElseThrow();
        TurnEntity childTurn = turnRepository.findById(child.getTurnId()).orElseThrow();
        return messageRepository.listByThread(childTurn.getThreadId()).stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(MessageEntity::getContent)
                .findFirst()
                .orElse("<no user message>");
    }

    private Chain buildChain() {
        Instant now = Instant.now();
        String projectId = "proj-ma11c2-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA11C2", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-ma11c2-" + UUID.randomUUID();
        ModelProfileEntity profile = new ModelProfileEntity(profileId, "fake-fake-answer",
                "fake", "fake-answer", "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now);
        profileRepository.save(profile);
        String threadId = IdGen.next("thr_ma11c2_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA11C2 会话",
                profileId, now, now));
        String runId = IdGen.next("run_ma11c2_");
        String turnId = IdGen.next("turn_ma11c2_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING", 1,
                IdGen.next("ws_"), ModelProfileService.configDigest(profile),
                10, 10, 100_000, now.plusSeconds(600), now, now));
        return new Chain(threadId, turnId, runId);
    }

    private record Chain(String threadId, String turnId, String runId) {
    }
}
