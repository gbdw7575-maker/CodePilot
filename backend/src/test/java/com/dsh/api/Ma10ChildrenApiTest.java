package com.dsh.api;

import com.dsh.common.IdGen;
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
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * MA-10 GET /runs/{runId}/children 直属子运行列表测试（规范 §10 统一预算口径）。
 *
 * <p>覆盖：子 Run 按创建时间升序一次返回、nextCursor 恒 null；每个子 Run 携带预算视图
 * （settledTokens/reservedTokens 与父 Run 同口径）；run 不存在 404；无子 Run 返回空页。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class Ma10ChildrenApiTest {

    @Autowired private ProjectRepository projectRepository;
    @Autowired private ModelProfileRepository profileRepository;
    @Autowired private ThreadRepository threadRepository;
    @Autowired private TurnRepository turnRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private MockMvc mockMvc;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;

    @TempDir Path projectRoot;

    @Test
    void listsChildrenOrderedWithBudget() throws Exception {
        Chain chain = buildChain();
        Instant t0 = Instant.now().minusSeconds(10);
        Instant t1 = t0.plusSeconds(2);
        String child1 = saveChild(chain, "run_ma10ch_c1_", "CODER", "COMPLETED", t0);
        String child2 = saveChild(chain, "run_ma10ch_c2_", "TESTER", "RUNNING", t1);
        // 子 Run 已实结部分 token（父子同一预算账本口径）
        RunEntity child1Entity = runRepository.findById(child1).orElseThrow();
        child1Entity.setBudgetModelRequestsUsed(2);
        child1Entity.setBudgetToolCallsUsed(3);
        child1Entity.setBudgetSettledTokens(250);
        runRepository.save(child1Entity);
        RunEntity child2Entity = runRepository.findById(child2).orElseThrow();
        child2Entity.setBudgetReservedTokens(640);
        runRepository.save(child2Entity);

        JsonNode page = getChildren(chain.runId(), 200);
        assertTrue(page.path("nextCursor").isNull(), "子 Run 有界一次返回，不分页");
        assertEquals(2, page.path("items").size());
        JsonNode first = page.path("items").get(0);
        JsonNode second = page.path("items").get(1);
        assertEquals(child1, first.path("id").asText(), "按 createdAt 升序");
        assertEquals(child2, second.path("id").asText());
        assertEquals(chain.runId(), first.path("parentRunId").asText());
        assertEquals("CODER", first.path("role").asText());
        assertEquals("COMPLETED", first.path("state").asText());
        assertEquals(250, first.path("budget").path("settledTokens").asInt(),
                "已实结 token 在子 Run 预算视图可见");
        assertEquals(0, first.path("budget").path("reservedTokens").asInt());
        assertEquals(640, second.path("budget").path("reservedTokens").asInt(),
                "进行中预约 token 同口径可见");
        assertTrue(second.path("budget").path("settledTokens").isNull());
    }

    @Test
    void unknownRunReturns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/runs/run_ma10ch_missing/children")
                        .header("X-DSH-Client", "local-ui"))
                .andReturn();
        assertEquals(404, result.getResponse().getStatus());
        JsonNode error = mapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("RESOURCE_NOT_FOUND", error.path("code").asText());
    }

    @Test
    void leafRunReturnsEmptyPage() throws Exception {
        Chain chain = buildChain();
        JsonNode page = getChildren(chain.runId(), 200);
        assertEquals(0, page.path("items").size());
        assertTrue(page.path("nextCursor").isNull());
    }

    // ------------------------------------------------------- helpers

    private JsonNode getChildren(String runId, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/runs/" + runId + "/children")
                        .header("X-DSH-Client", "local-ui"))
                .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(),
                "响应: " + result.getResponse().getStatus());
        return mapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String saveChild(Chain chain, String idPrefix, String role, String state,
                             Instant createdAt) {
        String childId = IdGen.next(idPrefix);
        runRepository.save(new RunEntity(childId, chain.turnId(), chain.runId(), role, state,
                "PENDING", 1, IdGen.next("ws_"), "digest-child", 30, 120, 320_000,
                createdAt.plusSeconds(600), createdAt, createdAt));
        return childId;
    }

    private Chain buildChain() {
        Instant now = Instant.now();
        String projectId = "proj-ma10ch-" + UUID.randomUUID();
        projectRepository.save(new ProjectEntity(projectId, "MA10CH", projectRoot.toString(),
                true, now, now));
        String profileId = "prof-ma10ch-" + UUID.randomUUID();
        profileRepository.save(new ModelProfileEntity(profileId, "fake-fake-ma10",
                "fake", "fake-ma10", "http://127.0.0.1:9", null,
                "disabled", null, 4096, false, "ACTIVE", now, now));
        String threadId = IdGen.next("thr_ma10ch_");
        threadRepository.save(new ThreadEntity(threadId, projectId, "MA10CH 会话",
                profileId, now, now));
        String runId = IdGen.next("run_ma10ch_");
        String turnId = IdGen.next("turn_ma10ch_");
        turnRepository.save(new TurnEntity(turnId, threadId, runId, "父任务",
                "RUNNING", "PENDING", threadId, now, now));
        runRepository.save(new RunEntity(runId, turnId, null, "MAIN", "RUNNING", "PENDING",
                1, IdGen.next("ws_"), "digest-ma10ch", 30, 120, 320_000,
                now.plusSeconds(600), now, now));
        return new Chain(projectId, threadId, turnId, runId);
    }

    private record Chain(String projectId, String threadId, String turnId, String runId) {
    }
}
