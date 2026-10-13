package com.dsh.agent.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-010：取消令牌父子链接。父取消即时传导子令牌（含多级链），子自身取消不反向影响父。
 */
class CancellationTokenLinkTest {

    @Test
    void parentCancelPropagatesToLinkedChild() {
        CancellationTokenRegistry registry = new CancellationTokenRegistry();
        registry.register("parent-run");
        CancellationToken child = registry.registerLinked("parent-run", "child-run");

        assertFalse(child.isCancelled());
        registry.requestCancel("parent-run");
        assertTrue(child.isCancelled(), "父 Run 取消必须传导给子令牌");
        assertThrows(CancellationToken.RunCancelledException.class, child::checkpoint);
    }

    @Test
    void cancellationPropagatesAlongDerivationChain() {
        CancellationTokenRegistry registry = new CancellationTokenRegistry();
        registry.register("main");
        CancellationToken child = registry.registerLinked("main", "child");
        CancellationToken grand = registry.registerLinked("child", "grand");

        registry.requestCancel("main");
        assertTrue(child.isCancelled());
        assertTrue(grand.isCancelled(), "取消沿派生链多级传播");
    }

    @Test
    void childCancelDoesNotAffectParentAndMissingParentTolerated() {
        CancellationTokenRegistry registry = new CancellationTokenRegistry();
        CancellationToken orphan = registry.registerLinked("absent-parent", "orphan-run");
        assertFalse(orphan.isCancelled(), "父令牌不存在时子令牌独立工作");
        orphan.cancel();
        assertTrue(orphan.isCancelled());

        registry.register("p2");
        CancellationToken c2 = registry.registerLinked("p2", "c2");
        c2.cancel();
        assertFalse(registry.get("p2").isCancelled(), "子取消不能反向传导父令牌");
    }
}
