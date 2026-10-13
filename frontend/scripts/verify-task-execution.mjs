import assert from 'node:assert/strict';
import {readFile,writeFile,mkdtemp,rm} from 'node:fs/promises';
import ts from 'typescript';
import {renderToStaticMarkup} from 'react-dom/server';
import {createElement} from 'react';

// Feed actual server HTTP fixture outputs to the actual client. Not browser click evidence.
const [previewFile,registrationFile]=process.argv.slice(2);
assert.ok(previewFile&&registrationFile,'Pass the actual backend HTTP fixture output paths');
const selected=JSON.parse(await readFile(previewFile,'utf8')),registration=JSON.parse(await readFile(registrationFile,'utf8'));
const directory=await mkdtemp(new URL('../.execution-test-',import.meta.url));let controls=0;
try {
  const compile=source=>ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext,jsx:ts.JsxEmit.ReactJSX}}).outputText;
  await writeFile(directory+'/client.mjs',compile(await readFile(new URL('../src/api/client.ts',import.meta.url),'utf8')));
  await writeFile(directory+'/panel.mjs',compile(await readFile(new URL('../src/TaskCheckPreviewPanel.tsx',import.meta.url),'utf8')).replace('"./api/client"','"./client.mjs"'));
  const {createTaskCheckSelectionLoader,createTaskCheckConfirmationLoader,validateTaskExecution}=await import(directory+'/client.mjs');
  const {TaskCheckSelectionView,TaskCheckSelectionPanel,TaskCheckConfirmationPanel}=await import(directory+'/panel.mjs');
  const p=selected.taskPreview,refs=selected.checkRefs,scope={rootRunId:p.rootRunId,planId:p.planId,planRevision:p.planRevision,task:{...p.task,state:p.taskState}};
  await validateTaskExecution(selected);controls++;
  let requests=[],states=[];
  globalThis.fetch=async(url,options)=>{requests.push({url,options});return Response.json(selected);};
  let loader=createTaskCheckSelectionLoader(scope,p,s=>states.push(s));await loader.load(refs,true);
  assert.deepEqual(states.at(-1).selection,selected);assert.ok(requests[0].url.includes('/acceptance-execution-preview?'));
  assert.equal(requests[0].options.headers['If-Match'],'"task-check-preview-v1-'+p.previewDigest+'"');controls++;
  const mutations=[s=>delete s.execution,s=>s.execution=null,s=>s.execution.sourceDigest='b'.repeat(64),
    s=>s.execution.executionSpecDigest='c'.repeat(64),s=>s.execution.checks[0].timeoutSeconds++,
    s=>s.execution.checks[0].argv=['echo','passed'],s=>s.execution.checks.pop(),s=>s.selectionDigest='d'.repeat(64),
    s=>{const d=JSON.parse(s.execution.canonicalDefinition);d.limits.cpuMillis++;s.execution.canonicalDefinition=JSON.stringify(d);},
    s=>s.execution.canonicalDefinition+='\n',s=>s.execution.canonicalDefinition='{"duplicate":1,"duplicate":2}',
    s=>s.taskPreview.projectId='other',s=>s.checkRefs=['other'],
    s=>{const d=JSON.parse(s.execution.canonicalDefinition);d.limits=null;s.execution.canonicalDefinition=JSON.stringify(d);},
    s=>{const d=JSON.parse(s.execution.canonicalDefinition);d.report={};s.execution.canonicalDefinition=JSON.stringify(d);},
    s=>{const d=JSON.parse(s.execution.canonicalDefinition);d.cacheManifest=[{path:'../escape',bytes:0,sha256:'a'.repeat(64)}];s.execution.canonicalDefinition=JSON.stringify(d);}];
  for(const change of mutations){const damaged=structuredClone(selected);change(damaged);globalThis.fetch=async()=>Response.json(damaged);await loader.load(refs,true);assert.equal(states.at(-1).selection,null);assert.ok(states.at(-1).error);controls++;}
  let queued=[];globalThis.fetch=()=>new Promise(resolve=>queued.push(resolve));states=[];
  loader=createTaskCheckSelectionLoader(scope,p,s=>states.push(s));let pending=loader.load(refs,true);loader.cancel();const cancelled=states.length;
  queued.shift()(Response.json(selected));await pending;assert.equal(states.length,cancelled);controls++;
  pending=loader.load(refs,true);loader.dispose();const disposed=states.length;queued.shift()(Response.json(selected));await pending;assert.equal(states.length,disposed);controls++;
  requests=[];states=[];globalThis.fetch=async(url,options)=>{requests.push({url,options});return Response.json(registration);};
  let confirm=createTaskCheckConfirmationLoader(selected,s=>states.push(s));await confirm.confirm(false);assert.equal(requests.length,0);
  await confirm.confirm(true);assert.deepEqual(states.at(-1).registration,registration);assert.equal(requests[0].options.headers['If-Match'],'"task-check-selection-v2-'+selected.selectionDigest+'"');
  assert.deepEqual(JSON.parse(requests[0].options.body),{taskVersion:p.task.taskVersion,checkRefs:refs,confirmed:true});controls++;
  for(const value of [undefined,'e'.repeat(64)]){globalThis.fetch=async()=>Response.json({...registration,executionSpecDigest:value});states=[];confirm=createTaskCheckConfirmationLoader(selected,s=>states.push(s));await confirm.confirm(true);assert.equal(states.at(-1).registration,null);assert.ok(states.at(-1).error);controls++;}
  let failed=true;requests=[];states=[];const mutable=structuredClone(selected);
  globalThis.fetch=async(url,options)=>{requests.push({url,options});if(failed){failed=false;throw new Error('response unknown');}return Response.json(registration);};
  confirm=createTaskCheckConfirmationLoader(mutable,s=>states.push(s));await confirm.confirm(true);mutable.execution.executionSpecDigest='f'.repeat(64);await confirm.confirm(true);
  assert.deepEqual(states.at(-1).registration,registration);assert.equal(requests[0].options.headers['Idempotency-Key'],requests[1].options.headers['Idempotency-Key']);assert.equal(requests[0].options.headers['If-Match'],requests[1].options.headers['If-Match']);controls++;
  const html=renderToStaticMarkup(createElement(TaskCheckSelectionView,{selection:selected}));assert.ok(html.includes('完整执行策略'));assert.ok(html.includes('CPU上限'));assert.ok(html.includes('cacheManifest'));assert.ok(html.includes(selected.execution.executionSpecDigest));
  const panel=renderToStaticMarkup(createElement(TaskCheckSelectionPanel,{scope,preview:p}));assert.ok(panel.includes('预览检查及完整执行策略'));
  const consent=renderToStaticMarkup(createElement(TaskCheckConfirmationPanel,{selection:selected}));assert.ok(consent.includes('及完整执行策略'));assert.ok(consent.includes('disabled=""'));assert.ok(!consent.includes('checked=""'));controls++;
  console.log(JSON.stringify({status:'PASS_M1D2b_CLIENT_EXECUTION',controls,scope:'actual HTTP server fixture identities, Canonical v1 cross-language SHA, exact v2 GET/POST, damaged responses, cancellation/disposal, sticky historical identity and React SSR; no browser clicks'}));
}finally{await rm(directory,{recursive:true,force:true});}
