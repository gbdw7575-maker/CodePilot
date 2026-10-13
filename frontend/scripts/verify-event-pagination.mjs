import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import ts from "typescript";

// 直接转译同一份 API 客户端源码，避免测试复制分页实现。
const source = await readFile(new URL("../src/api/client.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText;
const { listAllEvents, foldPlans } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString("base64")}`);

const event = (n) => ({ sequence: String(n), eventId: `evt-${n}`, type: "tool.completed", payload: {}, timestamp: "" });
const calls = [];
globalThis.fetch = async (url) => {
  const query = new URL(url, "http://localhost").searchParams;
  calls.push({ after: query.get("afterSequence"), through: query.get("throughSequence"), limit: query.get("limit") });
  const first = query.get("afterSequence") === "0";
  return Response.json(first
    ? { items: Array.from({ length: 200 }, (_, i) => event(i + 1)), nextAfterSequence: "200", hasMore: true, highWatermark: "201" }
    : { items: [event(200), event(201)], nextAfterSequence: "201", hasMore: false, highWatermark: "201" });
};
const items = await listAllEvents("thr-test");
assert.equal(items.length, 201, "超过单页上限的历史事件应补齐并按 eventId 去重");
assert.equal(items[200].sequence, "201");
assert.deepEqual(calls, [
  { after: "0", through: null, limit: "200" },
  { after: "200", through: "201", limit: "200" },
]);

globalThis.fetch = async () => Response.json({
  items: [], nextAfterSequence: "0", hasMore: true, highWatermark: "1",
});
await assert.rejects(listAllEvents("thr-stuck"), /游标没有前进/);
console.log("event pagination: 201 events, frozen highWatermark, dedupe and stalled cursor PASS");

/* ----------------------- MA-10：foldPlans 任务事件折叠 ----------------------- */

const ROOT = "run_root";
const taskEvent = (seq, type, payload, runId = ROOT, id = null) => ({
  sequence: String(seq),
  eventId: id ?? `te-${seq}-${Math.random().toString(36).slice(2, 8)}`,
  runId,
  type,
  payload,
  timestamp: "",
});
const taskRow = (state) => ({
  taskId: "A", taskVersion: 1, goal: "g", dependsOn: [], inputRefs: [],
  deliverable: null, acceptance: [], resourceScope: null, executionMode: "AUTO",
  required: true, state, attemptId: null, childRunId: null, reasonCode: null,
  evidenceValid: false, result: null,
});
let plans = [{
  planId: "plan_1", rootRunId: ROOT, revision: 1, status: "DRAFT", goal: null,
  createdAt: "", updatedAt: "", tasks: [taskRow("READY")],
}];

// 1. 跨根 runId 的任务事件必须被过滤（引用短路）
const beforeCross = plans;
plans = foldPlans(
  plans,
  taskEvent(300, "task.state.changed",
    { planId: "plan_1", taskId: "A", taskVersion: 1, from: "READY", to: "RUNNING", reasonCode: "X" },
    "run_other"),
  ROOT,
);
assert.equal(plans, beforeCross, "跨根 runId 事件不得折叠进当前任务表");

// 2. 派发 → 权威状态/attempt
plans = foldPlans(plans, taskEvent(301, "task.dispatched",
  { planId: "plan_1", taskId: "A", taskVersion: 1, attemptId: "att_1", state: "RUNNING" }), ROOT);
assert.equal(plans[0].tasks[0].state, "RUNNING");
assert.equal(plans[0].tasks[0].attemptId, "att_1");

// 3. CANCELLING（已请求取消）→ CANCELLED（已停止）迁移与原因码
plans = foldPlans(plans, taskEvent(302, "task.state.changed",
  { planId: "plan_1", taskId: "A", taskVersion: 1, attemptId: "att_1", from: "RUNNING",
    to: "CANCELLING", reasonCode: "CANCEL_REQUESTED" }), ROOT);
assert.equal(plans[0].tasks[0].state, "CANCELLING");
plans = foldPlans(plans, taskEvent(303, "task.state.changed",
  { planId: "plan_1", taskId: "A", taskVersion: 1, attemptId: "att_1", from: "CANCELLING",
    to: "CANCELLED", reasonCode: "ROOT_TERMINAL_CANCELLED" }), ROOT);
assert.equal(plans[0].tasks[0].state, "CANCELLED");
assert.equal(plans[0].tasks[0].reasonCode, "ROOT_TERMINAL_CANCELLED");

// 4. result.ready 只补结果证据，不改写权威任务态
plans = foldPlans(plans, taskEvent(304, "task.result.ready",
  { planId: "plan_1", taskId: "A", taskVersion: 1, attemptId: "att_1",
    childRunId: "sub_run_1", state: "CANCELLED", artifactRefs: ["art_1", "art_2"],
    verificationRef: null, inputDigest: "din", outputDigest: "dout" }), ROOT);
assert.equal(plans[0].tasks[0].state, "CANCELLED", "result.ready 不得改写权威状态");
assert.equal(plans[0].tasks[0].childRunId, "sub_run_1");
assert.deepEqual(plans[0].tasks[0].result.artifactRefs, ["art_1", "art_2"]);
assert.equal(plans[0].tasks[0].result.outputDigest, "dout");

// 5. taskVersion 不一致的旧版本事件必须忽略
const beforeVersion = plans;
plans = foldPlans(plans, taskEvent(305, "task.state.changed",
  { planId: "plan_1", taskId: "A", taskVersion: 9, from: "CANCELLED", to: "SUCCEEDED",
    reasonCode: "STALE" }), ROOT);
assert.equal(plans, beforeVersion, "旧 taskVersion 事件不得污染当前版本");

// 6. 混排补拉：模拟超页历史（含任务事件）经 App 同款 eventId 去重后顺序折叠，结果一致
const mixed = [
  taskEvent(1, "task.dispatched",
    { planId: "plan_2", taskId: "B", taskVersion: 1, attemptId: "att_b", state: "RUNNING" },
    ROOT, "dup-1"),
  taskEvent(1, "task.dispatched",
    { planId: "plan_2", taskId: "B", taskVersion: 1, attemptId: "att_b", state: "RUNNING" },
    ROOT, "dup-1"),
  taskEvent(2, "task.state.changed",
    { planId: "plan_2", taskId: "B", taskVersion: 1, attemptId: "att_b", from: "RUNNING",
      to: "SUCCEEDED", reasonCode: "VERIFICATION_PASSED" }),
];
let plans2 = [{
  planId: "plan_2", rootRunId: ROOT, revision: 1, status: "DRAFT", goal: null,
  createdAt: "", updatedAt: "", tasks: [taskRow("READY")].map((t) => ({ ...t, taskId: "B" })),
}];
const seen = new Set();
for (const e of mixed) {
  if (seen.has(e.eventId)) continue;
  seen.add(e.eventId);
  plans2 = foldPlans(plans2, e, ROOT);
}
assert.equal(plans2[0].tasks[0].state, "SUCCEEDED");
assert.equal(seen.size, 2, "重复 eventId 必须去重");
console.log("MA-10 foldPlans: cross-root filter, dispatch/cancel/result fold, version guard PASS");
