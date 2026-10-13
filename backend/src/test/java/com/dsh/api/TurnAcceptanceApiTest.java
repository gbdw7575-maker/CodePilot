package com.dsh.api;

import com.dsh.storage.RunRepository;
import com.dsh.storage.TurnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Turn 接受语义（API-01~05）：原样保存、校验、幂等、活动轮次与版本前置（DSH-002）。 */
class TurnAcceptanceApiTest extends ApiTestBase {

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private TurnRepository turnRepository;

    private MvcResult submit(String threadId, String idempotencyKey, String jsonInput) throws Exception {
        return postJson("/api/v1/threads/" + threadId + "/turns", jsonInput, idempotencyKey);
    }

    @Test
    void chineseAndNewlineInputIsStoredVerbatim() throws Exception {
        String threadId = bootstrapThread();
        String input = "第一行：修复登录超时\n第二行：期望 409\t结束";
        String json = mapper.writeValueAsString(java.util.Map.of("input", input));

        MvcResult submitted = submit(threadId, uniqueKey("turn"), json);
        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted().match(submitted);
        String turnId = jsonField(submitted, "turnId");
        String rootRunId = jsonField(submitted, "rootRunId");
        assertEquals("QUEUED", jsonField(submitted, "state"));
        assertEquals("0", jsonField(submitted, "eventsAfterSequence"));

        // DSH-003：同步 FakeLoop 已把 Run 跑到追问暂停；Turn 状态与根 Run 同事务推进
        mockMvc.perform(get("/api/v1/turns/" + turnId))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"v3\""))
                .andExpect(jsonPath("$.input").value(input))
                .andExpect(jsonPath("$.state").value("WAITING_INPUT"))
                .andExpect(jsonPath("$.verificationStatus").value("PENDING"));

        // 消息列表第一条为原样用户输入，sequence 为十进制字符串 "1"
        mockMvc.perform(get("/api/v1/threads/" + threadId + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].role").value("user"))
                .andExpect(jsonPath("$.items[0].content").value(input))
                .andExpect(jsonPath("$.items[0].turnId").value(turnId))
                .andExpect(jsonPath("$.items[0].sequence").value("1"));

        // 根 Run 暂停于追问；预算上限来自服务端配置，已有一轮 KNOWN 用量
        mockMvc.perform(get("/api/v1/runs/" + rootRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MAIN"))
                .andExpect(jsonPath("$.state").value("WAITING_INPUT"))
                .andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.budget.maxModelRequests").value(24))
                .andExpect(jsonPath("$.budget.maxToolCalls").value(80))
                .andExpect(jsonPath("$.budget.maxCumulativeTokens").value(200000))
                .andExpect(jsonPath("$.budget.deadlineAt").isNotEmpty())
                .andExpect(jsonPath("$.budget.modelRequestsUsed").value(1))
                .andExpect(jsonPath("$.usage.status").value("KNOWN"))
                .andExpect(jsonPath("$.usage.inputTokens").isNumber());
    }

    @Test
    void blankOrMissingInputRejectedWithoutSideEffects() throws Exception {
        String threadId = bootstrapThread();
        long runsBefore = runRepository.count();
        long turnsBefore = turnRepository.count();

        mockMvc.perform(post("/api/v1/threads/" + threadId + "/turns")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Idempotency-Key", uniqueKey("turn"))
                        .content("{\"input\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/threads/" + threadId + "/turns")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Idempotency-Key", uniqueKey("turn"))
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // 校验失败不创建任何资源
        assertEquals(runsBefore, runRepository.count(), "校验失败不得创建 Run");
        assertEquals(turnsBefore, turnRepository.count(), "校验失败不得创建 Turn");
    }

    @Test
    void unknownFieldRejected() throws Exception {
        String threadId = bootstrapThread();
        mockMvc.perform(post("/api/v1/threads/" + threadId + "/turns")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Idempotency-Key", uniqueKey("turn"))
                        .content("{\"input\":\"hi\",\"intent\":\"fix\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void oversizedInputRejectedWith413() throws Exception {
        String threadId = bootstrapThread();
        long runsBefore = runRepository.count();
        String huge = "a".repeat(100_001);

        mockMvc.perform(post("/api/v1/threads/" + threadId + "/turns")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Idempotency-Key", uniqueKey("turn"))
                        .content(mapper.writeValueAsString(new java.util.HashMap<>(java.util.Map.of("input", huge)))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("INPUT_TOO_LARGE"));

        assertEquals(runsBefore, runRepository.count(), "超长输入不得创建 Run");
    }

    @Test
    void idempotencyKeyIsRequired() throws Exception {
        String threadId = bootstrapThread();
        mockMvc.perform(post("/api/v1/threads/" + threadId + "/turns")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .content("{\"input\":\"hi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/threads/" + threadId + "/turns")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Idempotency-Key", "short")
                        .content("{\"input\":\"hi\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameKeySameBodyReplaysFirstResponse() throws Exception {
        String threadId = bootstrapThread();
        long turnsBefore = turnRepository.count();
        long runsBefore = runRepository.count();
        String key = uniqueKey("replay");
        String json = "{\"input\":\"幂等重放输入\"}";

        MvcResult first = submit(threadId, key, json);
        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted().match(first);
        String firstBody = first.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        MvcResult second = submit(threadId, key, json);
        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted().match(second);
        String secondBody = second.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(firstBody, secondBody, "相同键相同摘要必须重放首次持久化的响应体");
        assertEquals(turnsBefore + 1, turnRepository.count(), "重放不得新建 Turn");
        assertEquals(runsBefore + 1, runRepository.count(), "重放不得新建 Run");
    }

    @Test
    void sameKeyDifferentBodyConflicts() throws Exception {
        String threadId = bootstrapThread();
        String key = uniqueKey("conflict");

        MvcResult first = submit(threadId, key, "{\"input\":\"第一次内容\"}");
        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted().match(first);

        mockMvc.perform(post("/api/v1/threads/" + threadId + "/turns")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Idempotency-Key", key)
                        .content("{\"input\":\"不同的内容\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void secondTurnWhileActiveRejectedWithDetails() throws Exception {
        String threadId = bootstrapThread();
        MvcResult first = submit(threadId, uniqueKey("turn"), "{\"input\":\"活动轮次\"}");
        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted().match(first);
        String activeTurnId = jsonField(first, "turnId");

        mockMvc.perform(post("/api/v1/threads/" + threadId + "/turns")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Idempotency-Key", uniqueKey("turn"))
                        .content("{\"input\":\"第二个轮次\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_TURN_EXISTS"))
                .andExpect(jsonPath("$.details.activeTurnId").value(activeTurnId));

        // GET /threads 也能看到活动轮次指针
        mockMvc.perform(get("/api/v1/threads/" + threadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeTurnId").value(activeTurnId));
    }

    @Test
    void appendInputQueuesMessageWhileTurnActive() throws Exception {
        String threadId = bootstrapThread();
        MvcResult first = submit(threadId, uniqueKey("turn"), "{\"input\":\"第一段输入\"}");
        String turnId = jsonField(first, "turnId");

        // Run 停在 WAITING_INPUT：补充输入被接受（QUEUED_FOR_MODEL），同步恢复后跑到 COMPLETED
        mockMvc.perform(post("/api/v1/turns/" + turnId + "/inputs")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Idempotency-Key", uniqueKey("append"))
                        .content("{\"input\":\"补充：还要看日志\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.turnId").value(turnId))
                .andExpect(jsonPath("$.status").value("QUEUED_FOR_MODEL"));

        // 公开消息流：用户输入、追问、补充输入、最终回答（工具过程不入公开消息）
        mockMvc.perform(get("/api/v1/threads/" + threadId + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[2].sequence").value("3"))
                .andExpect(jsonPath("$.items[2].role").value("user"))
                .andExpect(jsonPath("$.items[2].content").value("补充：还要看日志"))
                .andExpect(jsonPath("$.items[3].role").value("assistant"));

        mockMvc.perform(get("/api/v1/turns/" + turnId))
                .andExpect(jsonPath("$.state").value("COMPLETED"));
    }

    @Test
    void untrustedProjectCannotSubmitTurn() throws Exception {
        String projectId = createProject("未信任-" + uniqueKey("u"), "D:/code/demo-4");
        String profileId = createProfile("未信任配置-" + uniqueKey("u"));
        String threadId = createThread(projectId, profileId);

        mockMvc.perform(post("/api/v1/threads/" + threadId + "/turns")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(CLIENT_HEADER, CLIENT_VALUE)
                        .header("Idempotency-Key", uniqueKey("turn"))
                        .content("{\"input\":\"未信任项目提交\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_TRUSTED"));
    }
}
