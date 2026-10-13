package com.dsh.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit, bounded report locations; no shell/glob or framework guessing. */
public record TestReportSpec(String format, List<String> directories) {
    public static final String POLICY = "surefire-reports-v1";
    public TestReportSpec {
        if (!("MAVEN_SUREFIRE".equals(format) || "GRADLE".equals(format)) || directories == null
                || directories.isEmpty() || directories.size() > 32)
            throw invalid();
        directories = List.copyOf(directories);
        var seen = new java.util.HashSet<String>();
        for (String directory : directories) {
            if (directory == null || directory.length() > 256
                    || !directory.matches("[a-zA-Z0-9_./-]+") || directory.startsWith("/")
                    || java.util.Arrays.stream(directory.split("/", -1))
                        .anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..") || p.equals(".git"))
                    || !seen.add(directory.toLowerCase(java.util.Locale.ROOT))) throw invalid();
        }
    }
    public String policy() { return "GRADLE".equals(format) ? "gradle-reports-v1" : POLICY; }
    public static TestReportSpec parse(Object value) {
        if (!(value instanceof Map<?, ?> map) || !map.keySet().equals(Set.of("format", "directories"))
                || !(map.get("format") instanceof String format)
                || !(map.get("directories") instanceof List<?> values)
                || values.stream().anyMatch(v -> !(v instanceof String))) throw invalid();
        return new TestReportSpec(format, values.stream().map(String.class::cast).toList());
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("无效 testReports：仅支持 MAVEN_SUREFIRE、GRADLE 及 1～32 个不同的工作区相对目录");
    }
}
