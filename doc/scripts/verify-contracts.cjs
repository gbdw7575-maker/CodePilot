// Local documentation checks, not a full OpenAPI / JSON Schema validator.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..', '项目文档');
const read = name => JSON.parse(fs.readFileSync(path.join(root, name), 'utf8'));
const api = read('openapi.json');
const events = read('events.schema.json');
const tools = read('tools.schema.json');
const fixtures = read('examples.json');
let refs = 0;
function resolve(doc, ref) {
  assert.ok(ref.startsWith('#/'), `Only local refs supported: ${ref}`);
  const found = ref.slice(2).split('/').reduce((v,k) => v?.[k.replaceAll('~1','/').replaceAll('~0','~')], doc);
  assert.notEqual(found, undefined, `Dangling ref: ${ref}`);
  return found;
}
function walk(value, visitor) {
  if (!value || typeof value !== 'object') return;
  visitor(value);
  Object.values(value).forEach(v => walk(v, visitor));
}
for (const doc of [api, events, tools]) walk(doc, value => {
  if (value.$ref) { resolve(doc, value.$ref); refs++; }
});

// Implements only keywords currently emitted by build-contracts.cjs.
const supported = new Set(['$ref','$schema','title','description','type','const','enum','anyOf','oneOf',
  'properties','required','additionalProperties','minProperties','items','minItems','maxItems','uniqueItems',
  'minimum','maximum','minLength','maxLength','pattern','format','default','examples','$defs']);
function checkKeywords(s) {
  if (typeof s === 'boolean') return;
  for (const k of Object.keys(s)) assert.ok(supported.has(k) || k.startsWith('x-'), `Unsupported Schema keyword: ${k}`);
  for (const v of Object.values(s.properties || {})) checkKeywords(v);
  for (const v of Object.values(s.$defs || {})) checkKeywords(v);
  for (const v of [...(s.anyOf || []), ...(s.oneOf || [])]) checkKeywords(v);
  if (s.items) checkKeywords(s.items);
  if (s.additionalProperties && typeof s.additionalProperties === 'object') checkKeywords(s.additionalProperties);
}
Object.values(api.components.schemas).forEach(checkKeywords);
checkKeywords(events); checkKeywords(tools);
// JSON equality ignores object member order and treats -0 as 0. Arrays stay ordered.
function jsonEqual(a, b) {
  if (a === b) return true;
  if (!a || !b || typeof a !== 'object' || typeof b !== 'object' || Array.isArray(a) !== Array.isArray(b)) return false;
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length && keys.every(k => Object.hasOwn(b, k) && jsonEqual(a[k], b[k]));
}
function valid(s, v, doc) {
  if (s === true) return true;
  if (s === false) return false;
  if (s.$ref && !valid(resolve(doc,s.$ref),v,doc)) return false;
  if ('const' in s && JSON.stringify(v) !== JSON.stringify(s.const)) return false;
  if (s.enum && !s.enum.includes(v)) return false;
  if (s.anyOf && !s.anyOf.some(x=>valid(x,v,doc))) return false;
  if (s.oneOf && s.oneOf.filter(x=>valid(x,v,doc)).length !== 1) return false;
  if (s.type) {
    const match = {null:v===null,object:v!==null&&typeof v==='object'&&!Array.isArray(v),array:Array.isArray(v),
      integer:Number.isSafeInteger(v),number:typeof v==='number'&&Number.isFinite(v),string:typeof v==='string',boolean:typeof v==='boolean'};
    if (!match[s.type]) return false;
  }
  if (typeof v === 'string') {
    const length = [...v].length;
    if (s.minLength !== undefined && length < s.minLength) return false;
    if (s.maxLength !== undefined && length > s.maxLength) return false;
    if (s.pattern && !new RegExp(s.pattern,'u').test(v)) return false;
    if (s.format === 'date-time' && (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?(?:Z|[+-]\d\d:\d\d)$/.test(v)||!Number.isFinite(Date.parse(v)))) return false;
    if (s.format === 'uri') { try { new URL(v); } catch { return false; } }
  }
  if (typeof v === 'number') {
    if (s.minimum !== undefined && v < s.minimum) return false;
    if (s.maximum !== undefined && v > s.maximum) return false;
  }
  if (Array.isArray(v)) {
    if (s.minItems !== undefined && v.length < s.minItems) return false;
    if (s.maxItems !== undefined && v.length > s.maxItems) return false;
    if (s.uniqueItems && v.some((x, i) => v.slice(0, i).some(y => jsonEqual(x, y)))) return false;
    if (s.items && !v.every(x=>valid(s.items,x,doc))) return false;
  } else if (v !== null && typeof v === 'object') {
    if (s.minProperties !== undefined && Object.keys(v).length < s.minProperties) return false;
    if ((s.required||[]).some(k=>!Object.hasOwn(v,k))) return false;
    for (const [k,x] of Object.entries(v)) {
      if (s.properties && Object.hasOwn(s.properties,k)) { if (!valid(s.properties[k],x,doc)) return false; }
      else if (s.additionalProperties === false) return false;
      else if (s.additionalProperties && typeof s.additionalProperties==='object' && !valid(s.additionalProperties,x,doc)) return false;
    }
  }
  return true;
}
assert.equal(valid({type:'array',uniqueItems:true}, ['A','B'], api), true);
assert.equal(valid({type:'array',uniqueItems:true}, ['A','A'], api), false);
assert.equal(valid({type:'array',uniqueItems:true}, [{a:1,b:2},{b:2,a:1}], api), false);
assert.equal(valid({type:'array',uniqueItems:true}, [0,-0], api), false);
assert.equal(valid({type:'array',uniqueItems:true}, [[1,2],[2,1]], api), true);
assert.equal(api.openapi,'3.1.0');
const operationIds = new Set();
let operations = 0;
for (const [p, methods] of Object.entries(api.paths)) for (const [method, op] of Object.entries(methods)) {
  operations++;
  assert.ok(!operationIds.has(op.operationId), `Duplicate operationId: ${op.operationId}`);
  operationIds.add(op.operationId);
  assert.ok(['MVP','EXTENSION'].includes(op['x-stage']));
  const params = (op.parameters||[]).map(x=>x.$ref?resolve(api,x.$ref):x);
  for (const [,name] of p.matchAll(/\{([^}]+)\}/g)) assert.ok(params.some(x=>x.name===name&&x.in==='path'&&x.required),`${p} path parameter ${name}`);
  assert.equal(new Set(params.map(x=>x.in+':'+x.name)).size, params.length);
  if (method !== 'get') assert.ok(params.some(x=>x.name==='X-DSH-Client'&&x.required),op.operationId);
  if (method === 'post') assert.ok(params.some(x=>x.name==='Idempotency-Key'&&x.required),op.operationId);
  for (const response of Object.values(op.responses)) assert.ok(response.headers?.['X-Request-Id'],`Missing response request id: ${op.operationId}`);
  assert.ok(op.responses.default?.content?.['application/json'],op.operationId);
}
let examples = 0;
for (const fixture of fixtures.cases) {
  const s = api.components.schemas[fixture.schema];
  assert.ok(s,`Missing fixture schema ${fixture.schema}`);
  assert.equal(valid(s,fixture.value,api),fixture.valid,`Fixture ${examples}: ${fixture.schema}`);
  if (['WsClientFrame','WsServerFrame','EventEnvelope'].includes(fixture.schema))
    assert.equal(valid(events.$defs[fixture.schema],fixture.value,events),fixture.valid,'Standalone event schema mismatch');
  examples++;
}
for (const s of Object.values(api.components.schemas)) for (const example of s.examples||[]) assert.ok(valid(s,example,api),'Embedded schema example');
const h='a'.repeat(64);
const toolInputs={read_artifact:{artifactId:'art_example',byteOffset:0,maxBytes:4096},list_files:{path:'.'},read_file:{path:'README.md',startLine:1,endLine:20},
  search_code:{query:'register'},apply_patch:{patch:'--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new\n',expectedFileHashes:{'a.txt':h}},
  run_command:{command:'./mvnw test'},run_probe:{fileName:'probe.sh',content:'echo diagnostic',command:'sh "$DSH_PROBE_FILE"'},git_status:{},git_diff:{},update_plan:{steps:[{text:'定位错误',status:'IN_PROGRESS'}]},
  tool_search:{query:'检查数据库结构'},run_subagent:{role:'REVIEWER',task:'检查补丁是否影响已有行为',artifactRefs:[]},
  start_orchestration:{goal:'完成编码并验证',template:'coding-review',artifactRefs:[]},
  dispatch_tasks:{planId:'plan_demo',expectedRevision:2,taskIds:['A'],requestKey:'rk-dispatch-ma05'},
  await_tasks:{taskIds:['A','B'],waitMode:'ALL',afterCursor:'42',timeoutMs:120000},
  read_task_result:{taskId:'A',attemptId:'attempt_1'},
  cancel_tasks:{taskIds:['B'],reason:'上游契约变更'}};
let toolCases=0;
for (const tool of tools['x-tools']) {
  assert.equal(tool.inputSchema.type, 'object', `Provider parameters must declare object: ${tool.name}`);
  assert.ok(toolInputs[tool.name],`Missing tool fixture: ${tool.name}`);
  assert.ok(valid(tools,{callId:'call_example',name:tool.name,arguments:toolInputs[tool.name]},tools),tool.name);
  assert.ok(!valid(tools,{callId:'call_example',name:tool.name,arguments:{...toolInputs[tool.name],injectedPermission:'ALLOW'}},tools),`Extra tool arg ${tool.name}`);
  toolCases+=2;
}
assert.equal(Object.keys(toolInputs).length,tools['x-tools'].length);
const planSchema = tools['x-tools'].find(t => t.name === 'update_plan').inputSchema;
assert.ok(!valid(planSchema, {}, tools), 'update_plan requires steps or tasks');
assert.ok(!valid(planSchema, {steps:[{text:'a',status:'PENDING'}],tasks:[]}, tools), 'update_plan forms stay exclusive');
toolCases += 2;

const artifactSchema=tools['x-tools'].find(t=>t.name==='read_artifact').inputSchema;
for(const args of [{},{artifactId:'../secret'},{artifactId:'art_example',byteOffset:-1},{artifactId:'art_example',maxBytes:16385},{artifactId:'art_example',path:'/tmp/secret'}]) assert.ok(!valid(artifactSchema,args,tools),'read_artifact invalid fixture');
toolCases += 5;
const probeSchema=tools['x-tools'].find(t=>t.name==='run_probe').inputSchema;
for(const args of [{},{fileName:'../test.py',content:'x',command:'true'},{fileName:'probe.py',content:'',command:'true'},{fileName:'probe.py',content:'x',command:'x'.repeat(4097)}]) assert.ok(!valid(probeSchema,args,tools),'run_probe invalid fixture');
toolCases += 4;
let links=0, markdownFiles=0, jsonBlocks=0;
// 显式局部验收入口；默认仍检查全部文档链接，局部通过不代表 G-DOC 通过。
const schemaOnly = process.argv.includes('--schema-only');
for (const name of schemaOnly ? [] : fs.readdirSync(root).filter(n=>n.endsWith('.md'))) {
  markdownFiles++;
  const buffer=fs.readFileSync(path.join(root,name));
  const content=new TextDecoder('utf-8',{fatal:true}).decode(buffer);
  assert.equal((content.match(/^```/gm)||[]).length%2,0,`Unpaired code fences: ${name}`);
  for (const [,json] of content.matchAll(/```json\s*\r?\n([\s\S]*?)\r?\n```/g)) { JSON.parse(json); jsonBlocks++; }
  for (const [,target] of content.matchAll(/\]\(([^)]+)\)/g)) {
    if (/^(?:https?:|#|mailto:)/.test(target)) continue;
    const [relative,anchor]=target.split('#');
    const file=path.resolve(root,decodeURIComponent(relative));
    assert.ok(fs.existsSync(file),`Broken local link: ${name} -> ${target}`);
    if (anchor && file.endsWith('.md')) {
      const headings=fs.readFileSync(file,'utf8').split(/\r?\n/).filter(x=>/^#{1,6} /.test(x))
        .map(x=>x.replace(/^#+ /,'').toLowerCase().replace(/[^\p{L}\p{N}_\-\s]/gu,'').replace(/\s/g,'-'));
      assert.ok(headings.includes(decodeURIComponent(anchor)),`Broken anchor: ${name} -> ${target}`);
    }
    links++;
  }
}
process.stdout.write(JSON.stringify({result:'PASS',scope:schemaOnly ? 'schemas-only; markdown-not-checked' : 'documentation-only; limited-schema-subset',
  operations,schemas:Object.keys(api.components.schemas).length,eventTypes:api.components.schemas.EventEnvelope.oneOf.length,
  tools:tools['x-tools'].length,fixtures:examples,toolCases,localRefs:refs,markdownFiles,localLinks:links,jsonBlocks},null,2)+'\n');
