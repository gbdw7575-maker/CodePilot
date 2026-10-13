package com.dsh.contract;

import com.dsh.agent.tool.RunSubagentTool;
import com.dsh.agent.tool.StartOrchestrationTool;
import com.dsh.api.dto.RunRole;
import com.dsh.api.dto.event.EventType;
import com.dsh.application.DshProperties;
import com.dsh.application.event.EventMeta;
import com.dsh.orchestration.OrchestrationReport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-010 契约对齐（开发文档 §12/§18、验收表 DSH-010）：
 * 角色枚举、编排配置默认（关闭/worker 禁派生/深度 2）、两扩展工具 JSON Schema、
 * 不新增事件类型（复用 tool.* / run.*）、报告携带 runId 与模板版本、事件归属含 parentRunId。
 */
class ContractAlignmentDsh010Test {

    private DshProperties properties(boolean enabled) {
        return new DshProperties(null, null, null, null, null, null, null, null, null,
                new DshProperties.Orchestration(enabled, 2, 8, 30, 60_000, 600, false, 4,
                        DshProperties.Orchestration.Plan.defaults(),
                        DshProperties.Orchestration.Quota.defaults(), 3, 500, null),
                null, null, null, null, null);
    }

    @Test
    void trustedRolesMatchContractAndEventTypesAreNotExtended() {
        assertEquals(Set.of("MAIN", "ARCHITECT", "CODER", "TESTER", "REVIEWER", "ORCHESTRATOR"),
                Arrays.stream(RunRole.values()).map(Enum::name).collect(Collectors.toSet()));

        long eventConstants = Arrays.stream(EventType.class.getDeclaredFields())
                .filter(f -> f.getType() == String.class
                        && java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .count();
        // DSH-010 基线 14 个；MA-10（AT-17）经契约评审新增 7 个编排事件（plan.updated、
        // task.dispatched、task.state.changed、task.result.ready、run.waiting_tasks、
        // integration.completed/failed），新基线 21。事件扩展须同步契约与本守卫。
        assertEquals(21, eventConstants,
                "DSH-010 基线 14 + MA-10 AT-17 授权新增 7 = 21 个事件类型");
        assertTrue(Arrays.stream(EventMeta.class.getRecordComponents())
                .anyMatch(c -> "parentRunId".equals(c.getName())),
                "EventMeta 必须携带 parentRunId 归属字段");
    }

    @Test
    void orchestrationDefaultsAreDisabledAndWorkersCannotSpawn() {
        DshProperties.Orchestration def =
                new DshProperties.Orchestration(false, 2, 8, 30, 60_000, 600, false, 4,
                        DshProperties.Orchestration.Plan.defaults(),
                        DshProperties.Orchestration.Quota.defaults(), 3, 500, null);
        assertFalse(def.enabled(), "编排能力默认关闭");
        assertFalse(def.workerCanSpawn(), "worker 子角色禁止再派生");
        assertEquals(2, def.maxDepth());
        assertEquals(8, def.subMaxModelRequests());
        assertEquals(30, def.subMaxToolCalls());
        assertEquals(60_000, def.subMaxTokens());
        assertEquals(600, def.subTimeoutSeconds());
        assertEquals(4, def.maxFanout(), "D-16 有界并行默认扇出 4");
    }

    @Test
    void runSubagentSchemaAndAuthorizationFollowContract() {
        RunSubagentTool disabled = new RunSubagentTool(null, properties(false));
        assertFalse(disabled.extensionMeta().authorized(), "默认门控关闭时不可见不可执行");

        RunSubagentTool tool = new RunSubagentTool(null, properties(true));
        assertTrue(tool.extensionMeta().authorized());
        assertEquals("ORCHESTRATION", tool.customDefinition().category());
        assertEquals("NONE", tool.customDefinition().sideEffect());
        assertFalse(tool.requiresWorkspace(), "架构类子 Agent 无需父工作区");

        JsonNode schema = tool.customDefinition().inputSchema();
        assertEquals(List.of("ARCHITECT", "CODER", "TESTER", "REVIEWER"),
                textList(schema.get("properties").get("role").get("enum")));
        List<String> required = textList(schema.get("required"));
        assertTrue(required.contains("role"));
        assertTrue(required.contains("task"));
    }

    @Test
    void startOrchestrationSchemaPinsCodingReviewTemplateAndNeedsWorkspace() {
        StartOrchestrationTool disabled = new StartOrchestrationTool(null, properties(false));
        assertFalse(disabled.extensionMeta().authorized());

        StartOrchestrationTool tool = new StartOrchestrationTool(null, properties(true));
        assertTrue(tool.extensionMeta().authorized());
        assertTrue(tool.requiresWorkspace(), "汇合需要父工作区");
        JsonNode schema = tool.customDefinition().inputSchema();
        assertEquals(List.of("coding-review"),
                textList(schema.get("properties").get("template").get("enum")));
        List<String> required = textList(schema.get("required"));
        assertTrue(required.contains("template"));
        assertTrue(required.contains("task"));
    }

    private static List<String> textList(JsonNode array) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    @Test
    void reportCarriesRunIdPerStageAndVersionedTemplate() {
        List<String> stageFields = Arrays.stream(OrchestrationReport.Stage.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertTrue(stageFields.containsAll(List.of("role", "runId", "state", "modelRequests",
                "toolCalls", "tokens", "patchArtifactId")),
                "每阶段必须带 runId 与三维用量，支持对照核算");

        List<String> reportFields = Arrays.stream(OrchestrationReport.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertTrue(reportFields.containsAll(List.of("template", "templateVersion", "stages",
                "merge", "totals", "reportArtifactId", "overallStatus")));

        List<String> mergeFields = Arrays.stream(OrchestrationReport.Merge.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertTrue(mergeFields.containsAll(List.of("status", "appliedPaths", "conflicts")));
    }
}
