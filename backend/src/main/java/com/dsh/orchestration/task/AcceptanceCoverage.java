package com.dsh.orchestration.task;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 验收条目覆盖判定（MA-11 §17 修复）。
 *
 * <p>MA-06 引入的宽松覆盖校验取「条目首个空格前的子串（无空格取前 8 字）」并要求其出现在
 * 子 Run 摘要中。真实模型对照（MA-11 §16）暴露出该口径会**误杀正确产物**：子 Agent 的摘要
 * 是「改动文件清单 + 验证方式」的自由文本，从未被要求逐字复述验收条目，于是
 * {@code slugify("Hello,} （在字符串字面量内部被空格切断）、{@code 非字母字符保持不}（中文散文
 * 前 8 字截断）一类关键词必然落空，任务被误判为 {@code VERIFICATION_FAILED}。
 * 实测：d02 两次 MULTI 试验的 5 个被判失败子任务，产物经重评分全部正确。</p>
 *
 * <p>本类保留两种口径，由 {@code dsh.orchestration.acceptance-coverage-mode} 选择：</p>
 * <ul>
 *   <li>{@link Mode#KEYWORD}：既有 MA-06 口径，**默认值**，行为逐字保持不变（回退用）。</li>
 *   <li>{@link Mode#IDENTIFIER}：取条目中的 ASCII 标识符（{@code [A-Za-z0-9_]{3,}}）做存在性
 *       判定——至少一个标识符出现即视为覆盖；条目若不含任何此类标识符（中文散文式条目，
 *       如「编译通过」）则不参与判定，不因「无法用关键词表达」而被误杀。</li>
 * </ul>
 *
 * <p>两种口径都只是**存在性兜底**，不做语义级判定；真实语义覆盖仍需后续切片评估。</p>
 */
final class AcceptanceCoverage {

    /** 覆盖判定口径。 */
    enum Mode {
        /** 既有 MA-06 口径（默认，行为不变）。 */
        KEYWORD,
        /** 修复口径：按 ASCII 标识符判定，散文式条目不参与判定。 */
        IDENTIFIER;

        /** 宽松解析配置值：null/空白 → {@link #KEYWORD}；未知取值保守回退 {@link #KEYWORD}。 */
        static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return KEYWORD;
            }
            String normalized = raw.trim();
            for (Mode mode : values()) {
                if (mode.name().equalsIgnoreCase(normalized)) {
                    return mode;
                }
            }
            return KEYWORD;
        }
    }

    /** 标识符：ASCII 字母/数字/下划线且长度 ≥ 3（长度 1–2 的片段如 `A`、`Y`、`Bz` 不具区分度）。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{3,}");

    private AcceptanceCoverage() {
    }

    /**
     * 按指定口径校验验收条目覆盖。
     *
     * @param items    验收条目（可为空）
     * @param haystack 可检索文本（子 Run 摘要 [+ 验证命令摘要 + 验证状态]）
     * @param mode     判定口径
     * @return 首个未覆盖条目的失败详情；全部覆盖或无可判定条目时返回 {@code null}
     */
    static String evaluate(List<String> items, String haystack, Mode mode) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        String hay = haystack == null ? "" : haystack.toLowerCase();
        for (String item : items) {
            if (mode == Mode.IDENTIFIER) {
                List<String> tokens = identifiers(item);
                if (tokens.isEmpty()) {
                    // 无可校验标识符（如中文散文式条目）→ 不参与判定，避免必然误杀
                    continue;
                }
                boolean hit = false;
                for (String token : tokens) {
                    if (hay.contains(token)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    return "验收条目未覆盖：\"" + item + "\"（标识符 " + tokens
                            + " 均未在子 Run 摘要与验证记录中出现）";
                }
            } else {
                String keyword = firstKeyword(item);
                if (keyword != null && !keyword.isEmpty()
                        && !hay.contains(keyword.toLowerCase())) {
                    return "验收条目未覆盖：\"" + item + "\"（关键词 \"" + keyword
                            + "\" 在子 Run 摘要与验证记录中均未出现）";
                }
            }
        }
        return null;
    }

    /** 取条目中的 ASCII 标识符（小写）。 */
    static List<String> identifiers(String item) {
        List<String> out = new ArrayList<>();
        if (item == null) {
            return out;
        }
        Matcher matcher = IDENTIFIER.matcher(item);
        while (matcher.find()) {
            out.add(matcher.group().toLowerCase());
        }
        return out;
    }

    /** MA-06 口径：首个空格前的子串；无空格则取前 8 字。 */
    static String firstKeyword(String item) {
        if (item == null || item.isBlank()) {
            return null;
        }
        String trimmed = item.trim();
        int space = trimmed.indexOf(' ');
        if (space > 0) {
            return trimmed.substring(0, space);
        }
        return trimmed.length() > 8 ? trimmed.substring(0, 8) : trimmed;
    }
}
