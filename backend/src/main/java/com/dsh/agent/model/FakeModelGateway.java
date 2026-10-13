package com.dsh.agent.model;

import com.dsh.agent.control.CancellationToken;
import com.dsh.api.dto.Usage;
import com.dsh.common.IdGen;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 离线确定性 Fake Provider（无远程端点）。
 *
 * <p>按 model 名选择脚本，供 FakeLoop 验收三类可复现场景：</p>
 * <ul>
 *   <li>包含 {@code error}：直接抛供应商错误 → run.failed/PROVIDER_ERROR；</li>
 *   <li>包含 {@code tool}（如 fake-tool）：首轮返回一次 echo 工具调用，收到工具结果后给最终回答；</li>
 *   <li>包含 {@code waiting}（如 fake-waiting）：首轮提出追问进入 WAITING_INPUT，补充输入后给最终回答；</li>
 *   <li>其余：一轮纯回答（ANSWER_COMPLETE/NOT_REQUIRED）。</li>
 * </ul>
 */
@Component
public class FakeModelGateway implements ModelGateway {

    public static final String PROVIDER = "fake";

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public ModelResponse generate(ModelRequest request, CancellationToken token) {
        String model = request.model() == null ? "" : request.model().toLowerCase();
        String lastUserInput = lastUserText(request);

        if (model.contains("error")) {
            throw new ModelGatewayException("fake 网关确定性错误: model=" + request.model());
        }

        if (model.contains("batch-approval")) {
            ModelMessage batch = request.messages().stream()
                    .filter(m -> "assistant".equals(m.role()) && m.toolCalls() != null
                            && m.toolCalls().size() == 2)
                    .reduce((first, last) -> last).orElse(null);
            if (batch == null) {
                return new ModelResponse("", List.of(
                        new ModelToolCall(IdGen.next("call_"), "run_command", "{\"command\":\"echo ok\"}"),
                        new ModelToolCall(IdGen.next("call_"), "write_file",
                                "{\"path\":\"should-not-run.txt\",\"content\":\"unexpected\\n\"}")),
                        FinishReason.TOOL_CALLS, usage(0, request), IdGen.next("mresp_"));
            }
            boolean paired = batch.toolCalls().stream().allMatch(call -> request.messages().stream()
                    .anyMatch(m -> "tool".equals(m.role()) && call.callId().equals(m.toolCallId())));
            if (!paired) {
                throw new ModelGatewayException("fake batch-approval 缺少工具结果配对");
            }
            return answer("审批批次工具结果已完整配对。", request);
        }

        // MA-05 任务调度脚本（主模型状态机 + worker CODER 快慢任务；必须放在 tool/write 等
        // 泛匹配之前，ma05 模型名不含这些关键词）。
        if (model.contains("ma05")) {
            return ma05Response(request, token);
        }

        // MA-06 验收闸门脚本（worker CODER 侧：验收通过/证据缺失/验收条目未覆盖）。
        if (model.contains("ma06")) {
            return ma06Response(request, token);
        }

        // MA-07 依赖产物物化与可恢复集成脚本（worker CODER 侧：物化上游产物读取+产出）。
        if (model.contains("ma07")) {
            return ma07Response(request, token);
        }

        // DSH-010：多 Agent 编排离线脚本（子角色继承同一 profile，用角色 prompt 标记区分父子）。
        if (model.contains("orchestration")) {
            return orchestrationResponse(model, request);
        }
        if (model.contains("subagent")) {
            return subagentResponse(request);
        }
        if (model.contains("loop-forever")) {
            // 取消传播安全点测试：在模型调用处挂起（此时 begin 已提交 RUNNING），
            // 直到取消令牌（子令牌挂父链）触发；避免靠多轮 echo 占用 mr/tc 预算窗口造成竞态。
            long parkDeadline = System.currentTimeMillis() + 30_000;
            while (!token.isCancelled() && System.currentTimeMillis() < parkDeadline) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // 取消后返回无所谓内容：runLoop 下一安全点会先走级联取消落终态
            return answer("取消后不应采用的答复", request);
        }

        if (model.contains("tool")) {
            if (request.messages().stream().anyMatch(m -> "tool".equals(m.role()))) {
                return answer("已根据工具结果完成处理。", request);
            }
            String echoText;
            if (model.contains("big")) {
                // D-08 测试：echo 超长正文（> LARGE_TOOL_OUTPUT_CHARS），验证完整输出落 ArtifactStore
                echoText = "x".repeat(10000);
            } else {
                echoText = truncate(lastUserInput, 80);
            }
            String arguments = "{\"text\":" + jsonQuote(echoText) + "}";
            ModelToolCall call = new ModelToolCall(IdGen.next("call_"), "echo", arguments);
            return new ModelResponse("", List.of(call), FinishReason.TOOL_CALLS,
                    usage(0, request), IdGen.next("mresp_"));
        }

        if (model.contains("patch")) {
            // fake-patch：输入含 fix/修复/patch 关键词时确定性产出一次 apply_patch 创建 fix.txt
            // （新建文件，期望哈希 null），收到工具结果后给最终回答；
            // 否则纯回答（无补丁）。用于 DSH-008 评测在同一 config 下覆盖 PASS 与 NO_CHANGES 两路径。
            if (request.messages().stream().anyMatch(m -> "tool".equals(m.role()))) {
                return answer("已应用补丁，任务完成。", request);
            }
            String lower = lastUserInput.toLowerCase();
            boolean wantsFix = lower.contains("fix") || lower.contains("修复") || lower.contains("patch");
            if (wantsFix) {
                String patch = "--- /dev/null\n+++ b/fix.txt\n@@ -0,0 +1,1 @@\n+patched\n";
                String arguments = "{\"patch\":" + jsonString(patch)
                        + ",\"expectedFileHashes\":{\"fix.txt\":null}}";
                ModelToolCall call = new ModelToolCall(IdGen.next("call_"), "apply_patch", arguments);
                return new ModelResponse("", List.of(call), FinishReason.TOOL_CALLS,
                        usage(0, request), IdGen.next("mresp_"));
            }
            return answer("无需修复，直接回答：" + truncate(lastUserInput, 200), request);
        }

        if (model.contains("write")) {
            // fake-write：首轮调用 write_file；收到工具结果后给最终回答。
            // 输入含 "noop" 时覆盖已存在的 noop.txt（同内容，需 expectedFileHash）→ 写后无净变化；
            // 否则新建 written.txt → 工作区改动触发验证。
            if (request.messages().stream().anyMatch(m -> "tool".equals(m.role()))) {
                return answer("写入完成，任务结束。", request);
            }
            String lower = lastUserInput.toLowerCase();
            if (lower.contains("noop")) {
                String hash = lastUserInput.replaceFirst("(?i).*noop\\s+", "").trim();
                String arguments = "{\"path\":\"noop.txt\",\"content\":\"same\\n\","
                        + "\"expectedFileHash\":" + jsonString(hash) + "}";
                ModelToolCall call = new ModelToolCall(IdGen.next("call_"), "write_file", arguments);
                return new ModelResponse("", List.of(call), FinishReason.TOOL_CALLS,
                        usage(0, request), IdGen.next("mresp_"));
            }
            // D-16 fan-out 故障注入：任务文本含 "fanout-file <相对路径>"（可选 ":: <内容>"）
            // 时写指定文件，便于在同一父快照上构造独立产物 / 同文件不同内容冲突两类样本。
            java.util.regex.Matcher fanout = java.util.regex.Pattern
                    .compile("fanout-file\\s+(\\S+)(?:\\s*::\\s*(\\S.*))?")
                    .matcher(lastUserInput);
            if (fanout.find()) {
                String fanoutPath = fanout.group(1).trim();
                String fanoutContent = (fanout.group(2) == null ? "data" : fanout.group(2).trim())
                        + "\n";
                String arguments = "{\"path\":" + jsonString(fanoutPath)
                        + ",\"content\":" + jsonString(fanoutContent) + "}";
                ModelToolCall call = new ModelToolCall(IdGen.next("call_"), "write_file", arguments);
                return new ModelResponse("", List.of(call), FinishReason.TOOL_CALLS,
                        usage(0, request), IdGen.next("mresp_"));
            }
            String arguments = "{\"path\":\"written.txt\",\"content\":\"data\\n\"}";
            ModelToolCall call = new ModelToolCall(IdGen.next("call_"), "write_file", arguments);
            return new ModelResponse("", List.of(call), FinishReason.TOOL_CALLS,
                    usage(0, request), IdGen.next("mresp_"));
        }

        if (model.contains("waiting")) {
            long userRounds = request.messages().stream().filter(m -> "user".equals(m.role())).count();
            if (userRounds <= 1) {
                String question = "请补充更多信息，以便继续处理。";
                return new ModelResponse(question, List.of(), FinishReason.WAIT_INPUT,
                        usage(question.length(), request), IdGen.next("mresp_"));
            }
            return answer("信息已补充，处理完成。", request);
        }

        return answer("fake 回答：" + truncate(lastUserInput, 200), request);
    }

    private ModelResponse answer(String text, ModelRequest request) {
        return new ModelResponse(text, List.of(), FinishReason.STOP,
                usage(text.length(), request), IdGen.next("mresp_"));
    }

    /** DSH-010：run_subagent 脚本——主角色首轮派生 ARCHITECT 子 Agent，子角色直接给结论。 */
    private ModelResponse subagentResponse(ModelRequest request) {
        String userText = allUserText(request);
        boolean afterTool = request.messages().stream().anyMatch(m -> "tool".equals(m.role()));
        if (userText.contains("子 Agent")) {
            return answer("子 Agent 结论：任务已完成（隔离上下文产出）。", request);
        }
        if (afterTool) {
            return answer("已收到子 Agent 结果，主流程继续完成。", request);
        }
        String arguments = "{\"role\":\"ARCHITECT\",\"task\":"
                + jsonString("为加法函数给出实现方案") + "}";
        ModelToolCall call = new ModelToolCall(IdGen.next("call_"), "run_subagent", arguments);
        return new ModelResponse("", List.of(call), FinishReason.TOOL_CALLS,
                usage(0, request), IdGen.next("mresp_"));
    }

    /**
     * DSH-010：start_orchestration coding-review 脚本。
     * 主角色首轮发起编排；子角色按 prompt 标记分流：CODER 打补丁 orc.txt，
     * 其余角色直接结论；模型名含 fail 时 TESTER 确定性抛错（角色失败短路路径）。
     */
    private ModelResponse orchestrationResponse(String model, ModelRequest request) {
        String userText = allUserText(request);
        boolean afterTool = request.messages().stream().anyMatch(m -> "tool".equals(m.role()));

        if (userText.contains("架构师子 Agent")) {
            return answer("架构方案：新增 orc.txt 承载编排产物，改动点单一，风险低。", request);
        }
        // 注意："测试工程师子 Agent" 包含 "工程师子 Agent"，TESTER 判定必须在 CODER 之前
        if (userText.contains("测试工程师子 Agent")) {
            if (model.contains("fail")) {
                throw new ModelGatewayException("tester 确定性失败（DSH-010 角色失败短路）");
            }
            return answer("测试通过：orc.txt 内容正确，结论 PASS。", request);
        }
        if (userText.contains("工程师子 Agent")) {
            if (afterTool) {
                return answer("编码完成：orc.txt 已创建，自检通过。", request);
            }
            String patch = "--- /dev/null\n+++ b/orc.txt\n@@ -0,0 +1,1 @@\n+orchestrated\n";
            String arguments = "{\"patch\":" + jsonString(patch)
                    + ",\"expectedFileHashes\":{\"orc.txt\":null}}";
            return new ModelResponse("",
                    List.of(new ModelToolCall(IdGen.next("call_"), "apply_patch", arguments)),
                    FinishReason.TOOL_CALLS, usage(0, request), IdGen.next("mresp_"));
        }
        if (userText.contains("评审员子 Agent")) {
            return answer("APPROVE：改动最小、正确，无安全与需求偏差问题。", request);
        }

        if (afterTool) {
            return answer("编排已结束并汇合，主流程完成。", request);
        }
        String arguments = "{\"template\":\"coding-review\",\"task\":"
                + jsonString("完成 orc 修复任务") + "}";
        return new ModelResponse("",
                List.of(new ModelToolCall(IdGen.next("call_"), "start_orchestration", arguments)),
                FinishReason.TOOL_CALLS, usage(0, request), IdGen.next("mresp_"));
    }

    /**
     * MA-05 确定性调度脚本（AT-07/08/09）。
     *
     * <p>主模型按已发生的工具调用推进状态机：update_plan → dispatch_tasks → await_tasks
     * （停车/恢复后历史保留）→ 必要时第二批 dispatch → 最终回答。场景由用户原文标记选择：
     * MA05-AT07（两个独立任务真实重叠）、MA05-AT08（C 只依赖快任务 A，慢任务 B 仍在途时
     * C 已启动）、MA05-AT09（短超时窗口结束，任务未全部完成）。worker CODER 侧按任务目标
     * 中的 MA05WORKER-FAST/SLOW 标记即时答复或挂起固定时长；SLOW 挂起期间响应取消令牌，
     * 取消后返回，由 runLoop 安全点落子 Run CANCELLED。</p>
     */
    private ModelResponse ma05Response(ModelRequest request, CancellationToken token) {
        String userText = allUserText(request);

        // worker CODER 子 Run：任务目标含 worker 标记（主用户原文不含该标记）
        // MA-06：worker 须产出实际文件变更（patchArtifactId），否则验收 EVIDENCE_MISSING；
        // 摘要须含 "self-check" 以覆盖验收条目关键词。先 apply_patch 写文件，再最终回答。
        if (userText.contains("MA05WORKER-SLOW")) {
            if (!hasToolResult(request)) {
                return ma05WriteFileCall(request);
            }
            // 固定 1800ms：足以与快任务形成确定性重叠窗口，又不拖长验收；期间响应取消。
            long deadline = System.currentTimeMillis() + 1_800;
            while (!token.isCancelled() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return token.isCancelled()
                    ? answer("慢任务收到取消，退出。", request)
                    : answer("慢任务已完成（MA05WORKER-SLOW），self-check 通过。", request);
        }
        if (userText.contains("MA05WORKER-FAST")) {
            if (!hasToolResult(request)) {
                return ma05WriteFileCall(request);
            }
            return answer("快任务已完成（MA05WORKER-FAST），self-check 通过。", request);
        }

        boolean at08 = userText.contains("MA05-AT08");
        boolean at09 = userText.contains("MA05-AT09");
        long planCalls = ma05ToolCount(request, "update_plan");
        long dispatchCalls = ma05ToolCount(request, "dispatch_tasks");
        long awaitCalls = ma05ToolCount(request, "await_tasks");

        if (planCalls == 0) {
            String tasks = at08
                    ? ma05Task("A", "快任务 MA05WORKER-FAST：立即完成", "")
                      + "," + ma05Task("B", "慢任务 MA05WORKER-SLOW：固定挂起", "")
                      + "," + ma05Task("C", "下游快任务 MA05WORKER-FAST", "\"A\"")
                    : ma05Task("A", "快任务 MA05WORKER-FAST：立即完成", "")
                      + "," + ma05Task("B", "慢任务 MA05WORKER-SLOW：固定挂起", "");
            String arguments = "{\"goal\":\"MA05 调度场景计划\",\"tasks\":[" + tasks + "]}";
            return ma05ToolCall("update_plan", arguments, request);
        }

        String planId = ma05PlanId(request);
        if (dispatchCalls == 0) {
            String arguments = "{\"planId\":" + jsonQuote(planId)
                    + ",\"expectedRevision\":1,\"taskIds\":[\"A\",\"B\"],"
                    + "\"requestKey\":\"rk-ma05-" + (at08 ? "at08" : at09 ? "at09" : "at07") + "-ab\"}";
            return ma05ToolCall("dispatch_tasks", arguments, request);
        }

        if (at08) {
            if (awaitCalls == 0) {
                return ma05ToolCall("await_tasks",
                        "{\"taskIds\":[\"A\",\"B\"],\"waitMode\":\"ANY\"}", request);
            }
            if (dispatchCalls == 1) {
                String arguments = "{\"planId\":" + jsonQuote(planId)
                        + ",\"expectedRevision\":1,\"taskIds\":[\"C\"],"
                        + "\"requestKey\":\"rk-ma05-at08-c\"}";
                return ma05ToolCall("dispatch_tasks", arguments, request);
            }
            if (awaitCalls == 1) {
                return ma05ToolCall("await_tasks",
                        "{\"taskIds\":[\"A\",\"B\",\"C\"],\"waitMode\":\"ALL\"}", request);
            }
            return answer("MA05-AT08 汇合完成：C 只等待 A，未被慢任务 B 阻塞。", request);
        }

        if (awaitCalls == 0) {
            String timeoutArg = at09 ? ",\"timeoutMs\":200" : "";
            return ma05ToolCall("await_tasks",
                    "{\"taskIds\":[\"A\",\"B\"],\"waitMode\":\"ALL\"" + timeoutArg + "}", request);
        }
        if (at09) {
            return answer("MA05-AT09 观察完成：await 超时窗口结束（timedOut=true），慢任务仍在途。",
                    request);
        }
        return answer("MA05-AT07 汇合完成：两个独立任务并行执行后全部成功。", request);
    }

    private long ma05ToolCount(ModelRequest request, String name) {
        return request.messages().stream()
                .filter(m -> "assistant".equals(m.role()) && m.toolCalls() != null)
                .flatMap(m -> m.toolCalls().stream())
                .filter(c -> name.equals(c.name()))
                .count();
    }

    private String ma05PlanId(ModelRequest request) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("plan_[A-Za-z0-9_-]+").matcher(allText(request));
        if (!matcher.find()) {
            throw new IllegalStateException("ma05 脚本在工具结果中找不到 planId");
        }
        return matcher.group();
    }

    private String allText(ModelRequest request) {
        StringBuilder sb = new StringBuilder();
        request.messages().forEach(m -> {
            if (m.content() != null) {
                sb.append(m.content()).append('\n');
            }
        });
        return sb.toString();
    }

    private String ma05Task(String id, String goal, String quotedDeps) {
        return "{\"taskId\":\"" + id + "\",\"goal\":" + jsonString(goal)
                + ",\"dependsOn\":[" + quotedDeps + "],\"deliverable\":\"deliver-" + id
                + "\",\"acceptance\":[\"self-check-" + id + "\"],"
                + "\"executionMode\":\"DELEGATED\",\"required\":true}";
    }

    private ModelResponse ma05ToolCall(String name, String arguments, ModelRequest request) {
        return new ModelResponse("",
                List.of(new ModelToolCall(IdGen.next("call_"), name, arguments)),
                FinishReason.TOOL_CALLS, usage(0, request), IdGen.next("mresp_"));
    }

    private String allUserText(ModelRequest request) {
        StringBuilder sb = new StringBuilder();
        request.messages().stream()
                .filter(m -> "user".equals(m.role()) && m.content() != null)
                .forEach(m -> sb.append(m.content()).append('\n'));
        return sb.toString();
    }

    /** 确定性用量：只声明 KNOWN 非 null 字段，金额不冒充已知。 */
    private Usage usage(int outputChars, ModelRequest request) {
        int inputChars = request.messages().stream()
                .mapToInt(m -> m.content() == null ? 0 : m.content().length())
                .sum();
        return new Usage("KNOWN", 10 + inputChars, 5 + outputChars, null, null, null, null, null);
    }

    private String lastUserText(ModelRequest request) {
        return request.messages().stream()
                .filter(m -> "user".equals(m.role()))
                .reduce((a, b) -> b)
                .map(ModelMessage::content)
                .orElse("");
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String jsonQuote(String raw) {
        String escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    /** 完整 JSON 字符串转义（含换行/制表/回车），用于含多行补丁的参数。 */
    private String jsonString(String raw) {
        String escaped = raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    // ------------------------------------------------------- MA-06 worker 脚本

    /**
     * MA-06 验收闸门脚本（AT-10/AT-11，规范 §9）。
     *
     * <p>仅处理 worker CODER 子 Run 侧（主模型状态机复用 MA-05 的 ma05Response）。
     * 场景由任务目标中的标记选择：</p>
     * <ul>
     *   <li>{@code MA06WORKER-PASS}：首轮 apply_patch 写 task-output.txt，次轮回答含
     *       "self-check" → 验收通过 → SUCCEEDED（AT-10 正例）</li>
     *   <li>{@code MA06WORKER-NOEVIDENCE}：纯文字回答（含 "self-check"）但无产物变更
     *       → EVIDENCE_MISSING → FAILED（AT-10：worker 自述成功但证据缺失）</li>
     *   <li>{@code MA06WORKER-BADSUMMARY}：首轮 apply_patch 写文件，次轮回答不含
     *       "self-check" → VERIFICATION_FAILED → FAILED（AT-10：验收条目未覆盖）</li>
     * </ul>
     */
    private ModelResponse ma06Response(ModelRequest request, CancellationToken token) {
        String userText = allUserText(request);

        if (userText.contains("MA06WORKER-PASS")) {
            if (!hasToolResult(request)) {
                return ma05WriteFileCall(request);
            }
            return answer("MA06 验收通过：产物已生成，self-check 通过。", request);
        }
        if (userText.contains("MA06WORKER-NOEVIDENCE")) {
            return answer("MA06 任务完成（MA06WORKER-NOEVIDENCE），self-check 通过。", request);
        }
        if (userText.contains("MA06WORKER-BADSUMMARY")) {
            if (!hasToolResult(request)) {
                return ma05WriteFileCall(request);
            }
            return answer("MA06 任务完成（MA06WORKER-BADSUMMARY），但摘要缺少验收关键词。", request);
        }
        // 非 worker 标记：复用 MA-05 主模型状态机
        return ma05Response(request, token);
    }

    /** 检查请求中是否已含工具结果消息（用于 worker 两阶段：写文件 → 最终回答）。 */
    private boolean hasToolResult(ModelRequest request) {
        return request.messages().stream().anyMatch(m -> "tool".equals(m.role()));
    }

    /**
     * MA-07 worker CODER 脚本（AT-12 依赖产物物化 + AT-13 集成验收）。
     *
     * <ul>
     *   <li>{@code MA07WORKER-PASS}：首轮 apply_patch 写 task-output.txt（产出实际文件变更作为
     *       验收证据），次轮回答声明已读取 upstream/ 物化产物 + self-check 通过 → SUCCEEDED。
     *       集成阶段把各 worker 产物合并到父工作区 → APPLIED。</li>
     *   <li>{@code MA07WORKER-NOUPSTREAM}：worker 无上游依赖（inputRefs 空），直接产出文件。
     *       验证空 artifactRefs 物化路径不报错（ok 空操作）。</li>
     * </ul>
     *
     * <p>物化冲突场景（多上游同名产物）在 SubagentService.materialize 层即失败返回
     * INPUT_CONFLICT，不进入模型调用，故无需脚本分支。</p>
     */
    private ModelResponse ma07Response(ModelRequest request, CancellationToken token) {
        String userText = allUserText(request);

        if (userText.contains("MA07WORKER-PASS")) {
            if (!hasToolResult(request)) {
                return ma05WriteFileCall(request);
            }
            return answer("MA07 任务完成：已读取工作区 upstream/ 物化上游产物，"
                    + "产出 task-output.txt，self-check 通过。", request);
        }
        if (userText.contains("MA07WORKER-NOUPSTREAM")) {
            if (!hasToolResult(request)) {
                return ma05WriteFileCall(request);
            }
            return answer("MA07 任务完成（无上游依赖）：产出 task-output.txt，self-check 通过。", request);
        }
        // 非 worker 标记：复用 MA-06 主模型状态机
        return ma06Response(request, token);
    }

    /**
     * MA-05/MA-06 worker 通用：首轮 apply_patch 创建 task-output.txt（新建文件，期望哈希 null）。
     * 次轮由调用方给出最终回答。产出实际文件变更 → patchArtifactId 非空 → 作为验收证据。
     */
    private ModelResponse ma05WriteFileCall(ModelRequest request) {
        String patch = "--- /dev/null\n+++ b/task-output.txt\n@@ -0,0 +1,1 @@\n+done\n";
        String arguments = "{\"patch\":" + jsonString(patch)
                + ",\"expectedFileHashes\":{\"task-output.txt\":null}}";
        return new ModelResponse("",
                List.of(new ModelToolCall(IdGen.next("call_"), "apply_patch", arguments)),
                FinishReason.TOOL_CALLS, usage(0, request), IdGen.next("mresp_"));
    }
}
