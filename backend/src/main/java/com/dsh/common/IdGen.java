package com.dsh.common;

import java.util.UUID;

/** 服务端分配的不透明标识；满足契约 ^[A-Za-z0-9_-]+$，长度不超过 128。 */
public final class IdGen {

    private IdGen() {
    }

    public static String next(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
