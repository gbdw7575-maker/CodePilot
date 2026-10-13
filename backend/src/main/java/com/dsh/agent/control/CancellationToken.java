package com.dsh.agent.control;

/**
 * 内存取消令牌：在 Loop 安全点（模型调用前、工具执行前）检查的 volatile 标志。
 * 单实例 JVM；跨实例取消由 Run 状态机 CANCELLING 表达，Loop 轮询持久化状态。
 */
public class CancellationToken {

    private volatile boolean cancelled = false;
    /** DSH-010：父 Run 令牌；父取消即时传导给子 Run（不依赖子自身标志）。 */
    private volatile CancellationToken parent;

    public boolean isCancelled() {
        return cancelled || (parent != null && parent.isCancelled());
    }

    public void cancel() {
        this.cancelled = true;
    }

    /** 绑定父令牌（子 Run 注册时调用），取消沿派生链传播。 */
    public void linkParent(CancellationToken parentToken) {
        this.parent = parentToken;
    }

    /** 取消已请求时抛出，中断 Loop 的模型/工具执行路径。 */
    public void checkpoint() {
        if (isCancelled()) {
            throw new RunCancelledException("运行已被请求取消");
        }
    }

    /** Loop 在安全点检测到取消时抛出，由上层转为终态。 */
    public static class RunCancelledException extends RuntimeException {
        public RunCancelledException(String message) {
            super(message);
        }
    }
}
