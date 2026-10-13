package com.dsh.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A4「验证失败回流修正」反馈文本的定向测试。
 *
 * <p>只覆盖纯函数 {@link LoopSteps#verificationFixFeedback}：反馈文本是模型唯一能看到的
 * 失败信息，因此必须与验证记录中的客观字段逐字一致，且不能臆造失败原因。
 * 状态迁移部分（reopenForVerificationFix）依赖真实存储，由容器集成回归覆盖。
 */
class VerificationFixFeedbackTest {

    @Test
    void statesObjectiveFactsFromVerificationRecord() {
        String text = LoopSteps.verificationFixFeedback(
                "python -m pytest tests/test_build_gettext.py", 1, 7, 6, 1, 1, 2);
        assertTrue(text.contains("python -m pytest tests/test_build_gettext.py"),
                "必须给出验证命令，模型才知道该重跑什么");
        assertTrue(text.contains("退出码：1"));
        assertTrue(text.contains("discovered=7"), "测试计数须与验证记录一致");
        assertTrue(text.contains("passed=6"));
        assertTrue(text.contains("skipped=1"));
        assertTrue(text.contains("第 1/2 次修正机会"), "必须让模型知道还剩几次机会");
        assertTrue(text.contains("run_command"), "应引导模型自己重跑命令看完整输出");
    }

    @Test
    void explainsZeroDiscoveredAsCommandProblemNotCodeProblem() {
        String text = LoopSteps.verificationFixFeedback("python -m pytest -q", 0, 0, 0, 0, 2, 3);
        assertTrue(text.contains("discovered=0"));
        assertTrue(text.contains("没有解析出任何测试"),
                "discovered=0 必须被解释为验证命令/输出格式问题，而不是改动正确");
        assertFalse(text.contains("改动正确") && !text.contains("不要"),
                "不得让模型把没跑出测试误读成改动正确");
    }

    @Test
    void doesNotExplainZeroDiscoveredWhenTestsDidRun() {
        String text = LoopSteps.verificationFixFeedback("python -m pytest -q", 1, 7, 6, 1, 1, 2);
        assertFalse(text.contains("没有解析出任何测试"),
                "discovered>0 时不应再提示命令没跑出测试，避免误导");
    }

    @Test
    void toleratesMissingCommandSummary() {
        String text = LoopSteps.verificationFixFeedback(null, 2, 3, 2, 0, 1, 1);
        assertTrue(text.contains("(未记录)"), "命令摘要缺失时占位，不能拼出 null");
        assertFalse(text.contains("null"));
    }

    @Test
    void staysShortEnoughToBeCheap() {
        String text = LoopSteps.verificationFixFeedback(
                "python -m pytest tests/test_build_gettext.py", 1, 7, 6, 1, 1, 2);
        assertTrue(text.length() < 600,
                "反馈文本应远小于一次请求的平均上下文，实测长度=" + text.length());
    }
}
