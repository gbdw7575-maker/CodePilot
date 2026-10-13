package com.dsh.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 项目验证配置。标量 command 保留既有 shell 语义，列表 command 按 argv 引用后执行。
 * 缺失配置按项目标记探测；显式但非法的配置必须拒绝，不能静默改用其他验证。
 */
public final class VerificationPlan {
    private static final int MAX_CONFIG_BYTES = 65536;
    private static final int MAX_PLAN_VERSION_CODE_POINTS = 64;
    private static final Set<String> KEYS = Set.of(
            "command", "expectTests", "timeoutSeconds", "planVersion", "purpose", "defectReproduction", "testReports");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> command;
    private final String shellCommand;
    private final boolean expectTests;
    private final int timeoutSeconds;
    private final String planVersion;
    private final String purpose;
    private final DefectReproductionPlan reproduction;
    private String acceptanceDescription;
    private TestReportSpec testReports;

    private VerificationPlan(List<String> command, boolean expectTests, int timeoutSeconds, String planVersion) {
        this(command, null, expectTests, timeoutSeconds, planVersion);
    }

    private VerificationPlan(List<String> command, String shellCommand, boolean expectTests,
                             int timeoutSeconds, String planVersion) {
        this(command, shellCommand, expectTests, timeoutSeconds, planVersion, "GENERAL", null);
    }

    private VerificationPlan(List<String> command, String shellCommand, boolean expectTests,
            int timeoutSeconds, String planVersion, String purpose, DefectReproductionPlan reproduction) {
        this.purpose = purpose; this.reproduction = reproduction;
        this.command = List.copyOf(command);
        this.shellCommand = shellCommand;
        this.expectTests = expectTests;
        this.timeoutSeconds = timeoutSeconds;
        this.planVersion = planVersion;
    }

    public List<String> command() { return command; }
    public boolean expectTests() { return expectTests; }
    public int timeoutSeconds() { return timeoutSeconds; }
    public String planVersion() { return planVersion; }
    public String purpose() { return purpose; }
    public boolean defectFix() { return "DEFECT_FIX".equals(purpose); }
    public DefectReproductionPlan reproduction() { return reproduction; }
    public String acceptanceDescription() { return acceptanceDescription; }
    public TestReportSpec testReports() { return testReports; }

    /** One resolver for verification, freshness, completion and read-only display. */
    public static VerificationPlan forRun(com.dsh.storage.RunEntity run, Path projectRoot) {
        VerificationPlan base = load(projectRoot);
        if (run.getVerificationRequirement() == null) return base;
        var requirement = RunVerificationRequirement.decode(run.getVerificationRequirement());
        var effective = new VerificationPlan(base.command, base.shellCommand, base.expectTests,
                base.timeoutSeconds, base.planVersion, requirement.purpose(), requirement.reproduction());
        effective.acceptanceDescription = requirement.acceptanceDescription();
        effective.testReports = base.testReports;
        return effective;
    }

    /** Configuration preview identity; separate from C10 goal/workspace-bound evidence. */
    public String displayDigest() {
        var value = JSON.createObjectNode().put("version", "verification-plan-display-v1")
                .put("command", commandSummary()).put("expectTests", expectTests)
                .put("timeoutSeconds", timeoutSeconds).put("planVersion", planVersion)
                .put("purpose", purpose);
        if (acceptanceDescription != null) value.put("acceptanceDescription", acceptanceDescription);
        if (reproduction != null) value.put("reproductionDigest", reproduction.digest());
        if (testReports != null) value.set("testReports", JSON.valueToTree(testReports));
        return com.dsh.common.Digest.sha256Hex(value.toString());
    }

    /** SandboxRunner 接收 shell 文本；列表中的每个参数必须保持字面值。 */
    public String commandSummary() {
        return shellCommand != null ? shellCommand : command.stream()
                .map(VerificationPlan::quoteArgument).collect(java.util.stream.Collectors.joining(" "));
    }

    private static String quoteArgument(String value) {
        if (value.matches("[a-zA-Z0-9_./:@%+=,-]+")) return value;
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static final VerificationPlan DEFAULT = new VerificationPlan(
            List.of("mvn", "test", "-q"), true, 600, "default-v1");

    public static VerificationPlan load(Path projectRoot) {
        Path verifyFile = projectRoot.resolve(".dsh").resolve("verify.yaml");
        if (!Files.exists(verifyFile)) return autoDetect(projectRoot);
        try {
            if (!Files.isRegularFile(verifyFile) || Files.size(verifyFile) > MAX_CONFIG_BYTES) {
                throw invalid();
            }
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(20);
            options.setCodePointLimit(MAX_CONFIG_BYTES);
            Object document = new Yaml(new SafeConstructor(options))
                    .load(normalizeLegacyCommand(Files.readString(verifyFile)));
            if (!(document instanceof Map<?, ?> config) || !KEYS.containsAll(config.keySet())) {
                throw invalid();
            }
            VerificationPlan detected = autoDetect(projectRoot);
            List<String> argv = detected.command();
            String shell = null;
            if (config.containsKey("command")) {
                Object value = config.get("command");
                if (value instanceof String text && !text.isBlank() && text.indexOf('\0') < 0) {
                    shell = text.strip();
                    // command() 保留旧标量配置的只读视图；实际执行使用未拆分的 shell 文本。
                    argv = List.of(shell.split("\\s+"));
                } else if (value instanceof List<?> values && !values.isEmpty()) {
                    List<String> parsed = new ArrayList<>();
                    for (Object arg : values) {
                        if (!(arg instanceof String text) || text.indexOf('\0') >= 0) throw invalid();
                        parsed.add(text);
                    }
                    if (parsed.get(0).isBlank()) throw invalid();
                    argv = parsed;
                } else {
                    throw invalid();
                }
            }
            boolean expect = true;
            if (config.containsKey("expectTests")) {
                if (!(config.get("expectTests") instanceof Boolean flag)) throw invalid();
                expect = flag;
            }
            int timeout = 600;
            if (config.containsKey("timeoutSeconds")) {
                if (!(config.get("timeoutSeconds") instanceof Integer seconds) || seconds <= 0) throw invalid();
                timeout = seconds;
            }
            String version = "default-v1";
            if (config.containsKey("planVersion")) {
                if (!(config.get("planVersion") instanceof String text) || !validPlanVersion(text)) throw invalid();
                version = text;
            }
            String purpose = "GENERAL";
            if (config.containsKey("purpose")) {
                if (!(config.get("purpose") instanceof String text) || !Set.of("GENERAL", "DEFECT_FIX").contains(text)) throw invalid();
                purpose = text;
            }
            DefectReproductionPlan reproduction = config.containsKey("defectReproduction")
                    ? DefectReproductionPlan.parse(config.get("defectReproduction")) : null;
            if (reproduction != null && !"DEFECT_FIX".equals(purpose)) throw invalid();
            var plan = new VerificationPlan(argv, shell, expect, timeout, version, purpose, reproduction);
            if (config.containsKey("testReports")) plan.testReports = TestReportSpec.parse(config.get("testReports"));
            return plan;
        } catch (IOException | org.yaml.snakeyaml.error.YAMLException e) {
            // 不带原始 YAML 内容，避免解析器错误把配置值写进普通日志。
            throw invalid();
        }
    }

    /** 有限兼容历史行解析允许的 command: echo Tests run: 5；其他内容按标准 YAML 解析。 */
    private static String normalizeLegacyCommand(String source) throws JsonProcessingException {
        StringBuilder normalized = new StringBuilder();
        for (String line : source.split("\\R", -1)) {
            if (line.startsWith("command:")) {
                String value = line.substring("command:".length()).strip();
                if (value.contains(": ") && !value.isEmpty()
                        && "[{'\"|>".indexOf(value.charAt(0)) < 0) {
                    line = "command: " + JSON.writeValueAsString(value);
                }
            }
            normalized.append(line).append('\n');
        }
        return normalized.toString();
    }

    private static boolean validPlanVersion(String text) {
        // PostgreSQL VARCHAR counts characters, not UTF-16 units or UTF-8 bytes.
        // Never strip/truncate: even trailing spaces participate in the plan identity.
        return !text.isBlank() && text.codePointCount(0, text.length()) <= MAX_PLAN_VERSION_CODE_POINTS
                && text.codePoints().noneMatch(cp -> cp == 0
                        || (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("无效的 .dsh/verify.yaml：请检查 command、expectTests、timeoutSeconds、planVersion、purpose 和 defectReproduction");
    }

    public static VerificationPlan defaultPlan() { return DEFAULT; }

    /** 混合项目优先 Maven，其次 Gradle、Python；其他项目保持历史默认。 */
    private static VerificationPlan autoDetect(Path projectRoot) {
        if (Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            return new VerificationPlan(List.of("mvn", "test", "-q"), true, 600, "auto-java-v1");
        }
        if (Files.isRegularFile(projectRoot.resolve("build.gradle"))
                || Files.isRegularFile(projectRoot.resolve("build.gradle.kts"))) {
            List<String> argv = Files.isRegularFile(projectRoot.resolve("gradlew"))
                    ? List.of("sh", "./gradlew", "test", "--console=plain")
                    : List.of("gradle", "test", "--console=plain");
            return new VerificationPlan(argv, true, 600, "auto-gradle-v1");
        }
        if (Files.isRegularFile(projectRoot.resolve("pyproject.toml"))
                || Files.isRegularFile(projectRoot.resolve("setup.py"))
                || Files.isRegularFile(projectRoot.resolve("setup.cfg"))
                || Files.isRegularFile(projectRoot.resolve("pytest.ini"))
                || Files.isRegularFile(projectRoot.resolve("tox.ini"))) {
            return new VerificationPlan(List.of("python", "-m", "pytest", "-q"),
                    true, 600, "auto-python-v1");
        }
        return DEFAULT;
    }
}
