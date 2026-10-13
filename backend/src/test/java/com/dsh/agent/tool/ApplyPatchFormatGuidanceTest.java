package com.dsh.agent.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * apply_patch 解析失败时的报错必须是**可执行**的指引。
 *
 * <p>动机（2026-10-06 实测）：`ext-sphinx-doc__sphinx-10466` 的模型连续两次提交
 * `*** Begin Patch` 标记格式，都被一句"补丁中没有任何文件段"挡回，白烧两轮
 * （seq 125 / 127）。旧文案没说要什么格式，模型只能猜。</p>
 */
class ApplyPatchFormatGuidanceTest {

    @Test
    void detectsMarkerFormatAndNamesIt() {
        String marker = "*** Begin Patch\n*** Update File: sphinx/domains/index.py\n@@\n-old\n+new\n*** End Patch";
        String msg = ApplyPatchTool.patchFormatGuidance(marker);
        assertTrue(msg.contains("*** Begin Patch"), "要点名识别到的标记格式: " + msg);
        assertTrue(msg.contains("unified diff"), "要给出目标格式: " + msg);
    }

    @Test
    void genericMessageStillExplainsTheFormat() {
        String msg = ApplyPatchTool.patchFormatGuidance("我把 process_doc 改了一下");
        assertTrue(msg.contains("没有任何文件段"), msg);
        assertTrue(msg.contains("unified diff"), "要给出目标格式: " + msg);
    }

    @Test
    void explainsRequiredStructure() {
        String msg = ApplyPatchTool.patchFormatGuidance("");
        assertTrue(msg.contains("--- a/") && msg.contains("+++ b/"),
                "要说明文件段头: " + msg);
        assertTrue(msg.contains("@@"), "要说明 hunk 头: " + msg);
    }

    @Test
    void doesNotPretendToAcceptMarkerFormat() {
        String msg = ApplyPatchTool.patchFormatGuidance("*** Begin Patch");
        assertTrue(msg.contains("不支持"), "必须明说不支持，而不是让模型继续试: " + msg);
        assertFalse(msg.contains("补丁中没有任何文件段"),
                "识别到标记格式时应给针对性提示，而不是笼统文案");
    }

    @Test
    void nullPatchTextIsSafe() {
        String msg = ApplyPatchTool.patchFormatGuidance(null);
        assertTrue(msg.contains("unified diff"), msg);
    }
}
