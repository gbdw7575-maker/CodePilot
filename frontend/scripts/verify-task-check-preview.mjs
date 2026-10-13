import assert from "node:assert/strict";
import { readFile, writeFile, mkdtemp, rm } from "node:fs/promises";
import ts from "typescript";
import { renderToStaticMarkup } from "react-dom/server";
import { createElement } from "react";

// Compile the actual client and view in this container. The temporary directory stays
// under this frontend so Node can resolve its installed React dependencies.
const directory = await mkdtemp(new URL("../.preview-test-", import.meta.url));
try {
  const compile = source => ts.transpileModule(source, { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX,
  } }).outputText;
  const clientPath = `${directory}/client.mjs`;
  await writeFile(clientPath, compile(await readFile(new URL("../src/api/client.ts", import.meta.url), "utf8")));
  const panelPath = `${directory}/panel.mjs`;
  await writeFile(panelPath, compile(await readFile(new URL("../src/TaskCheckPreviewPanel.tsx", import.meta.url), "utf8"))
    .replace('"./api/client"', '"./client.mjs"'));
  const { getTaskCheckPreview, taskCheckPreviewScopeKey, createTaskCheckPreviewLoader } = await import(clientPath);
  const { TaskCheckPreviewView } = await import(panelPath);
  const definition = { taskId: "A", taskVersion: 1, goal: "修复 <script>文本</script>",
    acceptance: ["保持原文", "通过测试"], deliverable: "补丁", dependsOn: ["B"],
    inputRefs: [{ taskId: "B", output: "结果" }], resourceScope: { read: ["src/**"], write: ["src/a.ts"] },
    executionMode: "DELEGATED", required: true };
  const scope = { rootRunId: "run-a", planId: "plan-a", planRevision: 1, task: { ...definition, state: "DRAFT" } };
  const preview = { status: "PREVIEW_ONLY", projectId: "project-a", rootRunId: scope.rootRunId,
    turnId: "turn-a", planId: scope.planId, planRevision: 1, taskState: "DRAFT", task: definition,
    requirements: [{ messageId: "msg-a", sequence: "9007199254740993", content: "  原始需求\n<script>保留文本</script>  " }],
    availableChecks: [{ id: "trusted-test", argv: ["python", "check.py", "--strict"], timeoutSeconds: 60,
      expectTests: true, minPassed: 2, verifiers: [{ path: "check.py", sha256: "a".repeat(64) }] }] };
  let calls = [];
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return Response.json(preview); };
  await getTaskCheckPreview("run/a", "plan b", "A?x");
  assert.equal(calls[0].url, "/api/v1/runs/run%2Fa/plans/plan%20b/tasks/A%3Fx/acceptance-preview");
  assert.equal(calls[0].options.method ?? "GET", "GET");
  assert.equal(calls[0].options.body, undefined);
  assert.ok(!JSON.stringify(calls[0].options.headers).includes("Idempotency-Key"));
  let states = [];
  let loader = createTaskCheckPreviewLoader(scope, state => states.push(state));
  await loader.load();
  assert.deepEqual(states[0], { loading: true, preview: null, error: null });
  assert.equal(states.at(-1).preview.requirements[0].sequence, "9007199254740993");
  for (const mutate of [p => p.status = "CONFIRMED", p => p.rootRunId = "run-b", p => p.planId = "plan-b",
    p => p.planRevision++, p => p.taskState = "READY", p => p.task.goal = "同版本新目标",
    p => p.task.acceptance.reverse(), p => p.task.resourceScope.write.push("other"), p => delete p.task.goal,
    p => delete p.task, p => p.task.required = false, p => p.task.dependsOn = [],
    p => p.task.inputRefs[0].output = "变更引用", p => p.task.executionMode = "INLINE" ]) {
    const damaged = structuredClone(preview); mutate(damaged);
    globalThis.fetch = async () => Response.json(damaged);
    await loader.load();
    assert.equal(states.at(-1).preview, null); assert.ok(states.at(-1).error);
  }
  // The latest request wins; older success and older failure cannot resurrect content.
  const pending = [];
  globalThis.fetch = () => new Promise((resolve, reject) => pending.push({ resolve, reject }));
  states = []; loader = createTaskCheckPreviewLoader(scope, state => states.push(state));
  const old = loader.load(), latest = loader.load();
  pending[1].resolve(Response.json(preview)); await latest;
  const afterLatest = states.length;
  pending[0].reject(new Error("旧请求错误")); await old; assert.equal(states.length, afterLatest);
  const disposed = loader.load(); loader.dispose(); const count = states.length;
  pending[2].resolve(Response.json(preview)); await disposed; assert.equal(states.length, count);
  await loader.load(); assert.equal(pending.length, 3);
  globalThis.fetch = async () => Response.json({ code: "SERVICE_UNAVAILABLE", message: "目录未启用" }, { status: 503 });
  states = []; loader = createTaskCheckPreviewLoader(scope, state => states.push(state)); await loader.load();
  assert.equal(states.at(-1).error, "目录未启用"); assert.equal(states.at(-1).preview, null);
  const key = taskCheckPreviewScopeKey(scope);
  for (const mutate of [s => s.rootRunId += "x", s => s.planId += "x", s => s.planRevision++,
    s => s.task.state = "READY", s => s.task.goal += "x", s => s.task.acceptance.reverse(),
    s => s.task.taskVersion++, s => s.task.deliverable += "x", s => s.task.required = false,
    s => s.task.resourceScope.read.push("other"), s => s.task.inputRefs[0].output += "x" ]) {
    const changed = structuredClone(scope); mutate(changed); assert.notEqual(taskCheckPreviewScopeKey(changed), key);
  }
  assert.equal(taskCheckPreviewScopeKey({ ...scope, task: { ...scope.task, result: { durationMs: 20 } } }), key);
  const html = renderToStaticMarkup(createElement(TaskCheckPreviewView, { preview }));
  for (const value of ["仅预览，尚未确认或登记", "用户原始需求与补充", "原始需求\n&lt;script&gt;保留文本&lt;/script&gt;",
    "正确", "可信目录可选检查（尚未选择）", "trusted-test", "--strict", "check.py", "a".repeat(64), "src/a.ts"]) {
    if (value !== "正确") assert.ok(html.includes(value), value);
  }
  assert.ok(!html.includes("<script>")); assert.ok(!html.includes("<button"));
  console.log("PASS: actual preview client GET, complete definition/scope checks, missing fields, newest/disposed fences, errors, scope key and real React SSR (no browser click proof)");
} finally {
  // mkdtemp produced this one directory; no project files are removed.
  await rm(directory, { recursive: true, force: true });
}
