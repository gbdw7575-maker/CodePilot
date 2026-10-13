package com.dsh.agent;

import com.dsh.agent.model.ModelResponse;
import com.dsh.application.ArtifactStore;
import com.dsh.application.VerificationEvidenceContext;
import com.dsh.application.VerificationPlan;
import com.dsh.common.Digest;
import com.dsh.storage.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** C11：结构与来源检查不是语义裁判；仅显式开启时改变主 Run 收尾路径。 */
@Service
public class CompletionReviewService {
    private static final String PREFIX = "completion-review-state-v1-";
    public static final Set<String> READ_TOOLS = Set.of(
            "read_file", "search_code", "list_files", "git_status", "git_diff", "read_artifact");
    public enum Action { NONE, TOOLS, INVALID, STALE, READY, REWORK }
    public record Decision(Action action, String summary) { }
    public record State(int revision, String runId, String mode, int used, int maxResponses, String verificationId,
                        String candidateId, List<String> inputIds, String diagnostic, JsonNode report) { }
    public record EntryContext(String runId, VerificationEvidenceContext.Binding binding,
                               VerificationPlan plan) { }
    private final RunRepository runs;
    private final TurnRepository turns;
    private final ThreadRepository threads;
    private final ProjectRepository projects;
    private final MessageRepository messages;
    private final ArtifactRepository artifacts;
    private final VerificationRepository verifications;
    private final ArtifactStore store;
    private final VerificationEvidenceContext evidence;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final int limit;

    public CompletionReviewService(RunRepository runs, TurnRepository turns, ThreadRepository threads,
            ProjectRepository projects, MessageRepository messages, ArtifactRepository artifacts,
            VerificationRepository verifications, ArtifactStore store, VerificationEvidenceContext evidence,
            ObjectMapper mapper, @Value("${dsh.loop.completion-review-enabled:false}") boolean enabled,
            @Value("${dsh.loop.completion-review-max-responses:3}") int limit) {
        this.runs=runs; this.turns=turns; this.threads=threads; this.projects=projects;
        this.messages=messages; this.artifacts=artifacts; this.verifications=verifications;
        this.store=store; this.evidence=evidence; this.mapper=mapper; this.enabled=enabled;
        this.limit=limit >= 1 && limit <= 8 ? limit : 3;
    }

    public Map<String,Object> executionSettings() {
        return Map.of("loop.completionReviewEnabled",enabled,"loop.completionReviewMaxResponses",limit);
    }
    private boolean enabled(RunEntity run) {
        return enabled && (run.getRole()==null || "MAIN".equals(run.getRole()));
    }
    private String projectId(RunEntity run) {
        return threads.findById(turns.findById(run.getTurnId()).orElseThrow().getThreadId())
                .orElseThrow().getProjectId();
    }
    public State state(String runId) {
        var found=artifacts.findFirstByRunIdAndNameStartingWithOrderByNameDesc(runId,PREFIX);
        if (found.isEmpty()) return null;
        var run=runs.findById(runId).orElseThrow();
        var artifact=found.get();
        try {
            if (artifact.isDownloadable() || !projectId(run).equals(artifact.getProjectId()))
                throw new IllegalStateException("复核产物归属无效");
            var page=store.readTextPage(artifact.getId(),0,16384);
            byte[] bytes=page.text().getBytes(StandardCharsets.UTF_8);
            if (page.hasMore() || page.sizeBytes()!=artifact.getSizeBytes()
                    || !Digest.sha256Hex(bytes).equals(artifact.getSha256()))
                throw new IllegalStateException("复核产物封存字节无效");
            var state=mapper.readValue(bytes,State.class);
            if (!runId.equals(state.runId()) || state.revision()<1 || state.maxResponses()<1 || state.maxResponses()>8 || state.used()<0 || state.used()>state.maxResponses()
                    || state.inputIds()==null || !Set.of("ACTIVE","READY","DORMANT").contains(state.mode())
                    || !artifact.getName().equals(name(state.revision())))
                throw new IllegalStateException("复核产物结构无效");
            return state;
        } catch (Exception invalid) { throw new IllegalStateException("复核状态不可核验",invalid); }
    }
    private static String name(int revision) { return PREFIX+String.format(Locale.ROOT,"%08d",revision)+".json"; }
    private void save(RunEntity run, State state) {
        try {
            byte[] bytes=mapper.writeValueAsBytes(state);
            if (bytes.length>16384) throw new IllegalStateException("复核状态超过封存上限");
            store.save(run.getId(),projectId(run),name(state.revision()),"application/json",bytes,false);
        } catch (java.io.IOException invalid) { throw new IllegalStateException("复核状态封存失败",invalid); }
    }
    private boolean fresh(RunEntity run, State state) {
        if (state==null || state.verificationId()==null) return false;
        var verification=verifications.findById(state.verificationId()).orElse(null);
        return verification!=null && "PASSED".equals(verification.getStatus())
                && evidence.isFresh(run,verification);
    }
    public boolean active(String runId) {
        var state=state(runId);
        return state!=null && "ACTIVE".equals(state.mode()) && fresh(runs.findById(runId).orElseThrow(),state);
    }
    public boolean allowsTool(String runId,String tool) {
        var state=state(runId);
        return state==null || !"ACTIVE".equals(state.mode()) || READ_TOOLS.contains(tool);
    }
    public boolean satisfied(String runId) {
        var state=state(runId);
        if (state==null && !enabled) return true;
        var run=runs.findById(runId).orElseThrow();
        if (state==null && !enabled(run)) return true;
        return state!=null && "READY".equals(state.mode()) && fresh(run,state) && readyReportValid(state);
    }

    /** 安全边界重建：旧回复不再能引用已过期的验证，已用复核额度不重置。 */
    @Transactional
    public void invalidateAtBoundary(String runId) {
        var state=state(runId);
        if (state==null) return;
        var run=runs.findByIdForUpdate(runId).orElseThrow();
        if (state!=null && !"DORMANT".equals(state.mode()) && (!fresh(run,state) || "READY".equals(state.mode()) && !readyReportValid(state))) {
            save(run,new State(state.revision()+1,runId,"DORMANT",state.used(),state.maxResponses(),state.verificationId(),
                    state.candidateId(),state.inputIds(),"目标、计划、工作区或封存证据已改变；旧复核无效",null));
        }
    }
    public boolean exhausted(String runId) {
        var state=state(runId);
        return state!=null && state.used()>=state.maxResponses() && !"READY".equals(state.mode());
    }

    /** C13：两臂共用的计划提示；控制面摘要只在内部批次消息保存，不交给供应商。 */
    public EntryContext entryContext(RunEntity run) {
        if (run.getRole()!=null && !"MAIN".equals(run.getRole())) return null;
        try {
            var project=projects.findById(projectId(run)).orElseThrow();
            var plan=VerificationPlan.forRun(run, Path.of(project.getRootPath()));
            return new EntryContext(run.getId(),evidence.capture(run,plan),plan);
        } catch (RuntimeException unavailable) { return null; }
    }
    public String entryPrompt(EntryContext context) {
        if (context==null) return "";
        var plan=context.plan();
        String command=plan.commandSummary();
        if (command.length()>2048) command="（命令超过提示上限，请读取 .dsh/verify.yaml 的完整配置）";
        String version=plan.planVersion();
        if (version.length()>128) version=version.substring(0,128)+"…";
        return "\n\n## 当前有效验收计划\n工作目录=.；command="+command
                +(plan.defectFix()?"\npurpose=DEFECT_FIX；必须同一登记断言在基线失败、当前候选通过；未复现不能称为已修复。":"")
                +(plan.acceptanceDescription()!=null ? "\n用户登记验收要求="+plan.acceptanceDescription() : "")
                +(plan.reproduction()!=null ? "\n登记断言="+plan.reproduction().assertionId()
                    +"；文件="+plan.reproduction().fileName()+"；SHA-256="+plan.reproduction().digest()
                    +"；复现命令="+plan.reproduction().command() : "")
                +"\nexpectTests="+plan.expectTests()+"；timeoutSeconds="+plan.timeoutSeconds()+"；planVersion="+version
                +"\n完成候选修改后，用run_command在根目录单独执行上述完整命令；不要追加过滤输出管道、"
                +"重定向或其他命令冒充同一次验收。完整日志已由产物保存，无需tail隐藏退出状态。"
                +"验收失败应处理具体错误；通过也不证明需求全部覆盖。Harness仍需独立验证，复核启用时还需覆盖/未解决项。\n"
                +"缺陷修复应先建立可区分原问题的复现/回归检查，比较同项检查在修改前后的结果；"
                +"未复现或未覆盖的条件必须明确，不能用既有测试绿代替缺陷覆盖。"
                +"临时诊断及输出使用run_probe的临时目录，stdout/stderr由产物保存；"
                +"交付前用git_diff核对每个变更与需求的关系，不把临时日志加入补丁，也不删除用户原有文件。\n";
    }
    public JsonNode entryMarker(EntryContext context) {
        if (context==null) return mapper.nullNode();
        return mapper.createObjectNode().put("version","completion-entry-v1").put("runId",context.runId())
                .put("goalDigest",context.binding().goalDigest()).put("planDigest",context.binding().planDigest());
    }

    /** 仅最新完整配对批次；后续潜在副作用作废，纯读取/后端未执行结果不遮蔽。 */
    public String candidate(String runId) {
        var state=state(runId);
        if (!enabled && state==null) return null;
        var run=runs.findById(runId).orElseThrow();
        if ((!enabled(run) && state==null) || !"RUNNING".equals(run.getState()) || run.getWorkspaceRef()==null) return null;
        if (state!=null && !"DORMANT".equals(state.mode())) return null;
        var turn=turns.findById(run.getTurnId()).orElseThrow();
        var stored=messages.listByThread(turn.getThreadId()).stream()
                .filter(m->run.getTurnId().equals(m.getTurnId()))
                .sorted(Comparator.comparingLong(MessageEntity::getSequence)).toList();
        int head=stored.size()-1;
        while (head>=0 && "tool".equals(stored.get(head).getRole())) head--;
        if (head<0 || !"assistant_tool".equals(stored.get(head).getRole())) return null;
        var batch=stored.get(head);
        if (state!=null && batch.getId().equals(state.candidateId())) return null;
        try {
            var invocation=mapper.readTree(batch.getContent());
            var marker=invocation.path("completionEntry");
            var project=projects.findById(projectId(run)).orElseThrow();
            var plan=VerificationPlan.forRun(run, Path.of(project.getRootPath()));
            var binding=evidence.capture(run,plan);
            if (!"completion-entry-v1".equals(marker.path("version").asText())
                    || !runId.equals(marker.path("runId").asText())
                    || !binding.goalDigest().equals(marker.path("goalDigest").asText())
                    || !binding.planDigest().equals(marker.path("planDigest").asText())) return null;
            String expected=Digest.sha256Hex(plan.commandSummary().trim()+"\u0000.");
            var calls=invocation.path("calls");
            if (!calls.isArray() || calls.isEmpty() || stored.size()-head-1!=calls.size()) return null;
            var seen=new HashSet<String>();
            boolean matched=false;
            for (int i=0;i<calls.size();i++) {
                var call=calls.get(i);
                String callId=call.path("callId").asText(), name=call.path("name").asText();
                var result=mapper.readTree(stored.get(head+1+i).getContent());
                if (callId.isBlank() || !seen.add(callId) || !callId.equals(result.path("callId").asText())
                        || !name.equals(result.path("name").asText())) return null;
                var structured=result.path("structuredContent");
                boolean commandMatches="run_command".equals(name)
                        && "SUCCEEDED".equals(result.path("status").asText())
                        && "COMPLETED".equals(structured.path("termination").asText())
                        && structured.path("cleanupConfirmed").isBoolean() && structured.path("cleanupConfirmed").asBoolean()
                        && structured.path("exitCode").isIntegralNumber() && structured.path("exitCode").asInt()==0
                        && expected.equals(structured.path("commandDigest").asText());
                if (commandMatches) {
                    var arguments=mapper.readTree(call.path("arguments").asText());
                    // 结果与原调用双核对，不能只相信结果声称的摘要。
                    commandMatches=arguments.path("command").isTextual()
                            && plan.commandSummary().trim().equals(arguments.path("command").asText().trim())
                            && (!arguments.has("workdir") || arguments.path("workdir").isTextual()
                            && ".".equals(arguments.path("workdir").asText()));
                }
                if (commandMatches) matched=true;
                else if (!READ_TOOLS.contains(name)
                        && !("DENIED".equals(result.path("status").asText())
                        && result.path("notExecutedByHost").isBoolean() && result.path("notExecutedByHost").asBoolean())) matched=false;
            }
            return matched ? batch.getId() : null;
        } catch (Exception unrecognized) { return null; }
    }

    /** 在独立验证前持久消费批次；失败/重启不能让同一批次无限重验。 */
    @Transactional
    public boolean consumeCandidate(String runId,String candidateId) {
        var run=runs.findByIdForUpdate(runId).orElseThrow();
        if (!candidateId.equals(candidate(runId))) return false;
        var previous=state(runId);
        save(run,new State(previous==null?1:previous.revision()+1,runId,"DORMANT",
                previous==null?0:previous.used(),previous==null?limit:previous.maxResponses(),
                previous==null?null:previous.verificationId(),candidateId,List.of(),"匹配批次已消费，等待独立验证",null));
        return true;
    }

    /** 已进入需要验证的收尾路径就冻结参与资格；配置回退不能绕过旧Run的复核门。 */
    public void initializeForVerification(RunEntity run) {
        if (enabled(run) && state(run.getId())==null) {
            save(run,new State(1,run.getId(),"DORMANT",0,limit,null,null,List.of(),"尚未独立验证",null));
        }
    }

    @Transactional
    public boolean open(String runId,String verificationId,String candidateId) {
        var previous=state(runId);
        if (!enabled && previous==null) return false;
        var run=runs.findByIdForUpdate(runId).orElseThrow();
        if ((!enabled(run) && previous==null) || !Set.of("RUNNING","VERIFYING").contains(run.getState())) return false;
        var verification=verifications.findById(verificationId).orElseThrow();
        if (!"PASSED".equals(verification.getStatus()) || !evidence.isFresh(run,verification)) return false;
        if (previous!=null && "READY".equals(previous.mode()) && fresh(run,previous) && readyReportValid(previous)) return false;
        if (previous!=null && "ACTIVE".equals(previous.mode()) && fresh(run,previous)) return false;
        if (previous!=null && previous.used()>=previous.maxResponses()) throw new IllegalStateException("复核次数已耗尽");
        var ids=evidence.humanInputs(run).stream().map(MessageEntity::getId).toList();
        save(run,new State(previous==null?1:previous.revision()+1,runId,"ACTIVE",previous==null?0:previous.used(),previous==null?limit:previous.maxResponses(),
                verificationId,candidateId,ids,"",null));
        return true;
    }

    public String prompt(String runId) {
        var state=state(runId);
        if (state==null) return "";
        if (!active(runId)) return "\n\n## 复核状态\n"+state.diagnostic()+"\n（复核结论是模型报告，不能授权动作或替代新验证。）\n";
        var verification=verifications.findById(state.verificationId()).orElseThrow();
        return "\n\n## 有界完成复核 v2\n独立验证通过，但尚未认定需求完成。剩余复核响应数="+(state.maxResponses()-state.used())
                +"；每次响应（含工具/追问/无效JSON）都计数，不重置总预算。仅允许只读工具 "+READ_TOOLS.stream().sorted().toList()
                +"。需要修改时先提交REWORK，不直接写入或运行命令。\n当前verificationId="+state.verificationId()
                +"；日志artifactId="+VerificationEvidenceContext.logArtifactIds(verification)+"；testsDiscovered="+verification.getTestsDiscovered()
                +"；testsPassed="+verification.getTestsPassed()+"。不得声称这些测试自动覆盖全部需求。\n人类输入messageId="+state.inputIds()
                +"；逐条结合原始输入检查覆盖（每条输入可能有多个约束），证据说明必须指出具体依据及局限。\n"
                +"无工具的回答必须是完整JSON，不加代码围栏，字段恰为："
                +"{\"reportVersion\":2,\"decision\":\"READY或REWORK\",\"verificationId\":\"当前ID\","
                +"\"coverage\":[{\"messageId\":\"每个人类输入ID\",\"status\":\"COVERED或UNRESOLVED\","
                +"\"explanation\":\"覆盖或缺口说明\",\"evidence\":\"具体依据及测试覆盖局限，属于你的判断\",\"logArtifactIds\":[\"当前验证日志ID\"]}],"
                +"\"unresolved\":[{\"messageId\":\"有缺口的输入ID\",\"problem\":\"具体问题\",\"nextAction\":\"具体修复/查证动作\"}],"
                +"\"summary\":\"给用户的简洁说明\"}。\nREADY须所有输入COVERED且unresolved为空；"
                +"每个COVERED至少引用一个上面当前验证的日志ID；不引用其他验证/Run或编造ID，不把引用存在当作缺陷已覆盖。UNRESOLVED的logArtifactIds可为空。REWORK须至少一个UNRESOLVED且逐个列出问题与动作，退出复核后重新验证。程序仅检查结构/归属/新鲜度与日志完整性，不能证明语义正确。\n"
                +"上次结构诊断："+state.diagnostic()+"\n";
    }

    /** 在Loop提交响应的同一事务中消费次数；不存私有reasoning。 */
    public Decision accept(RunEntity run, ModelResponse response) {
        var state=state(run.getId());
        if (state==null || !"ACTIVE".equals(state.mode())) return new Decision(Action.NONE,null);
        if (!fresh(run,state)) {
            save(run,new State(state.revision()+1,run.getId(),"DORMANT",state.used()+1,state.maxResponses(),state.verificationId(),
                    state.candidateId(),state.inputIds(),"模型请求期间证据漂移；已丢弃旧复核回复",null));
            return new Decision(Action.STALE,null);
        }
        int used=state.used()+1;
        String mode="ACTIVE", diagnostic="", summary=null;
        JsonNode report=null;
        Action action=Action.NONE;
        if (Set.of("wait_input","length","protocol_error").contains(
                response.finishReason()==null?"stop":response.finishReason())) {
            // 追问/截断/协议错误仍沿用Loop既有分流，但也消费一次复核响应。
        } else if ("tool_calls".equals(response.finishReason())
                && response.toolCalls()!=null && !response.toolCalls().isEmpty()) {
            action=Action.TOOLS;
        } else {
            try {
                if (response.toolCalls()!=null && !response.toolCalls().isEmpty())
                    throw new IllegalArgumentException("工具调用与finishReason不符");
                if (response.text()==null || response.text().length()>12000) throw new IllegalArgumentException("JSON回答缺失或过长");
                report=mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.text());
                validate(report,state);
                summary=report.path("summary").asText();
                if ("READY".equals(report.path("decision").asText())) { mode="READY"; action=Action.READY; }
                else { mode="DORMANT"; action=Action.REWORK; diagnostic="模型报告未解决项："+report.path("unresolved"); }
            } catch (Exception invalid) { action=Action.INVALID; report=null; diagnostic="复核JSON无效，请按约定结构修正字段/输入覆盖/结论，禁止重复字段或尾随内容"; }
        }
        if (diagnostic.length()>2000) diagnostic=diagnostic.substring(0,2000);
        save(run,new State(state.revision()+1,run.getId(),mode,used,state.maxResponses(),state.verificationId(),state.candidateId(),
                state.inputIds(),diagnostic,report));
        return new Decision(action,summary);
    }
    private static void fields(JsonNode node, String... expected) {
        var actual=new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!node.isObject() || !actual.equals(Set.of(expected))) throw new IllegalArgumentException("字段不匹配");
    }
    private static String text(JsonNode node,String field,int max) {
        var value=node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length()>max)
            throw new IllegalArgumentException(field+"缺失或长度无效");
        return value.asText();
    }
    private boolean readyReportValid(State state) {
        try { validate(state.report(), state); return "READY".equals(state.report().path("decision").asText()); }
        catch (RuntimeException invalid) { return false; }
    }

    private void validate(JsonNode report,State state) {
        fields(report,"reportVersion","decision","verificationId","coverage","unresolved","summary");
        if (!report.path("reportVersion").isIntegralNumber() || !report.path("reportVersion").canConvertToInt()
                || report.path("reportVersion").asInt()!=2)
            throw new IllegalArgumentException("旧版复核报告需重新复核");
        var verification=verifications.findById(state.verificationId()).orElseThrow();
        if (!state.runId().equals(verification.getRunId())) throw new IllegalArgumentException("验证归属不符");
        var allowedLogs=VerificationEvidenceContext.logArtifactIds(verification);
        String decision=text(report,"decision",16);
        if (!Set.of("READY","REWORK").contains(decision) || !state.verificationId().equals(text(report,"verificationId",128)))
            throw new IllegalArgumentException("结论或验证ID不符");
        text(report,"summary",2000);
        var coverage=report.path("coverage"); var unresolved=report.path("unresolved");
        if (!coverage.isArray() || !unresolved.isArray()) throw new IllegalArgumentException("覆盖/未解决项必须为数组");
        var seen=new HashSet<String>(); var gaps=new HashSet<String>();
        for (var entry:coverage) {
            fields(entry,"messageId","status","explanation","evidence","logArtifactIds");
            String id=text(entry,"messageId",128), status=text(entry,"status",16);
            if (!seen.add(id) || !state.inputIds().contains(id) || !Set.of("COVERED","UNRESOLVED").contains(status))
                throw new IllegalArgumentException("覆盖项未知/重复或状态无效");
            text(entry,"explanation",1000); text(entry,"evidence",1000);
            var refs=entry.path("logArtifactIds");
            if (!refs.isArray() || refs.size()>8 || "COVERED".equals(status) && refs.isEmpty())
                throw new IllegalArgumentException("覆盖证据必须引用当前验证日志");
            var seenLogs=new HashSet<String>();
            for (var ref:refs) {
                if (!ref.isTextual() || !allowedLogs.contains(ref.asText()) || !seenLogs.add(ref.asText()))
                    throw new IllegalArgumentException("日志引用未知或重复");
            }
            if ("UNRESOLVED".equals(status)) gaps.add(id);
        }
        if (!seen.equals(new HashSet<>(state.inputIds()))) throw new IllegalArgumentException("没有覆盖全部当前人类输入");
        var listed=new HashSet<String>();
        for (var entry:unresolved) {
            fields(entry,"messageId","problem","nextAction");
            String id=text(entry,"messageId",128);
            if (!gaps.contains(id) || !listed.add(id)) throw new IllegalArgumentException("未解决项未知/重复");
            text(entry,"problem",1000); text(entry,"nextAction",1000);
        }
        if (!listed.equals(gaps) || "READY".equals(decision) && !gaps.isEmpty()
                || "REWORK".equals(decision) && gaps.isEmpty()) throw new IllegalArgumentException("结论与未解决项不一致");
    }
}
