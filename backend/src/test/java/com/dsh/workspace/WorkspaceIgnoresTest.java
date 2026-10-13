package com.dsh.workspace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 构建/缓存产物忽略清单单测：覆盖真实负载中污染补丁的
 * .pytest_cache/__pycache__/target/.egg-info，以及不误伤正常源码文件。
 */
class WorkspaceIgnoresTest {

    @Test
    void ignoresCacheAndBuildArtifactsAtAnyDepth() {
        assertTrue(WorkspaceIgnores.isIgnored(".pytest_cache/v/cache/lastfailed"));
        assertTrue(WorkspaceIgnores.isIgnored(".pytest_cache/README.md"));
        assertTrue(WorkspaceIgnores.isIgnored("src/app/__pycache__/module.cpython-311.pyc"));
        assertTrue(WorkspaceIgnores.isIgnored("a/b/__pycache__/x.pyo"));
        assertTrue(WorkspaceIgnores.isIgnored("target/classes/com/dsh/App.class"));
        assertTrue(WorkspaceIgnores.isIgnored("build/libs/app.jar"));
        assertTrue(WorkspaceIgnores.isIgnored("node_modules/left-pad/index.js"));
        assertTrue(WorkspaceIgnores.isIgnored(".venv/lib/python3.11/site-packages/x.py"));
        assertTrue(WorkspaceIgnores.isIgnored("docs/_build/html/index.html"));
        assertTrue(WorkspaceIgnores.isIgnored("src/dsh.egg-info/PKG-INFO"));
        assertTrue(WorkspaceIgnores.isIgnored(".git/HEAD"));
        assertTrue(WorkspaceIgnores.isIgnored(".coverage"));
        assertTrue(WorkspaceIgnores.isIgnored("subdir/.DS_Store"));
    }

    @Test
    void directoryTrailingSegmentIsMatchedAsDirectory() {
        assertTrue(WorkspaceIgnores.isIgnored("src/.pytest_cache", true));
        assertTrue(WorkspaceIgnores.isIgnored("target", true));
        // 末段按文件判断时，普通文件名叫 target 不应被误伤
        assertFalse(WorkspaceIgnores.isIgnored("notes/target"));
    }

    @Test
    void doesNotIgnoreNormalSourceAndDocs() {
        assertFalse(WorkspaceIgnores.isIgnored("src/main/java/com/dsh/App.java"));
        assertFalse(WorkspaceIgnores.isIgnored("tests/test_app.py"));
        assertFalse(WorkspaceIgnores.isIgnored("CHANGES.rst"));
        assertFalse(WorkspaceIgnores.isIgnored(".gitignore"));
        assertFalse(WorkspaceIgnores.isIgnored("build.gradle"));
        assertFalse(WorkspaceIgnores.isIgnored("pom.xml"));
        assertFalse(WorkspaceIgnores.isIgnored("README.md"));
        assertFalse(WorkspaceIgnores.isIgnored("a/site_config.py"));
    }

    @Test
    void handlesNullAndEmpty() {
        assertFalse(WorkspaceIgnores.isIgnored(null));
        assertFalse(WorkspaceIgnores.isIgnored(""));
        assertFalse(WorkspaceIgnores.isIgnored("."));
    }
}
