package com.dsh.orchestration.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-11 §17 验收条目覆盖判定测试。
 *
 * <p>背景：MA-11 §16 真实委派对照中，d02 两次 MULTI 试验的 5 个子任务被判
 * {@code VERIFICATION_FAILED}（「验收条目未覆盖」），但其产物经重建合并树重评分
 * **全部正确**（M3/M4 各 9/9，未改动基线 6/9）。根因是 MA-06 的 KEYWORD 口径取
 * 「首个空格前子串」并要求其在**子 Agent 自由文本摘要**中出现——摘要只写「改动文件清单 +
 * 验证方式」，从未被要求逐字复述验收条目，故必然误杀。</p>
 *
 * <p>本测试用**真实子 Run 的验收条目（逐字）与摘要片段（节选）**锁定两种口径的行为：
 * KEYWORD 复现记录在案的误杀，IDENTIFIER 修复它且保留「标识符确实缺失时仍失败」的反跳过能力。
 * 完整摘要的全量回放见验收证据 {@code d02失败机理与重评分证据.json}。</p>
 */
class Ma11AcceptanceCoverageTest {

    // ---------------------------------------------- 真实 d02 子任务的 验收条目 + 摘要节选

    private record Fixture(String name, List<String> acceptance, String summaryExcerpt,
                           String keywordFirstMiss) {
    }

    /** 摘要片段为真实子 Run 摘要的节选（保留标识符与验收对照行），非人工构造。 */
    private static final List<Fixture> D02_FIXTURES = List.of(
            new Fixture("M3/fix-slugify",
                    List.of("slugify(\"Hello, World!\")==\"hello-world\"",
                            "slugify(\"  A--B  \")==\"a-b\"",
                            "slugify(\"!!!\")==\"\"",
                            "仅修改 Slugify.java"),
                    "改动文件清单\n- src/text/Slugify.java（唯一改动文件）\n"
                            + "逻辑自检（对照验收标准，按代码路径推演）：\n"
                            + "- `\"Hello, World!\"` → `hello-world` ✔（逗号+空格合并为一个 `-`）\n"
                            + "- `\"  A--B  \"` → `a-b` ✔",
                    "slugify(\"Hello,"),
            new Fixture("M3/fix-caesar",
                    List.of("shift(\"Bz!\",1)==\"Ca!\"",
                            "shift(\"Ca!\",-1)==\"Bz!\"",
                            "shift(\"Y\",2)==\"A\"",
                            "shift(\"A\",-2)==\"Y\"",
                            "非字母字符保持不变",
                            "仅修改 Caesar.java"),
                    "### 改动文件清单\n"
                            + "- `src/codec/Caesar.java`（唯一改动文件，未新建任何其他文件）\n"
                            + "对照验收用例逐条推演（基于修改后逻辑）：\n"
                            + "- `shift(\"Bz!\",1)` → `b→c`、`z→a`、`!` 保留 = `\"Ca!\"` ✅\n"
                            + "- `shift(\"Ca!\",-1)` → move=25，`C→B`、`a→z`、`!` 保留 = `\"Bz!\"` ✅\n"
                            + "- `shift(\"Y\",2)` → move=2，`Y→A`（`(24+2)%26=0`）✅\n"
                            + "- `shift(\"A\",-2)` → move=24，`A→Y`（`(0+24)%26=24`）✅\n"
                            + "- 非字母字符（如 `!`）走 else 分支原样输出 ✅",
                    "非字母字符保持不"),
            new Fixture("M4/fix_slugify",
                    List.of("slugify(\"Hello, World!\")==\"hello-world\"",
                            "slugify(\"  A--B  \")==\"a-b\"",
                            "slugify(\"--\")==\"\"",
                            "小写化与非字母数字语义保留，编译通过"),
                    "**改动文件清单**\n"
                            + "- `src/text/Slugify.java`（唯一改动，仅方法体逻辑）\n"
                            + "- 语义核对：\n"
                            + "  - `slugify(\"Hello, World!\")` → 小写 `hello, world!`，"
                            + "`,`/空格/`!` 合并为一个 `-` → `hello-world` ✅\n"
                            + "  - `slugify(\"  A--B  \")` → 前导空格不产生 `-`，`--` 合并 → `a-b` ✅\n"
                            + "  - `slugify(\"--\")` → 全部非字母数字，前导分支不追加、末尾裁剪 → `\"\"` ✅\n"
                            + "  - 小写化与「仅 a-z0-9 视为字母数字」的原有语义保留 ✅",
                    "小写化与非字母数"),
            new Fixture("M4/fix_caesar",
                    List.of("shift(\"Bz!\",1)==\"Ca!\"",
                            "shift(\"Ca!\",-1)==\"Bz!\"",
                            "shift(\"Y\",2)==\"A\"",
                            "shift(\"A\",-2)==\"Y\"",
                            "非字母字符不变且不出现越界字符，编译通过"),
                    "改动文件清单\n- src/codec/Caesar.java（唯一改动文件）\n"
                            + "- `shift(\"Bz!\",1)`：B→C，z→a，!不变 = `\"Ca!\"` ✓\n"
                            + "- `shift(\"Ca!\",-1)`：move=25，C→B，a→z，!不变 = `\"Bz!\"` ✓\n"
                            + "- `shift(\"Y\",2)`：(`'Y'-'A'+2)%26` → `'A'` ✓\n"
                            + "- `shift(\"A\",-2)`：(`'A'-'A'+24)%26` → `'Y'` ✓",
                    "非字母字符不变且"),
            new Fixture("M4/fix_discount",
                    List.of("original>=50000 且 vip 时 price(50000,true)==40000",
                            "price(100000,true)==80000",
                            "满 10000 分与以下、非会员分支行为不变",
                            "仅修改该文件，编译通过"),
                    "## 改动文件清单\n- `src/shop/DiscountPrice.java`（唯一改动文件）\n"
                            + "  - 满 50000 分支：`return original - original / 10;` → "
                            + "`return original * 8 / 10;`（唯一一行改动）\n"
                            + "## 验证方式与结果\n"
                            + "- 代码语义核对（先乘后除，向下取整）：\n"
                            + "  - `price(50000, true)` = 50000*8/10 = 40000 ✅\n"
                            + "  - `price(100000, true)` = 100000*8/10 = 80000 ✅\n"
                            + "  - `price(10000, true)` = 10000*9/10 = 9000（未改）✅\n"
                            + "  - `price(9999, true)` = 9999（未改）✅",
                    "original>=50000"));

    // ------------------------------------------------------- KEYWORD：复现记录在案的误杀

    @Test
    void keywordMode_reproducesTheRecordedFalseNegatives() {
        for (Fixture f : D02_FIXTURES) {
            String mismatch = AcceptanceCoverage.evaluate(
                    f.acceptance(), f.summaryExcerpt().toLowerCase(), AcceptanceCoverage.Mode.KEYWORD);
            assertNotNull(mismatch, f.name() + "：KEYWORD 口径应误杀（复现 MA-11 §16 记录）");
            assertTrue(mismatch.contains(f.keywordFirstMiss()),
                    f.name() + "：失败详情应点名记录在案的首个未命中关键词，实际=" + mismatch);
        }
    }

    // ------------------------------------------------------- IDENTIFIER：修复误杀

    @Test
    void identifierMode_rescuesTheRecordedFalseNegatives() {
        for (Fixture f : D02_FIXTURES) {
            String mismatch = AcceptanceCoverage.evaluate(
                    f.acceptance(), f.summaryExcerpt().toLowerCase(),
                    AcceptanceCoverage.Mode.IDENTIFIER);
            assertNull(mismatch, f.name() + "：IDENTIFIER 口径应通过，实际=" + mismatch);
        }
    }

    // ------------------------------------------------------- 反跳过能力仍保留

    @Test
    void identifierMode_stillFailsWhenNoIdentifierAppears() {
        // MA-06 既有夹具：条目 self-check-A；BADSUMMARY worker 的摘要不含 self/check
        String badSummary = "MA06 任务完成（MA06WORKER-BADSUMMARY），但摘要缺少验收关键词。";
        String mismatch = AcceptanceCoverage.evaluate(List.of("self-check-A"), badSummary,
                AcceptanceCoverage.Mode.IDENTIFIER);
        assertNotNull(mismatch, "标识符确实缺失时仍须失败（不能退化为永不失败的闸门）");
        assertTrue(mismatch.contains("标识符"), "失败详情应说明是标识符缺失，实际=" + mismatch);
    }

    @Test
    void identifierMode_passesWhenIdentifierAppears() {
        String goodSummary = "MA06 验收通过：产物已生成，self-check 通过。";
        assertNull(AcceptanceCoverage.evaluate(List.of("self-check-A"), goodSummary,
                AcceptanceCoverage.Mode.IDENTIFIER));
    }

    // ------------------------------------------------------- 散文式条目：不再必然误杀

    @Test
    void identifierMode_skipsProseOnlyItemsWhileKeywordModeEnforcesThem() {
        // 该条目 18 字、无空格 → KEYWORD 取前 8 字「小写化与非字母数」，任何摘要都难以逐字包含
        List<String> proseOnly = List.of("小写化与非字母数字语义保留，编译通过");
        assertNotNull(AcceptanceCoverage.evaluate(proseOnly, "无关摘要",
                AcceptanceCoverage.Mode.KEYWORD), "KEYWORD 口径会误杀纯散文条目");
        assertNull(AcceptanceCoverage.evaluate(proseOnly, "无关摘要",
                AcceptanceCoverage.Mode.IDENTIFIER), "IDENTIFIER 口径下无可校验标识符 → 不参与判定");
    }

    @Test
    void identifierMode_splitsOnNonAlphanumericAndDropsShortFragments() {
        // A / B / Y / Bz / 2 等长度 < 3 的片段不具区分度，不参与判定；只保留 shift
        assertEquals(List.of("shift"), AcceptanceCoverage.identifiers("shift(\"A\",-2)==\"Y\""));
        assertEquals(List.of("shift"), AcceptanceCoverage.identifiers("shift(\"Bz!\",1)==\"Ca!\""));
        assertTrue(AcceptanceCoverage.identifiers("非字母字符保持不变").isEmpty());
        assertEquals(List.of("discountprice", "java"),
                AcceptanceCoverage.identifiers("仅修改 DiscountPrice.java"));
    }

    // ------------------------------------------------------- 配置解析

    @Test
    void modeParse_defaultsToKeywordForMissingOrUnknownValues() {
        assertEquals(AcceptanceCoverage.Mode.KEYWORD, AcceptanceCoverage.Mode.parse(null));
        assertEquals(AcceptanceCoverage.Mode.KEYWORD, AcceptanceCoverage.Mode.parse(""));
        assertEquals(AcceptanceCoverage.Mode.KEYWORD, AcceptanceCoverage.Mode.parse("   "));
        assertEquals(AcceptanceCoverage.Mode.KEYWORD, AcceptanceCoverage.Mode.parse("SOMETHING_ELSE"));
        assertEquals(AcceptanceCoverage.Mode.IDENTIFIER, AcceptanceCoverage.Mode.parse("IDENTIFIER"));
        assertEquals(AcceptanceCoverage.Mode.IDENTIFIER, AcceptanceCoverage.Mode.parse(" identifier "));
        assertEquals(AcceptanceCoverage.Mode.KEYWORD, AcceptanceCoverage.Mode.parse("keyword"));
    }

    @Test
    void emptyOrNullAcceptanceIsAlwaysCovered() {
        assertEquals(null, AcceptanceCoverage.evaluate(List.of(), "any",
                AcceptanceCoverage.Mode.IDENTIFIER));
        assertEquals(null, AcceptanceCoverage.evaluate(null, "any",
                AcceptanceCoverage.Mode.KEYWORD));
    }
}
