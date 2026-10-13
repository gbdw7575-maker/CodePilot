import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import ts from "typescript";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
const compile = async (file) => ts.transpileModule(await readFile(new URL(file, import.meta.url), "utf8"), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX }
}).outputText;
const data = source => 'data:text/javascript;base64,' + Buffer.from(source).toString('base64');
const clientUrl = data(await compile("../src/api/client.ts"));
const helperUrl = data(await compile("../src/verificationRequirement.ts"));
const { prepareVerificationOptions } = await import(helperUrl);
const { submitTurn, previewVerificationPlan, getRunVerificationPlan } = await import(clientUrl);
const plan = { command: "python -m pytest -q", expectTests: true, timeoutSeconds: 30, planVersion: "v1",
  purpose: "GENERAL", acceptanceDescription: null, defectReproduction: null, digest: 'a'.repeat(64), source: "PROJECT_CONFIGURATION" };
const draft = { acceptanceDescription: "重复 location 必须合并", assertionId: "sample", fileName: "check.sh", content: "echo assertion\n", command: 'sh "$DSH_PROBE_FILE"' };
assert.deepEqual(prepareVerificationOptions(plan, "INHERIT", draft, false), { projectPlanDigest: plan.digest });
assert.deepEqual(prepareVerificationOptions(plan, "GENERAL", draft, false).verification, { purpose: "GENERAL" });
assert.equal(prepareVerificationOptions(plan, "DEFECT_FIX", draft, false), null);
for (const bad of [{ content: "中".repeat(5462) }, { content: "" }, { fileName: "../check.sh" },
  { assertionId: "bad id" }, { command: "x".repeat(4097) }, { acceptanceDescription: " " }, { content: "a\0b" }])
  assert.equal(prepareVerificationOptions(plan, "DEFECT_FIX", { ...draft, ...bad }, true), null);
const options = prepareVerificationOptions(plan, "DEFECT_FIX", draft, true);
assert.equal(options.verification.defectReproduction.content, draft.content);
const requests = [];
globalThis.fetch = async (url, init) => { requests.push({ url, body: init?.body && JSON.parse(init.body) }); return Response.json(plan); };
await previewVerificationPlan("proj_demo"); await getRunVerificationPlan("run_demo");
await submitTurn("thread_demo", "原样输入", options);
await submitTurn("thread_demo", "旧调用");
assert.equal(requests[0].url, "/api/v1/projects/proj_demo/verification-plan");
assert.equal(requests[1].url, "/api/v1/runs/run_demo/verification-plan");
assert.deepEqual(requests[2].body, { input: "原样输入", ...options });
assert.deepEqual(requests[3].body, { input: "旧调用" });
const editorSource = await readFile(new URL("../src/VerificationRequirementEditor.tsx", import.meta.url), "utf8");
assert.ok(editorSource.includes("重新读取项目验收配置"));
assert.ok(editorSource.includes("setPlan(null); setError(\"\"); setConfirmed(false)"));
let editor = await compile("../src/VerificationRequirementEditor.tsx");
editor = editor.replaceAll('"./api/client"', JSON.stringify(clientUrl))
  .replaceAll('"./verificationRequirement"', JSON.stringify(helperUrl))
  .replaceAll('"react/jsx-runtime"', JSON.stringify(new URL("../node_modules/react/jsx-runtime.js", import.meta.url).href))
  .replaceAll('"react"', JSON.stringify(new URL("../node_modules/react/index.js", import.meta.url).href));
const { VerificationPlanSummary } = await import(data(editor));
const effective = { ...plan, purpose: "DEFECT_FIX", source: "RUN_REGISTRATION", acceptanceDescription: draft.acceptanceDescription,
  defectReproduction: options.verification.defectReproduction };
const html = renderToStaticMarkup(React.createElement(VerificationPlanSummary, { plan: effective }));
for (const text of ["本次运行验收要求", plan.command, draft.acceptanceDescription, "基线失败", "候选通过", draft.content.trim()]) assert.ok(html.includes(text), text);
const reportHtml = renderToStaticMarkup(React.createElement(VerificationPlanSummary, { plan: {
  ...plan, testReports: { format: "MAVEN_SUREFIRE", directories: ["a/target/surefire-reports", "b/target/surefire-reports"] }
} }));
for (const text of ["MAVEN_SUREFIRE", "a/target/surefire-reports", "b/target/surefire-reports", "必须是本次执行的新报告"])
  assert.ok(reportHtml.includes(text), text);
console.log("C32A frontend: declared report format/directories/freshness are displayed PASS");
const gradleHtml = renderToStaticMarkup(React.createElement(VerificationPlanSummary, { plan: {
  ...plan, testReports: { format: "GRADLE", directories: ["a/build/test-results/test", "b/custom/xml"] }
} }));
for (const text of ["GRADLE", "a/build/test-results/test", "b/custom/xml", "必须是本次执行的新报告"])
  assert.ok(gradleHtml.includes(text), text);
console.log("C32B frontend: Gradle explicit task directories displayed PASS");
// Verify first-send queue and existing conversation propagate the same prepared options.
const app = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");
for (const token of ["setQueuedPrompt({ text, options })", "doSubmit(queued.text, queued.options)", "submitTurn(thread.id, text, options)",
  "await props.onSend(text, options)", "setEditorRevision(n => n + 1)", "prepared && prepared.projectId === props.project?.id"]) assert.ok(app.includes(token), token);
console.log("C29 frontend: validation, preview/read routes, exact submission/legacy request, SSR acceptance display and queue propagation PASS");
