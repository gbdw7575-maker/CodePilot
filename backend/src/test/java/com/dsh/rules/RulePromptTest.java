package com.dsh.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基线提示的契约：必须约束「改测试文件/夹具」，且必须保持静态（前缀缓存前提）。
 *
 * <p>依据：`doc/验收文档/SWE-bench失败原因剖析.md` §2 —— Agent 改夹具导致自伤回归，
 * 剥掉夹具改动后同一补丁即 resolved（18 passed）。
 */
class RulePromptTest {

    @Test
    void forbidsEditingExistingTestsAndFixtures() {
        String base = RulePrompt.BASE;
        assertAll(
                () -> assertTrue(base.contains("不要修改既有测试文件"), "必须明确禁止改既有测试文件"),
                () -> assertTrue(base.contains("测试夹具"), "必须点名夹具"),
                () -> assertTrue(base.contains("conftest.py"), "必须给出可识别的夹具例子"));
    }

    @Test
    void allowsNewTestsOnlyWhenExplicitlyAsked() {
        assertTrue(RulePrompt.BASE.contains("只有被明确要求新增测试时才动这些文件"),
                "不应一刀切禁止新增测试，只禁止为让改动通过而改既有断言");
    }

    @Test
    void noLongerEncouragesKeepingSelfAuthoredTestsInPatch() {
        assertFalse(RulePrompt.BASE.contains("正式新增回归测试使用"),
                "原措辞鼓励把自写测试保留在补丁中，实测导致无效功与自伤回归，已移除");
    }

    @Test
    void verificationMustRelyOnPreExistingTests() {
        assertTrue(RulePrompt.BASE.contains("不能基于你自己新增或改写的断言"),
                "自验不能把自己写的断言当作通过证据");
    }

    @Test
    void baseIsStaticTextForPrefixCache() {
        // system 前缀若混入动态内容，DeepSeek 前缀缓存会逐轮失效（诊断文档 §12）。
        assertEquals(RulePrompt.BASE, RulePrompt.build(null));
        assertEquals(RulePrompt.BASE, RulePrompt.build(
                new RuleLoader.LoadedRules(java.util.List.of(), "0123456789abcdef0123456789abcdef")));
        assertFalse(RulePrompt.BASE.contains("%d"), "不得残留格式化占位符");
        assertFalse(RulePrompt.BASE.contains("${"), "不得残留属性占位符");
    }
}
