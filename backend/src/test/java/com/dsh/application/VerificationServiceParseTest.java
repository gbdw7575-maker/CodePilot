package com.dsh.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证输出解析单测：pytest 收尾行与 Maven surefire 两种格式，
 * 覆盖真实负载中「Python 项目只认 Maven 格式 → testsDiscovered=0 → 误判失败」。
 */
class VerificationServiceParseTest {

    @Test
    void parsesPytestMixedSummary() {
        String output = """
                collected 6 items

                test_a.py ....                                                     [ 66%]
                test_b.py .F.                                                      [100%]
                ========================= short test summary info =================
                FAILED test_b.py::test_x
                ========= 1 failed, 5 passed, 2 skipped, 1 error in 0.42s ==========
                """;
        TestCounts counts = VerificationService.parseTestCounts(output);
        assertEquals(9, counts.discovered());
        assertEquals(5, counts.passed());
        assertEquals(2, counts.failures()); // failed + error
        assertEquals(2, counts.skipped());
    }

    @Test
    void parsesPytestAllPassed() {
        String output = "....                                                                  [100%]\n"
                + "============================== 4 passed in 0.03s ==============================\n";
        TestCounts counts = VerificationService.parseTestCounts(output);
        assertEquals(4, counts.discovered());
        assertEquals(4, counts.passed());
        assertEquals(0, counts.failures());
    }

    @Test
    void parsesMavenSurefireFallback() {
        String output = """
                Running com.dsh.AppTest
                Tests run: 12, Failures: 2, Errors: 0, Skipped: 1
                BUILD FAILURE
                """;
        TestCounts counts = VerificationService.parseTestCounts(output);
        assertEquals(12, counts.discovered());
        assertEquals(9, counts.passed());
        assertEquals(2, counts.failures());
        assertEquals(1, counts.skipped());
    }

    @Test
    void parsesPytestQuietSummaryWithoutEqualsDecoration() {
        // pytest -q 的收尾行没有 = 装饰（真实负载实测：sphinx 基线仓库级自验就是这样被
        // 误判的——匹配不到 → 回退 Maven → 计数 0 → expectTests=true 下 exit=0 也判 FAILED）。
        String output = "....                                                                  [100%]\n"
                + "7 passed, 1 skipped in 4.86s\n";
        TestCounts counts = VerificationService.parseTestCounts(output);
        assertEquals(8, counts.discovered());
        assertEquals(7, counts.passed());
        assertEquals(0, counts.failures());
        assertEquals(1, counts.skipped());
    }

    @Test
    void parsesPytestQuietSummaryWithFailures() {
        String output = "F.F                                                                  [100%]\n"
                + "1 failed, 6 passed, 2 errors in 0.42s\n";
        TestCounts counts = VerificationService.parseTestCounts(output);
        assertEquals(9, counts.discovered());
        assertEquals(6, counts.passed());
        assertEquals(3, counts.failures()); // 1 failed + 2 errors
    }

    @Test
    void parsesPytestQuietErrorsOnlySummary() {
        // 仅收集期报错：pytest -q 输出 `1 error in 0.10s`（单数 error）。
        TestCounts counts = VerificationService.parseTestCounts("1 error in 0.10s\n");
        assertEquals(1, counts.discovered());
        assertEquals(0, counts.passed());
        assertEquals(1, counts.failures());
    }

    @Test
    void lastPytestSummaryWinsAcrossForms() {
        // 命令里跑了两轮 pytest：最后一轮（安静形态）才是本命令的最终结果。
        String output = "============================== 4 passed in 0.03s ==============================\n"
                + "some intermediate output\n"
                + "2 passed in 0.10s\n";
        TestCounts counts = VerificationService.parseTestCounts(output);
        assertEquals(2, counts.discovered());
        assertEquals(2, counts.passed());
    }

    @Test
    void plainTextWithErrorWordsIsNotPytestSummary() {
        // 日志正文里的「N errors occurred」没有行尾 `in <时长>` 特征，不能当 pytest 摘要，
        // 也不含 Maven 计数 ⇒ discovered=0（保持回退语义，不误报计数）。
        TestCounts counts = VerificationService.parseTestCounts(
                "retrying after 3 errors occurred\nsomething else\n");
        assertEquals(0, counts.discovered());
        assertEquals(0, counts.passed());
    }

    @Test
    void emptyOutputMeansNoTestsDiscovered() {
        TestCounts counts = VerificationService.parseTestCounts("bash: mvn: command not found\n");
        assertEquals(0, counts.discovered());
        assertEquals(0, counts.passed());
    }

    @Test
    void mavenErrorsAreNotPassedTests() {
        assertEquals(new TestCounts(5, 2, 2, 1), VerificationService.parseTestCounts(
                "Tests run: 5, Failures: 1, Errors: 1, Skipped: 1\n"));
    }

    @Test
    void mavenSuiteAndResultsSummaryAreNotDoubleCounted() {
        assertEquals(new TestCounts(5, 4, 1, 0), VerificationService.parseTestCounts("""
                [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in ATest
                [ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0 -- in BTest
                [INFO] Results:
                [ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0
                """));
    }

    @Test
    void mavenReactorAggregatesModulesAndKeepsEarlierFailure() {
        assertEquals(new TestCounts(8, 6, 1, 1), VerificationService.parseTestCounts("""
                [INFO] Tests run: 3, Failures: 0, Errors: 1, Skipped: 0 -- in ATest
                [INFO] Results:
                [ERROR] Tests run: 3, Failures: 0, Errors: 1, Skipped: 0
                [INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 1 -- in BTest
                [INFO] Results:
                [INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 1
                """));
    }

    @Test
    void quietMavenWithoutResultsAggregatesSuites() {
        assertEquals(new TestCounts(5, 4, 0, 1), VerificationService.parseTestCounts("""
                Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in ATest
                Tests run: 3, Failures: 0, Errors: 0, Skipped: 1 -- in BTest
                """));
    }
}
