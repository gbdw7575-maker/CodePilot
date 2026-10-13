package com.dsh.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-01 契约冻结测试基准入口（Agent 编排升级开发规范 §13/§18）。
 *
 * <p>本测试只校验 {@code orchestration/ma01-task-contract-fixtures.json} 中冻结的
 * <strong>目标任务契约</strong>（设计冻结，非已发布运行时接口）：AT-01/AT-02 的契约级
 * 正反例、任务状态到 RunState 的映射不变量、门控与错误码词表、任务图上限。
 * 语义校验函数是冻结规则的参考实现：MA-02 PlanService 的校验行为必须与其等价，
 * 但本测试不实现任何产品功能，不启动模型，不访问数据库。</p>
 */
class Ma01TaskContractFreezeTest {

    /** 现行契约已发布的 HTTP 错误码词表（doc/scripts/build-contracts.cjs x-error-codes）。 */
    private static final Set<String> PUBLISHED_ERROR_CODES = Set.of(
            "VALIDATION_FAILED", "INVALID_CURSOR", "INVALID_INPUT", "INPUT_TOO_LARGE",
            "RESOURCE_NOT_FOUND", "ORIGIN_DENIED", "PERMISSION_DENIED", "PROJECT_NOT_TRUSTED",
            "PATH_OUTSIDE_WORKSPACE", "CREDENTIAL_UNAVAILABLE", "MODEL_CAPABILITY_UNVERIFIED",
            "ACTIVE_TURN_EXISTS", "RESOURCE_BUSY", "INVALID_STATE", "IDEMPOTENCY_CONFLICT",
            "IDEMPOTENCY_IN_PROGRESS", "PRECONDITION_REQUIRED", "VERSION_CONFLICT",
            "APPROVAL_STALE", "APPROVAL_ALREADY_DECIDED", "WORKSPACE_CONFLICT",
            "EVENT_CURSOR_EXPIRED", "MEMORY_DISABLED", "RATE_LIMITED",
            "PROVIDER_UNAVAILABLE", "SERVICE_UNAVAILABLE", "INTERNAL_ERROR");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode fixtures;
    private static Map<String, JsonNode> schemas;

    @BeforeAll
    static void loadFixtures() throws IOException {
        try (InputStream in = Ma01TaskContractFreezeTest.class
                .getResourceAsStream("/orchestration/ma01-task-contract-fixtures.json")) {
            assertNotNull(in, "缺少 MA-01 契约冻结 fixtures 资源文件");
            fixtures = MAPPER.readTree(in);
        }
        schemas = new HashMap<>();
        fixtures.get("schemas").fields().forEachRemaining(e -> schemas.put(e.getKey(), e.getValue()));
    }

    // ---------- AT-01/AT-02：契约级 Schema 正反例 ----------

    @Test
    void schemaCasesMatchFrozenContract() {
        int checked = 0;
        for (JsonNode c : fixtures.get("schemaCases")) {
            String schemaName = c.get("schema").asText();
            assertTrue(schemas.containsKey(schemaName), "未冻结的 Schema: " + schemaName);
            boolean actual = valid(schemas.get(schemaName), c.get("value"));
            assertEquals(c.get("valid").asBoolean(), actual,
                    "正反例与冻结契约不符: " + c.get("name").asText());
            checked++;
        }
        assertTrue(checked >= 25, "正反例数量异常少: " + checked);
    }

    // ---------- AT-02：计划图语义正反例（冻结规则参考实现） ----------

    @Test
    void semanticCasesAreDetectedByFrozenRules() {
        for (JsonNode c : fixtures.get("semanticCases")) {
            String kind = c.get("kind").asText();
            assertEquals(kind, detectSemanticViolation(kind, c.get("value")),
                    "语义反例未被冻结规则识别: " + c.get("name").asText());
        }
    }

    @Test
    void wellFormedPlanPassesFrozenRules() {
        // 取 schemaCases 中 update_plan.create.valid 的 tasks 作为良构正例
        JsonNode tasks = fixtures.get("schemaCases").get(0).get("value").get("tasks");
        assertEquals("update_plan.create.valid", fixtures.get("schemaCases").get(0).get("name").asText());
        assertNullViolation(planViolation(tasks));
    }

    /** 冻结的计划图校验规则：重复 ID、未知依赖、自依赖、循环、非法输入引用、任务图过大。 */
    private static String planViolation(JsonNode tasks) {
        int maxTasks = fixtures.get("limits").get("maxTasksPerPlan").asInt();
        int maxDeps = fixtures.get("limits").get("maxDependsOnPerTask").asInt();
        if (tasks.size() > maxTasks) {
            return "TASK_GRAPH_TOO_LARGE";
        }
        Set<String> ids = new HashSet<>();
        Map<String, List<String>> deps = new HashMap<>();
        Map<String, List<String>> inputRefs = new HashMap<>();
        for (JsonNode t : tasks) {
            String id = t.get("taskId").asText();
            if (!ids.add(id)) {
                return "DUPLICATE_TASK_ID";
            }
            List<String> d = new ArrayList<>();
            t.get("dependsOn").forEach(n -> d.add(n.asText()));
            if (d.size() > maxDeps) {
                return "TASK_GRAPH_TOO_LARGE";
            }
            deps.put(id, d);
            List<String> refs = new ArrayList<>();
            if (t.has("inputRefs")) {
                t.get("inputRefs").forEach(n -> refs.add(n.get("taskId").asText()));
            }
            inputRefs.put(id, refs);
        }
        for (Map.Entry<String, List<String>> e : deps.entrySet()) {
            for (String d : e.getValue()) {
                if (d.equals(e.getKey())) {
                    return "SELF_DEPENDENCY";
                }
                if (!ids.contains(d)) {
                    return "UNKNOWN_DEPENDENCY";
                }
            }
            for (String ref : inputRefs.get(e.getKey())) {
                if (!ids.contains(ref)) {
                    return "INVALID_INPUT_REF";
                }
            }
        }
        return cycleKind(deps);
    }

    private static String cycleKind(Map<String, List<String>> deps) {
        // Kahn 拓扑排序：存在剩余节点即有环；环上节点自依赖其自身路径时报告 CYCLE_DETECTED
        Map<String, Integer> indegree = new HashMap<>();
        deps.keySet().forEach(k -> indegree.put(k, 0));
        deps.values().forEach(list -> list.forEach(d -> indegree.merge(d, 1, Integer::sum)));
        Set<String> seen = new LinkedHashSet<>();
        boolean progress = true;
        while (progress && seen.size() < deps.size()) {
            progress = false;
            for (Map.Entry<String, Integer> e : indegree.entrySet()) {
                if (e.getValue() == 0 && !seen.contains(e.getKey())) {
                    seen.add(e.getKey());
                    deps.getOrDefault(e.getKey(), List.of()).forEach(d -> indegree.merge(d, -1, Integer::sum));
                    progress = true;
                }
            }
        }
        return seen.size() < deps.size() ? "CYCLE_DETECTED" : null;
    }

    private void assertNullViolation(String violation) {
        assertEquals(null, violation, "良构计划不应有违例: " + violation);
    }

    /** 各语义反例对应的最小判定；kind 与 fixtures.reasonCodes 键一致。 */
    private String detectSemanticViolation(String kind, JsonNode value) {
        return switch (kind) {
            case "DUPLICATE_TASK_ID" -> planViolation(arrayToTasks(value, null, null));
            case "UNKNOWN_DEPENDENCY" -> planViolation(arrayToTasks(null, value, null));
            case "SELF_DEPENDENCY" -> planViolation(arrayToTasks(null, value, null));
            case "CYCLE_DETECTED" -> planViolation(arrayToTasks(null, value, null));
            case "INVALID_INPUT_REF" -> planViolation(arrayToTasks(null, null, value));
            case "TASK_GRAPH_TOO_LARGE" -> {
                int n = value.asInt();
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    ids.add("T" + i);
                }
                yield planViolation(arrayToTasks(MAPPER.valueToTree(ids), null, null));
            }
            case "REVISION_MISMATCH" ->
                    value.get("expected").asInt() != value.get("current").asInt() ? kind : "NO_VIOLATION";
            case "REVISION_WITHOUT_PLAN_ID" -> !value.has("planId") && value.has("expectedRevision") ? kind : "NO_VIOLATION";
            case "TASK_LOCKED" -> {
                String state = value.get("state").asText();
                JsonNode terminal = fixtures.get("taskStates").get("terminal");
                boolean terminalState = false;
                for (JsonNode t : terminal) {
                    terminalState |= t.asText().equals(state);
                }
                yield terminalState ? "NO_VIOLATION" : kind;
            }
            case "DEPENDENCY_NOT_DISPATCHED" -> {
                Set<String> dispatched = new HashSet<>();
                for (JsonNode row : value.get("plan")) {
                    if ("DISPATCHED".equals(row.get(1).asText())) {
                        dispatched.add(row.get(0).asText());
                    }
                }
                boolean violation = false;
                for (JsonNode id : value.get("dispatch")) {
                    violation |= !dispatched.contains(id.asText()); // B 未派发即整批拒绝
                }
                yield violation ? kind : "NO_VIOLATION";
            }
            case "REQUEST_KEY_CONFLICT" -> {
                JsonNode first = value.get("first");
                JsonNode second = value.get("second");
                boolean sameKey = first.get("requestKey").asText().equals(second.get("requestKey").asText());
                boolean sameArgs = first.get("taskIds").equals(second.get("taskIds"))
                        && first.get("expectedRevision").equals(second.get("expectedRevision"));
                yield sameKey && !sameArgs ? kind : "NO_VIOLATION";
            }
            default -> throw new IllegalStateException("未登记的语义违例类别: " + kind);
        };
    }

    /** 组装最小 TaskDef 数组：ids 显式给定时用之；否则逐行取 [taskId, dependsOn, inputRefs]。 */
    private static JsonNode arrayToTasks(JsonNode ids, JsonNode depRows, JsonNode refRows) {
        var factory = MAPPER.getNodeFactory();
        var tasks = factory.arrayNode();
        if (ids != null) {
            for (JsonNode id : ids) {
                tasks.addObject().put("taskId", id.asText()).set("dependsOn", factory.arrayNode());
            }
            return tasks;
        }
        var rows = depRows != null ? depRows : refRows;
        for (JsonNode row : rows) {
            var task = tasks.addObject();
            task.put("taskId", row.get(0).asText());
            var deps = factory.arrayNode();
            if (depRows != null) {
                row.get(1).forEach(n -> deps.add(n.asText()));
            }
            task.set("dependsOn", deps);
            if (refRows != null && row.size() > 2) {
                var refs = factory.arrayNode();
                row.get(2).forEach(n -> refs.addObject().put("taskId", n.asText()));
                task.set("inputRefs", refs);
            }
        }
        return tasks;
    }

    // ---------- 状态映射、门控、错误码词表、上限不变量 ----------

    @Test
    void taskStateMappingCoversAllStatesAndPublishedRunStatesOnly() {
        JsonNode values = fixtures.get("taskStates").get("values");
        Set<String> taskStates = new HashSet<>();
        values.forEach(n -> taskStates.add(n.asText()));

        Set<String> mapped = new HashSet<>();
        fixtures.get("stateMapping").forEach(row -> {
            String taskState = row.get("taskState").asText();
            assertTrue(mapped.add(taskState), "状态映射重复: " + taskState);
            assertFalse(row.get("note").asText().isBlank(), "映射缺少冻结说明: " + taskState);
            JsonNode runState = row.get("runState");
            if (!runState.isNull()) {
                assertTrue(fixtures.get("runStateValues").toString().contains("\"" + runState.asText() + "\""),
                        "映射到了未发布的 RunState: " + runState.asText());
            }
        });
        assertEquals(taskStates, mapped, "状态映射必须逐态覆盖且不多不少");
    }

    @Test
    void terminalStatesAreClosedUnderMapping() {
        Set<String> terminal = new HashSet<>();
        fixtures.get("taskStates").get("terminal").forEach(n -> terminal.add(n.asText()));
        Map<String, String> mapping = new HashMap<>();
        fixtures.get("stateMapping").forEach(row -> mapping.put(row.get("taskState").asText(),
                row.get("runState").isNull() ? null : row.get("runState").asText()));
        // 终态任务除 SUCCEEDED 映射 COMPLETED 外，其余不映射运行中 RunState
        assertEquals("COMPLETED", mapping.get("SUCCEEDED"));
        assertEquals("FAILED", mapping.get("FAILED"));
        assertEquals("CANCELLED", mapping.get("CANCELLED"));
        assertTrue(!terminal.contains("RUNNING") && !terminal.contains("CANCELLING"));
    }

    @Test
    void gatingFollowsOrchestrationPolicy() {
        JsonNode gating = fixtures.get("gating");
        Set<String> always = names(gating.get("alwaysMounted"));
        Set<String> gated = names(gating.get("orchestrationGated"));
        Set<String> workerDenied = names(gating.get("workerDenied"));
        assertTrue(always.contains("update_plan"), "update_plan 应常驻");
        assertTrue(always.stream().noneMatch(gated::contains), "常驻与门控集合不得相交");
        assertTrue(workerDenied.containsAll(gated), "worker 必须被拒绝所有新调度工具");
        Set.of("run_subagent", "start_orchestration", "fanout_subagents")
                .forEach(t -> assertTrue(workerDenied.contains(t), "worker 必须被拒绝既有派生工具: " + t));
    }

    @Test
    void reasonCodesStayInsidePublishedErrorVocabulary() {
        fixtures.get("reasonCodes").fields().forEachRemaining(e ->
                assertTrue(PUBLISHED_ERROR_CODES.contains(e.getValue().asText()),
                        "reasonCode " + e.getKey() + " 映射到未发布错误码 " + e.getValue().asText()));
    }

    @Test
    void limitsAreBoundedAndConfigNamesAligned() {
        JsonNode limits = fixtures.get("limits");
        limits.fields().forEachRemaining(e ->
                assertTrue(e.getValue().asInt() > 0 && e.getValue().asInt() <= 16000,
                        "上限必须为正且有界: " + e.getKey()));
        assertEquals(limits.get("maxDispatchBatch").asInt(), limits.get("maxAwaitBatch").asInt());
        assertEquals(limits.get("maxDispatchBatch").asInt(), limits.get("maxCancelBatch").asInt());

        Map<String, Integer> configDefaults = new HashMap<>();
        fixtures.get("configNames").forEach(n ->
                configDefaults.put(n.get("name").asText(), n.get("default").asInt()));
        assertEquals(limits.get("maxTasksPerPlan").asInt(),
                configDefaults.get("dsh.orchestration.plan-max-tasks"));
        assertEquals(limits.get("maxDependsOnPerTask").asInt(),
                configDefaults.get("dsh.orchestration.plan-max-deps-per-task"));
        assertEquals(limits.get("maxInputRefsPerTask").asInt(),
                configDefaults.get("dsh.orchestration.plan-max-input-refs-per-task"));
        assertEquals(limits.get("maxDispatchBatch").asInt(),
                configDefaults.get("dsh.orchestration.dispatch-max-batch"));
        assertTrue(configDefaults.containsKey("dsh.orchestration.await-default-timeout-ms"));
    }

    private static Set<String> names(JsonNode array) {
        Set<String> out = new HashSet<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    // ---------- 迷你 Schema 校验器（子集，对齐 verify-contracts.cjs 支持的关键字） ----------

    private static boolean valid(JsonNode schema, JsonNode value) {
        if (schema.has("$ref")) {
            return valid(schemas.get(schema.get("$ref").asText()), value);
        }
        if (schema.has("anyOf")) {
            boolean any = false;
            for (JsonNode sub : schema.get("anyOf")) {
                any |= valid(sub, value);
            }
            if (!any) {
                return false;
            }
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode e : schema.get("enum")) {
                found |= e.equals(value);
            }
            if (!found) {
                return false;
            }
        }
        if (schema.has("type") && !typeMatches(schema.get("type").asText(), value)) {
            return false;
        }
        if (value.isTextual()) {
            String s = value.asText();
            if (schema.has("minLength") && s.length() < schema.get("minLength").asInt()) {
                return false;
            }
            if (schema.has("maxLength") && s.length() > schema.get("maxLength").asInt()) {
                return false;
            }
            if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").asText()).matcher(s).find()) {
                return false;
            }
        }
        if (value.isNumber()) {
            if (schema.has("minimum") && value.decimalValue().doubleValue() < schema.get("minimum").asDouble()) {
                return false;
            }
            if (schema.has("maximum") && value.decimalValue().doubleValue() > schema.get("maximum").asDouble()) {
                return false;
            }
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
                return false;
            }
            if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) {
                return false;
            }
            if (schema.has("items")) {
                for (JsonNode item : value) {
                    if (!valid(schema.get("items"), item)) {
                        return false;
                    }
                }
            }
        }
        if (value.isObject()) {
            if (schema.has("required")) {
                for (JsonNode req : schema.get("required")) {
                    if (!value.has(req.asText())) {
                        return false;
                    }
                }
            }
            JsonNode props = schema.get("properties");
            var fields = value.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                if (props != null && props.has(e.getKey())) {
                    if (!valid(props.get(e.getKey()), e.getValue())) {
                        return false;
                    }
                } else if (schema.has("additionalProperties")
                        && schema.get("additionalProperties").isBoolean()
                        && !schema.get("additionalProperties").asBoolean()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean typeMatches(String type, JsonNode value) {
        return switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            case "null" -> value.isNull();
            default -> throw new IllegalStateException("未冻结的类型: " + type);
        };
    }
}
