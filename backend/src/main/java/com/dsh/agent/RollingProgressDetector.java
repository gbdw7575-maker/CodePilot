package com.dsh.agent;

import com.dsh.common.Digest;
import com.dsh.storage.MessageEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

/** P04: 从已提交转录重建有界观察窗口；不把模型自述或新 operationId 当作进展。 */
final class RollingProgressDetector {
    static final int WINDOW = 8;
    /** 窗口内失败命令达到这个条数即判定为"失败主导"（不再把它当有效探索）。 */
    static final int FAILURE_DOMINANT = 3;
    static final int LOOKBACK = 64;
    private static final int TEXT_LIMIT = 8000;
    private static final Set<String> VOLATILE = Set.of("operationId", "durationMs", "sandbox",
            "cleanupConfirmed", "readCount", "coveredRanges", "forceFullHint", "alreadyRead");
    private static final Pattern DIAGNOSTIC = Pattern.compile(
            "(?i)(fail|error|exception|traceback|assert|expected|passed|tests run|not found|skipped)");

    record Snapshot(int observations, int modificationCandidates, int newEvidence,
                    int diagnosticChanges, int unobservable, int failedCommands,
                    boolean lookbackLimited) {
        /**
         * <b>前缀稳定性（2026-10-06 修复）</b>：本段被拼进 system 消息，而
         * {@code prefixHash} 覆盖 system 内容（见 {@code LoopSteps.assemble}：
         * {@code CachePrefix.compute(messages.get(0).content(), tools)}）。
         * 此前把 observations / newEvidence / diagnosticChanges / unobservable
         * 等**每轮都在变**的计数直接内嵌进文本，导致本段一旦激活，
         * 每个请求 system 都变一次 ⇒ 前缀缓存每轮全部失效。
         * 实测（`multi-d19-sphinx-doc__sphinx-8459`）：本类 {@code WINDOW}=8，
         * 而前缀哈希的首次变化恰好落在第 7、8 个请求，之后反复横跳
         * （49 请求 12 次变化、9 个不同哈希且哈希复用 = 文本在「有提示/无提示」间切换）。
         * 现改为只保留定性结论（{@code signal}，共 2 种），数值不再进入文本。
         */
        String hint() {
            if (observations < WINDOW || modificationCandidates > 0) return null;
            String signal;
            String action;
            if (failedCommands >= FAILURE_DOMINANT) {
                // 2026-10-06：窗口内失败命令占多数时，"有探索证据"是假象——
                // `psf__requests-1142` 连跑 36 条失败命令（pip download / 翻 conda 包元数据 /
                // git reflog）排查环境，每条都算新证据，收敛提示一直咬不住，
                // 最终 51 请求 / 1,562,775 token、**0 次写操作**，官方判定 empty_patch。
                signal = "近期命令执行反复失败，且尚未产出任何修改候选。";
                action = "停止继续排查环境或重跑同类命令：用已经读到的证据直接产出最小改动并写入文件，"
                        + "再说明验证受阻的原因。";
            } else if (newEvidence == 0 && diagnosticChanges == 0) {
                signal = "近期未观察到新的修改候选、证据或诊断变化。";
                action = "请说明当前假设与可区分的下一步；如目标需要修改，聚焦已有证据产出并验证补丁；"
                        + "若无需修改请总结结论。";
            } else {
                signal = "近期有探索证据，但没有新的修改候选。";
                action = "请说明当前假设与可区分的下一步；如目标需要修改，聚焦已有证据产出并验证补丁；"
                        + "确有偶发故障或新输入可重跑，重跑仍计入原预算；若无需修改请总结结论。";
            }
            return "\n\n## 近期活动检查\n" + signal + action
                    + "假设是否收敛仍未知，修改候选也不等于已通过验证的补丁。\n";
        }
    }
    private record Signal(boolean modification, boolean evidence, boolean diagnostic, boolean unknown,
                          boolean failedCommand) {}

    static Snapshot analyze(List<MessageEntity> stored, String turnId, ObjectMapper mapper) {
        List<JsonNode> observations = new ArrayList<>();
        boolean limited = false;
        // 用户补充输入开启新的观察段；不同 Turn 不共享判定。原始转录不改写。
        for (int i = stored.size() - 1; i >= 0; i--) {
            MessageEntity message = stored.get(i);
            if (!turnId.equals(message.getTurnId())) continue;
            if ("user".equals(message.getRole())) break;
            if (!"tool".equals(message.getRole())) continue;
            if (observations.size() == LOOKBACK) { limited = true; break; }
            try { observations.add(mapper.readTree(message.getContent())); }
            catch (Exception malformed) { observations.add(mapper.nullNode()); }
        }
        Collections.reverse(observations);
        Set<String> evidence = new HashSet<>(), modifications = new HashSet<>(), diagnostics = new HashSet<>();
        Map<String, String> previousDiagnostics = new HashMap<>();
        Map<String, Coverage> readCoverage = new HashMap<>();
        List<Signal> signals = new ArrayList<>();
        for (JsonNode outcome : observations) {
            signals.add(classify(outcome, evidence, modifications, diagnostics,
                    previousDiagnostics, readCoverage));
        }
        int changes = 0, facts = 0, diagnostic = 0, unknown = 0, failures = 0;
        int start = Math.max(0, signals.size() - WINDOW);
        for (Signal s : signals.subList(start, signals.size())) {
            if (s.modification()) changes++;
            if (s.evidence()) facts++;
            if (s.diagnostic()) diagnostic++;
            if (s.unknown()) unknown++;
            if (s.failedCommand()) failures++;
        }
        return new Snapshot(signals.size() - start, changes, facts, diagnostic, unknown, failures, limited);
    }

    private static Signal classify(JsonNode n, Set<String> evidence, Set<String> modifications,
                                   Set<String> diagnostics, Map<String, String> previous,
                                   Map<String, Coverage> coverage) {
        if (n == null || !n.isObject()) return new Signal(false, false, false, true, false);
        String name = n.path("name").asText();
        String status = n.path("status").asText();
        JsonNode data = n.path("structuredContent");
        boolean succeeded = "SUCCEEDED".equals(status);
        if (Set.of("write_file", "apply_patch").contains(name) && succeeded) {
            String digest = data.path("workspaceDigest").asText();
            boolean known = data.path("changed").isBoolean() && !digest.isBlank();
            boolean fresh = !digest.isBlank() && modifications.add(digest);
            return new Signal(known && data.path("changed").asBoolean() && fresh, false, false, !known, false);
        }
        if ("read_file".equals(name) && succeeded) {
            String path = data.path("path").asText(), sha = data.path("sha256").asText();
            int first = data.path("startLine").asInt(), last = data.path("endLine").asInt();
            if (path.isBlank() || sha.isBlank() || first < 1 || last < first) return new Signal(false,false,false,true,false);
            boolean fresh = coverage.computeIfAbsent(path + "\0" + sha, key -> new Coverage()).add(first, last);
            return new Signal(false, fresh && !data.path("alreadyRead").asBoolean(), false, false, false);
        }
        if ("read_artifact".equals(name) && succeeded) {
            String sha = data.path("sha256").asText();
            long first = data.path("byteOffset").asLong(-1), next = data.path("nextOffset").asLong(-1);
            if (sha.isBlank() || first < 0 || next < first) return new Signal(false,false,false,true,false);
            boolean fresh = next > first && coverage.computeIfAbsent("artifact\0" + sha, key -> new Coverage()).add(first, next - 1);
            return new Signal(false, fresh, false, false, false);
        }
        if (Set.of("run_command", "run_probe").contains(name)) {
            if (!data.path("termination").asText().equals("COMPLETED") || !n.path("exitCode").isIntegralNumber()) {
                return new Signal(false,false,false,"UNKNOWN".equals(status), false);
            }
            boolean failed = n.path("exitCode").asInt() != 0;
            String text = text(n), diagnosticText = text.lines().filter(line -> DIAGNOSTIC.matcher(line).find())
                    .reduce("", (a, b) -> a + b + "\n");
            String signature = Digest.sha256Hex(n.path("exitCode").asText() + "\0"
                    + (diagnosticText.isBlank() ? text : diagnosticText));
            boolean fresh = !text.isBlank() && evidence.add("command\0" + signature);
            String command = data.path("commandDigest").asText();
            String old = command.isBlank() ? null : previous.put(command, signature);
            boolean freshDiagnostic = !command.isBlank() && diagnostics.add(command + "\0" + signature);
            boolean changed = old != null && !old.equals(signature) && !diagnosticText.isBlank()
                    && freshDiagnostic;
            // 2026-10-06：非零退出**仍按原口径计入证据**（改动它会推翻既有语义保证），
            // 但单独计数。窗口内失败命令达到 FAILURE_DOMINANT 时改走更强的提示态——
            // `psf__requests-1142` 连跑 36 条失败命令排查环境、0 次写操作、预算烧完没交卷。
            return new Signal(false, fresh, changed, false, failed);
        }
        if (succeeded && Set.of("search_code", "git_diff", "git_status", "read_task_result", "await_tasks").contains(name)) {
            String key = name + "\0" + canonical(data) + "\0" + text(n);
            return new Signal(false, evidence.add(Digest.sha256Hex(key)), false, false, false);
        }
        // echo、计划文字、未识别扩展结果不证明任务进展；拒绝/失败也不当作新证据。
        return new Signal(false, false, false, "UNKNOWN".equals(status), false);
    }

    private static String text(JsonNode outcome) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : outcome.path("content")) {
            if (!"text".equals(block.path("type").asText())) continue;
            String text = block.path("text").asText();
            sb.append(text, 0, Math.min(text.length(), Math.max(0, TEXT_LIMIT - sb.length())));
            if (sb.length() >= TEXT_LIMIT) break;
        }
        return sb.toString().replace("\r", "")
                .replaceAll("\\b\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z\\b", "<time>")
                .replaceAll("(?i)\\b\\d+(?:\\.\\d+)?\\s*(?:ms|seconds?|secs?)\\b", "<duration>")
                .replaceAll("(?i)(\\bin |\\btime elapsed: )\\d+(?:\\.\\d+)?s\\b", "$1<duration>").trim();
    }
    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            List<String> keys = new ArrayList<>(); node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            StringBuilder sb = new StringBuilder("{");
            for (String key : keys) if (!VOLATILE.contains(key)) sb.append(key.length()).append(':').append(key)
                    .append('=').append(canonical(node.get(key))).append(';');
            return sb.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (JsonNode item : node) sb.append(canonical(item)).append(';');
            return sb.append(']').toString();
        }
        return node.toString();
    }
    private static final class Coverage {
        private final List<long[]> ranges = new ArrayList<>();
        boolean add(long first, long last) {
            boolean fresh = ranges.stream().noneMatch(r -> r[0] <= first && r[1] >= last);
            ranges.add(new long[]{first,last}); ranges.sort(Comparator.comparingLong(r -> r[0]));
            for (int i = 1; i < ranges.size();) {
                long[] prior = ranges.get(i-1), current = ranges.get(i);
                if (current[0] <= prior[1] || (prior[1] < Long.MAX_VALUE && current[0] == prior[1]+1)) {
                    prior[1] = Math.max(prior[1],current[1]); ranges.remove(i);
                } else i++;
            }
            return fresh;
        }
    }
}
