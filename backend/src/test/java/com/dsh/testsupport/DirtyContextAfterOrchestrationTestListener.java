package com.dsh.testsupport;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * MA-10 测试基础设施：编排（orchestration）包的测试类结束后关闭并驱逐其 ApplicationContext。
 *
 * <p>仅 schema 隔离还不够：同一缓存上下文内的多个测试类共享 worker 池与
 * {@code WorkerConcurrencyManager} 的进程内三级许可。先前类尚在退避/在途的子任务会占满
 * 全局许可，使后续类的正常派发被判 CONCURRENCY_LIMIT（满额重试到 RETRY_LIMIT_EXCEEDED）。
 * 编排包每个测试类后丢弃上下文，可保证引擎、许可、调度线程不跨类残留；非编排测试不涉及
 * worker 池，仍走 Spring 默认上下文缓存。</p>
 */
public class DirtyContextAfterOrchestrationTestListener implements TestExecutionListener {

    @Override
    public void afterTestClass(TestContext testContext) {
        Class<?> testClass = testContext.getTestClass();
        if (testClass != null && testClass.getPackageName().startsWith("com.dsh.orchestration")) {
            testContext.markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        }
    }
}
