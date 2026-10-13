
const API_BASE = "/api/v1";

export interface TaskAcceptanceModeView {rootRunId:string;mode:"UNFROZEN"|"LEGACY_COVERAGE_ONLY"|"EXECUTABLE_CHECKS";
  frozen:boolean;optInAvailable:boolean;executionAvailable:boolean;frozenAt:string|null;}
export interface TaskAcceptanceModeState {loading:boolean;mode:TaskAcceptanceModeView|null;error:string|null;}
/** Explicit human opt-in only. Sticky key after an unknown response and disposal on root changes. */
export function createTaskAcceptanceModeLoader(root:string,update:(s:TaskAcceptanceModeState)=>void){
  const key=idemKey();let active=true,pending=false,done=false;
  function validate(value:TaskAcceptanceModeView){
    if(value.rootRunId!==root||!["UNFROZEN","LEGACY_COVERAGE_ONLY","EXECUTABLE_CHECKS"].includes(value.mode)
      ||typeof value.frozen!=="boolean"||typeof value.optInAvailable!=="boolean"||typeof value.executionAvailable!=="boolean"
      ||(value.executionAvailable&&value.mode!=="EXECUTABLE_CHECKS")
      ||(value.mode==="UNFROZEN"?(value.frozen||value.frozenAt!==null):(!value.frozen||typeof value.frozenAt!=="string"||!Number.isFinite(Date.parse(value.frozenAt)))))
      throw new Error("验收模式响应归属或状态不一致，请重读根任务");
    return value;
  }
  async function perform(write:boolean){
    if(!active||pending||(write&&done))return;
    pending=true;update({loading:true,mode:null,error:null});
    try{
      const value=validate(await request<TaskAcceptanceModeView>(`${API_BASE}/runs/${encodeURIComponent(root)}/task-acceptance-mode`,write?
        {method:"POST",headers:{"Idempotency-Key":key},body:JSON.stringify({mode:"EXECUTABLE_CHECKS",confirmed:true})}:undefined));
      if(write&&value.mode!=="EXECUTABLE_CHECKS")throw new Error("服务器未确认强验收模式");
      if(active){done=value.frozen;update({loading:false,mode:value,error:null});}
    }catch(error){if(active)update({loading:false,mode:null,error:(error as ApiError).message||"响应未知，请重试或重读模式"});}
    finally{pending=false;}
  }
  return {load:()=>perform(false),confirm(consent:boolean){if(!active||pending||done)return Promise.resolve();if(!consent){update({loading:false,mode:null,error:"请明确同意冻结强验收模式"});return Promise.resolve();}return perform(true);},dispose(){active=false;}};
}

export interface TaskCheckRegistration {
  status:"REGISTERED";registrationId:string;projectId:string;rootRunId:string;turnId:string;planId:string;taskId:string;
  taskVersion:number;checkRefs:string[];checksDigest:string;bindingDigest:string;snapshotDigest:string;
  observedPlanRevision:number;recordedAt:string;
  executionSpecDigest?:string;
}
export interface TaskCheckConfirmationState {loading:boolean;registration:TaskCheckRegistration|null;error:string|null;}
/** Sticky HTTP key on uncertain-response retry; scope disposal never accepts a late UI response. */
export function createTaskCheckConfirmationLoader(selection:TaskCheckSelectionPreview,update:(state:TaskCheckConfirmationState)=>void){
  // Copy input identities now: callers cannot silently mutate a pending request's approved selection.
  const p=selection.taskPreview,version=p.task.taskVersion,refs=[...selection.checkRefs],digest=selection.selectionDigest;
  const root=p.rootRunId,plan=p.planId,task=p.task.taskId,project=p.projectId,turn=p.turnId,checks=selection.checksDigest;
  const key=idemKey();let active=true,pending=false,done=false;
  const executionDigest=selection.execution?.executionSpecDigest;
  const selectionVersion=executionDigest?"2":"1";
  return {
    async confirm(consent:boolean){
      if(!active||pending||done)return;
      if(!consent){update({loading:false,registration:null,error:"请先核对并明确同意登记这组检查"});return;}
      pending=true;update({loading:true,registration:null,error:null});
      try{
        const saved=await request<TaskCheckRegistration>(`${API_BASE}/runs/${encodeURIComponent(root)}/plans/${encodeURIComponent(plan)}/tasks/${encodeURIComponent(task)}/acceptance-registration`,
          {method:"POST",headers:{"Idempotency-Key":key,"If-Match":`"task-check-selection-v${selectionVersion}-${digest}"`},
            body:JSON.stringify({taskVersion:version,checkRefs:refs,confirmed:true})});
        if(!active)return;
        if(saved.status!=="REGISTERED"||saved.projectId!==project||saved.rootRunId!==root||saved.turnId!==turn||saved.planId!==plan
          ||saved.taskId!==task||saved.taskVersion!==version||JSON.stringify(saved.checkRefs)!==JSON.stringify(refs)||saved.checksDigest!==checks
          ||!/^checkreg_[a-f0-9]{32}$/.test(saved.registrationId)||![saved.bindingDigest,saved.snapshotDigest].every(h=>/^[a-f0-9]{64}$/.test(h))
          ||saved.executionSpecDigest!==executionDigest
          ||!Number.isInteger(saved.observedPlanRevision)||saved.observedPlanRevision<1||!Number.isFinite(Date.parse(saved.recordedAt)))
          throw new Error("登记响应与选定任务不一致，请重新读取任务状态");
        done=true;update({loading:false,registration:saved,error:null});
      }catch(error){if(active)update({loading:false,registration:null,error:(error as ApiError).message||"确认响应未知；重试会使用同一幂等键，请勿更换选定检查"});}
      finally{pending=false;}
    },
    dispose(){active=false;},
  };
}

export interface ApiError {
  code: string;
  message: string;
  requestId?: string;
  retryable?: boolean;
  details?: Record<string, unknown>;
}

export interface Health {
  status: string;
  contractVersion: string;
  timestamp: string;
}

export interface PageDto<T> {
  items: T[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface Project {
  id: string;
  name: string;
  rootPath: string;
  trusted: boolean;
  version: number;
}

export interface ModelProfile {
  id: string;
  name: string;
  provider: string;
  model: string;
}

export interface Thread {
  id: string;
  projectId: string;
  title?: string;
  modelProfileId: string;
  activeTurnId: string | null;
  updatedAt?: string;
}

export interface MessageItem {
  id: string;
  threadId: string;
  turnId: string | null;
  role: string;
  content: string;
  sequence: string;
  createdAt: string;
}

export interface TurnAccepted {
  turnId: string;
  rootRunId: string;
}

export interface Run {
  id: string;
  turnId: string;
  parentRunId?: string | null;
  role?: string;
  state: string;
  verificationStatus: string;
  stopReason: string | null;
  summary: string | null;
  budget?: RunBudget;
}

/** MA-10：根/子 Run 统一预算口径（settled=已结算，reserved=在途预留）。 */
export interface RunBudget {
  maxModelRequests: number;
  modelRequestsUsed: number;
  maxToolCalls: number;
  toolCallsUsed: number;
  maxCumulativeTokens: number;
  settledTokens: number | null;
  reservedTokens: number;
  deadlineAt: string | null;
  usageStatus: string | null;
}

/* ----------------------- MA-10 计划任务视图（/runs/{id}/plans） ----------------------- */

export interface PlanInputRef {
  taskId: string;
  output: string;
}

export interface PlanResourceScope {
  read: string[];
  write: string[];
}

export interface PlanTaskResult {
  attemptId: string;
  state: string;
  errorCode: string | null;
  errorMessage: string | null;
  artifactRefs: string[];
  patchArtifactId: string | null;
  outputDigest: string | null;
  inputDigest: string | null;
  verificationRef: string | null;
  modelRequestsUsed: number;
  toolCallsUsed: number;
  settledTokens: number | null;
  durationMs: number;
}

export interface PlanTaskRow {
  taskId: string;
  taskVersion: number;
  goal: string;
  dependsOn: string[];
  inputRefs: PlanInputRef[];
  deliverable: string | null;
  acceptance: string[];
  resourceScope: PlanResourceScope | null;
  executionMode: string;
  required: boolean;
  state: string;
  attemptId: string | null;
  childRunId: string | null;
  reasonCode: string | null;
  evidenceValid: boolean;
  result: PlanTaskResult | null;
}

export interface PlanView {
  planId: string;
  rootRunId: string;
  revision: number;
  status: string;
  goal: string | null;
  createdAt: string;
  updatedAt: string;
  tasks: PlanTaskRow[];
}

export interface PlanViewList {
  plans: PlanView[];
}

export interface TaskCheckPreviewDefinition {
  taskId: string; taskVersion: number; goal: string; acceptance: string[]; deliverable: string;
  dependsOn: string[]; inputRefs: PlanInputRef[]; resourceScope: PlanResourceScope | null;
  executionMode: string; required: boolean;
}
export interface TaskCheckPreview {
  status: "PREVIEW_ONLY"; projectId: string; rootRunId: string; turnId: string;
  planId: string; planRevision: number; taskState: string; task: TaskCheckPreviewDefinition;
  requirements: Array<{ messageId: string; sequence: string; content: string }>;
  taskDefinitionDigest: string; requirementsDigest: string; registryDigest: string;
  imageDigest: string; executionPolicyDigest: string; previewDigest: string;
  availableChecks: Array<{ id: string; argv: string[]; timeoutSeconds: number; expectTests: true;
    minPassed: number; verifiers: Array<{ path: string; sha256: string }> }>;
}
export interface TaskCheckPreviewScope {
  rootRunId: string; planId: string; planRevision: number;
  task: Pick<PlanTaskRow, keyof TaskCheckPreviewDefinition | "state">;
}
export interface TaskCheckPreviewState {
  loading: boolean; preview: TaskCheckPreview | null; error: string | null;
}
const previewDefinition = (task: Pick<PlanTaskRow, "taskId" | "taskVersion" | "goal" | "acceptance" | "deliverable"
  | "dependsOn" | "inputRefs" | "resourceScope" | "executionMode" | "required">) => ({
  taskId: task.taskId, taskVersion: task.taskVersion, goal: task.goal,
  acceptance: task.acceptance, deliverable: task.deliverable, dependsOn: task.dependsOn,
  inputRefs: task.inputRefs.map(ref => ({ taskId: ref.taskId, output: ref.output })),
  resourceScope: task.resourceScope == null ? null : { read: task.resourceScope.read, write: task.resourceScope.write },
  executionMode: task.executionMode, required: task.required,
});
export const taskCheckPreviewScopeKey = (scope: TaskCheckPreviewScope) =>
  JSON.stringify([scope.rootRunId, scope.planId, scope.planRevision, scope.task.state, previewDefinition(scope.task)]);

export const getTaskCheckPreview = (rootRunId: string, planId: string, taskId: string) =>
  request<TaskCheckPreview>(`${API_BASE}/runs/${encodeURIComponent(rootRunId)}/plans/${encodeURIComponent(planId)}/tasks/${encodeURIComponent(taskId)}/acceptance-preview`);

/** Same loader used by the component: newest request wins; disposed scopes never update UI. */
export function createTaskCheckPreviewLoader(scope: TaskCheckPreviewScope, update: (state: TaskCheckPreviewState) => void) {
  const expectedDefinition = JSON.stringify(previewDefinition(scope.task));
  const expectedState = scope.task.state;
  const rootId = scope.rootRunId, planId = scope.planId, revision = scope.planRevision, taskId = scope.task.taskId;
  let active = true, serial = 0;
  return {
    async load() {
      if (!active) return;
      const requestId = ++serial;
      update({ loading: true, preview: null, error: null });
      try {
        const preview = await getTaskCheckPreview(rootId, planId, taskId);
        if (!active || requestId !== serial) return;
        if (preview.status !== "PREVIEW_ONLY" || preview.rootRunId !== rootId || preview.planId !== planId
            || preview.planRevision !== revision || preview.taskState !== expectedState
            || JSON.stringify(previewDefinition(preview.task)) !== expectedDefinition) {
          throw new Error("预览与当前任务不一致，请刷新计划后重试");
        }
        update({ loading: false, preview, error: null });
      } catch (error) {
        if (!active || requestId !== serial) return;
        const message = error && typeof error === "object" && "message" in error ? String(error.message) : "预览加载失败";
        update({ loading: false, preview: null, error: message });
      }
    },
    dispose() { active = false; serial++; },
  };
}

export interface TaskCheckSelectionPreview {
  status: "SELECTED_PREVIEW_ONLY"; taskPreview: TaskCheckPreview; checkRefs: string[];
  selectedChecks: TaskCheckPreview["availableChecks"]; checksDigest: string; selectionDigest: string;
  execution?:TaskCheckExecutionView;
}
export interface TaskCheckSelectionState {
  loading: boolean; selection: TaskCheckSelectionPreview | null; error: string | null;
}
const previewCheckValue = (check: TaskCheckPreview["availableChecks"][number]) => ({
  id: check.id, argv: check.argv, timeoutSeconds: check.timeoutSeconds, expectTests: check.expectTests,
  minPassed: check.minPassed, verifiers: check.verifiers.map(file => ({ path: file.path, sha256: file.sha256 })),
});
const previewValue = (preview: TaskCheckPreview) => ({
  status: preview.status, projectId: preview.projectId, rootRunId: preview.rootRunId, turnId: preview.turnId,
  planId: preview.planId, planRevision: preview.planRevision, taskState: preview.taskState, task: previewDefinition(preview.task),
  requirements: preview.requirements.map(source => ({ messageId: source.messageId, sequence: source.sequence, content: source.content })),
  taskDefinitionDigest: preview.taskDefinitionDigest, requirementsDigest: preview.requirementsDigest,
  registryDigest: preview.registryDigest, imageDigest: preview.imageDigest, executionPolicyDigest: preview.executionPolicyDigest,
  availableChecks: preview.availableChecks.map(previewCheckValue), previewDigest: preview.previewDigest,
});
export function taskCheckSelectionRefs(preview: TaskCheckPreview, refs: readonly string[]): string[] {
  const values = [...refs];
  if (values.length < 1 || values.length > 8 || new Set(values).size !== values.length
      || values.some(id => typeof id !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(id)
        || !preview.availableChecks.some(check => check.id === id))) {
    throw new Error("请选择1～8个不同的可信检查");
  }
  return values.sort();
}
export const getTaskCheckSelectionPreview = (rootRunId: string, planId: string, taskId: string,
  previewDigest: string, refs: readonly string[],complete=false) => {
  if (!/^[a-f0-9]{64}$/.test(previewDigest)) throw new Error("需求预览标识无效，请重新读取");
  const query = new URLSearchParams(); refs.forEach(id => query.append("checkRef", id));
  return request<TaskCheckSelectionPreview>(`${API_BASE}/runs/${encodeURIComponent(rootRunId)}/plans/${encodeURIComponent(planId)}/tasks/${encodeURIComponent(taskId)}/acceptance-${complete?"execution":"selection"}-preview?${query}`,
    { headers: { "If-Match": `"task-check-preview-v1-${previewDigest}"` } });
};
/** Selection changes cancel older results, including when the new selection is invalid. */
export function createTaskCheckSelectionLoader(scope: TaskCheckPreviewScope, preview: TaskCheckPreview,
  update: (state: TaskCheckSelectionState) => void) {
  const expected = JSON.stringify(previewValue(preview));
  const expectedScope = taskCheckPreviewScopeKey(scope);
  const rootId = scope.rootRunId, planId = scope.planId, taskId = scope.task.taskId;
  let active = true, serial = 0;
  const empty = (): TaskCheckSelectionState => ({ loading: false, selection: null, error: null });
  return {
    async load(refs: readonly string[],complete=false) {
      if (!active) return;
      const requestId = ++serial;
      update({ loading: true, selection: null, error: null });
      try {
        const selectedRefs = taskCheckSelectionRefs(preview, refs);
        const expectedChecks = JSON.stringify(selectedRefs.map(id => previewCheckValue(preview.availableChecks.find(check => check.id === id)!)));
        const selection = await getTaskCheckSelectionPreview(rootId, planId, taskId, preview.previewDigest, selectedRefs,complete);
        if (!active || requestId !== serial) return;
        const actual = selection.taskPreview;
        if (selection.status !== "SELECTED_PREVIEW_ONLY" || JSON.stringify(previewValue(actual)) !== expected
            || taskCheckPreviewScopeKey({ rootRunId: actual.rootRunId, planId: actual.planId, planRevision: actual.planRevision,
              task: { ...actual.task, state: actual.taskState } }) !== expectedScope
            || JSON.stringify(selection.checkRefs) !== JSON.stringify(selectedRefs)
            || JSON.stringify(selection.selectedChecks.map(previewCheckValue)) !== expectedChecks
            || !/^[a-f0-9]{64}$/.test(selection.checksDigest) || !/^[a-f0-9]{64}$/.test(selection.selectionDigest)) {
          throw new Error("选定预览与当前需求或检查不一致，请重新读取后选择");
        }
        if(complete)await validateTaskExecution(selection);
        else if(selection.execution!==undefined)throw new Error("检查预览意外包含完整执行定义，请重新读取");
        if(!active||requestId!==serial)return;
        update({ loading: false, selection, error: null });
      } catch (error) {
        if (!active || requestId !== serial) return;
        const message = error && typeof error === "object" && "message" in error ? String(error.message) : "选定预览加载失败";
        update({ loading: false, selection: null, error: message });
      }
    },
    cancel() { if (active) { serial++; update(empty()); } },
    dispose() { active = false; serial++; },
  };
}

export interface EventEnvelope {
  sequence: string;
  eventId: string;
  threadId?: string;
  turnId?: string;
  runId?: string;
  parentRunId?: string | null;
  traceId?: string | null;
  spanId?: string | null;
  type: string;
  payload: Record<string, unknown>;
  timestamp: string;
}

export interface EventPage {
  items: EventEnvelope[];
  nextAfterSequence: string;
  hasMore: boolean;
  highWatermark: string;
}

export interface DiffFile {
  path: string;
  status: string;
  oldPath?: string | null;
  binary: boolean;
}

export interface Diff {
  runId: string;
  baseDigest: string;
  workspaceDigest: string;
  files: DiffFile[];
  patch: string;
  truncated: boolean;
  artifactId: string | null;
}

export interface Verification {
  id: string;
  runId: string;
  status: string;
  exitCode: number;
  testsDiscovered: number;
  testsPassed: number;
  testsSkipped: number;
  stale: boolean;
  logArtifactIds: string[];
  startedAt: string;
  finishedAt: string;
}

export interface VerificationPage {
  items: Verification[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface Approval {
  id: string;
  turnId: string;
  runId: string;
  toolCallId: string;
  toolName: string;
  argumentsSummary: string;
  parametersDigest: string;
  workspaceDigest: string;
  risk: string;
  reason: string;
  scope: string;
  status: string;
  expiresAt: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

function idemKey(): string {
  return "web-" + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const isWrite = options && ["POST", "PUT", "PATCH", "DELETE"].includes(options.method ?? "GET");
  const response = await fetch(url, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      "X-DSH-Client": "local-ui",
      ...(isWrite ? { "Idempotency-Key": idemKey() } : {}),
      ...(options?.headers ?? {}),
    },
  });
  if (!response.ok) {
    throw await toApiError(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export async function toApiError(response: Response): Promise<ApiError> {
  const requestId = response.headers.get("X-Request-Id") ?? "";
  try {
    const body = (await response.json()) as ApiError;
    if (body && typeof body.code === "string" && typeof body.message === "string") {
      return body;
    }
  } catch {
    // fall through
  }
  return {
    code: "INTERNAL_ERROR",
    message: `HTTP ${response.status}`,
    requestId,
    retryable: response.status >= 500 || response.status === 429,
    details: {},
  };
}

export const fetchHealth = (signal?: AbortSignal) =>
  request<Health>(`${API_BASE}/health`, { signal });

export const listProjects = () =>
  request<PageDto<Project>>(`${API_BASE}/projects`).then((p) => p.items);

export const createProject = (name: string, rootPath: string) =>
  request<Project>(`${API_BASE}/projects`, {
    method: "POST",
    body: JSON.stringify({ name, rootPath }),
  });

export const trustProject = (projectId: string, trusted: boolean, version: number) =>
  request<Project>(`${API_BASE}/projects/${projectId}/trust`, {
    method: "PUT",
    headers: { "If-Match": `"v${version}"` },
    body: JSON.stringify({ trusted }),
  });

export const deleteProject = (projectId: string) =>
  request<void>(`${API_BASE}/projects/${projectId}`, { method: "DELETE" });

export const deleteThread = (projectId: string, threadId: string) =>
  request<void>(
    `${API_BASE}/projects/${projectId}/threads/${threadId}`,
    { method: "DELETE" },
  );

export const listModelProfiles = () =>
  request<PageDto<ModelProfile>>(`${API_BASE}/model-profiles`).then((p) => p.items);

export const listThreads = (projectId: string) =>
  request<PageDto<Thread>>(`${API_BASE}/projects/${projectId}/threads`).then((p) => p.items);

export const createThread = (projectId: string, modelProfileId: string) =>
  request<Thread>(`${API_BASE}/projects/${projectId}/threads`, {
    method: "POST",
    body: JSON.stringify({ modelProfileId }),
  });

export const listMessages = (threadId: string, limit = 100) =>
  request<PageDto<MessageItem>>(
    `${API_BASE}/threads/${threadId}/messages?limit=${limit}`,
  ).then((p) => p.items);

export interface DefectReproductionDefinition {
  assertionId: string; fileName: string; content: string; command: string;
}
export type RunVerificationRequirement = { purpose: "GENERAL" } | {
  purpose: "DEFECT_FIX"; acceptanceDescription: string;
  defectReproduction: DefectReproductionDefinition;
};
export interface VerificationPlanView {
  command: string; expectTests: boolean; timeoutSeconds: number; planVersion: string;
  purpose: "GENERAL" | "DEFECT_FIX"; acceptanceDescription: string | null;
  defectReproduction: DefectReproductionDefinition | null; digest: string;
  source: "PROJECT_CONFIGURATION" | "RUN_REGISTRATION";
  testReports?: { format: "MAVEN_SUREFIRE" | "GRADLE"; directories: string[] } | null;
}
export interface TurnSubmissionOptions {
  verification?: RunVerificationRequirement; projectPlanDigest?: string;
}
export const previewVerificationPlan = (projectId: string) =>
  request<VerificationPlanView>(API_BASE + "/projects/" + encodeURIComponent(projectId) + "/verification-plan");
export const getRunVerificationPlan = (runId: string) =>
  request<VerificationPlanView>(API_BASE + "/runs/" + encodeURIComponent(runId) + "/verification-plan");

export const submitTurn = (threadId: string, input: string, options: TurnSubmissionOptions = {}) =>
  request<TurnAccepted>(`${API_BASE}/threads/${threadId}/turns`, {
    method: "POST",
    body: JSON.stringify({ input, ...options }),
  });

/** Append text to the active Turn; its frozen verification registration is never resubmitted. */
export const appendTurnInput = (turnId: string, input: string) =>
  request<{ messageId: string; turnId: string; status: string; createdAt: string }>(
    `${API_BASE}/turns/${encodeURIComponent(turnId)}/inputs`, {
      method: "POST", body: JSON.stringify({ input }),
    });

export const getRun = (runId: string) => request<Run>(`${API_BASE}/runs/${runId}`);

export const cancelRun = (runId: string) =>
  request<Run>(`${API_BASE}/runs/${runId}/cancel`, { method: "POST" });

/** MA-10：根 Run 的计划任务快照（定义 + 运行态 + 证据）。 */
export const listPlans = (runId: string) =>
  request<PlanViewList>(`${API_BASE}/runs/${encodeURIComponent(runId)}/plans`)
    .then((p) => p.plans);

/** MA-10：根 Run 的直接子 Run（预算同口径；nextCursor 恒 null）。 */
export const listChildRuns = (runId: string) =>
  request<PageDto<Run>>(`${API_BASE}/runs/${encodeURIComponent(runId)}/children`)
    .then((p) => p.items);

/** MA-10 编排任务事件集合。 */
const TASK_LIVE_EVENTS = new Set([
  "task.dispatched",
  "task.state.changed",
  "task.result.ready",
]);

function strOr(v: unknown, fallback: string | null): string | null {
  return typeof v === "string" && v ? v : fallback;
}

/**
 * MA-10：把 task.* 实时事件折叠进 /plans 快照（事件补拉/WS 同源）。
 * - 仅接受 envelope.runId === 当前根 runId 的事件，防止跨 Turn/跨根污染；
 * - 以 (planId, taskId, taskVersion) 精确定位任务行；找不到行时原样返回（等 plan.updated
 *   触发重新拉取快照）；
 * - 权威任务态只来自 task.dispatched/task.state.changed；task.result.ready 只补结果证据。
 * 纯函数，同引用短路，便于 React memo 与 node 脚本断言。
 */
export function foldPlans(plans: PlanView[], e: EventEnvelope, rootRunId: string): PlanView[] {
  if (!rootRunId || e.runId !== rootRunId || !TASK_LIVE_EVENTS.has(e.type)) return plans;
  const p = (e.payload ?? {}) as Record<string, unknown>;
  const planId = strOr(p.planId, "");
  const taskId = strOr(p.taskId, "");
  const taskVersion = Number(p.taskVersion);
  if (!planId || !taskId || !Number.isInteger(taskVersion)) return plans;

  let changed = false;
  const next = plans.map((plan) => {
    if (plan.planId !== planId) return plan;
    const tasks = plan.tasks.map((t) => {
      if (t.taskId !== taskId || t.taskVersion !== taskVersion) return t;
      changed = true;
      const childRunId = strOr(p.childRunId, t.childRunId);
      if (e.type === "task.result.ready") {
        const refs = Array.isArray(p.artifactRefs) ? p.artifactRefs.map(String) : undefined;
        const old = t.result;
        const result: PlanTaskResult = {
          attemptId: strOr(p.attemptId, t.attemptId) ?? old?.attemptId ?? "",
          state: strOr(p.state, old?.state ?? "") ?? "",
          errorCode: old?.errorCode ?? null,
          errorMessage: old?.errorMessage ?? null,
          artifactRefs: refs ?? old?.artifactRefs ?? [],
          patchArtifactId: old?.patchArtifactId ?? null,
          outputDigest: strOr(p.outputDigest, old?.outputDigest ?? null),
          inputDigest: strOr(p.inputDigest, old?.inputDigest ?? null),
          verificationRef: p.verificationRef == null
            ? (old?.verificationRef ?? null)
            : String(p.verificationRef),
          modelRequestsUsed: old?.modelRequestsUsed ?? 0,
          toolCallsUsed: old?.toolCallsUsed ?? 0,
          settledTokens: old?.settledTokens ?? null,
          durationMs: old?.durationMs ?? 0,
        };
        return { ...t, childRunId, result };
      }
      // task.dispatched / task.state.changed：权威状态迁移
      const to = strOr(p.to, e.type === "task.dispatched" ? strOr(p.state, null) : null);
      const reasonCode = strOr(p.reasonCode, t.reasonCode);
      return {
        ...t,
        state: to ?? t.state,
        attemptId: strOr(p.attemptId, t.attemptId),
        childRunId,
        reasonCode,
      };
    });
    return { ...plan, tasks };
  });
  return changed ? next : plans;
}

export const listEvents = (threadId: string, afterSequence = "0", limit = 100, throughSequence?: string) =>
  request<EventPage>(
    `${API_BASE}/threads/${threadId}/events?afterSequence=${afterSequence}&limit=${limit}`
      + (throughSequence ? `&throughSequence=${throughSequence}` : ""),
  );

/** 以首屏 highWatermark 冻结上界，逐页补齐，避免超过单页上限时漏事件。 */
export async function listAllEvents(threadId: string): Promise<EventEnvelope[]> {
  const seen = new Set<string>();
  const events: EventEnvelope[] = [];
  let cursor = "0";
  let through: string | undefined;
  for (;;) {
    const page = await listEvents(threadId, cursor, 200, through);
    through ??= page.highWatermark;
    for (const event of page.items) {
      if (!seen.has(event.eventId)) {
        seen.add(event.eventId);
        events.push(event);
      }
    }
    if (!page.hasMore) return events;
    if (BigInt(page.nextAfterSequence) <= BigInt(cursor)) {
      throw new Error("事件补拉游标没有前进");
    }
    cursor = page.nextAfterSequence;
  }
}

export const getDiff = (runId: string) =>
  request<Diff>(`${API_BASE}/runs/${runId}/diff`);

/** Artifact 内容直链（验证日志、完整工具输出等），不内联抓取正文以外的大量数据。 */
export const artifactContentUrl = (artifactId: string) =>
  `${API_BASE}/artifacts/${encodeURIComponent(artifactId)}/content`;

export const listVerifications = (runId: string) =>
  request<VerificationPage>(`${API_BASE}/runs/${runId}/verifications`);

export const listApprovals = (turnId: string) =>
  request<PageDto<Approval>>(`${API_BASE}/turns/${turnId}/approvals`).then((p) => p.items);

export const decideApproval = (
  approval: Approval,
  decision: "APPROVE" | "DENY",
) =>
  request<{ status: string }>(`${API_BASE}/approvals/${approval.id}/decision`, {
    method: "POST",
    body: JSON.stringify({
      decision,
      parametersDigest: approval.parametersDigest,
      workspaceDigest: approval.workspaceDigest,
    }),
  });

/* --------------------------- 模型配置与凭据（本地工具端点） --------------------------- */

export interface ModelOptionsPayload {
  thinking: "disabled" | "enabled";
  reasoningEffort?: "low" | "high" | "max" | null;
  maxOutputTokens?: number | null;
  streaming: boolean;
}

export interface ModelProfileWritePayload {
  name: string;
  provider: "deepseek" | "fake" | "openai-compat";
  model: string;
  baseUrl: string;
  credentialRef: string;
  options: ModelOptionsPayload;
}

export const saveCredential = (credentialRef: string, apiKey: string) =>
  request<{ ref: string; saved: boolean }>(`${API_BASE}/credentials/${encodeURIComponent(credentialRef)}`, {
    method: "PUT",
    body: JSON.stringify({ apiKey }),
  });

export const listCredentials = () =>
  request<{ items: Array<{ ref: string; local: boolean }> }>(`${API_BASE}/credentials`);

export const createModelProfile = (payload: ModelProfileWritePayload) =>
  request<ModelProfile>(`${API_BASE}/model-profiles`, {
    method: "POST",
    body: JSON.stringify(payload),
  });

export interface ModelValidationResult {
  profileId: string;
  level: "CONNECTIVITY" | "TOOL_ROUNDTRIP";
  passed: boolean;
  checks: Array<{ name: string; passed: boolean; detail?: string | null }>;
}

export const validateModelProfile = (profileId: string, level: "CONNECTIVITY" | "TOOL_ROUNDTRIP" = "CONNECTIVITY") =>
  request<ModelValidationResult>(`${API_BASE}/model-profiles/${profileId}/validate`, {
    method: "POST",
    body: JSON.stringify({ level }),
  });

/* --------------------------- 服务端目录浏览（选工作区） --------------------------- */

export interface FsEntry {
  name: string;
  path: string;
}

export interface FsListing {
  path: string | null;
  parent: string | null;
  directories: FsEntry[];
}

export const listFsEntries = (path?: string) =>
  request<FsListing>(
    `${API_BASE}/fs/entries${path ? `?path=${encodeURIComponent(path)}` : ""}`,
  );

/* --------------------------- WebSocket 事件流 --------------------------- */

export interface WsEventFrame {
  type: "event";
  event: EventEnvelope;
}

export interface WsReadyFrame {
  type: "ready";
  threadId: string;
  highWatermark: string;
}

export interface WsCaughtUpFrame {
  type: "caught_up";
  threadId: string;
  throughSequence: string;
}

export interface WsResyncFrame {
  type: "resync_required";
  threadId: string;
  earliestAvailableSequence: string;
  highWatermark: string;
}

export interface WsErrorFrame {
  type: "error";
  message: string;
}

type WsFrame = WsEventFrame | WsReadyFrame | WsCaughtUpFrame | WsResyncFrame | WsErrorFrame;

/**
 * WebSocket 事件流客户端（事件协议 §1）。
 * - 连接 /ws/v1/events，发送 subscribe，接收 ready → event* → caught_up → 实时 event
 * - 断线自动重连，按 lastSequence 补拉
 * - 收到 resync_required 时调用 onResync 让上层走 REST 全量重建
 */
/** 事件序号按十进制字符串比较，避免超过 JS 安全整数后丢精度。 */
export function newerSequence(current: string, candidate: string): string {
  if (!/^(0|[1-9]\d*)$/.test(candidate)) return current;
  return BigInt(candidate) > BigInt(current) ? candidate : current;
}

export class EventStreamClient {
  private ws: WebSocket | null = null;
  private threadId: string;
  private lastSequence: string;
  private onEvent: (e: EventEnvelope) => void;
  private onResync: () => void;
  private closed = false;
  private reconnectTimer: number | null = null;
  private reconnectDelay = 1000;

  constructor(
    threadId: string,
    lastSequence: string,
    onEvent: (e: EventEnvelope) => void,
    onResync: () => void,
  ) {
    this.threadId = threadId;
    this.lastSequence = lastSequence;
    this.onEvent = onEvent;
    this.onResync = onResync;
  }

  connect() {
    const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
    const url = `${proto}//${window.location.host}/ws/v1/events`;
    this.ws = new WebSocket(url);

    this.ws.onopen = () => {
      this.reconnectDelay = 1000;
      this.ws?.send(
        JSON.stringify({
          type: "subscribe",
          threadId: this.threadId,
          afterSequence: this.lastSequence,
        }),
      );
    };

    this.ws.onmessage = (ev) => {
      try {
        const frame = JSON.parse(ev.data) as WsFrame;
        this.handleFrame(frame);
      } catch (e) {
        console.warn("WS frame parse error", e);
      }
    };

    this.ws.onclose = () => {
      if (this.closed) return;
      this.scheduleReconnect();
    };

    this.ws.onerror = () => {
      // onclose 将处理重连
    };
  }

  private handleFrame(frame: WsFrame) {
    switch (frame.type) {
      case "event":
        this.onEvent(frame.event);
        this.lastSequence = newerSequence(this.lastSequence, frame.event.sequence);
        break;
      case "ready":
        // 高水位信息，无需额外处理
        break;
      case "caught_up":
        // 已追上，后续为实时事件
        break;
      case "resync_required":
        this.onResync();
        break;
      case "error":
        console.warn("WS error frame:", frame.message);
        break;
    }
  }

  private scheduleReconnect() {
    if (this.reconnectTimer) return;
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, this.reconnectDelay);
    this.reconnectDelay = Math.min(this.reconnectDelay * 2, 15000);
  }

  setLastSequence(seq: string) {
    this.lastSequence = seq;
  }

  close() {
    this.closed = true;
    if (this.reconnectTimer) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.ws?.close();
    this.ws = null;
  }
}


export interface TaskCheckExecutionView {
  sourceDigest: string; canonicalDefinition: string; executionSpecDigest: string;
  checks: Array<{checkId:string;argv:string[];timeoutSeconds:number;minPassed:number;
    verifiers:TaskCheckPreview["availableChecks"][number]["verifiers"];checksDigest:string}>;
}
/** The same bounded Canonical v1 subset as the server; only verifies response consistency. */
function canonical(value:unknown,depth=0):string {
  if(depth>16)throw new Error("执行定义嵌套过深");
  if(value===null||typeof value==="boolean")return JSON.stringify(value);
  if(typeof value==="number"&&Number.isSafeInteger(value))return String(value);
  if(typeof value==="string") {
    for(let i=0;i<value.length;i++){
      const c=value.charCodeAt(i);
      if(c>=0xd800&&c<=0xdbff){const next=value.charCodeAt(++i);if(!(next>=0xdc00&&next<=0xdfff))throw new Error("无效Unicode");}
      else if(c>=0xdc00&&c<=0xdfff)throw new Error("无效Unicode");
    }
    return JSON.stringify(value);
  }
  if(Array.isArray(value))return "["+value.map(v=>canonical(v,depth+1)).join(",")+"]";
  if(value&&typeof value==="object"){
    const object=value as Record<string,unknown>,keys=Object.keys(object).sort();
    if(keys.some(key=>/[^\x00-\x7f]/.test(key)))throw new Error("无效规范键");
    return "{"+keys.map(key=>JSON.stringify(key)+":"+canonical(object[key],depth+1)).join(",")+"}";
  }
  throw new Error("执行定义包含不支持的值");
}
async function digest(value:unknown) {
  const bytes=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(canonical(value)));
  return Array.from(new Uint8Array(bytes),b=>b.toString(16).padStart(2,"0")).join("");
}
export async function validateTaskExecution(selection:TaskCheckSelectionPreview) {
  const execution=selection.execution,p=selection.taskPreview;
  const fail=()=>{throw new Error("完整执行预览与当前任务不一致，请重新读取");};
  if(!execution||![execution.sourceDigest,execution.executionSpecDigest].every(h=>typeof h==="string"&&/^[a-f0-9]{64}$/.test(h))
      ||typeof execution.canonicalDefinition!=="string"||new TextEncoder().encode(execution.canonicalDefinition).length>524288
      ||!Array.isArray(execution.checks)||execution.checks.length!==p.availableChecks.length)fail();
  const e=execution!;const definition=JSON.parse(e.canonicalDefinition);
  const exactKeys=(value:unknown,names:string[])=>!!value&&typeof value==="object"&&!Array.isArray(value)
    &&JSON.stringify(Object.keys(value).sort())===JSON.stringify([...names].sort());
  if(!exactKeys(e,["sourceDigest","canonicalDefinition","checks","executionSpecDigest"])
      ||!exactKeys(definition,["version","projectId","registryDigest","imageDigest","executionPolicy","adapterId","limits","report","cacheManifest","checkRefs"]))fail();
  const ranges:Record<string,[number,number]>={cpuMillis:[1000,2000],memoryBytes:[536870912,2147483648],pids:[64,256],
    tmpfsBytes:[67108864,536870912],logBytes:[1024,1048576],reportFiles:[1,64],reportFileBytes:[1024,1048576],reportTotalBytes:[1024,4194304]};
  if(!exactKeys(definition.limits,Object.keys(ranges))||Object.entries(ranges).some(([key,[min,max]])=>
    !Number.isSafeInteger(definition.limits[key])||definition.limits[key]<min||definition.limits[key]>max)
    ||definition.limits.tmpfsBytes>definition.limits.memoryBytes||definition.limits.reportTotalBytes<definition.limits.reportFileBytes)fail();
  const report=definition.report;
  if(!exactKeys(report,["format","policy","directories","transport","lifecycle"])||report.format!=="MAVEN_SUREFIRE"
      ||report.policy!=="surefire-reports-v1"||JSON.stringify(report.directories)!=='["target/surefire-reports"]'
      ||report.transport!=="BOUNDED_DOCKER_STDOUT_BYTES_V1"||report.lifecycle!=="MAVEN_EXIT_CAPTURE_RELEASE_V1")fail();
  if(!Array.isArray(definition.cacheManifest)||definition.cacheManifest.length<1||definition.cacheManifest.length>4096)fail();
  const aliases=new Set<string>();let total=0,prior="";
  for(const file of definition.cacheManifest){
    if(!exactKeys(file,["path","bytes","sha256"])||typeof file.path!=="string"||file.path.length>256
        ||file.path.split("/").length>32||file.path.split("/").some((part:string)=>!/^[-A-Za-z0-9_.]+$/.test(part)||part==="."||part==="..")
        ||file.path<=prior||aliases.has(file.path.toLowerCase())||!Number.isSafeInteger(file.bytes)||file.bytes<0||file.bytes>67108864
        ||typeof file.sha256!=="string"||!/^[a-f0-9]{64}$/.test(file.sha256))fail();
    prior=file.path;aliases.add(file.path.toLowerCase());total+=file.bytes;if(total>268435456)fail();
  }
  if(canonical(definition)!==e.canonicalDefinition||definition.version!=="dsh-task-execution-v1"
      ||definition.adapterId!=="MAVEN_JUNIT_SOURCE_V1"||definition.projectId!==p.projectId
      ||definition.registryDigest!==p.registryDigest||definition.imageDigest!==p.imageDigest
      ||await digest(definition.executionPolicy)!==p.executionPolicyDigest
      ||JSON.stringify(definition.checkRefs)!==JSON.stringify(p.availableChecks.map(c=>c.id)))fail();
  for(let i=0;i<e.checks.length;i++){
    const c=e.checks[i],actual=p.availableChecks[i];
    if(c.checkId!==actual.id||c.timeoutSeconds!==actual.timeoutSeconds||c.minPassed!==actual.minPassed
        ||canonical(c.argv)!==canonical(actual.argv)||canonical(c.verifiers)!==canonical(actual.verifiers)
        ||c.checksDigest!==await digest([actual]))fail();
  }
  if(await digest({version:"dsh-task-execution-v1",sourceDigest:e.sourceDigest,definition,checks:e.checks})!==e.executionSpecDigest
      ||await digest(selection.selectedChecks)!==selection.checksDigest
      ||await digest({version:"task-check-selection-v2",previewDigest:p.previewDigest,checksDigest:selection.checksDigest,
        checkRefs:selection.checkRefs,execution:e})!==selection.selectionDigest)fail();
}
