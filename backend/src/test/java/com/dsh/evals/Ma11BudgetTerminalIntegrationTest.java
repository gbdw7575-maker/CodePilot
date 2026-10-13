package com.dsh.evals;

import com.dsh.api.dto.ModelOptions;
import com.dsh.api.dto.ModelProfile;
import com.dsh.api.dto.ModelProfileWrite;
import com.dsh.api.dto.Provider;
import com.dsh.application.ModelProfileService;
import com.dsh.evals.model.EvalBudget;
import com.dsh.evals.model.EvalCategory;
import com.dsh.evals.model.EvalConfig;
import com.dsh.evals.model.EvalDataset;
import com.dsh.evals.model.EvalFailureType;
import com.dsh.evals.model.EvalGraderSpec;
import com.dsh.evals.model.EvalReport;
import com.dsh.evals.model.EvalSample;
import com.dsh.evals.model.EvalTrial;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-11 严格预算终态判定集成回归（验收记录 §11）：
 * 最后一次响应结算后累计 token 越过上限时，不得以成功终态收尾。
 *
 * <p>此前只在下一轮请求前检查（准入判定），最后一次响应可以不经过准入检查直接完成，
 * 于是「结算 206233 > 上限 200000」的 trial 仍被记为 PASS（见 §10.3.2）。本回归用
 * 离线 Fake Provider 固定该行为：越界的最终回答必须落 BUDGET_LIMIT 失败。</p>
 *
 * <p>不使用真实 Provider 与真实凭据；预算上限取极小值以确定性越界。</p>
 */
@SpringBootTest
class Ma11BudgetTerminalIntegrationTest {

    @Autowired
    EvalRunner evalRunner;
    @Autowired
    ModelProfileService modelProfileService;
    @Autowired
    ModelProfileRepository modelProfileRepository;

    @TempDir
    Path tempDir;

    private static final EvalGraderSpec NOOP_GRADER =
            new EvalGraderSpec("echo Tests run: 1, Failures: 0", ".", 30, true, 1);

    private ModelProfile profile(String name, String model) {
        return modelProfileService.create(new ModelProfileWrite(
                name, Provider.fake, model, "http://127.0.0.1:9",
                null, new ModelOptions(ModelOptions.Thinking.disabled, null, 4096, false)));
    }

    private EvalConfig config(String name, ModelProfile profile, EvalBudget budget, int repeatCount) {
        ModelProfileEntity entity = modelProfileRepository.findById(profile.id()).orElseThrow();
        String digest = ModelProfileService.configDigest(entity);
        String configDigest = EvalConfig.computeDigest(name, "v1", "dev", digest, budget,
                "local-eval", repeatCount, null);
        return new EvalConfig(name, "v1", "dev", Set.of(), profile.id(), digest, budget,
                null, "local-eval", repeatCount, configDigest);
    }

    private Path baseline(String name) throws Exception {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("README.md"), "ma11 budget baseline\n", StandardCharsets.UTF_8);
        return dir;
    }

    /** B03: 极小预算提前拒绝，不能为了重现历史越界而先花费一次调用。 */
    @Test
    void finalAnswerCannotBeRequestedWithInsufficientBudget() throws Exception {
        ModelProfile p = profile("ma11-budget-plain", "fake-plain");
        EvalBudget budget = new EvalBudget(4, 4, 1, 2);
        EvalDataset dataset = new EvalDataset("ma11-budget", "v1",
                List.of(new EvalSample("b1", "请说明本次改动", EvalCategory.SIMPLE, NOOP_GRADER)),
                Set.of("b1"), Set.of());

        EvalReport report = evalRunner.evaluate(config("ma11-budget", p, budget, 1),
                dataset, baseline("b-plain"), null);

        EvalTrial trial = report.trials().get(0);
        assertEquals("FAILED", trial.terminalState());
        assertEquals("BUDGET_LIMIT", trial.stopReason());
        assertEquals(EvalFailureType.BUDGET_EXCEEDED, trial.failureType());
        assertEquals("SKIPPED", trial.graderStatus());
        assertTrue(trial.settledTokens() == null || trial.settledTokens() == 0,
                "调用前拒绝不得伪造供应商已产生用量");
        assertEquals(0, report.passedTrials());
    }

    /** 同配置对照组：预算充足时同样的最终回答照旧完成，严格判定不得误伤。 */
    @Test
    void finalAnswerWithinCapStillCompletes() throws Exception {
        ModelProfile p = profile("ma11-budget-control", "fake-plain");
        EvalBudget budget = new EvalBudget(4, 4, 200000, 2);
        EvalDataset dataset = new EvalDataset("ma11-budget-ok", "v1",
                List.of(new EvalSample("b1", "请说明本次改动", EvalCategory.SIMPLE, NOOP_GRADER)),
                Set.of("b1"), Set.of());

        EvalReport report = evalRunner.evaluate(config("ma11-budget-ok", p, budget, 1),
                dataset, baseline("b-control"), null);

        EvalTrial trial = report.trials().get(0);
        assertEquals("COMPLETED", trial.terminalState());
        assertEquals(EvalFailureType.AGENT_NO_CHANGES, trial.failureType());
        assertEquals(0, report.failureBreakdown().get(EvalFailureType.BUDGET_EXCEEDED.name()));
    }

    /** B03: 不足以容纳请求时连工具轮也不得发起，更不能留下工具副作用。 */
    @Test
    void toolRoundIsRejectedBeforeProviderInvocation() throws Exception {
        ModelProfile p = profile("ma11-budget-tool", "fake-patch");
        EvalBudget budget = new EvalBudget(4, 4, 1, 2);
        EvalDataset dataset = new EvalDataset("ma11-budget-tool", "v1",
                List.of(new EvalSample("b1", "请修复该模块", EvalCategory.SIMPLE, NOOP_GRADER)),
                Set.of("b1"), Set.of());

        EvalReport report = evalRunner.evaluate(config("ma11-budget-tool", p, budget, 1),
                dataset, baseline("b-tool"), null);

        EvalTrial trial = report.trials().get(0);
        assertEquals("FAILED", trial.terminalState());
        assertEquals("BUDGET_LIMIT", trial.stopReason());
        assertEquals(EvalFailureType.BUDGET_EXCEEDED, trial.failureType());
        assertEquals(0, trial.toolCalls(), "调用前拒绝不得执行模型工具副作用");
    }
}
