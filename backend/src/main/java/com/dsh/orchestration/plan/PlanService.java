package com.dsh.orchestration.plan;

import com.dsh.application.DshProperties;
import com.dsh.common.IdGen;
import com.dsh.orchestration.plan.PlanDtos.InputRef;
import com.dsh.orchestration.plan.PlanDtos.PlanDraft;
import com.dsh.orchestration.plan.PlanDtos.ResourceScope;
import com.dsh.orchestration.plan.PlanDtos.SavedPlan;
import com.dsh.orchestration.plan.PlanDtos.SavedTask;
import com.dsh.orchestration.plan.PlanDtos.TaskDraft;
import com.dsh.storage.PlanEntity;
import com.dsh.storage.PlanRepository;
import com.dsh.storage.RunEntity;
import com.dsh.storage.RunRepository;
import com.dsh.storage.TaskEntity;
import com.dsh.storage.TaskRepository;
import com.dsh.storage.TaskAttemptRepository;
import com.dsh.orchestration.acceptance.CanonicalTaskJson;
import com.dsh.storage.ThreadEntity;
import com.dsh.storage.ThreadRepository;
import com.dsh.storage.TurnEntity;
import com.dsh.storage.TurnRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 计划草案服务（MA-02，Agent 编排升级规范 §4/§5）。
 *
 * <p>职责：update_plan v2 的创建/更新落库；revision 乐观并发；任务图语义校验（规则与
 * MA-01 {@code Ma01TaskContractFreezeTest} 的冻结参考实现等价：重复 ID、未知依赖、自依赖、
 * 循环、非法输入引用、图过大）；活跃任务不可静默修改（TASK_LOCKED）。</p>
 *
 * <p>边界：只写 DRAFT 计划，<strong>不启动任何 worker/执行</strong>；不发事件；任务状态推进、
 * attempt 创建与事件契约在后续切片。归属以根 Run 为准（模型传入的 planId 经 rootRunId 复核），
 * projectId 由服务端经 Run→Turn→Thread 解析，模型不可指定。</p>
 */
@Service
public class PlanService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String TASK_STATE_DRAFT = "DRAFT";

    // MA-01 fixtures 冻结的文本上限（配置只管数量类上限，长度类为契约常量）
    static final int MAX_TASK_ID = 64;
    static final int MAX_GOAL = 2000;
    static final int MAX_DELIVERABLE = 500;
    static final int MAX_ACCEPT_ITEMS = TaskAcceptance.MAX_ITEMS;
    static final int MAX_ACCEPT_ITEM = TaskAcceptance.MAX_ITEM_LENGTH;
    static final int MAX_PLAN_TEXT = 16000;
    static final Set<String> EXECUTION_MODES = Set.of("DELEGATED", "INLINE");

    /** 进入这些状态的任务，其冻结字段不可在草案修订中静默变更或移除（规范 §4.3）。 */
    static final Set<String> LOCKED_TASK_STATES =
            Set.of("PENDING", "READY", "RUNNING", "VERIFYING", "WAITING_APPROVAL", "CANCELLING", "SUCCEEDED");

    private static final Pattern TASK_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final int ROOT_WALK_LIMIT = 32;

    private final PlanRepository planRepository;
    private final TaskRepository taskRepository;
    private final TaskAttemptRepository attemptRepository;
    @PersistenceContext private EntityManager entityManager;
    private final RunRepository runRepository;
    private final TurnRepository turnRepository;
    private final ThreadRepository threadRepository;
    private final ObjectMapper objectMapper;
    private final DshProperties properties;
    private final org.springframework.beans.factory.ObjectProvider<
            com.dsh.orchestration.event.OrchestrationEventBridge> bridge;

    public PlanService(PlanRepository planRepository, TaskRepository taskRepository, TaskAttemptRepository attemptRepository,
                       RunRepository runRepository, TurnRepository turnRepository,
                       ThreadRepository threadRepository, ObjectMapper objectMapper,
                       DshProperties properties,
                       org.springframework.beans.factory.ObjectProvider<
                               com.dsh.orchestration.event.OrchestrationEventBridge> bridge) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.attemptRepository = attemptRepository;
        this.runRepository = runRepository;
        this.turnRepository = turnRepository;
        this.threadRepository = threadRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.bridge = bridge;
    }

    /**
     * 创建或更新一份计划草案。整批语义：任一校验失败不落任何任务行。
     *
     * @throws PlanServiceException 冻结 reasonCode（VALIDATION_FAILED、REVISION_MISMATCH、
     *         TASK_LOCKED 等，见 MA-01 fixtures reasonCodes）
     */
    // 校验拒绝发生在任何计划写入之前；模型可修正后继续，不污染调用方 Loop 事务。
    // 内部/数据库异常仍按默认规则回滚，不能使用 noRollbackFor=Exception。
    @Transactional(noRollbackFor = PlanValidationException.class)
    public SavedPlan saveDraft(String runId, PlanDraft draft) {
        if (draft == null || draft.tasks() == null) {
            throw new PlanValidationException("VALIDATION_FAILED", "计划任务列表不能为空");
        }
        RootContext root = resolveRoot(runId);
        DshProperties.Orchestration.Plan limits = properties.orchestration().plan();

        validateTasks(draft, limits);
        validateRevisionPair(draft);

        Instant now = Instant.now();
        PlanEntity plan;
        boolean create = draft.planId() == null;
        Map<String, TaskEntity> existing = new LinkedHashMap<>();
        Map<String, String> incomingSignatures = new HashMap<>();
        for (TaskDraft task : draft.tasks()) incomingSignatures.put(task.taskId(), frozenSignature(task));
        List<TaskEntity> replaced = new ArrayList<>();
        int generation = 1;
        if (create) {
            plan = new PlanEntity(IdGen.next("plan_"), root.rootRunId(), root.projectId(),
                    1, STATUS_DRAFT, normalizeGoal(draft.goal()), now, now);
        } else {
            plan = planRepository.findForUpdate(draft.planId(), root.rootRunId())
                    .orElseThrow(() -> new PlanValidationException("PLAN_NOT_FOUND",
                            "计划不存在或不属于当前 Run: " + draft.planId()));
            // A locking query can still return a stale instance already managed by the caller.
            entityManager.refresh(plan, LockModeType.PESSIMISTIC_WRITE);
            if (plan.getRevision() != draft.expectedRevision()) {
                throw new PlanValidationException("REVISION_MISMATCH",
                        "计划修订号冲突：expectedRevision=" + draft.expectedRevision()
                                + "，当前 revision=" + plan.getRevision());
            }
            for (TaskEntity task : taskRepository.findForPlanUpdate(plan.getId())) {
                entityManager.refresh(task, LockModeType.PESSIMISTIC_WRITE);
                if (task.getTaskVersion() < 1) throw new PlanValidationException("VALIDATION_FAILED", "现有任务版本损坏");
                existing.put(task.getTaskId(), task);
            }
            assertNoLockedMutation(existing.values(), draft.tasks());
            assertNoRequiredRemoval(existing.values(), draft.tasks());
            for (TaskEntity task : existing.values()) {
                String signature = incomingSignatures.get(task.getTaskId());
                if (signature == null || !signature.equals(frozenSignature(task))) {
                    if (!attemptRepository.findByPlanIdAndTaskIdOrderByAttemptNoDesc(plan.getId(), task.getTaskId()).isEmpty())
                        throw new PlanValidationException("TASK_LOCKED", "已有尝试的任务不能通过普通草案编辑换代或删除；需要显式重规划流程");
                    replaced.add(task);
                }
            }
            Integer saved = plan.getTaskVersionHighWater();
            if (plan.getRevision() < 1 || (saved != null && saved < 1))
                throw new PlanValidationException("VALIDATION_FAILED", "计划修订或版本高水位损坏");
            int high = Math.max(plan.getRevision(), saved == null ? 0 : saved);
            high = Math.max(high, attemptRepository.maximumTaskVersion(plan.getId()));
            for (TaskEntity task : existing.values()) high = Math.max(high, task.getTaskVersion());
            try { generation = Math.addExact(high, 1); Math.addExact(plan.getRevision(), 1); }
            catch (ArithmeticException exhausted) { throw new PlanValidationException("VALIDATION_FAILED", "计划修订或任务版本已耗尽"); }
        }

        List<TaskEntity> rows = new ArrayList<>();
        for (TaskDraft t : draft.tasks()) {
            TaskEntity prior = existing.get(t.taskId());
            if (prior != null && !replaced.contains(prior)) { rows.add(prior); continue; }
            rows.add(new TaskEntity(
                    IdGen.next("task_"), plan.getId(), t.taskId(), generation, t.goal().strip(),
                    writeJson(t.dependsOn() == null ? List.of() : t.dependsOn()),
                    writeJson(t.inputRefs() == null ? List.of() : t.inputRefs()),
                    t.deliverable().strip(),
                    writeJson(t.acceptance()),
                    t.resourceScope() == null ? null : writeJson(t.resourceScope()),
                    t.executionMode(), t.required(), TASK_STATE_DRAFT, now, now));
        }
        // All validation/serialization above: no validation exception after the first write.
        if (!create) {
            plan.setRevision(plan.getRevision() + 1); plan.setGoal(normalizeGoal(draft.goal())); plan.setUpdatedAt(now);
            taskRepository.deleteAll(replaced); taskRepository.flush();
        }
        plan.setTaskVersionHighWater(generation);
        planRepository.save(plan);
        taskRepository.saveAll(rows);
        // MA-10：计划落库同事务桥接 plan.updated（前端刷新计划快照）；开关关闭时桥接 bean 缺席。
        com.dsh.orchestration.event.OrchestrationEventBridge b = bridge.getIfAvailable();
        if (b != null) {
            b.planUpdated(root.rootRunId(), plan.getId(), plan.getRevision());
        }
        return toView(plan, rows);
    }

    // ---------- 校验（规则与 MA-01 fixtures 参考实现等价） ----------

    private void validateTasks(PlanDraft draft, DshProperties.Orchestration.Plan limits) {
        List<TaskDraft> tasks = draft.tasks();
        if (tasks.isEmpty()) {
            throw new PlanValidationException("VALIDATION_FAILED", "tasks 至少包含 1 个任务");
        }
        if (tasks.size() > limits.maxTasksPerPlan()) {
            throw new PlanValidationException("TASK_GRAPH_TOO_LARGE",
                    "任务数 " + tasks.size() + " 超过单计划上限 " + limits.maxTasksPerPlan());
        }

        int textBudget = length(draft.goal());
        Set<String> ids = new HashSet<>();
        Map<String, List<String>> deps = new LinkedHashMap<>();
        Map<String, List<InputRef>> refs = new LinkedHashMap<>();
        for (TaskDraft t : tasks) {
            String id = t.taskId() == null ? "" : t.taskId();
            if (id.isBlank() || id.length() > MAX_TASK_ID || !TASK_ID_PATTERN.matcher(id).matches()) {
                throw new PlanValidationException("VALIDATION_FAILED",
                        "taskId 非法（需匹配 ^[A-Za-z0-9_-]+$ 且长度 ≤ " + MAX_TASK_ID + "）: " + id);
            }
            if (!ids.add(id)) {
                throw new PlanValidationException("DUPLICATE_TASK_ID", "任务标识重复: " + id);
            }
            if (isBlank(t.goal()) || length(t.goal()) > MAX_GOAL) {
                throw new PlanValidationException("VALIDATION_FAILED",
                        "任务 " + id + " 的 goal 必填且 ≤ " + MAX_GOAL + " 字");
            }
            if (isBlank(t.deliverable()) || length(t.deliverable()) > MAX_DELIVERABLE) {
                throw new PlanValidationException("VALIDATION_FAILED",
                        "任务 " + id + " 的 deliverable 必填且 ≤ " + MAX_DELIVERABLE + " 字");
            }
            if (t.acceptance() == null || t.acceptance().isEmpty()) {
                throw new PlanValidationException("VALIDATION_FAILED",
                        "任务 " + id + " 必须在执行前登记至少 1 条 acceptance");
            }
            if (t.acceptance().size() > MAX_ACCEPT_ITEMS) {
                throw new PlanValidationException("TASK_GRAPH_TOO_LARGE",
                        "任务 " + id + " 验收条目超过 " + MAX_ACCEPT_ITEMS + " 条");
            }
            for (String a : t.acceptance()) {
                if (isBlank(a) || a.length() > MAX_ACCEPT_ITEM) {
                    throw new PlanValidationException("VALIDATION_FAILED",
                            "任务 " + id + " 的验收条目必须非空且 ≤ " + MAX_ACCEPT_ITEM + " 字");
                }
            }
            if (!EXECUTION_MODES.contains(t.executionMode())) {
                throw new PlanValidationException("VALIDATION_FAILED",
                        "任务 " + id + " 的 executionMode 必须是 DELEGATED 或 INLINE");
            }
            List<String> d = t.dependsOn() == null ? List.of() : t.dependsOn();
            if (d.size() > limits.maxDepsPerTask()) {
                throw new PlanValidationException("TASK_GRAPH_TOO_LARGE",
                        "任务 " + id + " 依赖数超过 " + limits.maxDepsPerTask());
            }
            if (new HashSet<>(d).size() != d.size()) {
                throw new PlanValidationException("VALIDATION_FAILED",
                        "任务 " + id + " 的 dependsOn 存在重复项");
            }
            List<InputRef> r = t.inputRefs() == null ? List.of() : t.inputRefs();
            if (r.size() > limits.maxInputRefsPerTask()) {
                throw new PlanValidationException("TASK_GRAPH_TOO_LARGE",
                        "任务 " + id + " inputRefs 超过 " + limits.maxInputRefsPerTask());
            }
            for (InputRef ref : r) {
                if (ref == null || isBlank(ref.taskId()) || isBlank(ref.output())) {
                    throw new PlanValidationException("VALIDATION_FAILED",
                            "任务 " + id + " 的 inputRefs 每项必须含 taskId 与 output");
                }
            }
            if (t.resourceScope() != null) {
                ResourceScope rs = t.resourceScope();
                if ((rs.read() == null) != (rs.write() == null)
                        || rs.read() == null) {
                    throw new PlanValidationException("VALIDATION_FAILED",
                            "任务 " + id + " 的 resourceScope 必须同时提供 read/write 数组");
                }
            }
            deps.put(id, d);
            refs.put(id, r);
            textBudget += length(t.goal()) + length(t.deliverable());
            for (String a : t.acceptance()) {
                textBudget += length(a);
            }
        }
        if (textBudget > MAX_PLAN_TEXT) {
            throw new PlanValidationException("TASK_GRAPH_TOO_LARGE",
                    "计划文本总量 " + textBudget + " 超过上限 " + MAX_PLAN_TEXT);
        }

        for (Map.Entry<String, List<String>> e : deps.entrySet()) {
            for (String d : e.getValue()) {
                if (d.equals(e.getKey())) {
                    throw new PlanValidationException("SELF_DEPENDENCY",
                            "任务 " + d + " 不能依赖自身");
                }
                if (!ids.contains(d)) {
                    throw new PlanValidationException("UNKNOWN_DEPENDENCY",
                            "任务 " + e.getKey() + " 依赖了计划外任务: " + d);
                }
            }
            for (InputRef ref : refs.get(e.getKey())) {
                if (!ids.contains(ref.taskId())) {
                    throw new PlanValidationException("INVALID_INPUT_REF",
                            "任务 " + e.getKey() + " 引用了计划外任务的产物: " + ref.taskId());
                }
            }
        }
        if (hasCycle(deps)) {
            throw new PlanValidationException("CYCLE_DETECTED", "任务依赖存在循环");
        }
    }

    private void validateRevisionPair(PlanDraft draft) {
        boolean hasId = draft.planId() != null && !draft.planId().isBlank();
        boolean hasRev = draft.expectedRevision() != null;
        if (hasId != hasRev) {
            throw new PlanValidationException("REVISION_WITHOUT_PLAN_ID",
                    "更新计划必须同时提供 planId 与 expectedRevision；创建计划二者均省略");
        }
        if (hasRev && draft.expectedRevision() < 1) {
            throw new PlanValidationException("VALIDATION_FAILED", "expectedRevision 必须 ≥ 1");
        }
    }

    /**
     * 活跃/已成功任务的冻结字段在草案修订中不可变更，也不可移除（规范 §4.3）。
     * MA-02 产品流程尚不会产生非 DRAFT 任务；本检查是对后续切片与直接数据写入的前向护栏。
     */
    private void assertNoLockedMutation(java.util.Collection<TaskEntity> current, List<TaskDraft> incoming) {
        Map<String, TaskDraft> incomingById = new HashMap<>();
        for (TaskDraft t : incoming) {
            incomingById.put(t.taskId(), t);
        }
        for (TaskEntity existing : current) {
            if (!LOCKED_TASK_STATES.contains(existing.getState())) {
                continue;
            }
            TaskDraft next = incomingById.get(existing.getTaskId());
            if (next == null) {
                throw new PlanValidationException("TASK_LOCKED",
                        "任务 " + existing.getTaskId() + " 处于 " + existing.getState()
                                + "，不可从计划中移除；先取消并确认结束后再修订");
            }
            if (!frozenSignature(existing).equals(frozenSignature(next))) {
                throw new PlanValidationException("TASK_LOCKED",
                        "任务 " + existing.getTaskId() + " 处于 " + existing.getState()
                                + "，目标/依赖/输入/验收/范围/模式/必需性不可静默修改");
            }
        }
    }

    /**
     * MA-09 AT-16/AT-02：必需任务不可被 update_plan 静默移除（规范 §4.3"移除必需任务
     * 要解释其需求由何处覆盖；不能自动放弃用户要求来获得成功终态"）。
     *
     * <p>仅 CANCELLED 的必需任务可被移除（主 Agent 已显式 cancel_tasks 留下审计痕迹）。
     * FAILED/BLOCKED 的必需任务必须保留在新计划中（可修改定义，不可删除），
     * 否则用户必需需求会被自动放弃。</p>
     */
    private void assertNoRequiredRemoval(java.util.Collection<TaskEntity> current, List<TaskDraft> incoming) {
        java.util.Set<String> incomingIds = new java.util.HashSet<>();
        for (TaskDraft t : incoming) {
            incomingIds.add(t.taskId());
        }
        for (TaskEntity existing : current) {
            if (!existing.isRequired()) {
                continue;
            }
            if (incomingIds.contains(existing.getTaskId())) {
                continue;
            }
            // 必需任务被移除：仅 CANCELLED 允许（已显式取消，留下审计痕迹）
            if (!"CANCELLED".equals(existing.getState())) {
                throw new PlanValidationException("REQUIRED_TASK_REMOVED",
                        "必需任务 " + existing.getTaskId() + " 处于 " + existing.getState()
                                + "，不可从计划中移除；先 cancel_tasks 取消后再修订，"
                                + "或在新计划中保留该需求");
            }
        }
    }

    /** 冻结字段签名：与 TaskEntity 中 JSON 列的规范化内容一致。 */
    private String frozenSignature(TaskEntity e) {
        var node = objectMapper.createObjectNode().put("taskId", e.getTaskId()).put("goal", e.getGoal())
                .put("deliverable", e.getDeliverable()).put("executionMode", e.getExecutionMode()).put("required", e.isRequired());
        node.set("dependsOn", frozenJson(e.getDependsOn())); node.set("inputRefs", frozenJson(e.getInputRefs()));
        node.set("acceptance", frozenJson(e.getAcceptance()));
        node.set("resourceScope", e.getResourceScope() == null ? objectMapper.nullNode() : frozenJson(e.getResourceScope()));
        return canonicalSignature(node);
    }

    private String frozenSignature(TaskDraft t) {
        var normalized = new TaskDraft(t.taskId(), t.goal().strip(), t.dependsOn() == null ? List.of() : t.dependsOn(),
                t.inputRefs() == null ? List.of() : t.inputRefs(), t.deliverable().strip(), t.acceptance(),
                t.resourceScope(), t.executionMode(), t.required());
        return canonicalSignature(objectMapper.valueToTree(normalized));
    }

    private com.fasterxml.jackson.databind.JsonNode frozenJson(String raw) {
        try { return CanonicalTaskJson.parse(raw.getBytes(StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException invalid) { throw new PlanValidationException("VALIDATION_FAILED", "现有任务冻结JSON损坏"); }
    }
    private String canonicalSignature(com.fasterxml.jackson.databind.JsonNode value) {
        try { return CanonicalTaskJson.digest(value); }
        catch (IllegalArgumentException invalid) { throw new PlanValidationException("VALIDATION_FAILED", "任务定义包含不支持的规范化内容"); }
    }

    // ---------- 基础设施 ----------

    private RootContext resolveRoot(String runId) {
        RunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new PlanValidationException("RESOURCE_NOT_FOUND",
                        "Run 不存在: " + runId));
        RunEntity root = run;
        int guard = 0;
        while (root.getParentRunId() != null && guard++ < ROOT_WALK_LIMIT) {
            final RunEntity current = root;
            root = runRepository.findById(current.getParentRunId())
                    .orElseThrow(() -> new PlanValidationException("RESOURCE_NOT_FOUND",
                            "父 Run 链断裂: " + current.getParentRunId()));
        }
        TurnEntity turn = turnRepository.findById(root.getTurnId()).orElseThrow();
        ThreadEntity thread = threadRepository.findById(turn.getThreadId()).orElseThrow();
        return new RootContext(root.getId(),
                thread.getProjectId() == null ? "" : thread.getProjectId());
    }

    private static boolean hasCycle(Map<String, List<String>> deps) {
        Map<String, Integer> indegree = new HashMap<>();
        deps.keySet().forEach(k -> indegree.put(k, 0));
        deps.values().forEach(list -> list.forEach(d -> indegree.merge(d, 1, Integer::sum)));
        Set<String> removed = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((k, v) -> {
            if (v == 0) {
                queue.add(k);
            }
        });
        while (!queue.isEmpty()) {
            String node = queue.pop();
            if (!removed.add(node)) {
                continue;
            }
            for (String d : deps.getOrDefault(node, List.of())) {
                if (indegree.merge(d, -1, Integer::sum) == 0) {
                    queue.add(d);
                }
            }
        }
        return removed.size() < deps.size();
    }

    private SavedPlan toView(PlanEntity plan, List<TaskEntity> rows) {
        List<SavedTask> tasks = new ArrayList<>();
        for (TaskEntity e : rows) {
            tasks.add(new SavedTask(e.getTaskId(), e.getTaskVersion(), e.getState(),
                    e.getGoal(), readStringList(e.getDependsOn()), e.getDeliverable(),
                    readStringList(e.getAcceptance()), e.getExecutionMode(), e.isRequired()));
        }
        return new SavedPlan(plan.getId(), plan.getRevision(), plan.getStatus(), plan.getGoal(),
                tasks);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new PlanServiceException("INTERNAL_ERROR", "计划字段序列化失败: " + e.getMessage());
        }
    }

    private List<String> readStringList(String json) {
        try {
            List<String> out = new ArrayList<>();
            objectMapper.readTree(json).forEach(n -> out.add(n.asText()));
            return List.copyOf(out);
        } catch (JsonProcessingException e) {
            throw new PlanServiceException("INTERNAL_ERROR", "计划字段反序列化失败: " + e.getMessage());
        }
    }

    private static String normalizeGoal(String goal) {
        return goal == null || goal.isBlank() ? null : goal.strip();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }

    private record RootContext(String rootRunId, String projectId) {
    }
}
