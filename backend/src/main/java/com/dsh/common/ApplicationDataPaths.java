package com.dsh.common;

import java.nio.file.Path;

/** Host-only application state. Workspace snapshots must never include this directory. */
public final class ApplicationDataPaths {

    private ApplicationDataPaths() {
    }

    public static Path dataDirectory() {
        return Path.of("data").toAbsolutePath().normalize();
    }

    public static Path credentialFile() {
        return dataDirectory().resolve("credentials.properties");
    }
}
