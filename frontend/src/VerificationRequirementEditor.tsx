import { useEffect, useState } from "react";
import { previewVerificationPlan, type TurnSubmissionOptions, type VerificationPlanView, type RunVerificationRequirement } from "./api/client";

import { prepareVerificationOptions, type Draft } from "./verificationRequirement";

export function VerificationPlanSummary({ plan }: { plan: VerificationPlanView }) {
  return <div style={{ fontSize: 12, lineHeight: 1.6, overflowWrap: "anywhere" }}>
    <strong>{plan.source === "RUN_REGISTRATION" ? "本次运行验收要求" : "项目验收预览"}</strong>
    <div>回归命令：<code>{plan.command}</code></div>
    <div>要求发现测试：{plan.expectTests ? "是" : "否"}；超时 {plan.timeoutSeconds} 秒；版本 {plan.planVersion}</div>
    {plan.testReports && <div>结构化报告：{plan.testReports.format}；目录：{plan.testReports.directories.join("、")}；必须是本次执行的新报告。</div>}
    {plan.purpose === "DEFECT_FIX" && <div>缺陷验收：同一断言在基线失败、候选通过；普通测试通过不能替代。</div>}
    {plan.acceptanceDescription && <div>验收说明：{plan.acceptanceDescription}</div>}
    {plan.defectReproduction && <details><summary>登记断言 {plan.defectReproduction.assertionId}（查看源码）</summary>
      <div>文件：{plan.defectReproduction.fileName}；命令：<code>{plan.defectReproduction.command}</code></div>
      <pre style={{ whiteSpace: "pre-wrap", maxHeight: 180, overflow: "auto" }}>{plan.defectReproduction.content}</pre>
    </details>}
  </div>;
}

export function VerificationRequirementEditor(props: {
  projectId: string; disabled: boolean;
  onPrepared: (projectId: string, options: TurnSubmissionOptions | null) => void;
  initialRequirement?: RunVerificationRequirement;
}) {
  const [plan, setPlan] = useState<VerificationPlanView | null>(null);
  const [error, setError] = useState(""); const [reload, setReload] = useState(0);
  const [mode, setMode] = useState(props.initialRequirement?.purpose ?? "INHERIT"); const [confirmed, setConfirmed] = useState(false);
  const [draft, setDraft] = useState<Draft>(() => ({ acceptanceDescription: "", assertionId: "", fileName: "check.sh", content: "", command: 'sh "$DSH_PROBE_FILE"', ...(props.initialRequirement?.purpose === "DEFECT_FIX" ? { acceptanceDescription: props.initialRequirement.acceptanceDescription, ...props.initialRequirement.defectReproduction } : {}) }));
  useEffect(() => {
    let live = true; setPlan(null); setError(""); setConfirmed(false); props.onPrepared(props.projectId, null);
    void previewVerificationPlan(props.projectId).then(p => { if (live) setPlan(p); })
      .catch(e => { if (live) setError(e.message ?? "验收配置读取失败"); });
    return () => { live = false; };
  }, [props.projectId, reload, props.onPrepared]);
  useEffect(() => { props.onPrepared(props.projectId, plan ? prepareVerificationOptions(plan, mode, draft, confirmed) : null); },
    [props.projectId, props.onPrepared, plan, mode, draft, confirmed]);
  const change = (key: keyof Draft, value: string) => { setDraft(d => ({ ...d, [key]: value })); setConfirmed(false); };
  return <details style={{ margin: "8px 0", padding: 8, border: "1px solid #e5e7eb", borderRadius: 8 }} open={mode === "DEFECT_FIX" || !!error}>
    <summary style={{ fontSize: 12, cursor: "pointer" }}>执行前查看验收要求{mode === "DEFECT_FIX" ? " · 本次缺陷复现登记" : ""}</summary>
    {!plan && !error && <div>正在读取验收配置…</div>}
    {error && <div role="alert">{error} <button disabled={props.disabled} onClick={() => setReload(n => n + 1)}>重新读取</button></div>}
    {plan && <><VerificationPlanSummary plan={plan} />
      <button disabled={props.disabled} onClick={() => setReload(n => n + 1)}>重新读取项目验收配置</button>
      <label style={{ display: "block", fontSize: 12 }}>本次任务验收方式
        <select disabled={props.disabled} value={mode} onChange={e => { setMode(e.target.value); setConfirmed(false); }}>
          <option value="INHERIT">沿用项目配置</option><option value="GENERAL">一般任务（项目回归验证）</option>
          <option value="DEFECT_FIX">缺陷修复（登记复现）</option>
        </select>
      </label>
      {mode === "GENERAL" && <div style={{ fontSize: 12 }}>本次仅采用项目回归命令，不使用项目级缺陷断言。</div>}
      {mode === "DEFECT_FIX" && <fieldset disabled={props.disabled} style={{ marginTop: 8 }}>
        <legend>本次运行的复现与验收说明</legend>
        {([['acceptanceDescription', '行为验收说明（最多 2000 字符）'], ['assertionId', '断言 ID'], ['fileName', '复现文件名'], ['command', '复现命令']] as const).map(([key, label]) =>
          <label key={key} style={{ display: "block", fontSize: 12, marginBottom: 6 }}>{label}
            <input style={{ display: "block", width: "100%" }} value={draft[key]} onChange={e => change(key, e.target.value)} /></label>)}
        <label style={{ fontSize: 12 }}>复现源码（UTF-8 最多 16 KiB）
          <textarea rows={6} style={{ display: "block", width: "100%", fontFamily: "monospace" }} value={draft.content} onChange={e => change("content", e.target.value)} />
        </label>
        <div style={{ fontSize: 12 }}>基线：退出 1，最后输出 DSH_DEFECT_V1 FAIL + 断言 ID；候选：退出 0，最后输出 DSH_DEFECT_V1 PASS + 断言 ID。断言应检查上述行为；需要在沙箱中执行。</div>
        <label style={{ fontSize: 12 }}><input type="checkbox" checked={confirmed} onChange={e => setConfirmed(e.target.checked)} />已审核验收说明和源码，确认用于本次任务</label>
        {!prepareVerificationOptions(plan, mode, draft, confirmed) && <div role="status" style={{ fontSize: 12 }}>填写完整、格式正确并确认后才能提交。</div>}
      </fieldset>}
      <div style={{ fontSize: 12 }}>提交时核对项目配置摘要；启动后登记不可改写。新需求会使旧验证证据过期。</div>
    </>}
  </details>;
}
