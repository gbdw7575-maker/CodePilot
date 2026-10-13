import {useEffect,useRef,useState} from "react";
import {createTaskAcceptanceModeLoader,type TaskAcceptanceModeState} from "./api/client";

export function TaskAcceptanceModePanel({rootRunId}:{rootRunId:string}){
  const [state,setState]=useState<TaskAcceptanceModeState>({loading:true,mode:null,error:null});
  const [consent,setConsent]=useState(false);
  const loader=useRef<ReturnType<typeof createTaskAcceptanceModeLoader>|null>(null);
  useEffect(()=>{setConsent(false);const current=createTaskAcceptanceModeLoader(rootRunId,setState);loader.current=current;void current.load();
    return()=>{current.dispose();if(loader.current===current)loader.current=null;};},[rootRunId]);
  const selectable=state.mode?.mode==="UNFROZEN"&&state.mode.optInAvailable;
  return <section aria-label="根任务验收模式" style={{fontSize:12,marginBottom:8}}>
    <p>强验收需要逐任务确认检查；冻结后本根任务不能降回旧模式。{state.mode?.mode==="EXECUTABLE_CHECKS"&&(state.mode.executionAvailable?"强执行已开放，派发时仍须核验每个任务的当前检查绑定。":"当前未开放强执行，派发会被拒绝。")}</p>
    {state.mode&&<p role="status">当前模式：{state.mode.mode==="UNFROZEN"?"尚未冻结":state.mode.mode==="EXECUTABLE_CHECKS"?"强验收（已冻结）":"旧覆盖检查（已冻结）"}
      {state.mode.frozenAt&&`；冻结时间：${state.mode.frozenAt}`}。{!state.mode.optInAvailable&&"Host未开放新的强模式选择。"}</p>}
    <label><input type="checkbox" checked={consent} disabled={state.loading||!selectable}
      onChange={event=>setConsent(event.target.checked)}/> 我明确同意为本根任务冻结强验收模式，并逐任务确认检查</label>
    <button type="button" disabled={!consent||state.loading||!selectable} onClick={()=>void loader.current?.confirm(consent)}>冻结强验收模式</button>
    <button type="button" disabled={state.loading} onClick={()=>void loader.current?.load()}>重读模式</button>
    {state.error&&<p role="alert">{state.error}</p>}
  </section>;
}
