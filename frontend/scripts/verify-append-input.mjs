import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import ts from "typescript";

const source = await readFile(new URL("../src/api/client.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText;
const { appendTurnInput } = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));
const calls = [];
globalThis.fetch = async (url, init) => {
  calls.push({ url, method: init.method, body: JSON.parse(init.body) });
  return Response.json({ messageId: 'message', turnId: 'turn', status: 'QUEUED_FOR_MODEL' });
};
await appendTurnInput('turn/encoded', '  新约束：稳定功能不能退化\n');
assert.deepEqual(calls, [{ url: '/api/v1/turns/turn%2Fencoded/inputs', method: 'POST', body: { input: '  新约束：稳定功能不能退化\n' } }]);
globalThis.fetch = async () => Response.json({ code: 'INVALID_STATE', message: '轮次已终态，不能补充输入', retryable: false }, { status: 409 });
await assert.rejects(() => appendTurnInput('turn', '保留草稿'), e => e.code === 'INVALID_STATE');
console.log('C31 append API: exact text, encoded Turn, text-only body, terminal rejection PASS');
