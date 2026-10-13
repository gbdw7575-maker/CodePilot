package com.dsh.memory;

import com.dsh.storage.EventEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * L5 情景记忆摘要提取器（S2 新增）：从一组 EventEntity 提取 Run 的情景摘要与召回关键词。
 * <p>
 * 摘要策略（保持简单、通用、无基准特化）：
 * <ul>
 *   <li>失败原因：从 run.failed / run.cancelled 事件中提取 reason/message 字段</li>
 *   <li>修复动作：从 tool.completed 事件中提取工具名（write_file/apply_patch/run_command）</li>
 *   <li>用户反馈：从 input_applied 事件中提取输入摘要（前若干字符）</li>
 *   <li>验证结果：从 verification.completed 事件中提取退出码/测试数</li>
 * </ul>
 * 关键词复用 CJK 二元组分词 + 英文单词切分，去停用词，空格分隔便于 LIKE 召回。
 */
@Component
public class EpisodicMemoryExtractor {

    private static final int SUMMARY_MAX_CHARS = 200;
    private static final int INPUT_PREVIEW_CHARS = 60;
    private static final int MAX_KEYWORDS = 16;

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "and", "or", "but", "if", "then", "else", "for", "of", "to", "in",
            "on", "at", "by", "with", "from", "as", "it", "this", "that",
            "的", "了", "在", "是", "和", "与", "或", "为", "对", "中",
            "我", "你", "他", "她", "它", "们", "上", "下", "不", "也", "都",
            "请", "把", "让", "使", "被", "给"
    );

    /** 从一组事件提取摘要 + 关键词；返回 [summary, keywords] 长度=2 数组。 */
    public Extraction extract(List<EventEntity> events, String finalState, String verificationStatus) {
        StringBuilder summary = new StringBuilder();
        Set<String> keywords = new LinkedHashSet<>();

        for (EventEntity e : events) {
            String type = e.getType();
            String envelope = e.getEnvelopeJson() == null ? "" : e.getEnvelopeJson();
            switch (type) {
                case "run.failed", "run.cancelled" -> {
                    String reason = extractJsonField(envelope, "reason");
                    if (reason != null && !reason.isBlank()) {
                        appendField(summary, "失败原因", truncate(reason, INPUT_PREVIEW_CHARS));
                        collectKeywords(reason, keywords);
                    }
                    String message = extractJsonField(envelope, "message");
                    if (message != null && !message.isBlank()) {
                        appendField(summary, "失败信息", truncate(message, INPUT_PREVIEW_CHARS));
                        collectKeywords(message, keywords);
                    }
                }
                case "tool.completed" -> {
                    String toolName = extractJsonField(envelope, "toolName");
                    if (toolName != null && !toolName.isBlank()) {
                        appendField(summary, "工具", toolName);
                        keywords.add(toolName);
                    }
                }
                case "run.input_applied" -> {
                    String input = extractJsonField(envelope, "input");
                    if (input != null && !input.isBlank()) {
                        appendField(summary, "用户输入", truncate(input, INPUT_PREVIEW_CHARS));
                        collectKeywords(input, keywords);
                    }
                }
                case "verification.completed" -> {
                    String exitCode = extractJsonField(envelope, "exitCode");
                    if (exitCode != null) {
                        appendField(summary, "验证退出码", exitCode);
                    }
                    String testPassed = extractJsonField(envelope, "passed");
                    if (testPassed != null) {
                        appendField(summary, "测试通过", testPassed);
                    }
                }
                case "run.completed" -> {
                    String state = extractJsonField(envelope, "state");
                    if (state != null) {
                        appendField(summary, "终态", state);
                    }
                }
                default -> {
                    // 其他事件类型不提取（保持摘要精简）
                }
            }
        }

        if (summary.length() == 0) {
            summary.append("无显著事件");
        }
        if (finalState != null) {
            keywords.add(finalState);
        }
        if (verificationStatus != null) {
            keywords.add(verificationStatus);
        }

        String summaryText = truncate(summary.toString(), SUMMARY_MAX_CHARS);
        String keywordStr = String.join(" ", limit(keywords, MAX_KEYWORDS));
        return new Extraction(summaryText, keywordStr);
    }

    private void appendField(StringBuilder sb, String label, String value) {
        if (sb.length() > 0) {
            sb.append("；");
        }
        sb.append(label).append(":").append(value);
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private List<String> limit(Set<String> set, int max) {
        List<String> result = new ArrayList<>(set);
        return result.size() <= max ? result : result.subList(0, max);
    }

    /** 极简 JSON 字段提取（避免对每个事件类型建 record 反序列化；envelopeJson 结构稳定）。 */
    private String extractJsonField(String json, String field) {
        if (json == null || json.isBlank()) {
            return null;
        }
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length()) {
            return null;
        }
        char c = json.charAt(i);
        if (c == '"') {
            int end = json.indexOf('"', i + 1);
            if (end < 0) {
                return null;
            }
            return json.substring(i + 1, end);
        }
        // 数字或字面量：扫到下一个逗号或 }
        int end = i;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(i, end).trim();
    }

    /**
     * 关键词分词：CJK 按二元组分词，英文按非字母字符切分；去停用词、去数字串。
     * 与 TokenEstimator 的 CJK 判断保持一致（Character.UnicodeBlock 判断 CJK 范围）。
     */
    private void collectKeywords(String text, Set<String> sink) {
        if (text == null || text.isBlank()) {
            return;
        }
        int n = text.length();
        int i = 0;
        StringBuilder englishWord = new StringBuilder();
        while (i < n) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                // 先收尾英文单词
                flushEnglish(englishWord, sink);
                // CJK 二元组
                if (i + 1 < n && isCjk(text.charAt(i + 1))) {
                    String bigram = text.substring(i, i + 2);
                    if (!STOPWORDS.contains(bigram)) {
                        sink.add(bigram);
                    }
                    i += 2;
                } else {
                    String single = String.valueOf(c);
                    if (!STOPWORDS.contains(single)) {
                        sink.add(single);
                    }
                    i += 1;
                }
            } else if (Character.isLetterOrDigit(c)) {
                englishWord.append(c);
                i += 1;
            } else {
                flushEnglish(englishWord, sink);
                i += 1;
            }
        }
        flushEnglish(englishWord, sink);
    }

    private void flushEnglish(StringBuilder word, Set<String> sink) {
        if (word.length() == 0) {
            return;
        }
        String w = word.toString().toLowerCase();
        word.setLength(0);
        if (w.isBlank() || w.length() < 2 || STOPWORDS.contains(w)) {
            return;
        }
        // 全数字跳过（如 404 是工具/状态码语境的噪声）
        boolean allDigit = true;
        for (int i = 0; i < w.length(); i++) {
            if (!Character.isDigit(w.charAt(i))) {
                allDigit = false;
                break;
            }
        }
        if (allDigit) {
            return;
        }
        sink.add(w);
    }

    private boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    /** 提取结果：summaryText 与 keywords（空格分隔）。 */
    public record Extraction(String summaryText, String keywords) {
    }
}
