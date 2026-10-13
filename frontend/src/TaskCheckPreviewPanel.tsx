import { useEffect, useRef, useState } from "react";
import { createTaskCheckPreviewLoader, taskCheckPreviewScopeKey, type TaskCheckPreview, type TaskCheckPreviewScope,
  type TaskCheckPreviewState, createTaskCheckSelectionLoader, type TaskCheckSelectionPreview,
  type TaskCheckSelectionState, createTaskCheckConfirmationLoader, type TaskCheckConfirmationState } from "./api/client";

export function TaskCheckPreviewView({ preview }: { preview: TaskCheckPreview }) {
  return <section aria-label="子任务验收预览" style={{ marginTop: 8, fontSize: 12 }}>
    <p>仅预览，尚未确认或登记。这些检查是否充分覆盖需求，需要用户核对。</p>
    <strong>用户原始需求与补充</strong>
    <ol>{preview.requirements.map(source => <li key={source.messageId}>
      <pre style={{ whiteSpace: "pre-wrap", fontFamily: "inherit" }}>{source.content}</pre>
    </li>)}</ol>
    <p>任务目标：{preview.task.goal}</p>
    <p>交付物：{preview.task.deliverable}</p>
    <strong>验收文本</strong><ul>{preview.task.acceptance.map((item, index) => <li key={index}>{item}</li>)}</ul>
    <p>依赖：{preview.task.dependsOn.join("、") || "无"}；输入引用：{JSON.stringify(preview.task.inputRefs)}</p>
    <p>申请读写范围：{preview.task.resourceScope ? JSON.stringify(preview.task.resourceScope) : "未声明"}</p>
    <p>执行方式：{preview.task.executionMode}；{preview.task.required ? "必需任务" : "可选任务"}</p>
    <strong>可信目录可选检查（尚未选择）</strong>
    {preview.availableChecks.map(check => <details key={check.id}>
      <summary>{check.id} · 至少通过 {check.minPassed} 项测试 · 超时 {check.timeoutSeconds} 秒</summary>
      <pre style={{ whiteSpace: "pre-wrap" }}>{JSON.stringify(check.argv)}</pre>
      <ul>{check.verifiers.map(file => <li key={file.path}>{file.path} · {file.sha256}</li>)}</ul>
    </details>)}
  </section>;
}

export function TaskCheckSelectionView({ selection }: { selection: TaskCheckSelectionPreview }) {
  const execution=selection.execution?JSON.parse(selection.execution.canonicalDefinition):null;
  return <section aria-label="选定检查预览">
    <p>本次选定检查仅供核对，尚未确认或登记。请核对是否充分覆盖原始需求。</p>
    <ul>{selection.selectedChecks.map(check => <li key={check.id}>
      <strong>{check.id}</strong> · 至少通过 {check.minPassed} 项 · 超时 {check.timeoutSeconds} 秒
      <pre style={{ whiteSpace: "pre-wrap" }}>{JSON.stringify(check.argv)}</pre>
      <ul>{check.verifiers.map(file => <li key={file.path}>{file.path} · {file.sha256}</li>)}</ul>
    </li>)}</ul>
    <details><summary>预览标识</summary>
      <p>选定集合：<code>{selection.checksDigest}</code></p>
      <p>绑定预览：<code>{selection.selectionDigest}</code></p>
    </details>
    {selection.execution && <section aria-label="完整执行策略">
      <strong>本次同时登记完整执行策略</strong>
      <p>下方包含资源上限、报告采集方式和全部离线依赖身份。请核对后再确认。</p>
      <p>网络关闭，候选文件和检查器只读。CPU上限 {execution.limits.cpuMillis/1000} 核，内存 {execution.limits.memoryBytes/1048576} MiB，进程上限 {execution.limits.pids}。</p>
      <p>临时空间 {execution.limits.tmpfsBytes/1048576} MiB；日志上限 {execution.limits.logBytes} 字节；报告最多 {execution.limits.reportFiles} 个，共 {execution.limits.reportTotalBytes} 字节。</p>
      <p>报告：{execution.report.format}；离线依赖共 {execution.cacheManifest.length} 个文件。完整身份与采集方式见下方定义。</p>
      <details><summary>查看完整执行定义</summary>
        <pre style={{whiteSpace:"pre-wrap"}}>{JSON.stringify(JSON.parse(selection.execution.canonicalDefinition),null,2)}</pre>
      </details>
      <p>执行身份：<code>{selection.execution.executionSpecDigest}</code></p>
    </section>}
  </section>;
}

export function TaskCheckConfirmationPanel({ selection }: { selection: TaskCheckSelectionPreview }) {
  const [consent,setConsent]=useState(false);
  const [state,setState]=useState<TaskCheckConfirmationState>({loading:false,registration:null,error:null});
  const loader=useRef<ReturnType<typeof createTaskCheckConfirmationLoader>|null>(null);
  useEffect(()=>{const current=createTaskCheckConfirmationLoader(selection,setState);loader.current=current;
    return()=>{current.dispose();if(loader.current===current)loader.current=null;};},[selection.selectionDigest]);
  return <section aria-label="确认子任务验收登记">
    <p>请核对上方原始需求、任务定义和选定检查，确认其覆盖所需行为。登记不会执行检查，也不表示任务通过。</p>
    <label><input type="checkbox" checked={consent} disabled={state.loading||!!state.registration}
      onChange={event=>setConsent(event.target.checked)}/> 我已核对需求和这组选定检查{selection.execution?"及完整执行策略":""}，同意为本任务版本登记</label>
    <button type="button" disabled={!consent||state.loading||!!state.registration}
      onClick={()=>void loader.current?.confirm(consent)}>{state.loading?"正在确认登记…":"确认登记选定检查"}</button>
    {state.error&&<p role="alert">{state.error}</p>}
    {state.registration&&<p role="status">已登记：{state.registration.registrationId}。登记时间：{state.registration.recordedAt}。尚未执行或验证通过。</p>}
  </section>;
}

export function TaskCheckSelectionPanel({ scope, preview }: { scope: TaskCheckPreviewScope; preview: TaskCheckPreview }) {
  const [refs, setRefs] = useState<string[]>([]);
  const [state, setState] = useState<TaskCheckSelectionState>({ loading: false, selection: null, error: null });
  const loader = useRef<ReturnType<typeof createTaskCheckSelectionLoader> | null>(null);
  const key = `${taskCheckPreviewScopeKey(scope)}:${preview.previewDigest}`;
  useEffect(() => {
    const current = createTaskCheckSelectionLoader(scope, preview, setState);
    loader.current = current;
    return () => { current.dispose(); if (loader.current === current) loader.current = null; };
  }, [key]);
  return <div>
    <fieldset><legend>选择本任务的验收检查（1～8项）</legend>
      {preview.availableChecks.map(check => <label key={check.id} style={{ display: "block" }}>
        <input type="checkbox" checked={refs.includes(check.id)} disabled={!refs.includes(check.id) && refs.length >= 8}
          onChange={event => { const checked = event.target.checked; loader.current?.cancel(); setRefs(current => checked
            ? [...current, check.id] : current.filter(id => id !== check.id)); }} /> {check.id}
      </label>)}
    </fieldset>
    <p>已选择 {refs.length} 项。选择不会自动确认；检查覆盖范围需要用户核对。</p>
    <button type="button" disabled={state.loading || refs.length === 0}
      onClick={() => void loader.current?.load(refs)}>{state.loading ? "正在读取选定预览…" : "预览选定检查"}</button>
    <button type="button" disabled={state.loading || refs.length === 0}
      onClick={() => void loader.current?.load(refs,true)}>预览检查及完整执行策略</button>
    {state.error && <p role="alert">{state.error}</p>}
    {state.selection && <><TaskCheckSelectionView selection={state.selection} />
      <TaskCheckConfirmationPanel key={state.selection.selectionDigest} selection={state.selection}/></>}
  </div>;
}

/** Parent remounts this panel when the complete task/scope key changes. */
export function TaskCheckPreviewPanel({ scope }: { scope: TaskCheckPreviewScope }) {
  const [state, setState] = useState<TaskCheckPreviewState>({ loading: false, preview: null, error: null });
  const loader = useRef<ReturnType<typeof createTaskCheckPreviewLoader> | null>(null);
  const scopeKey = taskCheckPreviewScopeKey(scope);
  useEffect(() => {
    const current = createTaskCheckPreviewLoader(scope, setState);
    loader.current = current;
    return () => { current.dispose(); if (loader.current === current) loader.current = null; };
  }, [scopeKey]);
  return <div>
    <button type="button" disabled={state.loading} onClick={() => void loader.current?.load()}>
      {state.loading ? "正在读取验收预览…" : "查看验收预览"}
    </button>
    {state.error && <p role="alert">{state.error}</p>}
    {state.preview && <>
      <TaskCheckPreviewView preview={state.preview} />
      <TaskCheckSelectionPanel key={state.preview.previewDigest} scope={scope} preview={state.preview} />
    </>}
  </div>;
}
