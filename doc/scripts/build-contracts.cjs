// Documentation generator only. Does not implement or call the DSH service.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..', '项目文档');
const S = {};
const ref = name => ({ $ref: '#/components/schemas/' + name });
const str = (description, extra = {}) => ({ type: 'string', ...(description ? {description} : {}), ...extra });
const integer = (minimum = 0) => ({type:'integer',minimum});
const bool = {type:'boolean'};
const id = str('服务端分配的不透明标识；客户端不得解析', {minLength:1,maxLength:128,pattern:'^[A-Za-z0-9_-]+$'});
const text = str(null,{minLength:1,maxLength:100000,pattern:'\\S'});
const short = str(null,{minLength:1,maxLength:200});
// Java 21 String.isBlank whitespace; /u counts paired supplementary characters as code points.
const planVersion = str('完整原文，1～64 Unicode码点；不可全为Java空白、含NUL或未配对代理项；不裁剪/规范化', {
 minLength:1,maxLength:64,
 pattern:'^(?=[\\s\\S]*[^\\u0009-\\u000d\\u001c-\\u0020\\u1680\\u2000-\\u2006\\u2008-\\u200a\\u2028\\u2029\\u205f\\u3000])[^\\u0000\\uD800-\\uDFFF]+$'
});
const date = {type:'string',format:'date-time'};
const seq = str('十进制字符串；比较时用 BigInt，非 JavaScript Number',{pattern:'^(0|[1-9][0-9]*)$'});
const hash = str(null,{pattern:'^[a-f0-9]{64}$'});
const en = (...values) => ({type:'string',enum:values});
const arr = items => ({type:'array',items});
const nullable = schema => ({anyOf:[schema,{type:'null'}]});
const obj = (properties, required = Object.keys(properties)) => ({type:'object',properties,required,additionalProperties:false});
const jsonObject = {type:'object',additionalProperties:true};
const schema = (name, properties, required) => S[name] = obj(properties,required);
const page = name => schema(name+'Page',{items:arr(ref(name)),nextCursor:nullable(str())});
const stamp = {version:integer(1),createdAt:date,updatedAt:date};

const errorCodes = {
  VALIDATION_FAILED:400, INVALID_CURSOR:400, INVALID_INPUT:422, INPUT_TOO_LARGE:413,
  RESOURCE_NOT_FOUND:404, ORIGIN_DENIED:403, PERMISSION_DENIED:403, PROJECT_NOT_TRUSTED:403,
  PATH_OUTSIDE_WORKSPACE:403, CREDENTIAL_UNAVAILABLE:422, MODEL_CAPABILITY_UNVERIFIED:422,
  ACTIVE_TURN_EXISTS:409, RESOURCE_BUSY:409, INVALID_STATE:409, IDEMPOTENCY_CONFLICT:409,
  IDEMPOTENCY_IN_PROGRESS:409, PRECONDITION_REQUIRED:428, VERSION_CONFLICT:412,
  APPROVAL_STALE:409, APPROVAL_ALREADY_DECIDED:409, WORKSPACE_CONFLICT:409,
  EVENT_CURSOR_EXPIRED:410, MEMORY_DISABLED:409, RATE_LIMITED:429,
  PROVIDER_UNAVAILABLE:503, SERVICE_UNAVAILABLE:503, INTERNAL_ERROR:500
};
S.ErrorCode=en(...Object.keys(errorCodes));
schema('ApiError',{code:ref('ErrorCode'),message:str(),requestId:id,retryable:bool,details:jsonObject});
schema('Health',{status:en('UP','DEGRADED'),contractVersion:str(),timestamp:date});
schema('Project',{id,name:short,rootPath:str(),trusted:bool,...stamp});
schema('CreateProject',{name:short,rootPath:str('绝对路径；服务端校验 allowed roots 和真实路径',{minLength:1})});
schema('TrustRequest',{trusted:bool});
page('Project');
schema('ModelOptions',{thinking:en('disabled','enabled'),reasoningEffort:nullable(en('low','high','max')),maxOutputTokens:integer(1),streaming:bool});
schema('ModelProfileWrite',{name:short,provider:en('deepseek','fake','openai-compat'),model:str(null,{minLength:1}),baseUrl:str('受信任的端点；禁止任意重定向携带凭据',{format:'uri'}),credentialRef:nullable(short),options:ref('ModelOptions')});
schema('ModelProfile',{id,...S.ModelProfileWrite.properties,capabilityStatus:en('UNKNOWN','VERIFIED','FAILED'),...stamp});
S.ModelProfilePatch={...obj(S.ModelProfileWrite.properties,[]),minProperties:1};
page('ModelProfile');
schema('ModelValidationRequest',{level:en('CONNECTIVITY','TOOL_ROUNDTRIP')});
schema('ModelValidation',{profileId:id,level:en('CONNECTIVITY','TOOL_ROUNDTRIP'),passed:bool,checkedAt:date,checks:arr(obj({name:str(),passed:bool,message:str()})),usage:nullable(ref('Usage'))});
schema('Usage',{status:en('KNOWN','ESTIMATED','UNKNOWN'),inputTokens:nullable(integer()),outputTokens:nullable(integer()),cacheHitTokens:nullable(integer()),cacheMissTokens:nullable(integer()),cost:nullable(str('十进制定点字符串，不使用浮点累计',{pattern:'^[0-9]+(\\.[0-9]+)?$'})),currency:nullable(str(null,{pattern:'^[A-Z]{3}$'})),priceVersion:nullable(str())});
S.RunState=en('QUEUED','PREPARING','RUNNING','WAITING_APPROVAL','WAITING_INPUT','VERIFYING','CANCELLING','RECOVERING','BLOCKED','COMPLETED','FAILED','CANCELLED');
S.VerificationStatus=en('PENDING','PASSED','FAILED','UNVERIFIED','ENV_ERROR','NOT_REQUIRED');
S.StopReason=en('ANSWER_COMPLETE','VERIFICATION_PASSED','VERIFICATION_FAILED','VERIFICATION_UNAVAILABLE','VERIFICATION_STALE','COMPLETION_REVIEW_LIMIT','COMPLETION_REVIEW_REQUIRED','BUDGET_LIMIT','CONTEXT_LIMIT','USER_CANCELLED','PROVIDER_ERROR','TOOL_ERROR','ENVIRONMENT_ERROR','RECOVERY_REQUIRED');
S.CompletionBasis=en('VERIFICATION_PASSED','NO_CHANGE','NO_NET_CHANGE_AFTER_WRITE');
schema('Thread',{id,projectId:id,title:short,modelProfileId:id,activeTurnId:nullable(id),...stamp});
schema('CreateThread',{title:short,modelProfileId:id},['modelProfileId']);
S.ThreadPatch={...obj({title:short,modelProfileId:id},[]),minProperties:1};
page('Thread');
const assertionName=str(null,{minLength:1,maxLength:80,pattern:'^[A-Za-z][A-Za-z0-9_.-]{0,79}$'});
schema('DefectReproductionDefinition',{assertionId:assertionName,fileName:{...assertionName,pattern:'^(?!.*\\.\\.)[A-Za-z][A-Za-z0-9_.-]{0,79}$'},content:str('UTF-8 最多 16384 字节；不可空白/含 NUL',{minLength:1,maxLength:16384,pattern:'\\S','x-max-utf8-bytes':16384}),command:str('沙箱执行；DSH_PROBE_FILE 为登记源码路径',{minLength:1,maxLength:4096,pattern:'\\S'})});
S.RunVerificationRequirement={oneOf:[obj({purpose:{const:'GENERAL'}}),obj({purpose:{const:'DEFECT_FIX'},acceptanceDescription:str('用户审核的行为验收说明',{minLength:1,maxLength:2000,pattern:'\\S'}),defectReproduction:ref('DefectReproductionDefinition')})]};
schema('SubmitTurn',{input:text,verification:ref('RunVerificationRequirement'),projectPlanDigest:nullable(hash)},['input']);
schema('AppendTurnInput',{input:text});
schema('TestReportSpec',{format:en('MAVEN_SUREFIRE','GRADLE'),directories:{...arr(str('不同的工作区相对目录；不递归、不允许链接或通配符',{minLength:1,maxLength:256,pattern:'^(?!/)(?!.*(?:^|/)(?:\\.|\\.\\.|\\.git)(?:/|$))[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*$'})),minItems:1,maxItems:32,'x-unique-directories':true}});
schema('VerificationPlanView',{command:str(),expectTests:bool,timeoutSeconds:integer(1),planVersion,purpose:en('GENERAL','DEFECT_FIX'),acceptanceDescription:nullable(str()),defectReproduction:nullable(ref('DefectReproductionDefinition')),digest:hash,source:en('PROJECT_CONFIGURATION','RUN_REGISTRATION'),testReports:nullable(ref('TestReportSpec'))},['command','expectTests','timeoutSeconds','planVersion','purpose','acceptanceDescription','defectReproduction','digest','source']);
schema('TurnAccepted',{turnId:id,rootRunId:id,state:ref('RunState'),eventsAfterSequence:seq});
schema('Turn',{id,threadId:id,rootRunId:id,input:text,state:ref('RunState'),verificationStatus:ref('VerificationStatus'),stopReason:nullable(ref('StopReason')),...stamp});
schema('Message',{id,threadId:id,turnId:nullable(id),role:en('user','assistant'),content:str(),sequence:seq,createdAt:date});
page('Message');
schema('InputAccepted',{messageId:id,turnId:id,status:en('QUEUED_FOR_MODEL'),createdAt:date});
schema('BudgetView',{maxModelRequests:integer(1),modelRequestsUsed:integer(),maxToolCalls:integer(1),toolCallsUsed:integer(),maxCumulativeTokens:integer(1),settledTokens:nullable(integer()),reservedTokens:integer(),deadlineAt:date,usageStatus:en('KNOWN','ESTIMATED','UNKNOWN')});
schema('Run',{id,turnId:id,parentRunId:nullable(id),traceId:nullable(id),role:en('MAIN','ARCHITECT','CODER','TESTER','REVIEWER','ORCHESTRATOR'),state:ref('RunState'),verificationStatus:ref('VerificationStatus'),stopReason:nullable(ref('StopReason')),attempt:integer(1),workspaceRef:id,configDigest:hash,budget:ref('BudgetView'),usage:ref('Usage'),summary:nullable(str()),completionBasis:nullable(ref('CompletionBasis')),...stamp});
page('Run');
schema('ToolOutcome',{status:en('SUCCEEDED','FAILED','DENIED','CANCELLED','UNKNOWN','SKIPPED'),errorCode:nullable(str()),summary:str(),content:arr(ref('ContentBlock')),structuredContent:nullable(jsonObject),artifactIds:arr(id),truncated:bool,durationMs:integer(),exitCode:nullable({type:'integer'}),isError:bool,callId:id,source:str()});
S.ContentBlock={oneOf:[obj({type:{const:'text'},text:str()}),obj({type:{const:'artifact'},artifactId:id,mimeType:str(),description:str(),unsupported:bool})]};
schema('ToolCall',{id,runId:id,name:str(),definitionVersion:str(),argumentsSummary:str(),state:en('REQUESTED','WAITING_APPROVAL','RUNNING','SUCCEEDED','FAILED','DENIED','CANCELLED','UNKNOWN','SKIPPED'),result:nullable(ref('ToolOutcome')),createdAt:date,updatedAt:date});
page('ToolCall');
schema('Approval',{id,turnId:id,runId:id,toolCallId:id,toolName:str(),argumentsSummary:str(),parametersDigest:hash,workspaceDigest:hash,risk:en('LOW','MEDIUM','HIGH','CRITICAL'),reason:str(),scope:{const:'ONCE'},status:en('PENDING','APPROVED','DENIED','EXPIRED','INVALIDATED'),expiresAt:date,...stamp});
page('Approval');
schema('ApprovalDecision',{decision:en('APPROVE','DENY'),parametersDigest:hash,workspaceDigest:hash});
schema('DiffFile',{path:str(),status:en('ADDED','MODIFIED','DELETED','RENAMED'),oldPath:nullable(str()),binary:bool});
schema('Diff',{runId:id,baseDigest:hash,workspaceDigest:hash,files:arr(ref('DiffFile')),patch:str(),truncated:bool,artifactId:nullable(id)});
schema('Verification',{id,runId:id,planVersion:str(),workspaceDigest:hash,preVerificationDigest:nullable(hash),imageDigest:str(),commandSummary:str(),status:ref('VerificationStatus'),exitCode:nullable({type:'integer'}),testsDiscovered:nullable(integer()),testsPassed:nullable(integer()),testsSkipped:nullable(integer()),workspaceModified:bool,stale:bool,logArtifactIds:arr(id),startedAt:date,finishedAt:nullable(date)});
S.Verification.properties.status.description = 'GENERAL保持现有回归规则；显式DEFECT_FIX还须同一登记断言基线失败且当前候选通过，否则即使exitCode=0且普通测试通过也为FAILED；不证明登记断言完整覆盖用户目标';
S.Verification.properties.stale = {type:'boolean',description:'无法证明当前Run/Turn用户输入、有效验证计划（DEFECT_FIX含复现定义与基线清单摘要）、执行后工作区和封存证据一致时为true；旧未绑定或缺失/损坏证据也过期；日志归属、列表、大小、哈希和实际字节必须与封存版本一致'};
page('Verification');
schema('Checkpoint',{id,runId:id,workspaceDigest:hash,artifactIds:arr(id),createdAt:date,version:integer(1)});
page('Checkpoint');
schema('RestoreRequest',{expectedWorkspaceDigest:hash});
schema('RestoreResult',{checkpointId:id,previousWorkspaceDigest:hash,workspaceDigest:hash,invalidatedVerificationIds:arr(id),restoredAt:date});
schema('Artifact',{id,runId:nullable(id),projectId:id,name:str(),mediaType:str(),sizeBytes:integer(),sha256:hash,createdAt:date,downloadable:bool});
schema('RuleFile',{relativePath:str(),sha256:hash,content:str(),truncated:bool});
schema('Rules',{projectId:id,files:arr(ref('RuleFile')),effectiveVersion:hash});
schema('MemorySettings',{enabled:bool,indexMaxLines:integer(1),indexMaxUtf8Bytes:integer(1),version:integer(1)});
schema('UpdateMemorySettings',{enabled:bool});
schema('Memory',{id,projectId:id,category:en('user','feedback','project','reference'),title:short,content:str(null,{maxLength:25000}),sourceRefs:arr(str()),scope:{const:'PROJECT'},...stamp});
schema('MemoryUpdate',{category:en('user','feedback','project','reference'),title:short,content:str(null,{maxLength:25000}),sourceRefs:arr(str())});
page('Memory');
schema('ToolDefinition',{name:str(),description:str(),inputSchema:jsonObject,version:str(),category:en('CORE','EXTENSION','MCP','AGENT','ORCHESTRATION'),sideEffect:en('READ_ONLY','WORKSPACE_WRITE','EXECUTE','DELEGATE'),mounted:bool});
page('ToolDefinition');
// MA-10 计划/任务只读视图（GET /runs/{runId}/plans；规范 §11）：定义字段来自冻结计划，
// 运行态实时来自持久化行；不含隐藏推理与凭据。
S.TaskState=en('DRAFT','PENDING','READY','RUNNING','VERIFYING','WAITING_APPROVAL','BLOCKED','FAILED','CANCELLING','CANCELLED','SUCCEEDED');
const evtTaskId=str('计划内任务逻辑标识',{minLength:1,maxLength:64,pattern:'^[A-Za-z0-9_-]+$'});
schema('PlanInputRef',{taskId:evtTaskId,output:short});
schema('PlanResourceScope',{read:{...arr(str()),maxItems:64},write:{...arr(str()),maxItems:64}});
schema('PlanTaskResult',{attemptId:id,state:en('SUCCEEDED','FAILED','CANCELLED'),errorCode:nullable(str()),errorMessage:nullable(str('终态错误或验收失败摘要；不包含隐藏推理与凭据',{maxLength:4000})),artifactRefs:arr(id),patchArtifactId:nullable(id),outputDigest:nullable(hash),inputDigest:nullable(hash),verificationRef:nullable(id),modelRequestsUsed:integer(),toolCallsUsed:integer(),settledTokens:nullable(integer()),durationMs:integer()});
schema('PlanTaskRow',{taskId:evtTaskId,taskVersion:integer(1),goal:str(null,{minLength:1,maxLength:2000,pattern:'\\S'}),dependsOn:{...arr(evtTaskId),maxItems:6},inputRefs:{...arr(ref('PlanInputRef')),maxItems:8},deliverable:str(null,{minLength:1,maxLength:500,pattern:'\\S'}),acceptance:{...arr(str(null,{minLength:1,maxLength:200,pattern:'\\S'})),maxItems:8},resourceScope:nullable(ref('PlanResourceScope')),executionMode:en('DELEGATED','INLINE'),required:bool,state:ref('TaskState'),attemptId:nullable(id),childRunId:nullable(id),reasonCode:nullable(str('最近一次状态迁移原因码；事件 task.state.changed 为权威增量来源，快照首次读取前可能为 null')),evidenceValid:bool,result:nullable(ref('PlanTaskResult'))});
schema('PlanView',{planId:id,rootRunId:id,revision:integer(1),status:en('DRAFT'),goal:nullable(str(null,{maxLength:2000})),createdAt:date,updatedAt:date,tasks:{...arr(ref('PlanTaskRow')),maxItems:12}});
schema('PlanViewList',{plans:arr(ref('PlanView'))});
// M1C2b read-only available checks. This is never a confirmation/registration DTO.
const previewTaskId=str(null,{minLength:1,maxLength:64,pattern:'^[A-Za-z0-9_-]+$'});
schema('TaskCheckPreviewRequirement',{messageId:id,sequence:seq,content:str('逐字人类来源原文，不用模型摘要替换',{minLength:1,maxLength:100000})});
schema('TaskCheckPreviewInputRef',{taskId:previewTaskId,output:str(null,{minLength:1,maxLength:200})});
schema('TaskCheckPreviewScope',{read:{...arr(str(null,{minLength:1,maxLength:512})),maxItems:32},write:{...arr(str(null,{minLength:1,maxLength:512})),maxItems:32}});
schema('TaskCheckPreviewDefinition',{taskId:previewTaskId,taskVersion:integer(1),goal:str(null,{minLength:1,maxLength:2000}),acceptance:{...arr(str(null,{minLength:1,maxLength:200})),minItems:1,maxItems:8},deliverable:str(null,{minLength:1,maxLength:500}),dependsOn:{...arr(previewTaskId),maxItems:6,uniqueItems:true},inputRefs:{...arr(ref('TaskCheckPreviewInputRef')),maxItems:8},resourceScope:nullable(ref('TaskCheckPreviewScope')),executionMode:en('INLINE','DELEGATED'),required:bool});
schema('TaskCheckPreviewVerifier',{path:str('控制目录内相对路径，不是宿主真实路径',{minLength:1,maxLength:240}),sha256:hash});
schema('TaskCheckPreviewCheck',{id:previewTaskId,argv:{...arr(str(null,{minLength:1,maxLength:512})),minItems:1,maxItems:32},timeoutSeconds:{...integer(1),maximum:600},expectTests:{type:'boolean',enum:[true]},minPassed:{...integer(1),maximum:1000000},verifiers:{...arr(ref('TaskCheckPreviewVerifier')),minItems:1,maxItems:16}});
schema('TaskCheckPreview',{status:en('PREVIEW_ONLY'),projectId:id,rootRunId:id,turnId:id,planId:id,planRevision:integer(1),taskState:ref('TaskState'),task:ref('TaskCheckPreviewDefinition'),requirements:{...arr(ref('TaskCheckPreviewRequirement')),minItems:1,maxItems:32},taskDefinitionDigest:hash,requirementsDigest:hash,registryDigest:hash,imageDigest:hash,executionPolicyDigest:hash,availableChecks:{...arr(ref('TaskCheckPreviewCheck')),minItems:1,maxItems:32},previewDigest:hash});
schema('TaskCheckSelectionPreview',{status:en('SELECTED_PREVIEW_ONLY'),taskPreview:ref('TaskCheckPreview'),checkRefs:{...arr(previewTaskId),minItems:1,maxItems:8,uniqueItems:true},selectedChecks:{...arr(ref('TaskCheckPreviewCheck')),minItems:1,maxItems:8},checksDigest:hash,selectionDigest:hash});
schema('TaskCheckExecutionProfile',{checkId:previewTaskId,argv:{...arr(str(null,{minLength:1,maxLength:512})),minItems:1,maxItems:32},timeoutSeconds:{...integer(1),maximum:600},minPassed:{...integer(1),maximum:1000000},verifiers:{...arr(ref('TaskCheckPreviewVerifier')),minItems:1,maxItems:16},checksDigest:hash});
schema('TaskCheckExecutionView',{sourceDigest:hash,canonicalDefinition:str('完整Canonical v1 JSON文本，UTF-8最多512KiB；包含项目/目录/镜像/七字段策略/适配器/八项limits/报告方式/全缓存manifest/全目录检查引用；运行时严格解析和实际文件身份重读，无宿主绝对路径',{minLength:1,maxLength:524288}),checks:{...arr(ref('TaskCheckExecutionProfile')),minItems:1,maxItems:32},executionSpecDigest:hash});
S.TaskCheckSelectionPreview.properties.execution=ref('TaskCheckExecutionView'); // optional on the unchanged v1 path, never null
S.TaskCheckExecutionPreview=obj({...S.TaskCheckSelectionPreview.properties}); // complete route requires execution
schema('ConfirmTaskChecks',{taskVersion:integer(1),checkRefs:{...arr(previewTaskId),minItems:1,maxItems:8,uniqueItems:true},confirmed:{type:'boolean',enum:[true]}});
schema('EnableTaskAcceptanceMode',{mode:en('EXECUTABLE_CHECKS'),confirmed:{type:'boolean',enum:[true]}});
schema('TaskAcceptanceModeView',{rootRunId:id,mode:en('UNFROZEN','LEGACY_COVERAGE_ONLY','EXECUTABLE_CHECKS'),frozen:bool,optInAvailable:bool,executionAvailable:bool,frozenAt:nullable(date)});
schema('TaskCheckRegistration',{status:en('REGISTERED'),registrationId:str(null,{pattern:'^checkreg_[a-f0-9]{32}$'}),projectId:id,rootRunId:id,turnId:id,planId:id,taskId:previewTaskId,taskVersion:integer(1),checkRefs:{...arr(previewTaskId),minItems:1,maxItems:8,uniqueItems:true},checksDigest:hash,bindingDigest:hash,snapshotDigest:hash,observedPlanRevision:integer(1),recordedAt:date});
S.TaskCheckRegistration.properties.executionSpecDigest=hash; // v2 identity; omitted from historical v1 response

const eventCommon={eventId:id,projectId:id,threadId:id,turnId:nullable(id),runId:nullable(id),parentRunId:nullable(id),traceId:nullable(id),spanId:nullable(id),sequence:{...seq,pattern:'^[1-9][0-9]*$'},timestamp:date,schemaVersion:{const:'1.0'}};
const events={
 'run.state_changed':obj({from:nullable(ref('RunState')),to:ref('RunState'),version:integer(1)}),
 'run.completed':obj({state:{const:'COMPLETED'},verificationStatus:en('PASSED','NOT_REQUIRED'),completionBasis:ref('CompletionBasis'),summary:str(),version:integer(1)}),
 'run.failed':obj({state:{const:'FAILED'},reason:ref('StopReason'),message:str(),version:integer(1)}),
 'run.cancelled':obj({state:{const:'CANCELLED'},reason:{const:'USER_CANCELLED'},version:integer(1)}),
 'run.input_applied':obj({messageId:id,attempt:integer(1)}),
 'assistant.text.delta':obj({messageId:id,index:integer(),text:str()}),
 'assistant.message.completed':obj({messageId:id,content:str()}),
 'model.completed':obj({modelRequested:str(),modelReturned:nullable(str()),finishReason:str(),durationMs:integer(),usage:ref('Usage')}),
 'tool.started':obj({callId:id,name:str(),argumentsSummary:str()}),
 'tool.completed':obj({callId:id,name:str(),result:ref('ToolOutcome')}),
 'tool.discovery.completed':obj({query:str(),loadedNames:arr(str()),catalogVersion:str(),degraded:bool}),
 'approval.requested':obj({approvalId:id,toolCallId:id,expiresAt:date}),
 'approval.resolved':obj({approvalId:id,status:en('APPROVED','DENIED','EXPIRED','INVALIDATED')}),
 'memory.changed':obj({memoryId:id,action:en('CREATED','UPDATED','DELETED'),version:integer(1)}),
 'checkpoint.created':obj({checkpointId:id,workspaceDigest:hash}),
 'verification.completed':obj({verificationId:id,status:ref('VerificationStatus'),workspaceDigest:hash,preVerificationDigest:nullable(hash),workspaceModified:bool,stale:bool}),
 // MA-10 编排任务域（规范 §11；AT-17）：与状态推进同事务写入，前端按根 runId 过滤折叠。
 // payload 只含状态/引用/摘要/digest，不含隐藏推理、凭据或绝对路径。
 'plan.updated':obj({planId:id,revision:integer(1)}),
 'task.dispatched':obj({planId:id,taskId:evtTaskId,taskVersion:integer(1),attemptId:nullable(id),state:ref('TaskState')}),
 'task.state.changed':obj({planId:id,taskId:evtTaskId,taskVersion:integer(1),attemptId:nullable(id),childRunId:nullable(id),from:nullable(ref('TaskState')),to:ref('TaskState'),reasonCode:str()}),
 'task.result.ready':obj({planId:id,taskId:evtTaskId,taskVersion:integer(1),attemptId:id,childRunId:nullable(id),state:en('SUCCEEDED','FAILED','CANCELLED'),artifactRefs:arr(id),verificationRef:nullable(id),inputDigest:nullable(hash),outputDigest:nullable(hash)}),
 'run.waiting_tasks':obj({taskIds:{...arr(evtTaskId),minItems:1,maxItems:12},waitMode:en('ANY','ALL'),cursor:seq,waitUntil:date}),
 'integration.completed':obj({integrationId:id,state:{const:'APPLIED'},resultDigest:nullable(hash),message:str()}),
 'integration.failed':obj({integrationId:nullable(str('集成记录标识；父工作区租约缺失等未落库失败时为 null',{pattern:'^intg_[A-Za-z0-9_-]+$'})),state:en('CONFLICTS','RECOVERED_BLOCKED','FAILED'),resultDigest:nullable(hash),message:str()})
};
S.EventEnvelope={oneOf:[]};
for(const [type,payload] of Object.entries(events)) {
 const name='Event_'+type.replaceAll('.','_');
 schema(name,{...eventCommon,type:{const:type},payload});
 S.EventEnvelope.oneOf.push(ref(name));
}
schema('EventPage',{items:arr(ref('EventEnvelope')),nextAfterSequence:seq,hasMore:bool,highWatermark:seq});
S.WsClientFrame={oneOf:[obj({type:{const:'subscribe'},threadId:id,afterSequence:seq})]};
S.WsServerFrame={oneOf:[
 obj({type:{const:'ready'},threadId:id,highWatermark:seq}),
 obj({type:{const:'event'},event:ref('EventEnvelope')}),
 obj({type:{const:'caught_up'},threadId:id,throughSequence:seq}),
 obj({type:{const:'resync_required'},threadId:id,earliestAvailableSequence:seq,highWatermark:seq}),
 obj({type:{const:'error'},error:ref('ApiError')})
]};

const paths={};
const parameters={
 IdempotencyKey:{name:'Idempotency-Key',in:'header',required:true,description:'在作用域内稳定且唯一；24 小时内相同 key+规范请求返回原状态/响应，不重复副作用',schema:str(null,{minLength:8,maxLength:128})},
 IfMatch:{name:'If-Match',in:'header',required:true,description:'资源 ETag，例如 "v3"；不接受 *',schema:str(null,{pattern:'^"v[1-9][0-9]*"$'})},
 ClientHeader:{name:'X-DSH-Client',in:'header',required:true,description:'同源本地 UI 标记，不是身份凭据；配合 Origin/Host 校验及禁用跨域',schema:{type:'string',const:'local-ui'}},
 Cursor:{name:'cursor',in:'query',schema:str(),description:'不透明游标，与过滤条件绑定'},
 Limit:{name:'limit',in:'query',schema:{type:'integer',minimum:1,maximum:200,default:50}},
 AfterSequence:{name:'afterSequence',in:'query',schema:{...seq,default:'0'},description:'仅返回 sequence 大于该值的当前 thread 事件'},
 ThroughSequence:{name:'throughSequence',in:'query',schema:seq,description:'可选固定上界；首次响应的 highWatermark 可传入后续页，值须不超过服务端当前高水位'}
};
const pref=n=>({$ref:'#/components/parameters/'+n});
function operation(method,p,operationId,summary,request,response,status='200',opts={}){
 const params=[...p.matchAll(/\{([^}]+)\}/g)].map(m=>({name:m[1],in:'path',required:true,schema:id}));
 if(method!=='get'){params.push(pref('ClientHeader'));if(method==='post')params.push(pref('IdempotencyKey'));}
 if(opts.etag)params.push(pref('IfMatch'));
 if(opts.page)params.push(pref('Cursor'),pref('Limit'));
 if(opts.events)params.push(pref('AfterSequence'),pref('ThroughSequence'),pref('Limit'));
 const good={description:status==='202'?'请求已持久化并接受；不代表执行完成':'成功',headers:{'X-Request-Id':{schema:id},...(opts.versioned?{ETag:{schema:str(),description:'"v{version}"'}}:{})}};
 if(response)good.content={'application/json':{schema:ref(response)}};
 const bad={description:'统一错误；状态和 code 映射见 x-error-codes。错误返回不表示 Agent 业务执行成功。',headers:{'X-Request-Id':{schema:id}},content:{'application/json':{schema:ref('ApiError')}}};
 const op={operationId,summary,tags:[opts.tag||p.split('/')[1]],parameters:params,responses:{[status]:good,default:bad},'x-stage':opts.stage||'MVP',description:opts.description||summary};
 if(request)op.requestBody={required:true,content:{'application/json':{schema:ref(request)}}};
 if(!paths[p])paths[p]={};paths[p][method]=op;
}
operation('get','/health','getHealth','健康检查',null,'Health');
operation('get','/projects','listProjects','项目列表',null,'ProjectPage','200',{page:true});
operation('post','/projects','createProject','注册项目，不复制或执行项目代码','CreateProject','Project','201',{versioned:true});
operation('get','/projects/{projectId}/verification-plan','previewVerificationPlan','读取可信项目的当前验收配置；不执行命令',null,'VerificationPlanView');
operation('get','/runs/{runId}/verification-plan','getRunVerificationPlan','读取 Run 当前有效验收要求；登记只写一次',null,'VerificationPlanView');
operation('get','/projects/{projectId}','getProject','项目详情',null,'Project','200',{versioned:true});
operation('put','/projects/{projectId}/trust','setProjectTrust','设置项目可信状态','TrustRequest','Project','200',{etag:true,versioned:true,description:'撤销信任立即阻止新写动作并使待审批失效；不会自动回滚已执行动作。'});
operation('get','/model-profiles','listModelProfiles','模型配置列表，不返回凭据值',null,'ModelProfilePage','200',{page:true});
operation('post','/model-profiles','createModelProfile','创建模型配置，仅读 credentialRef','ModelProfileWrite','ModelProfile','201',{versioned:true});
operation('get','/model-profiles/{modelProfileId}','getModelProfile','读取模型配置',null,'ModelProfile','200',{versioned:true});
operation('patch','/model-profiles/{modelProfileId}','patchModelProfile','更新配置；不改变已经冻结的运行配置','ModelProfilePatch','ModelProfile','200',{etag:true,versioned:true});
operation('post','/model-profiles/{modelProfileId}/validate','validateModelProfile','显式验证端点或多轮工具协议','ModelValidationRequest','ModelValidation','200',{description:'CONNECTIVITY 不发生成请求；TOOL_ROUNDTRIP 会消耗配置的模型额度，UI 必须明确展示。检测失败返回 passed=false；配置/输入/服务不可用返回统一错误。验证不执行项目代码。'});
operation('get','/projects/{projectId}/threads','listThreads','项目会话列表',null,'ThreadPage','200',{page:true});
operation('post','/projects/{projectId}/threads','createThread','创建会话','CreateThread','Thread','201',{versioned:true});
operation('get','/threads/{threadId}','getThread','读取会话',null,'Thread','200',{versioned:true});
operation('patch','/threads/{threadId}','patchThread','更新标题或非运行状态下的模型','ThreadPatch','Thread','200',{etag:true,versioned:true});
operation('get','/threads/{threadId}/messages','listMessages','读取用户可见消息；不返回推理协议字段',null,'MessagePage','200',{page:true});
operation('post','/threads/{threadId}/turns','submitTurn','提交原始自然语言输入','SubmitTurn','TurnAccepted','202',{description:'事务中保存用户消息、Turn、根 Run、配置快照及起始事件，提交后调度。可选 verification 为本 Run 冻结的用户验收登记；省略沿用项目配置。可选 projectPlanDigest 不匹配当前项目预览则 412，无登记或 Run 副作用。同 thread 同时只允许一个活动 Turn；相同幂等请求优先返回原结果，新请求冲突返回 409。'});
operation('get','/turns/{turnId}','getTurn','读取轮次与根运行状态',null,'Turn','200',{versioned:true});
operation('post','/turns/{turnId}/inputs','appendTurnInput','运行中补充自然语言输入','AppendTurnInput','InputAccepted','202',{description:'仅接受活动 Turn，持久化后在安全模型边界生效；WAITING_INPUT 可唤醒，终态返回 409。原文本不改写；verification/projectPlanDigest 禁止在追加输入中携带，既有登记不可修改。新增需求使旧验证证据过期。'});
operation('get','/runs/{runId}','getRun','读取运行、预算及验收状态',null,'Run','200',{versioned:true});
operation('post','/runs/{runId}/cancel','cancelRun','申请取消并传播到子运行',null,'Run','202',{description:'已取消/已完成运行返回 200 和当前状态，其他运行返回 202/CANCELLING。完成清理后才进入 CANCELLED；清理不能确认时 BLOCKED/RECOVERY_REQUIRED。'});
paths['/runs/{runId}/cancel'].post.responses['200']={description:'运行已经终止；不重新执行取消',headers:{'X-Request-Id':{schema:id}},content:{'application/json':{schema:ref('Run')}}};
operation('post','/runs/{runId}/resume','resumeRun','恢复 BLOCKED 运行，预算不重置',null,'Run','202',{etag:true,description:'仅 BLOCKED 且恢复检查允许时接受。普通BLOCKED同runId attempt+1，保留预算与工作区。RECOVERY_REQUIRED先持久取消、核验全部检查并保留Docker名称围栏；成功后结束本代为CANCELLED/CHECK_RECOVERY_FENCED，释放活跃指针，不重放未知操作。未知仍409阻塞；终态禁止恢复。'});
operation('get','/runs/{runId}/plans','listPlans','根 Run 计划与任务权威快照（含证据/取消态）',null,'PlanViewList','200',{description:'MA-10：返回该根 Run 全部计划修订（最新在前），内嵌任务定义、权威状态、最新 attempt 结果与证据有效性。编排开关关闭时历史仍可读；run 存在但无计划返回空列表，run 不存在返回 404。CANCELLING 表示已请求取消、CANCELLED 表示已停止。'});
operation('get','/runs/{runId}/task-acceptance-mode','getTaskAcceptanceMode','读取根任务持久化验收模式',null,'TaskAcceptanceModeView','200',{stage:'EXTENSION',description:'真实MAIN根及所属Turn；不存在404。未冻结UNFROZEN；历史根沿旧模式读取、不自动升级。optInAvailable仅表示Host允许用户选择，不代表执行器就绪；executionAvailable表示已冻结强根的受控执行开关是否开放，默认false；逐任务v2登记仍须当前有效。关闭全局开关不改变已冻结强模式。损坏模式409拒绝执行。'});
paths['/runs/{runId}/task-acceptance-mode'].get.responses['200'].headers['Cache-Control']={schema:{type:'string',enum:['no-store']}};
operation('post','/runs/{runId}/task-acceptance-mode','enableTaskAcceptanceMode','用户明确冻结强验收模式','EnableTaskAcceptanceMode','TaskAcceptanceModeView','200',{stage:'EXTENSION',description:'仅本地可信HTTP/UI，严格confirmed=true及EXECUTABLE_CHECKS；无模型工具，不接受旧模式/自动降级。独立dsh.orchestration.executable-checks-enabled默认false，未开启503；只允许未派发/无attempt/全部DRAFT的活动真实根，已有旧模式或运行历史409。第一次成功后不可变，同强模式重放保留时间；HTTP幂等记录历史响应，当前可用性请GET重读。首次有效旧派发冻结LEGACY_COVERAGE_ONLY。强派发真实登记或当前完整绑定无效时整批EVIDENCE_MISSING拒绝，原因MISSING_REGISTRATION/INVALID_EVIDENCE/STALE_BINDING/EXECUTION_NOT_AVAILABLE写入已有错误详情，不新增错误码；受控dsh.orchestration.strong-runtime-enabled默认false；开启后v2当前绑定才可写dispatch/attempt并执行强worker，独立检查和完整当前凭据CAS通过才成功，不沿旧PASSED/关键词/patch通过。'});
operation('get','/runs/{runId}/plans/{planId}/tasks/{taskId}/acceptance-preview','previewTaskChecks','只读展示原始需求/任务定义与可信目录全部可选检查；尚未确认',null,'TaskCheckPreview','200',{description:'M1C2b：仅真实根Run及所属计划/任务；项目/目录由服务器解析，控制根须独立于所有项目和工作区。PREVIEW_ONLY不选择检查、不登记、不执行或放行派发。原文来源复用内部反馈封存校验；完整定义/有序来源/目录进入摘要，读取期间观察漂移返回412；损坏/不支持数据400、未配置503、未信任403、跨根/不存在404。需求最多32条/每条100000 Java字符/总262144 Java字符，超限413，不截断。完整目录1..32个可选检查，未来选定集合1..8另行确认；不能以预览替代CAS。'});
paths['/runs/{runId}/plans/{planId}/tasks/{taskId}/acceptance-preview'].get.responses['200'].headers.ETag={schema:str(null,{pattern:'^"task-check-preview-v1-[a-f0-9]{64}"$'}),description:'完整只读预览摘要；不是通用v版本ETag或已确认登记'};
paths['/runs/{runId}/plans/{planId}/tasks/{taskId}/acceptance-preview'].get.responses['200'].headers['Cache-Control']={schema:{type:'string',enum:['no-store']}};
operation('get','/runs/{runId}/plans/{planId}/tasks/{taskId}/acceptance-selection-preview','previewSelectedTaskChecks','根据实际需求预览选择检查并生成绑定预览；尚未确认',null,'TaskCheckSelectionPreview','200',{stage:'EXTENSION',description:'M1C2c只读：1..8个重复checkRef，严格唯一ID，禁止逗号拼接/未知参数/任意命令。必须用实际M1C2b ETag作为If-Match；缺失428，格式/选择无效400，完整任务/需求/目录漂移412。归属/信任/配置拒绝复用M1C2b。checkRefs按ID排序，定义保持argv/验证器数组顺序，checksDigest对齐M1C1；selectionDigest绑定完整需求预览和选定集合，不是确认/登记/派发授权或DB CAS。无模型工具或业务写入，完整目录漂移不自动削减集合。'});
const selectionOperation=paths['/runs/{runId}/plans/{planId}/tasks/{taskId}/acceptance-selection-preview'].get;
selectionOperation.parameters.push({name:'checkRef',in:'query',required:true,style:'form',explode:true,schema:{...arr(previewTaskId),minItems:1,maxItems:8,uniqueItems:true}},
 {name:'If-Match',in:'header',required:true,schema:str(null,{pattern:'^"task-check-preview-v1-[a-f0-9]{64}"$'}),description:'先读取实际任务需求预览；不接受通配符、弱ETag、通用v版本或选定预览ETag'});
selectionOperation.responses['200'].headers.ETag={schema:str(null,{pattern:'^"task-check-selection-v1-[a-f0-9]{64}"$'}),description:'选定集合的只读预览身份，尚未用户确认'};
selectionOperation.responses['200'].headers['Cache-Control']={schema:{type:'string',enum:['no-store']}};
operation('post','/runs/{runId}/plans/{planId}/tasks/{taskId}/acceptance-registration','confirmTaskChecks','用户明确确认精确检查集合并不可变登记','ConfirmTaskChecks','TaskCheckRegistration','200',{stage:'EXTENSION',description:'M1C2d2b：本地可信HTTP/UI确认，无模型工具；confirmed必须true，taskVersion与当前DRAFT匹配且无attempt，活动真实根及所属计划/任务。If-Match必须实际选定预览ETag，缺失428/格式400/源漂移412；目录前置及提交前实际重读，数据库需求追加/计划编辑/派发与确认协调，文件系统没有与DB共同原子事务。相同版本不同绑定409；同绑定保留首次登记/时间。Idempotency-Key重放首次状态/响应属于历史事实，不表示当前可派发或执行成功。尚未强模式/派发门/Verifier。'});
const registrationOperation=paths['/runs/{runId}/plans/{planId}/tasks/{taskId}/acceptance-registration'].post;
registrationOperation.parameters=registrationOperation.parameters.filter(p=>p.name!=='If-Match');
registrationOperation.parameters.push({name:'If-Match',in:'header',required:true,schema:str(null,{pattern:'^"task-check-selection-v1-[a-f0-9]{64}"$'})});
registrationOperation.responses['200'].headers.ETag={schema:str(null,{pattern:'^"task-check-registration-v1-[a-f0-9]{64}"$'})};
registrationOperation.parameters.find(p=>p.name==='If-Match').schema.pattern='^"task-check-selection-v[12]-[a-f0-9]{64}"$';
registrationOperation.responses['200'].headers.ETag.schema.pattern='^"task-check-registration-v[12]-[a-f0-9]{64}"$';
registrationOperation.description+=' M1D2b：v2选定ETag要求完整实际执行描述及所有缓存字节，登记绑定完整execution并返回executionSpecDigest；写前/提交前重读。v1响应/绑定字节保留，不自动补签旧登记，同版本v1与v2冲突409；只有新任务版本可重新登记。强派发和执行仍不可用。';
operation('get','/runs/{runId}/plans/{planId}/tasks/{taskId}/acceptance-execution-preview','previewTaskExecution','只读展示精确检查集合及完整实际执行描述',null,'TaskCheckExecutionPreview','200',{stage:'EXTENSION',description:'M1D2b：checkRef与If-Match沿用需求预览协议；同Host启动控制根及完整项目/工作区排除，完整执行描述与缓存实际字节验证和双读，禁止客户端提供命令/路径/策略/摘要。源变化412，不可用或损坏拒绝不降级v1。响应execution必须完整存在，含原始描述SHA、完整规范定义及全目录检查配置；selectionDigest v2覆盖全部execution，尚未确认登记或执行。'});
const executionOperation=paths['/runs/{runId}/plans/{planId}/tasks/{taskId}/acceptance-execution-preview'].get;
executionOperation.parameters.push({name:'checkRef',in:'query',required:true,schema:str(null,{pattern:'^[A-Za-z0-9_-]{1,64}$'}),description:'重复参数1～8项，不接受逗号拼接或未知参数'},
 {name:'If-Match',in:'header',required:true,schema:str(null,{pattern:'^"task-check-preview-v1-[a-f0-9]{64}"$'})});
executionOperation.responses['200'].headers.ETag={schema:str(null,{pattern:'^"task-check-selection-v2-[a-f0-9]{64}"$'})};
operation('get','/runs/{runId}/children','listChildRuns','直属子运行列表（含子预算分账）',null,'RunPage','200',{description:'MA-10：直属子 Run 一次返回（数量受 worker 并发与根预算有界），nextCursor 恒为 null；budget 中 settledTokens/reservedTokens 为父子统一预算口径。开关关闭时历史子 Run 仍可读。'});
operation('get','/runs/{runId}/tool-calls','listToolCalls','工具执行记录',null,'ToolCallPage','200',{page:true});
operation('get','/runs/{runId}/tools','listActiveTools','当前挂载的工具定义',null,'ToolDefinitionPage','200',{page:true,stage:'EXTENSION'});
operation('get','/projects/{projectId}/tools','listProjectTools','项目授权可见的工具目录，仅查询',null,'ToolDefinitionPage','200',{page:true,stage:'EXTENSION'});
operation('get','/runs/{runId}/diff','getRunDiff','最终/当前工作区相对基线差异',null,'Diff');
operation('get','/runs/{runId}/verifications','listVerifications','验证证据与过期标志',null,'VerificationPage','200',{page:true});
operation('get','/runs/{runId}/checkpoints','listCheckpoints','检查点列表',null,'CheckpointPage','200',{page:true});
operation('post','/checkpoints/{checkpointId}/restore','restoreCheckpoint','恢复本次 Agent 变更，不恢复执行','RestoreRequest','RestoreResult','200',{etag:true,description:'工作区无活动写入者；If-Match 是检查点版本，同时比较 expectedWorkspaceDigest；冲突返回 409。恢复前保存安全快照，只处理所属运行变更并使验证过期。'});
operation('get','/turns/{turnId}/approvals','listApprovals','轮次审批列表',null,'ApprovalPage','200',{page:true});
operation('post','/approvals/{approvalId}/decision','decideApproval','批准或拒绝一次具体动作','ApprovalDecision','Approval','200',{etag:true,versioned:true,description:'必须 PENDING 且未过期，参数/工作区 digest 匹配。只记录授权，实际执行仍复核状态与权限；相同幂等键先重放原响应。'});
operation('get','/threads/{threadId}/events','listEvents','事件补拉：按 thread sequence 升序',null,'EventPage','200',{events:true,description:'读到固定 highWatermark；afterSequence 不保留则 410，details 返回 earliestAvailableSequence 与 highWatermark。禁止任意跳过未应用事件更新游标。'});
operation('get','/artifacts/{artifactId}','getArtifact','受控产物元数据',null,'Artifact');
operation('get','/artifacts/{artifactId}/content','downloadArtifact','下载已授权且 downloadable 的产物',null,null);
paths['/artifacts/{artifactId}/content'].get.responses['200'].content={'application/octet-stream':{schema:{type:'string',format:'binary'}}};
paths['/artifacts/{artifactId}/content'].get.responses['200'].headers['Content-Disposition']={schema:str(),description:'attachment；服务端生成安全文件名'};
operation('get','/projects/{projectId}/rules','getRules','读取适用项目规则，不提供自动改写接口',null,'Rules');
operation('get','/projects/{projectId}/memory/settings','getMemorySettings','读取自动记忆开关',null,'MemorySettings','200',{versioned:true,stage:'EXTENSION'});
operation('put','/projects/{projectId}/memory/settings','setMemorySettings','启停后续自动记忆读取/写入','UpdateMemorySettings','MemorySettings','200',{etag:true,versioned:true,stage:'EXTENSION'});
operation('get','/projects/{projectId}/memories','listMemories','查看项目经验列表',null,'MemoryPage','200',{page:true,stage:'EXTENSION'});
operation('get','/projects/{projectId}/memories/{memoryId}','getMemory','读取经验详情',null,'Memory','200',{versioned:true,stage:'EXTENSION'});
operation('put','/projects/{projectId}/memories/{memoryId}','updateMemory','用户编辑经验，使用版本保护','MemoryUpdate','Memory','200',{etag:true,versioned:true,stage:'EXTENSION'});
operation('delete','/projects/{projectId}/memories/{memoryId}','deleteMemory','删除经验并更新索引',null,null,'204',{etag:true,stage:'EXTENSION'});

const api={openapi:'3.1.0',info:{title:'DSH 本地编程 Agent API',version:'0.1.0-design',description:'待实现接口契约；不代表已有服务。原始自然语言输入，单 Loop，按需编排。'},servers:[{url:'http://127.0.0.1:8080/api/v1',description:'拟定本地开发地址'}],security:[],paths,components:{schemas:S,parameters},'x-error-codes':errorCodes,'x-websocket':{url:'ws://127.0.0.1:8080/ws/v1/events',schema:'events.schema.json'},'x-local-access':'仅 loopback + 同源；写请求 X-DSH-Client 与 Origin/Host 校验，不引入登录接口，不授权远程访问。'};

function closure(names){
 const out={};function visit(name){if(out[name])return;out[name]=S[name];const walk=v=>{if(!v||typeof v!=='object')return;if(v.$ref?.startsWith('#/components/schemas/'))visit(v.$ref.split('/').at(-1));Object.values(v).forEach(walk)};walk(S[name]);}
 names.forEach(visit);return JSON.parse(JSON.stringify(out).replaceAll('#/components/schemas/','#/$defs/'));
}
const eventSchema={$schema:'https://json-schema.org/draft/2020-12/schema',title:'DSH WebSocket 客户端/服务端帧与持久化事件',description:'选择 $defs.WsClientFrame、WsServerFrame 或 EventEnvelope 验证；顶层允许三者之一。',anyOf:[{$ref:'#/$defs/WsClientFrame'},{$ref:'#/$defs/WsServerFrame'},{$ref:'#/$defs/EventEnvelope'}],$defs:closure(['WsClientFrame','WsServerFrame','EventEnvelope'])};
const defs=[];
const tool=(name,description,inputSchema,category='CORE',sideEffect='READ_ONLY',version='1.0.0')=>defs.push({name,description,inputSchema,version,category,sideEffect});
const relativePath=str('任务副本内相对路径；服务端校验真实父目录及符号链接',{minLength:1,maxLength:1024});
tool('list_files','查看授权任务副本目录；不知道文件位置时先查看或搜索',obj({path:relativePath,glob:str(),limit:{type:'integer',minimum:1,maximum:1000}},['path']));
tool('read_file','按行读取文本；大文件分段读取，返回来源哈希。同一 Run 内重复读取「字节未变且行区间已读过」的内容时只返回引用而不重复正文（省上下文）；历史被压缩后需要重新查看正文，或确需再取一次，传 forceFull=true；读取未读过的新行区间或文件已变更时总是返回正文',obj({path:relativePath,startLine:integer(1),endLine:integer(1),forceFull:{...bool,description:'true 时即使内容未变也重新返回正文（用于上下文压缩后重新查看）',default:false}},['path']));
tool('read_artifact','工具输出或验证日志被截断/压缩而需要更多证据时，按产物 ID 回读当前 Run 的公开 UTF-8 文本；不读取其他 Run 或隐藏产物。byteOffset 从 0 开始，续读使用返回的 nextOffset；maxBytes 请求上限 4..16384，单页实际最多 4096 字节以保证正文完整回灌，返回 maxBytes 为有效值；页末保持完整字符；hasMore 只描述已保存产物，不代表采集阶段丢失内容可恢复',obj({artifactId:str('当前 Run 返回的 art_* 引用',{minLength:5,maxLength:128,pattern:'^art_[A-Za-z0-9_-]{1,124}$'}),byteOffset:{type:'integer',minimum:0,maximum:9007199254740991,default:0},maxBytes:{type:'integer',minimum:4,maximum:16384,default:4096}},['artifactId']));
tool('search_code','有报错/符号关键词但不确定文件位置时使用；Docker 内 ripgrep 尊重 .gitignore，可用 includeGlobs/excludeGlobs 显式覆盖；零命中返回空匹配，范围不越任务副本；rg 不可用时仅无 glob 的 literal 模式回退受限字面扫描',obj({query:str('字面串或正则；literal 为默认且大小写敏感',{minLength:1,maxLength:200}),path:relativePath,mode:en('literal','regex'),limit:{type:'integer',minimum:1,maximum:200},includeGlobs:{...arr(str('要包含的文件 glob',{minLength:1,maxLength:200})),maxItems:20},excludeGlobs:{...arr(str('要排除的文件 glob',{minLength:1,maxLength:200})),maxItems:20}},['query']));
tool('apply_patch','已读取目标内容后应用受控补丁；原文或哈希不匹配则返回冲突',obj({patch:text,expectedFileHashes:{type:'object',additionalProperties:nullable(hash)}}),'CORE','WORKSPACE_WRITE');
tool('run_command','仅在 Docker 中执行项目命令；主机命令与凭据不可访问',obj({command:text,workdir:relativePath,timeoutSeconds:{type:'integer',minimum:1,maximum:600}},['command']),'CORE','EXECUTE');
tool('run_probe','一次性诊断用：源码登记为当前 Run 产物，在 Linux 沙箱临时目录执行，诊断输出也留在该临时目录，避免污染源码差异和测试发现。command 使用 Bash errexit+pipefail，未显式处理的失败（含管道前段）终止执行；条件分支或 || 可显式处理预期失败。command 用 $DSH_PROBE_FILE 引用脚本、$DSH_WORKSPACE 引用项目。正式回归测试使用 write_file/apply_patch；退出 0 仅是观察，不是独立验收。命令仍有工作区写权限，经过 EXECUTE 审批、预算和超时。源码最多 16384 UTF-8 字节',obj({fileName:str('安全单文件名，不允许目录或连续点',{minLength:1,maxLength:80,pattern:'^[A-Za-z][A-Za-z0-9_.-]{0,79}$'}),content:str('UTF-8 探针源码',{minLength:1,maxLength:16384}),command:str('临时目录中执行的 Linux shell 命令',{minLength:1,maxLength:4096}),timeoutSeconds:{type:'integer',minimum:1,maximum:600},allowRepeated:bool},['fileName','content','command']),'CORE','EXECUTE','1.1.0');
tool('git_status','查看当前任务副本的结构化变更状态',obj({}));
tool('git_diff','检查相对任务基线的修改，含新文件',obj({paths:arr(relativePath)},[]));
// MA-02 update_plan 双分支：legacy steps（展示清单）与 v2 结构化计划（落库草案，不启动执行），二者互斥
const planStep=obj({text:short,status:en('PENDING','IN_PROGRESS','DONE')});
const planLegacy=obj({steps:{...arr(planStep),minItems:1}});
const taskId=()=>str('计划内任务逻辑标识；由模型提供、服务端校验',{minLength:1,maxLength:64,pattern:'^[A-Za-z0-9_-]+$'});
const boundedArr=(items,max)=>({type:'array',items,maxItems:max});
const planInputRef=obj({taskId:taskId(),output:short});
const planResourceScope=obj({read:boundedArr(str(),64),write:boundedArr(str(),64)},['read','write']);
const planTask=obj({
  taskId:taskId(),
  goal:str(null,{minLength:1,maxLength:2000,pattern:'\\S'}),
  dependsOn:boundedArr(str(null,{minLength:1,maxLength:64,pattern:'^[A-Za-z0-9_-]+$'}),6),
  inputRefs:boundedArr(planInputRef,8),
  deliverable:str(null,{minLength:1,maxLength:500,pattern:'\\S'}),
  acceptance:boundedArr(str(null,{minLength:1,maxLength:200,pattern:'\\S'}),8),
  resourceScope:planResourceScope,
  executionMode:en('DELEGATED','INLINE'),
  required:bool
},['taskId','goal','dependsOn','deliverable','acceptance','executionMode','required']);
const planV2=obj({
  planId:str('更新时与 expectedRevision 同填；创建时省略，由服务端分配 plan_*',{minLength:1,maxLength:128,pattern:'^plan_[A-Za-z0-9_-]+$'}),
  expectedRevision:integer(1),
  goal:str(null,{minLength:1,maxLength:2000,pattern:'\\S'}),
  tasks:boundedArr(planTask,12)
},['tasks']);
tool('update_plan','记录可读步骤与进展；不会修改用户原文或验收规则。两种互斥形态：steps 为旧展示清单（写工作区 plan.json）；tasks 为结构化计划草案（planId/expectedRevision 更新，服务端落库并返回 revision，只创建/更新草案，不启动任何 worker）',{type:'object',oneOf:[planLegacy,planV2]},'CORE','WORKSPACE_WRITE');
tool('tool_search','核心能力不足时主动搜索扩展工具；无匹配可换查询',obj({query:str(null,{minLength:1,maxLength:500})}),'EXTENSION');
tool('run_subagent','有独立局部工作时委派可信角色，返回产物与证据',obj({role:en('ARCHITECT','CODER','TESTER','REVIEWER'),task:text,artifactRefs:arr(id)}),'AGENT','DELEGATE');
tool('start_orchestration','需要明确分工和汇合时使用；简单请求留在主 Loop，可携带现有产物中途升级',obj({goal:text,template:{const:'coding-review'},artifactRefs:arr(id)}),'ORCHESTRATION','DELEGATE');
// MA-05 任务调度四工具（规范 §5.1）：均为登记/读模型语义（sideEffect=READ_ONLY，不触发 DELEGATE
// 审批）；真实子 Run 派生与三级并发/预算裁决在 worker 领取后复用 SubagentService，不在工具内执行。
const ma05PlanId=str('当前根 Run 内已提交计划的 planId（expectedRevision 须与服务端当前修订一致）',{minLength:1,maxLength:128,pattern:'^plan_[A-Za-z0-9_-]+$'});
const ma05RequestKey=str('根 Run 范围内稳定幂等键；同键同参数重放原登记，同键异参数返回 REQUEST_KEY_CONFLICT',{minLength:8,maxLength:128,pattern:'^[A-Za-z0-9][A-Za-z0-9_-]{7,127}$'});
const ma05TaskIds={...arr(taskId()),minItems:1,maxItems:12};
tool('dispatch_tasks','把已提交计划中的 DELEGATED 任务原子登记派发：整批校验（计划归属/修订/任务存在/依赖已派发/执行模式/当前状态），任一不满足整批拒绝且不启动任何任务；登记立即返回 dispatchId，任务在后台就绪队列执行，用 await_tasks 等待终态',obj({planId:ma05PlanId,expectedRevision:integer(1),taskIds:ma05TaskIds,requestKey:ma05RequestKey}),'ORCHESTRATION');
tool('await_tasks','等待任务集合 ANY/ALL 到达终态；立即满足或 timeoutMs=0 时立即返回（timedOut=true 表示窗口结束而非失败），否则主循环持久化停车并在条件满足/超时后只回填一次结果；afterCursor 为上次返回的十进制游标，changes 只给游标后的状态迁移',obj({taskIds:ma05TaskIds,waitMode:en('ANY','ALL'),afterCursor:str('上次 await/read 返回的 cursor 十进制字符串；缺省从 0 开始',{minLength:1,maxLength:128,pattern:'^(0|[1-9][0-9]*)$'}),timeoutMs:{type:'integer',minimum:0,maximum:600000,description:'等待窗口毫秒；0 表示零等待探询；缺省取服务端配置默认值（上限 600000）'}},['taskIds','waitMode']),'ORCHESTRATION');
tool('read_task_result','读取任务当前权威状态与最新 attempt 的终态结果（状态/错误码/产物引用/用量）；回答实时来自持久化行，不用内存缓存',obj({taskId:taskId(),attemptId:id}),'ORCHESTRATION');
tool('cancel_tasks','登记取消任务并返回“已接受+当前状态”，不宣称已立即终止：未启动任务直接 CANCELLED；运行中任务置 CANCELLING 并在安全点终止后收敛；已终态任务幂等返回当前状态；不存在的任务逐项报告',obj({taskIds:ma05TaskIds,reason:str(null,{minLength:1,maxLength:500,pattern:'\\S'})}),'ORCHESTRATION');
const outcomeDefs=closure(['ToolOutcome']);
const toolSchemas={$schema:'https://json-schema.org/draft/2020-12/schema',title:'DSH 模型工具调用',description:'这些工具不是公开 HTTP 执行端点；超时、预算、工作区和权限由系统注入。',oneOf:defs.map(t=>obj({callId:id,name:{const:t.name},arguments:t.inputSchema})), $defs:outcomeDefs,'x-tools':defs};

const ts='2026-09-22T08:00:00Z', h='a'.repeat(64);
const unknownUsage={status:'UNKNOWN',inputTokens:null,outputTokens:null,cacheHitTokens:null,cacheMissTokens:null,cost:null,currency:null,priceVersion:null};
const run={id:'run_example',turnId:'turn_example',parentRunId:null,traceId:'trace_example',role:'MAIN',state:'QUEUED',verificationStatus:'PENDING',stopReason:null,attempt:1,workspaceRef:'workspace_example',configDigest:h,budget:{maxModelRequests:24,modelRequestsUsed:0,maxToolCalls:80,toolCallsUsed:0,maxCumulativeTokens:200000,settledTokens:null,reservedTokens:0,deadlineAt:'2026-09-22T08:20:00Z',usageStatus:'UNKNOWN'},usage:unknownUsage,summary:null,completionBasis:null,version:1,createdAt:ts,updatedAt:ts};
const exampleCases=[
 {schema:'SubmitTurn',valid:true,value:{input:'修复重复邮箱注册返回 500 的问题，期望返回 409。'}},
 {schema:'SubmitTurn',valid:false,value:{input:'   '}},
 {schema:'SubmitTurn',valid:false,value:{input:'修复问题',intent:'fix'}},
 {schema:'TurnAccepted',valid:true,value:{turnId:'turn_example',rootRunId:'run_example',state:'QUEUED',eventsAfterSequence:'0'}},
 {schema:'Run',valid:true,value:run},
 {schema:'ApprovalDecision',valid:true,value:{decision:'APPROVE',parametersDigest:h,workspaceDigest:h}},
 {schema:'ApprovalDecision',valid:false,value:{decision:'APPROVE_ALL',parametersDigest:h,workspaceDigest:h}},
 {schema:'ModelProfileWrite',valid:true,value:{name:'DeepSeek 开发',provider:'deepseek',model:'deepseek-flash',baseUrl:'https://api.deepseek.com',credentialRef:'DEEPSEEK_API_KEY',options:{thinking:'disabled',reasoningEffort:null,maxOutputTokens:8192,streaming:false}}},
 {schema:'ModelProfileWrite',valid:true,value:{name:'OpenAI 兼容网关',provider:'openai-compat',model:'gpt-4o-mini',baseUrl:'http://127.0.0.1:8081/v1',credentialRef:null,options:{thinking:'disabled',reasoningEffort:null,maxOutputTokens:8192,streaming:true}}},
 {schema:'ModelProfileWrite',valid:false,value:{apiKey:'example-forbidden-field'}},
 {schema:'Usage',valid:true,value:unknownUsage},
 {schema:'ApiError',valid:true,value:{code:'ACTIVE_TURN_EXISTS',message:'当前会话已有活动轮次',requestId:'req_example',retryable:false,details:{activeTurnId:'turn_example'}}},
 {schema:'WsClientFrame',valid:true,value:{type:'subscribe',threadId:'thread_example',afterSequence:'0'}},
 {schema:'WsClientFrame',valid:false,value:{type:'subscribe',threadId:'thread_example',afterSequence:0}},
 {schema:'WsServerFrame',valid:true,value:{type:'ready',threadId:'thread_example',highWatermark:'10'}},
 {schema:'EventEnvelope',valid:true,value:{schemaVersion:'1.0',eventId:'evt_example',projectId:'project_example',threadId:'thread_example',turnId:'turn_example',runId:'run_example',parentRunId:null,traceId:'trace_example',spanId:'span_example',sequence:'12',timestamp:ts,type:'tool.started',payload:{callId:'call_example',name:'search_code',argumentsSummary:'query=register'}}},
 {schema:'EventEnvelope',valid:false,value:{type:'run.completed',payload:{}}},
 {schema:'EventEnvelope',valid:true,value:{schemaVersion:'1.0',eventId:'evt_plan_1',projectId:'project_example',threadId:'thread_example',turnId:'turn_example',runId:'run_example',parentRunId:null,traceId:'trace_example',spanId:null,sequence:'20',timestamp:ts,type:'plan.updated',payload:{planId:'plan_example',revision:2}}},
 {schema:'EventEnvelope',valid:true,value:{schemaVersion:'1.0',eventId:'evt_dispatch_1',projectId:'project_example',threadId:'thread_example',turnId:'turn_example',runId:'run_example',parentRunId:null,traceId:'trace_example',spanId:null,sequence:'21',timestamp:ts,type:'task.dispatched',payload:{planId:'plan_example',taskId:'impl_fix',taskVersion:1,attemptId:null,state:'PENDING'}}},
 {schema:'EventEnvelope',valid:true,value:{schemaVersion:'1.0',eventId:'evt_cancel_1',projectId:'project_example',threadId:'thread_example',turnId:'turn_example',runId:'run_example',parentRunId:null,traceId:'trace_example',spanId:null,sequence:'22',timestamp:ts,type:'task.state.changed',payload:{planId:'plan_example',taskId:'impl_fix',taskVersion:1,attemptId:'att_example',childRunId:'run_child_example',from:'RUNNING',to:'CANCELLING',reasonCode:'CANCEL_REQUESTED'}}},
 {schema:'EventEnvelope',valid:true,value:{schemaVersion:'1.0',eventId:'evt_result_1',projectId:'project_example',threadId:'thread_example',turnId:'turn_example',runId:'run_example',parentRunId:null,traceId:'trace_example',spanId:null,sequence:'23',timestamp:ts,type:'task.result.ready',payload:{planId:'plan_example',taskId:'test_fix',taskVersion:1,attemptId:'att_example_2',childRunId:'run_child_example',state:'SUCCEEDED',artifactRefs:['art_example'],verificationRef:'ver_example',inputDigest:h,outputDigest:h}}},
 {schema:'EventEnvelope',valid:true,value:{schemaVersion:'1.0',eventId:'evt_waiting_1',projectId:'project_example',threadId:'thread_example',turnId:'turn_example',runId:'run_example',parentRunId:null,traceId:'trace_example',spanId:null,sequence:'24',timestamp:ts,type:'run.waiting_tasks',payload:{taskIds:['impl_fix','test_fix'],waitMode:'ALL',cursor:'7',waitUntil:'2026-09-22T08:10:00Z'}}},
 {schema:'EventEnvelope',valid:true,value:{schemaVersion:'1.0',eventId:'evt_integration_1',projectId:'project_example',threadId:'thread_example',turnId:'turn_example',runId:'run_example',parentRunId:null,traceId:'trace_example',spanId:null,sequence:'25',timestamp:ts,type:'integration.completed',payload:{integrationId:'intg_example',state:'APPLIED',resultDigest:null,message:'已应用 2 个任务的产物'}}},
 {schema:'EventEnvelope',valid:false,value:{schemaVersion:'1.0',eventId:'evt_bad_1',projectId:'project_example',threadId:'thread_example',turnId:'turn_example',runId:'run_example',parentRunId:null,traceId:'trace_example',spanId:null,sequence:'26',timestamp:ts,type:'task.state.changed',payload:{planId:'plan_example',taskId:'impl_fix',taskVersion:1,attemptId:null,childRunId:null,from:'RUNNING',reasonCode:'CANCEL_REQUESTED'}}},
 {schema:'EventEnvelope',valid:false,value:{schemaVersion:'1.0',eventId:'evt_bad_2',projectId:'project_example',threadId:'thread_example',turnId:'turn_example',runId:'run_example',parentRunId:null,traceId:'trace_example',spanId:null,sequence:'27',timestamp:ts,type:'task.result.ready',payload:{planId:'plan_example',taskId:'test_fix',taskVersion:1,attemptId:'att_example_2',childRunId:null,state:'DONE',artifactRefs:[],verificationRef:null,inputDigest:null,outputDigest:null}}},
 {schema:'PlanViewList',valid:true,value:{plans:[{planId:'plan_example',rootRunId:'run_example',revision:2,status:'DRAFT',goal:'修复重复邮箱注册返回 500',createdAt:ts,updatedAt:ts,tasks:[
  {taskId:'impl_fix',taskVersion:1,goal:'注册接口对重复邮箱返回 409',dependsOn:[],inputRefs:[],deliverable:'auth 模块补丁',acceptance:['重复邮箱注册返回 409'],resourceScope:{read:['src/auth'],write:['src/auth']},executionMode:'DELEGATED',required:true,state:'CANCELLING',attemptId:'att_example',childRunId:'run_child_example',reasonCode:'CANCEL_REQUESTED',evidenceValid:false,result:null},
  {taskId:'test_fix',taskVersion:1,goal:'补充重复邮箱注册测试',dependsOn:['impl_fix'],inputRefs:[{taskId:'impl_fix',output:'patch'}],deliverable:'测试补丁',acceptance:['新增测试通过'],resourceScope:null,executionMode:'DELEGATED',required:true,state:'SUCCEEDED',attemptId:'att_example_2',childRunId:'run_child_example',reasonCode:null,evidenceValid:true,result:{attemptId:'att_example_2',state:'SUCCEEDED',errorCode:null,errorMessage:null,artifactRefs:['art_example'],patchArtifactId:'art_patch_example',outputDigest:h,inputDigest:h,verificationRef:'ver_example',modelRequestsUsed:3,toolCallsUsed:5,settledTokens:1820,durationMs:4200}}
 ]}]}}
];
api.components.schemas.SubmitTurn.examples=[exampleCases[0].value];
api.components.schemas.TurnAccepted.examples=[exampleCases[3].value];
api.components.schemas.Run.examples=[run];
api.components.schemas.ApiError.examples=[exampleCases[11].value];
const catalog=Object.entries(paths).flatMap(([p,ops])=>Object.entries(ops).map(([m,o])=>({method:m.toUpperCase(),path:'/api/v1'+p,operationId:o.operationId,stage:o['x-stage'],summary:o.summary,request:o.requestBody?.content['application/json'].schema.$ref.split('/').at(-1)||'—',responses:Object.keys(o.responses).filter(c=>c!=='default')})));
const write=(name,value)=>fs.writeFileSync(path.join(root,name),JSON.stringify(value,null,2)+'\n','utf8');
exampleCases.push({schema:'StopReason',valid:true,value:'VERIFICATION_STALE'},{schema:'StopReason',valid:false,value:'STALE_SUCCESS'},{schema:'StopReason',valid:true,value:'COMPLETION_REVIEW_LIMIT'},{schema:'StopReason',valid:true,value:'COMPLETION_REVIEW_REQUIRED'},{schema:'StopReason',valid:false,value:'REVIEW_IMPLICIT_SUCCESS'});
const c29definition={assertionId:'sample',fileName:'check.sh',content:'echo diagnostic',command:'sh "$DSH_PROBE_FILE"'};
exampleCases.push({schema:'RunVerificationRequirement',valid:true,value:{purpose:'GENERAL'}},
 {schema:'RunVerificationRequirement',valid:true,value:{purpose:'DEFECT_FIX',acceptanceDescription:'修复重复值',defectReproduction:c29definition}},
 {schema:'RunVerificationRequirement',valid:false,value:{purpose:'DEFECT_FIX'}},
 {schema:'RunVerificationRequirement',valid:false,value:{purpose:'GENERAL',defectReproduction:c29definition}},
 {schema:'RunVerificationRequirement',valid:false,value:{purpose:'DEFECT_FIX',acceptanceDescription:' ',defectReproduction:c29definition}},
 {schema:'SubmitTurn',valid:true,value:{input:'修复重复值',verification:{purpose:'GENERAL'},projectPlanDigest:h}},
 {schema:'AppendTurnInput',valid:false,value:{input:'补充要求',verification:{purpose:'GENERAL'}}},
 {schema:'DefectReproductionDefinition',valid:false,value:{...c29definition,fileName:'../check.sh'}});
exampleCases.push({schema:'TestReportSpec',valid:true,value:{format:'MAVEN_SUREFIRE',directories:['a/target/surefire-reports','b/target/surefire-reports']}},
 {schema:'TestReportSpec',valid:true,value:{format:'GRADLE',directories:['a/build/test-results/test','b/build/test-results/integrationTest']}},
 {schema:'TestReportSpec',valid:false,value:{format:'UNKNOWN',directories:['build/test-results/test']}},
 {schema:'TestReportSpec',valid:false,value:{format:'MAVEN_SUREFIRE',directories:[]}},
 {schema:'TestReportSpec',valid:false,value:{format:'MAVEN_SUREFIRE',directories:['../outside']}},
 {schema:'TestReportSpec',valid:false,value:{format:'MAVEN_SUREFIRE',directories:['/tmp/report']}},
 {schema:'TestReportSpec',valid:false,value:{format:'MAVEN_SUREFIRE',directories:['target/**']}},
 {schema:'TestReportSpec',valid:false,value:{format:'MAVEN_SUREFIRE',directories:['target/.git/report']}});
const versionPreview={command:'echo ok',expectTests:false,timeoutSeconds:30,purpose:'GENERAL',acceptanceDescription:null,defectReproduction:null,digest:h,source:'PROJECT_CONFIGURATION'};
for(const [value,valid] of [['a'.repeat(64),true],['中'.repeat(64),true],['😀'.repeat(64),true],[' '+'v'.repeat(62)+' ',true],['\u00a0',true],['a'.repeat(65),false],['😀'.repeat(65),false],['a'.repeat(64)+' ',false],['',false],['\u2003',false],['v\u0000x',false],['\uD800',false],['\uDC00',false]])
 exampleCases.push({schema:'VerificationPlanView',valid,value:{...versionPreview,planVersion:value}});
const taskPreviewExample={status:'PREVIEW_ONLY',projectId:'project_example',rootRunId:'run_example',turnId:'turn_example',planId:'plan_example',planRevision:1,taskState:'DRAFT',task:{taskId:'A',taskVersion:1,goal:'实现登录',acceptance:['拒绝错误密码'],deliverable:'登录实现',dependsOn:[],inputRefs:[],resourceScope:null,executionMode:'DELEGATED',required:true},requirements:[{messageId:'msg_example',sequence:'9007199254740993',content:'保留用户原始需求\n与约束'}],taskDefinitionDigest:h,requirementsDigest:h,registryDigest:h,imageDigest:h,executionPolicyDigest:h,availableChecks:[{id:'login-check',argv:['python','/verifier/check.py'],timeoutSeconds:30,expectTests:true,minPassed:4,verifiers:[{path:'check.py',sha256:h}]}],previewDigest:h};
exampleCases.push({schema:'TaskCheckPreview',valid:true,value:taskPreviewExample},
 {schema:'TaskCheckPreview',valid:false,value:{...taskPreviewExample,status:'CONFIRMED'}},
 {schema:'TaskCheckPreview',valid:false,value:{...taskPreviewExample,registrationId:'reg_forged'}},
 {schema:'TaskCheckPreview',valid:false,value:{...taskPreviewExample,requirements:[]}},
 {schema:'TaskCheckPreview',valid:false,value:{...taskPreviewExample,availableChecks:[]}},
 {schema:'TaskCheckPreview',valid:false,value:{...taskPreviewExample,requirements:[{...taskPreviewExample.requirements[0],sequence:9007199254740993}]}},
 {schema:'TaskCheckPreview',valid:false,value:{...taskPreviewExample,availableChecks:[{...taskPreviewExample.availableChecks[0],expectTests:false}]}},
 {schema:'TaskCheckPreview',valid:false,value:{...taskPreviewExample,task:{...taskPreviewExample.task,acceptance:[]}}});
const selectedPreviewExample={status:'SELECTED_PREVIEW_ONLY',taskPreview:taskPreviewExample,checkRefs:['login-check'],selectedChecks:taskPreviewExample.availableChecks,checksDigest:h,selectionDigest:h};
exampleCases.push({schema:'TaskCheckSelectionPreview',valid:true,value:selectedPreviewExample},
 {schema:'TaskCheckSelectionPreview',valid:false,value:{...selectedPreviewExample,status:'CONFIRMED'}},
 {schema:'TaskCheckSelectionPreview',valid:false,value:{...selectedPreviewExample,registrationId:'reg_forged'}},
 {schema:'TaskCheckSelectionPreview',valid:false,value:{...selectedPreviewExample,checkRefs:[]}},
 {schema:'TaskCheckSelectionPreview',valid:false,value:{...selectedPreviewExample,checkRefs:['login-check','login-check']}},
 {schema:'TaskCheckSelectionPreview',valid:false,value:{...selectedPreviewExample,checkRefs:Array.from({length:9},(_,i)=>'check_'+i)}},
 {schema:'TaskCheckSelectionPreview',valid:false,value:{...selectedPreviewExample,checkRefs:['echo passed']}},
 {schema:'TaskCheckSelectionPreview',valid:false,value:{...selectedPreviewExample,selectedChecks:[]}},
 {schema:'TaskCheckSelectionPreview',valid:false,value:{...selectedPreviewExample,taskPreview:{...taskPreviewExample,requirements:[]}}});
const confirmExample={taskVersion:1,checkRefs:['login-check'],confirmed:true};
const registrationExample={status:'REGISTERED',registrationId:'checkreg_'+'a'.repeat(32),projectId:'project_one',rootRunId:'run_one',turnId:'turn_one',planId:'plan_one',taskId:'A',taskVersion:1,checkRefs:['login-check'],checksDigest:h,bindingDigest:h,snapshotDigest:h,observedPlanRevision:1,recordedAt:ts};
const executionExample={sourceDigest:h,canonicalDefinition:'{"version":"dsh-task-execution-v1"}',checks:taskPreviewExample.availableChecks.map(c=>({checkId:c.id,argv:c.argv,timeoutSeconds:c.timeoutSeconds,minPassed:c.minPassed,verifiers:c.verifiers,checksDigest:h})),executionSpecDigest:h};
const executionPreviewExample={...selectedPreviewExample,execution:executionExample};
exampleCases.push({schema:'TaskCheckExecutionPreview',valid:true,value:executionPreviewExample},
 {schema:'TaskCheckExecutionPreview',valid:false,value:selectedPreviewExample},
 {schema:'TaskCheckExecutionPreview',valid:false,value:{...executionPreviewExample,execution:null}},
 {schema:'TaskCheckExecutionView',valid:false,value:{...executionExample,executionSpecDigest:'invalid'}},
 {schema:'TaskCheckExecutionView',valid:false,value:{...executionExample,checks:[]}},
 {schema:'TaskCheckExecutionView',valid:false,value:{...executionExample,hostPath:'/tmp/control'}},
 {schema:'TaskCheckExecutionView',valid:false,value:{...executionExample,canonicalDefinition:123}},
 {schema:'TaskCheckRegistration',valid:true,value:{...registrationExample,executionSpecDigest:h}},
 {schema:'TaskCheckRegistration',valid:false,value:{...registrationExample,executionSpecDigest:null}});
exampleCases.push({schema:'ConfirmTaskChecks',valid:true,value:confirmExample},
 {schema:'ConfirmTaskChecks',valid:false,value:{...confirmExample,confirmed:false}},
 {schema:'ConfirmTaskChecks',valid:false,value:{...confirmExample,taskVersion:0}},
 {schema:'ConfirmTaskChecks',valid:false,value:{...confirmExample,checkRefs:[]}},
 {schema:'ConfirmTaskChecks',valid:false,value:{...confirmExample,checkRefs:['login-check','login-check']}},
 {schema:'ConfirmTaskChecks',valid:false,value:{...confirmExample,command:'echo passed'}},
 {schema:'TaskCheckRegistration',valid:true,value:registrationExample},
 {schema:'TaskCheckRegistration',valid:false,value:{...registrationExample,status:'PASSED'}},
 {schema:'TaskCheckRegistration',valid:false,value:{...registrationExample,registrationId:'fake'}},
 {schema:'TaskCheckRegistration',valid:false,value:{...registrationExample,checksDigest:'wrong'}});
exampleCases.push({schema:'EnableTaskAcceptanceMode',valid:true,value:{mode:'EXECUTABLE_CHECKS',confirmed:true}},
 {schema:'EnableTaskAcceptanceMode',valid:false,value:{mode:'LEGACY_COVERAGE_ONLY',confirmed:true}},
 {schema:'EnableTaskAcceptanceMode',valid:false,value:{mode:'EXECUTABLE_CHECKS',confirmed:false}},
 {schema:'EnableTaskAcceptanceMode',valid:false,value:{mode:'EXECUTABLE_CHECKS',confirmed:'true'}},
 {schema:'EnableTaskAcceptanceMode',valid:false,value:{mode:'EXECUTABLE_CHECKS',confirmed:true,command:'echo passed'}},
 {schema:'TaskAcceptanceModeView',valid:true,value:{rootRunId:'run_example',mode:'UNFROZEN',frozen:false,optInAvailable:false,executionAvailable:false,frozenAt:null}},
 {schema:'TaskAcceptanceModeView',valid:true,value:{rootRunId:'run_example',mode:'EXECUTABLE_CHECKS',frozen:true,optInAvailable:true,executionAvailable:false,frozenAt:'2026-10-03T00:00:00Z'}},
 {schema:'TaskAcceptanceModeView',valid:true,value:{rootRunId:'run_example',mode:'EXECUTABLE_CHECKS',frozen:true,optInAvailable:true,executionAvailable:true,frozenAt:'2026-10-04T00:00:00Z'}},
 {schema:'TaskAcceptanceModeView',valid:false,value:{rootRunId:'run_example',mode:'PASSED',frozen:true,optInAvailable:true,executionAvailable:false,frozenAt:'2026-10-03T00:00:00Z'}});
write('openapi.json',api);write('events.schema.json',eventSchema);write('tools.schema.json',toolSchemas);write('examples.json',{description:'正反例只验证契约，不表示运行态测试通过',cases:exampleCases});
fs.writeFileSync(path.join(root,'HTTP接口索引.md'),'# DSH HTTP 接口索引\n\n由 `scripts/build-contracts.cjs` 从同一契约生成。接口均为待实现设计；字段详见 [OpenAPI](openapi.json)，语义详见 [接口文档](接口文档.md)。\n\n| 方法 | 路径 | operationId | 请求 DTO | 成功状态 | 阶段 | 用途 |\n|---|---|---|---|---|---|---|\n'+catalog.map(x=>`| ${x.method} | \`${x.path}\` | \`${x.operationId}\` | ${x.request} | ${x.responses.join('/')} | ${x.stage} | ${x.summary} |`).join('\n')+'\n','utf8');
process.stdout.write(JSON.stringify({operations:catalog.length,schemas:Object.keys(S).length,eventTypes:Object.keys(events).length,tools:defs.length,examples:exampleCases.length})+'\n');
