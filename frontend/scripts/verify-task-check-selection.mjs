import assert from "node:assert/strict";
import { readFile, writeFile, mkdtemp, rm } from "node:fs/promises";
import ts from "typescript";
import { renderToStaticMarkup } from "react-dom/server";
import { createElement } from "react";

const directory = await mkdtemp(new URL("../.selection-test-", import.meta.url));
try {
  const compile = source => ts.transpileModule(source, { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX,
  } }).outputText;
  await writeFile(`${directory}/client.mjs`, compile(await readFile(new URL("../src/api/client.ts", import.meta.url), "utf8")));
  await writeFile(`${directory}/panel.mjs`, compile(await readFile(new URL("../src/TaskCheckPreviewPanel.tsx", import.meta.url), "utf8"))
    .replace('"./api/client"', '"./client.mjs"'));
  const { taskCheckSelectionRefs, getTaskCheckSelectionPreview, createTaskCheckSelectionLoader } = await import(`${directory}/client.mjs`);
  const { TaskCheckSelectionView, TaskCheckSelectionPanel } = await import(`${directory}/panel.mjs`);
  const task = { taskId: "A", taskVersion: 1, goal: "修复", acceptance: ["正确行为"], deliverable: "补丁",
    dependsOn: [], inputRefs: [], resourceScope: null, executionMode: "DELEGATED", required: true };
  const scope = { rootRunId: "run-a", planId: "plan-a", planRevision: 1, task: { ...task, state: "DRAFT" } };
  const h = "a".repeat(64);
  const check = id => ({ id, argv: ["python", "--<script>保持文本</script>"], timeoutSeconds: 60,
    expectTests: true, minPassed: 4, verifiers: [{ path: "check.py", sha256: h }] });
  const preview = { status: "PREVIEW_ONLY", projectId: "project-a", rootRunId: scope.rootRunId, turnId: "turn-a",
    planId: scope.planId, planRevision: 1, taskState: "DRAFT", task,
    requirements: [{ messageId: "msg-a", sequence: "9007199254740993", content: "  原文\n保留  " }],
    taskDefinitionDigest: h, requirementsDigest: h, registryDigest: h, imageDigest: h, executionPolicyDigest: h,
    availableChecks: [check("alpha"), check("zeta"), ...Array.from({ length: 7 }, (_, i) => check(`check_${i}`))], previewDigest: h };
  const response = refs => ({ status: "SELECTED_PREVIEW_ONLY", taskPreview: structuredClone(preview),
    checkRefs: [...refs].sort(), selectedChecks: [...refs].sort().map(id => structuredClone(preview.availableChecks.find(c => c.id === id))),
    checksDigest: "b".repeat(64), selectionDigest: "c".repeat(64) });
  const refs = ["zeta", "alpha"];
  assert.deepEqual(taskCheckSelectionRefs(preview, refs), ["alpha", "zeta"]); assert.deepEqual(refs, ["zeta", "alpha"]);
  for (const invalid of [[], ["unknown"], ["alpha", "alpha"], [" alpha"], ["alpha,zeta"], ["../check.py"], ["echo passed"], preview.availableChecks.map(c => c.id)])
    assert.throws(() => taskCheckSelectionRefs(preview, invalid));
  assert.equal(taskCheckSelectionRefs(preview, preview.availableChecks.slice(0, 8).map(c => c.id)).length, 8);
  let calls = [];
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return Response.json(response(["alpha", "zeta"])); };
  await getTaskCheckSelectionPreview("run/a", "plan b", "A?x", h, ["alpha", "zeta"]);
  assert.equal(calls[0].url, "/api/v1/runs/run%2Fa/plans/plan%20b/tasks/A%3Fx/acceptance-selection-preview?checkRef=alpha&checkRef=zeta");
  assert.equal(calls[0].options.method ?? "GET", "GET"); assert.equal(calls[0].options.body, undefined);
  assert.equal(calls[0].options.headers["If-Match"], `"task-check-preview-v1-${h}"`);
  assert.ok(!Object.hasOwn(calls[0].options.headers, "Idempotency-Key"));
  let states = []; let loader = createTaskCheckSelectionLoader(scope, preview, s => states.push(s));
  await loader.load(refs); assert.equal(states.at(-1).selection.status, "SELECTED_PREVIEW_ONLY");
  for (const mutate of [s => s.status = "CONFIRMED", s => s.taskPreview.rootRunId = "other", s => s.taskPreview.planRevision++,
    s => s.taskPreview.task.goal = "同版本新目标", s => s.taskPreview.requirements[0].content = "替换原文",
    s => s.taskPreview.registryDigest = "d".repeat(64), s => s.taskPreview.previewDigest = "d".repeat(64),
    s => s.taskPreview.availableChecks.pop(), s => s.checkRefs = ["alpha"], s => s.checkRefs.reverse(),
    s => s.selectedChecks[0].argv.reverse(), s => s.selectedChecks[0].minPassed = 1,
    s => s.selectedChecks[0].verifiers[0].sha256 = "e".repeat(64), s => s.checksDigest = "",
    s => s.selectionDigest = "fake", s => delete s.taskPreview.task, s => delete s.selectedChecks]) {
    const damaged = response(refs); mutate(damaged); globalThis.fetch = async () => Response.json(damaged);
    await loader.load(refs); assert.equal(states.at(-1).selection, null); assert.ok(states.at(-1).error);
  }
  const reordered = response(refs); reordered.taskPreview = Object.fromEntries(Object.entries(reordered.taskPreview).reverse());
  globalThis.fetch = async () => Response.json(reordered); await loader.load(refs); assert.ok(states.at(-1).selection);
  // Options can be changed while a request is in flight; no old selection can reappear.
  const pending = []; globalThis.fetch = () => new Promise((resolve, reject) => pending.push({ resolve, reject }));
  states = []; loader = createTaskCheckSelectionLoader(scope, preview, s => states.push(s));
  const old = loader.load(["alpha"]), latest = loader.load(["zeta"]);
  pending[1].resolve(Response.json(response(["zeta"]))); await latest; const afterLatest = states.length;
  pending[0].resolve(Response.json(response(["alpha"]))); await old; assert.equal(states.length, afterLatest);
  const changing = ["alpha"]; const frozen = loader.load(changing); changing[0] = "zeta";
  pending[2].resolve(Response.json(response(["alpha"]))); await frozen; assert.deepEqual(states.at(-1).selection.checkRefs, ["alpha"]);
  const cancelled = loader.load(["alpha"]); loader.cancel(); const afterCancel = states.length;
  assert.deepEqual(states.at(-1), { loading: false, selection: null, error: null });
  pending[3].resolve(Response.json(response(["alpha"]))); await cancelled; assert.equal(states.length, afterCancel);
  const invalidated = loader.load(["alpha"]); await loader.load([]); const afterInvalid = states.length;
  pending[4].reject(new Error("旧请求错误")); await invalidated; assert.equal(states.length, afterInvalid); assert.ok(states.at(-1).error);
  const disposed = loader.load(["alpha"]); loader.dispose(); const afterDispose = states.length;
  pending[5].resolve(Response.json(response(["alpha"]))); await disposed; assert.equal(states.length, afterDispose);
  await loader.load(["alpha"]); assert.equal(pending.length, 6);
  globalThis.fetch = async () => Response.json({ code: "VERSION_CONFLICT", message: "请重新读取需求" }, { status: 412 });
  states = []; loader = createTaskCheckSelectionLoader(scope, preview, s => states.push(s)); await loader.load(["alpha"]);
  assert.equal(states.at(-1).error, "请重新读取需求"); assert.equal(states.at(-1).selection, null);
  const otherScope = { ...scope, rootRunId: "other" }; globalThis.fetch = async () => Response.json(response(["alpha"]));
  states = []; loader = createTaskCheckSelectionLoader(otherScope, preview, s => states.push(s)); await loader.load(["alpha"]); assert.ok(states.at(-1).error);
  const selectedHtml = renderToStaticMarkup(createElement(TaskCheckSelectionView, { selection: response(refs) }));
  assert.ok(selectedHtml.includes("尚未确认或登记")); assert.ok(selectedHtml.includes("alpha")); assert.ok(selectedHtml.includes("zeta"));
  assert.ok(selectedHtml.includes("&lt;script&gt;保持文本&lt;/script&gt;")); assert.ok(!selectedHtml.includes("<script>")); assert.ok(!selectedHtml.includes("<button"));
  const initialHtml = renderToStaticMarkup(createElement(TaskCheckSelectionPanel, { scope, preview }));
  assert.equal((initialHtml.match(/type="checkbox"/g) || []).length, 9); assert.ok(!initialHtml.includes("checked="));
  assert.ok(initialHtml.includes("已选择 0 项")); assert.ok(initialHtml.includes("disabled="));
  console.log("PASS: actual selection GET/If-Match, exact full preview and selected definitions, 1..8 refs, canonical order, newest/cancel/invalid/disposed fences, caller mutation, 412, real React SSR zero initial choices; no browser click proof");
} finally { await rm(directory, { recursive: true, force: true }); }
