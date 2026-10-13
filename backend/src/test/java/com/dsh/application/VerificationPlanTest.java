package com.dsh.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证计划自动探测单测：真实负载暴露 Python 项目被默认跑 mvn（容器退出 127），
 * 无 verify.yaml 时必须按标记文件选择正确工具链。
 */
class VerificationPlanTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsPythonProject() throws Exception {
        Files.writeString(tempDir.resolve("pyproject.toml"), "[project]\nname='x'\n");
        VerificationPlan plan = VerificationPlan.load(tempDir);
        assertEquals("auto-python-v1", plan.planVersion());
        assertEquals(List.of("python", "-m", "pytest", "-q"), plan.command());
    }

    @Test
    void detectsJavaProjectByPom() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>\n");
        // 混合项目同时有 pyproject 时 Java 优先
        Files.writeString(tempDir.resolve("setup.py"), "from setuptools import setup\n");
        VerificationPlan plan = VerificationPlan.load(tempDir);
        assertEquals("auto-java-v1", plan.planVersion());
        assertEquals(List.of("mvn", "test", "-q"), plan.command());
    }

    @Test
    void detectsGradleProject() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle.kts"), "plugins {}\n");
        assertEquals("auto-gradle-v1", VerificationPlan.load(tempDir).planVersion());
        assertEquals(List.of("gradle", "test", "--console=plain"), VerificationPlan.load(tempDir).command());
    }

    @Test
    void fallsBackToDefaultForUnknownProject() {
        VerificationPlan plan = VerificationPlan.load(tempDir);
        assertEquals("default-v1", plan.planVersion());
        assertEquals(List.of("mvn", "test", "-q"), plan.command());
    }

    @Test
    void explicitVerifyYamlWinsOverAutoDetect() throws Exception {
        Files.writeString(tempDir.resolve("pyproject.toml"), "[project]\n");
        Path dshDir = Files.createDirectories(tempDir.resolve(".dsh"));
        Files.writeString(dshDir.resolve("verify.yaml"),
                "command: make test\nplanVersion: custom-v1\nexpectTests: false\n");
        VerificationPlan plan = VerificationPlan.load(tempDir);
        assertEquals("custom-v1", plan.planVersion());
        assertEquals(List.of("make", "test"), plan.command());
    }

    private VerificationPlan configured(String yaml) throws Exception {
        Files.createDirectories(tempDir.resolve(".dsh"));
        Files.writeString(tempDir.resolve(".dsh/verify.yaml"), yaml);
        return VerificationPlan.load(tempDir);
    }

    @Test
    void parsesStandardBlockCommandListAndTypedOptions() throws Exception {
        var plan = configured("""
                command:
                  - python
                  - -m
                  - pytest
                  - 'tests/a file.py'
                expectTests: false
                timeoutSeconds: 45
                planVersion: custom-list-v1
                """);
        assertEquals(List.of("python", "-m", "pytest", "tests/a file.py"), plan.command());
        assertFalse(plan.expectTests());
        assertEquals(45, plan.timeoutSeconds());
        assertEquals("custom-list-v1", plan.planVersion());
        assertEquals("python -m pytest 'tests/a file.py'", plan.commandSummary());
    }

    @Test
    void parsesFlowCommandListWithLiteralShellMetacharacters() throws Exception {
        var plan = configured("command: [echo, 'a;b', \"it's fine\"]\n");
        assertEquals(List.of("echo", "a;b", "it's fine"), plan.command());
        assertEquals("echo 'a;b' 'it'\"'\"'s fine'", plan.commandSummary());
    }

    @Test
    void preservesScalarShellQuotingAndOperators() throws Exception {
        var plan = configured("command: >-\n  printf '%s' 'hello world' && echo done\nexpectTests: false\n");
        assertEquals("printf '%s' 'hello world' && echo done", plan.commandSummary());
    }

    @Test
    void preservesLegacyUnquotedColonCommand() throws Exception {
        var plan = configured("command: echo Tests run: 5\n");
        assertEquals("echo Tests run: 5", plan.commandSummary());
    }

    @Test
    void explicitPlanWithoutCommandUsesDetectedProject() throws Exception {
        Files.writeString(tempDir.resolve("pyproject.toml"), "[project]\n");
        assertEquals(List.of("python", "-m", "pytest", "-q"),
                configured("timeoutSeconds: 30\n").command());
    }

    @Test
    void rejectsInvalidExplicitPlansInsteadOfFallingBack() throws Exception {
        for (String yaml : List.of(
                "command: []\n", "command: ''\n", "command: [echo, null]\n",
                "command: [echo, 42]\n", "command: {exe: mvn}\n",
                "command: [echo\n", "command: echo ok\ncommand: echo override\n",
                "expectTests: nope\n", "timeoutSeconds: 0\n", "timeoutSeconds: 2.5\n",
                "planVersion: ''\n", "unexpectedOption: true\n")) {
            assertThrows(IllegalArgumentException.class, () -> configured(yaml), yaml);
        }
    }

    @Test
    void detectsGradleGroovyProjectAndUsesWrapperWithoutExecutableBit() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins {}\n");
        Files.writeString(tempDir.resolve("gradlew"), "#!/bin/sh\n");
        assertEquals(List.of("sh", "./gradlew", "test", "--console=plain"),
                VerificationPlan.load(tempDir).command());
    }

    @Test
    void reportSpecMustBeExplicitValidAndPreservedInRunPlan() throws Exception {
        var plan=configured("command: mvn -q test\ntestReports:\n  format: MAVEN_SUREFIRE\n  directories: [a/target/surefire-reports, b/target/surefire-reports]\n");
        assertEquals(List.of("a/target/surefire-reports","b/target/surefire-reports"),plan.testReports().directories());
        for(String block:List.of("null","{format: UNKNOWN, directories: [target]}",
                "{format: MAVEN_SUREFIRE, directories: [target, target]}",
                "{format: MAVEN_SUREFIRE, directories: [../out]}",
                "{format: MAVEN_SUREFIRE, directories: []}"))
            assertThrows(IllegalArgumentException.class,()->configured("testReports: "+block+"\n"));
        assertNull(configured("command: echo ok\n").testReports());
    }
}
